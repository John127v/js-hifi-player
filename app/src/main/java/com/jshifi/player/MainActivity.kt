package com.whitelabel.hifiplayer

import android.content.ContentUris
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.Equalizer
import android.media.audiofx.Visualizer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.hypot

data class Song(val id: Long, val title: String, val artist: String, val uri: Uri)
enum class RepeatMode { OFF, ALL, ONE }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HiFiFinal() }
    }
}

@Composable
fun HiFiFinal() {
    val context = LocalContext.current
    val cyan = Color(0xFF00E5FF)
    val card = Color(0xFF121821)
    val border = Color(0xFF1E3A4A)

    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var idx by remember { mutableIntStateOf(0) }
    var isPlaying by remember { mutableStateOf(false) }
    var showEq by remember { mutableStateOf(true) }
    var shuffle by remember { mutableStateOf(false) }
    var repeat by remember { mutableStateOf(RepeatMode.ALL) }
    var eqLevels by remember { mutableStateOf(List(5) { 0.2f }) }
    var fft by remember { mutableStateOf(List(16) { 0.05f }) }
    var vuL by remember { mutableFloatStateOf(0.08f) }
    var vuR by remember { mutableFloatStateOf(0.08f) }
    var hasPerm by remember { mutableStateOf(false) }

    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var eq by remember { mutableStateOf<Equalizer?>(null) }
    var vis by remember { mutableStateOf<Visualizer?>(null) }

    fun load() {
        val list = mutableListOf<Song>()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST),
            "${MediaStore.Audio.Media.IS_MUSIC}!=0",
            null,
            "TITLE ASC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)

            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                list.add(
                    Song(
                        id = id,
                        title = c.getString(titleCol) ?: "Desconhecida",
                        artist = c.getString(artistCol) ?: "",
                        uri = ContentUris.withAppendedId(uri, id)
                    )
                )
            }
        }
        songs = list
    }

    val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(android.Manifest.permission.READ_MEDIA_AUDIO, android.Manifest.permission.RECORD_AUDIO)
    } else {
        arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.RECORD_AUDIO)
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { map ->
        if (map.values.all { it }) {
            hasPerm = true
            load()
        }
    }

    fun play(i: Int) {
        if (songs.isEmpty()) return
        idx = i
        runCatching {
            vis?.enabled = false
            vis?.release()
            eq?.release()
            player?.stop()
            player?.release()

            val newPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(context, songs[idx].uri)
                prepare()
                start()
                setOnCompletionListener {
                    when (repeat) {
                        RepeatMode.ONE -> play(idx)
                        RepeatMode.ALL -> play(if (shuffle) songs.indices.random() else if (idx < songs.size - 1) idx + 1 else 0)
                        RepeatMode.OFF -> if (idx < songs.size - 1) play(idx + 1)
                    }
                }
            }
            player = newPlayer
            isPlaying = true

            val sessionId = newPlayer.audioSessionId
            if (sessionId != 0) {
                runCatching {
                    eq = Equalizer(0, sessionId).apply {
                        enabled = true
                        eqLevels.forEachIndexed { b, l ->
                            val r = bandLevelRange
                            setBandLevel(b.toShort(), (r[0] + (r[1] - r[0]) * l).toInt().toShort())
                        }
                    }
                }

                runCatching {
                    vis = Visualizer(sessionId).apply {
                        captureSize = Visualizer.getCaptureSizeRange()[1]
                        setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                            override fun onWaveFormDataCapture(v: Visualizer?, w: ByteArray?, rate: Int) {
                                w?.let {
                                    var mx = 0
                                    for (b in it) {
                                        val a = abs(b.toInt())
                                        if (a > mx) mx = a
                                    }
                                    val lvl = mx / 128f
                                    vuL = lvl
                                    vuR = lvl
                                }
                            }

                            override fun onFftDataCapture(v: Visualizer?, f: ByteArray?, rate: Int) {
                                f?.let {
                                    val out = MutableList(16) { 0.05f }
                                    for (k in 0..15) {
                                        if (k * 2 + 1 < it.size) {
                                            out[k] = (hypot(it[k * 2].toDouble(), it[k * 2 + 1].toDouble()).toFloat() / 50f).coerceIn(0.05f, 1f)
                                        }
                                    }
                                    fft = out
                                }
                            }
                        }, Visualizer.getMaxCaptureRate() / 2, true, true)
                        enabled = true
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                vis?.enabled = false
                vis?.release()
                eq?.release()
                player?.stop()
                player?.release()
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF070A10))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // HEADER
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF0F1219))
                .border(1.dp, border, RoundedCornerShape(14.dp))
                .padding(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Folder,
                    contentDescription = "Carregar Músicas",
                    tint = cyan,
                    modifier = Modifier
                        .size(26.dp)
                        .clickable { if (hasPerm) load() else permLauncher.launch(perms) }
                )
                Text("HI-FI PLAYER", color = cyan, fontWeight = FontWeight.Black, fontSize = 15.sp, letterSpacing = 1.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(
                        Icons.Filled.Equalizer,
                        contentDescription = "EQ",
                        tint = if (showEq) cyan else Color.Gray,
                        modifier = Modifier
                            .size(22.dp)
                            .clickable { showEq = !showEq }
                    )
                    Icon(
                        Icons.Filled.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (shuffle) cyan else Color.Gray,
                        modifier = Modifier
                            .size(22.dp)
                            .clickable { shuffle = !shuffle }
                    )
                    Icon(
                        Icons.Filled.Repeat,
                        contentDescription = "Repeat",
                        tint = if (repeat != RepeatMode.OFF) cyan else Color.Gray,
                        modifier = Modifier
                            .size(22.dp)
                            .clickable {
                                repeat = when (repeat) {
                                    RepeatMode.OFF -> RepeatMode.ALL
                                    RepeatMode.ALL -> RepeatMode.ONE
                                    RepeatMode.ONE -> RepeatMode.OFF
                                }
                            }
                    )
                }
            }
        }

        if (!hasPerm) {
            Button(
                onClick = { permLauncher.launch(perms) },
                colors = ButtonDefaults.buttonColors(containerColor = cyan),
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .height(44.dp)
                    .align(Alignment.CenterHorizontally),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text("PERMITIR ACESSO AS MUSICAS", color = Color.Black, fontWeight = FontWeight.Black, fontSize = 13.sp)
            }
        }

        // 5-BAND EQUALIZER
        if (showEq) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(card)
                    .border(1.dp, border, RoundedCornerShape(12.dp))
                    .padding(10.dp)
            ) {
                Column {
                    Text("5-BAND EQUALIZER • ARRASTE VERTICAL", color = cyan, fontSize = 10.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        listOf("60", "230", "910", "3.6K", "14K").forEachIndexed { i, f ->
                            var lv by remember { mutableFloatStateOf(eqLevels[i]) }
                            LaunchedEffect(eqLevels) { lv = eqLevels[i] }

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    Modifier
                                        .width(46.dp)
                                        .height(120.dp)
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(Color(0xFF080A0F))
                                        .border(1.dp, border, RoundedCornerShape(20.dp))
                                        .pointerInput(i) {
                                            detectVerticalDragGestures { _, dragAmount ->
                                                val nv = (lv - dragAmount / 120f).coerceIn(0f, 1f)
                                                lv = nv
                                                val nl = eqLevels.toMutableList()
                                                nl[i] = nv
                                                eqLevels = nl
                                                eq?.let { e ->
                                                    val r = e.bandLevelRange
                                                    e.setBandLevel(i.toShort(), (r[0] + (r[1] - r[0]) * nv).toInt().toShort())
                                                }
                                            }
                                        }
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .fillMaxHeight(lv)
                                            .align(Alignment.BottomCenter)
                                            .background(cyan.copy(alpha = 0.35f))
                                    )
                                    Box(
                                        Modifier
                                            .size(18.dp)
                                            .clip(CircleShape)
                                            .background(cyan)
                                            .align(Alignment.BottomCenter)
                                            .offset(y = -(lv * 104).dp)
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(f, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Text("${((lv - 0.5f) * 30).toInt()}dB", color = cyan, fontSize = 7.sp)
                            }
                        }
                    }
                }
            }
        }

        // VU METER
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(vuL to "-10 -5 0 +3", vuR to "-10 -5 0 +3").forEach { (lvl, _) ->
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(card)
                        .border(1.dp, border, RoundedCornerShape(10.dp))
                        .padding(6.dp)
                ) {
                    Column {
                        Canvas(
                            Modifier
                                .fillMaxWidth()
                                .height(20.dp)
                        ) {
                            val steps = 9
                            val w = size.width / steps
                            val db = 20 * kotlin.math.log10((lvl * 1.5f).coerceAtLeast(0.01f))
                            val thresholds = listOf(-10, -8, -6, -4, -2, 0, 1, 2, 3)

                            for (j in 0 until steps) {
                                val thr = thresholds[j]
                                val active = db >= thr
                                val col = if (active) {
                                    if (thr >= 1) Color(0xFFFF1744) else cyan
                                } else Color(0xFF1E2A3A)

                                drawRect(col, Offset(j * w, 0f), Size(w - 3.dp.toPx(), size.height))
                            }
                        }
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                            Text("-10", color = Color.Gray, fontSize = 7.sp)
                            Text("-5", color = Color.Gray, fontSize = 7.sp)
                            Text("0", color = Color.Gray, fontSize = 7.sp)
                            Text("+3", color = Color.Gray, fontSize = 7.sp)
                        }
                    }
                }
            }
        }

        // SPECTRUM ANALYZER
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(card)
                .border(1.dp, border, RoundedCornerShape(12.dp))
                .padding(8.dp)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black)
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val bw = size.width / 16
                    if (isPlaying) {
                        fft.forEachIndexed { i, h ->
                            val bh = size.height * h
                            val col = when {
                                h > 0.8f -> Color(0xFFFF1744)
                                h > 0.6f -> Color(0xFFFFEB3B)
                                else -> cyan
                            }
                            drawRoundRect(
                                color = col,
                                topLeft = Offset(i * bw + 4.dp.toPx(), size.height - bh),
                                size = Size(bw - 8.dp.toPx(), bh),
                                cornerRadius = CornerRadius(3.dp.toPx())
                            )
                        }
                    } else {
                        for (i in 0 until 16) {
                            drawRoundRect(
                                color = cyan,
                                topLeft = Offset(i * bw + 4.dp.toPx(), size.height - 6.dp.toPx()),
                                size = Size(bw - 8.dp.toPx(), 4.dp.toPx()),
                                cornerRadius = CornerRadius(2.dp.toPx())
                            )
                        }
                    }
                }
            }
        }

        // CONTROLES DE PLAYER
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly, Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1A2435))
                    .border(1.dp, border, CircleShape)
                    .clickable { if (songs.isNotEmpty()) play(if (idx > 0) idx - 1 else songs.size - 1) },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Anterior", tint = Color.White, modifier = Modifier.size(28.dp))
            }

            Box(
                Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(cyan)
                    .clickable {
                        if (songs.isEmpty()) return@clickable
                        if (isPlaying) {
                            player?.pause()
                            isPlaying = false
                        } else {
                            if (player == null) play(idx) else { player?.start(); isPlaying = true }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = "Play/Pause",
                    tint = Color.Black,
                    modifier = Modifier.size(42.dp)
                )
            }

            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1A2435))
                    .border(1.dp, border, CircleShape)
                    .clickable { if (songs.isNotEmpty()) play(if (idx < songs.size - 1) idx + 1 else 0) },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Próxima", tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }

        // LISTA DE MÚSICAS
        if (songs.isNotEmpty()) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                itemsIndexed(songs) { i, s ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (i == idx) Color(0xFF102030) else card)
                            .border(1.dp, if (i == idx) cyan else border, RoundedCornerShape(10.dp))
                            .clickable { play(i) }
                            .padding(10.dp)
                    ) {
                        Text(
                            text = s.title,
                            color = if (i == idx) cyan else Color.White,
                            fontSize = 12.sp,
                            fontWeight = if (i == idx) FontWeight.Black else FontWeight.Normal,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
