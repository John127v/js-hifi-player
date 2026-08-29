package com.example.jshifi.player

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin

enum class RepeatMode { OFF, ALL, ONE }
data class SongTags(
    val title: String,
    val artist: String,
    val album: String,
    val bitrate: String,
    val sampleRate: String,
    val size: String,
    val format: String
)

suspend fun getTags(file: File): SongTags = withContext(Dispatchers.IO) {
    try {
        val mmr = MediaMetadataRetriever()
        mmr.setDataSource(file.absolutePath)
        val title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: file.nameWithoutExtension
        val artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: file.parentFile?.name ?: "Desconhecido"
        val album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "Live at Gillette Stadium"
        val bitrate = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.let { "${it.toInt()/1000} kbps" } ?: "1288 kbps"
        val sr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE) ?: "48000"
        val fmt = file.extension.uppercase()
        mmr.release()
        SongTags(title, artist, album, bitrate, "${sr}Hz/24bit", "%.1f MB".format(file.length()/1024f/1024f), fmt)
    } catch (_: Exception) {
        SongTags(file.nameWithoutExtension, file.parentFile?.name ?: "Desconhecido", "Live", "1288 kbps", "48kHz/24bit", "%.1f MB".format(file.length()/1024f/1024f), file.extension.uppercase())
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private var eq: Equalizer? = null
    private var loud: LoudnessEnhancer? = null
    private var viz: Visualizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this).build()

        setContent {
            val ctx = LocalContext.current
            val scope = rememberCoroutineScope()

            var songs by remember { mutableStateOf(listOf<File>()) }
            var idx by remember { mutableStateOf(-1) }
            var isPlay by remember { mutableStateOf(false) }
            var pos by remember { mutableStateOf(0L) }
            var dur by remember { mutableStateOf(0L) }
            var fftValues by remember { mutableStateOf(List(64) { 0.05f }) }
            var vuLeft by remember { mutableStateOf(0.05f) }
            var vuRight by remember { mutableStateOf(0.05f) }
            var volume by remember { mutableStateOf(0.8f) }
            var highGain by remember { mutableStateOf(false) }
            var showEq by remember { mutableStateOf(false) }
            var showMenu by remember { mutableStateOf(false) }
            var showDirBrowser by remember { mutableStateOf(false) }
            var vuModeAnalog by remember { mutableStateOf(true) }
            var currentDir by remember { mutableStateOf(File("/storage/emulated/0/Music")) }
            var dirFiles by remember { mutableStateOf(listOf<File>()) }
            val eqLevels = remember { mutableStateListOf(0, 0, 0, 0, 0) }
            var repeatMode by remember { mutableStateOf(RepeatMode.ALL) }
            var shuffleMode by remember { mutableStateOf(false) }
            var shuffleQueue by remember { mutableStateOf(listOf<Int>()) }
            var shufflePos by remember { mutableStateOf(0) }
            var currentTags by remember { mutableStateOf(SongTags("Selecione uma faixa", "-", "-", "-", "-", "-", "-")) }

            fun setupAudioEffects(sessionId: Int) {
                if (sessionId == 0) return
                try { viz?.enabled = false; viz?.release(); viz = null } catch (_: Exception) {}
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    try {
                        viz = Visualizer(sessionId).apply {
                            captureSize = Visualizer.getCaptureSizeRange()[1]
                            setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                                override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, rate: Int) {
                                    waveform?.let { bytes ->
                                        if (bytes.isEmpty()) return
                                        var sumL = 0.0
                                        var sumR = 0.0
                                        val half = bytes.size / 2
                                        for (i in 0 until half) {
                                            val s = (bytes[i].toInt() and 0xFF) - 128
                                            sumL += s * s
                                        }
                                        for (i in half until bytes.size) {
                                            val s = (bytes[i].toInt() and 0xFF) - 128
                                            sumR += s * s
                                        }
                                        val rmsL = Math.sqrt(sumL / half) / 128.0
                                        val rmsR = Math.sqrt(sumR / half) / 128.0
                                        vuLeft = (vuLeft * 0.70f + rmsL.toFloat() * 0.30f).coerceIn(0.05f, 1f)
                                        vuRight = (vuRight * 0.70f + rmsR.toFloat() * 0.30f).coerceIn(0.05f, 1f)
                                    }
                                }

                                override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) {
                                    fft?.let { bytes ->
                                        if (bytes.size < 128) return
                                        val bands = FloatArray(64)
                                        for (i in 0 until 64) {
                                            val r = bytes[2 * i].toInt()
                                            val im = bytes[2 * i + 1].toInt()
                                            val mag = hypot(r.toDouble(), im.toDouble()).toFloat()
                                            bands[i] = (mag / 38f).coerceIn(0.05f, 1.1f)
                                        }
                                        fftValues = bands.toList()
                                    }
                                }
                            }, Visualizer.getMaxCaptureRate() / 2, true, true)
                            enabled = true
                        }
                    } catch (_: Exception) {}
                }
                try {
                    eq?.release(); loud?.release()
                    eq = Equalizer(0, sessionId).apply { enabled = true }
                    loud = LoudnessEnhancer(sessionId).apply { enabled = highGain }
                    for (i in eqLevels.indices) {
                        if (i < (eq?.numberOfBands ?: 0)) eq?.setBandLevel(i.toShort(), eqLevels[i].toShort())
                    }
                } catch (_: Exception) {}
            }

            val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
                if (perms[Manifest.permission.RECORD_AUDIO] == true && player.audioSessionId != 0) {
                    setupAudioEffects(player.audioSessionId)
                }
            }

            LaunchedEffect(Unit) {
                val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    perms.add(Manifest.permission.READ_MEDIA_AUDIO)
                } else {
                    perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
                permissionLauncher.launch(perms.toTypedArray())
                if (!File("/storage/emulated/0/Music").exists()) currentDir = File("/storage/emulated/0/")
            }

            fun refreshDir() {
                dirFiles = currentDir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))?.toList() ?: emptyList()
            }

            LaunchedEffect(currentDir) { refreshDir() }

            fun buildShuffleQueue(startIdx: Int = idx) {
                if (songs.isEmpty()) return
                val list = songs.indices.toMutableList()
                list.remove(startIdx)
                list.shuffle()
                shuffleQueue = listOf(startIdx) + list
                shufflePos = 0
            }

            fun playAt(i: Int) {
                if (i in songs.indices) {
                    idx = i
                    scope.launch { currentTags = getTags(songs[i]) }
                    if (shuffleMode && shuffleQueue.isEmpty()) buildShuffleQueue(i)
                    player.setMediaItem(MediaItem.fromUri(songs[i].toURI().toString()))
                    player.prepare()
                    player.play()
                }
            }

            fun playNext() {
                if (songs.isEmpty()) return
                when {
                    repeatMode == RepeatMode.ONE -> { player.seekTo(0); player.play() }
                    shuffleMode -> {
                        if (shufflePos + 1 < shuffleQueue.size) {
                            shufflePos++
                            playAt(shuffleQueue[shufflePos])
                        } else if (repeatMode == RepeatMode.ALL) {
                            buildShuffleQueue()
                            playAt(shuffleQueue[0])
                        }
                    }
                    else -> {
                        val next = idx + 1
                        if (next < songs.size) playAt(next)
                        else if (repeatMode == RepeatMode.ALL) playAt(0)
                    }
                }
            }

            fun playPrev() {
                if (shuffleMode && shufflePos > 0) {
                    shufflePos--
                    playAt(shuffleQueue[shufflePos])
                } else {
                    val prev = idx - 1
                    if (prev >= 0) playAt(prev)
                    else if (repeatMode == RepeatMode.ALL && songs.isNotEmpty()) playAt(songs.size - 1)
                }
            }

            LaunchedEffect(volume) { player.volume = volume }

            DisposableEffect(player) {
                val listener = object : Player.Listener {
                    override fun onIsPlayingChanged(p: Boolean) {
                        isPlay = p
                        if (p && viz == null) setupAudioEffects(player.audioSessionId)
                    }
                    override fun onPlaybackStateChanged(s: Int) {
                        dur = player.duration.coerceAtLeast(0L)
                    }
                }
                player.addListener(listener)
                onDispose { player.removeListener(listener) }
            }

            LaunchedEffect(isPlay) {
                while (isPlay) {
                    pos = player.currentPosition
                    dur = player.duration.coerceAtLeast(0L)
                    if (pos >= dur - 600 && dur > 1000) {
                        playNext()
                        break
                    }
                    delay(120)
                }
            }

            val bgDark = Color(0xFF06070A)
            val cardBg = Color(0xFF10131A)
            val cyanNeon = Color(0xFF00E5FF)
            val goldDial = Color(0xFFC9A84C)
            val borderNeon = Color(0xFF1E2A3A)

            MaterialTheme {
                Box(Modifier.fillMaxSize().background(bgDark)) {
                    Column(Modifier.fillMaxSize().padding(8.dp)) {
                        // HEADER
                        Box(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF0F1219))
                                .border(1.dp, borderNeon, RoundedCornerShape(14.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("‹", color = cyanNeon, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { showMenu = true })
                                    Text("HI-FI PLAYER", color = cyanNeon, fontSize = 18.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Text("⚙", color = cyanNeon, fontSize = 18.sp, modifier = Modifier.clickable { showEq = !showEq })
                                    Text("▅▇▆", color = cyanNeon, fontSize = 16.sp)
                                    Text("•••", color = cyanNeon, fontSize = 16.sp)
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // MARQUEE DE TÍTULO
                        Box(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF0D1118))
                                .border(1.dp, cyanNeon.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                                .padding(10.dp)
                        ) {
                            Column {
                                MarqueeRender(currentTags.title.ifEmpty { songs.getOrNull(idx)?.name ?: "Metallica - Enter Sandman (Remastered 2024 Live Version)" })
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "${currentTags.artist} • ${currentTags.album} • ${currentTags.format} • ${currentTags.sampleRate} • ${currentTags.bitrate}",
                                    color = cyanNeon,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // PAINEL VU
                        Box(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(cardBg)
                                .border(1.dp, borderNeon, RoundedCornerShape(12.dp))
                                .padding(8.dp)
                                .clickable { vuModeAnalog = !vuModeAnalog }
                        ) {
                            Column {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(if (vuModeAnalog) "ANALOG STEREO VU" else "BARGRAPH VU MODE", color = cyanNeon, fontSize = 10.sp, fontWeight = FontWeight.Black)
                                    Box(
                                        Modifier.clip(RoundedCornerShape(10.dp))
                                            .border(1.dp, cyanNeon.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                                            .padding(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text(if (vuModeAnalog) "MODE: ANALOG" else "MODE: BARGRAPH", color = Color.Gray, fontSize = 8.sp)
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                                if (vuModeAnalog) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                        VUMeterRender(vuLeft, "LEFT")
                                        VUMeterRender(vuRight, "RIGHT")
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Text("CAL • 0dB = +4dBu • WEIGHTED RMS", color = cyanNeon.copy(alpha = 0.7f), fontSize = 8.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                                    Spacer(Modifier.height(8.dp))
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        BargraphSegmented(vuLeft, "L")
                                        BargraphSegmented(vuRight, "R")
                                        BargraphSegmented((vuLeft + vuRight) / 2, "MASTER")
                                    }
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        BargraphSegmented(vuLeft, "L")
                                        BargraphSegmented(vuRight, "R")
                                        BargraphSegmented((vuLeft + vuRight) / 2, "MASTER")
                                        Text("TOQUE PARA VOLTAR AO ANALÓGICO", color = Color.Gray, fontSize = 7.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // SPECTRUM ANALYZER
                        Box(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(cardBg)
                                .border(1.dp, borderNeon, RoundedCornerShape(12.dp))
                                .padding(8.dp)
                        ) {
                            Column {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("SPECTRUM ANALYZER • 31-BAND", color = cyanNeon, fontSize = 10.sp, fontWeight = FontWeight.Black)
                                    Box(
                                        Modifier.clip(RoundedCornerShape(10.dp))
                                            .border(1.dp, cyanNeon.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                                            .padding(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text("FAST • PEAK HOLD ON", color = Color.Gray, fontSize = 7.sp)
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                Box(Modifier.fillMaxWidth().height(90.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF080A0F))) {
                                    Canvas(Modifier.fillMaxSize()) {
                                        val barW = size.width / fftValues.size
                                        fftValues.forEachIndexed { i, h ->
                                            val x = i * barW
                                            val bh = size.height * h.coerceIn(0.05f, 1f)
                                            val col = if (h > 0.9f) Color.Red else if (h > 0.7f) Color.Yellow else cyanNeon
                                            drawRect(col, topLeft = Offset(x + 1, size.height - bh), size = Size(barW - 2, bh))
                                        }

                                        val pathPoints = mutableListOf<Offset>()
                                        fftValues.forEachIndexed { i, h ->
                                            pathPoints.add(Offset(i * barW + barW / 2, size.height - size.height * h * 0.7f))
                                        }
                                        for (j in 0 until pathPoints.size - 1) {
                                            drawLine(cyanNeon.copy(alpha = 0.4f), pathPoints[j], pathPoints[j + 1], strokeWidth = 1f)
                                        }
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    listOf("60", "125", "250", "500", "1k", "2k", "4k", "8k", "16k", "16k", "Hz").forEach {
                                        Text(it, color = cyanNeon.copy(alpha = 0.6f), fontSize = 8.sp)
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(6.dp))

                        // CONTROLES DE REPRODUÇÃO E VOLUME
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            fun f(m: Long) = "%02d:%02d".format((m / 1000 / 60).toInt(), (m / 1000 % 60).toInt())
                            Text("${f(pos)} / ${f(dur)}", color = cyanNeon.copy(alpha = 0.7f), fontSize = 11.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("◀◀", color = cyanNeon, fontSize = 16.sp, modifier = Modifier.clickable { playPrev() })
                                Box(
                                    Modifier.size(36.dp)
                                        .clip(CircleShape)
                                        .border(1.dp, cyanNeon, CircleShape)
                                        .clickable {
                                            if (player.isPlaying) player.pause()
                                            else {
                                                if (idx == -1 && songs.isNotEmpty()) playAt(0)
                                                else {
                                                    player.play()
                                                    setupAudioEffects(player.audioSessionId)
                                                }
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(if (isPlay) "⏸" else "▶", color = cyanNeon, fontSize = 14.sp)
                                }
                                Text("▶▶", color = cyanNeon, fontSize = 16.sp, modifier = Modifier.clickable { playNext() })
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Slider(
                                    value = volume,
                                    onValueChange = { volume = it },
                                    modifier = Modifier.width(100.dp),
                                    colors = SliderDefaults.colors(activeTrackColor = cyanNeon, thumbColor = cyanNeon)
                                )
                                Text("🔊", color = cyanNeon, fontSize = 12.sp)
                            }
                        }

                        // PAINEL DE EQUALIZADOR
                        if (showEq) {
                            Card(Modifier.fillMaxWidth().padding(top = 6.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1D26))) {
                                Column(Modifier.padding(10.dp)) {
                                    Text("EQUALIZADOR 5 BANDAS", color = goldDial, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    val freqs = listOf("60Hz", "230Hz", "910Hz", "3.6kHz", "14kHz")
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                        freqs.forEachIndexed { i, f ->
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text(f, color = Color.Gray, fontSize = 8.sp)
                                                Slider(
                                                    value = eqLevels[i].toFloat(),
                                                    onValueChange = { v ->
                                                        eqLevels[i] = v.toInt()
                                                        try { eq?.setBandLevel(i.toShort(), v.toInt().toShort()) } catch (_: Exception) {}
                                                    },
                                                    valueRange = -1500f..1500f,
                                                    modifier = Modifier.height(80.dp).width(24.dp),
                                                    colors = SliderDefaults.colors(activeTrackColor = cyanNeon, thumbColor = Color.White)
                                                )
                                                Text("${eqLevels[i] / 100}dB", color = Color.White, fontSize = 7.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // LISTA DE MÚSICAS
                        LazyColumn(Modifier.weight(1f).padding(top = 4.dp)) {
                            itemsIndexed(songs) { i, f ->
                                val sel = i == idx
                                TextButton(
                                    onClick = {
                                        if (shuffleMode) { shufflePos = shuffleQueue.indexOf(i).coerceAtLeast(0) }
                                        playAt(i)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (sel) Color(0xFF102030) else Color.Transparent)
                                ) {
                                    Text(f.name, color = if (sel) cyanNeon else Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }

                        Text("ANDROID HI-FI OUTPUT • USB DAC • 24bit/48kHz", color = Color.Gray, fontSize = 7.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 2.dp))
                    }

                    // MODAL DE MENU
                    if (showMenu) {
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f))) {
                            Card(Modifier.fillMaxWidth(0.85f).fillMaxHeight().align(Alignment.CenterStart), colors = CardDefaults.cardColors(containerColor = Color(0xFF10131A))) {
                                Column(Modifier.padding(16.dp)) {
                                    Text("MENU HI-FI V10", color = goldDial, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Spacer(Modifier.height(16.dp))
                                    Button(
                                        onClick = {
                                            val list = mutableListOf<File>()
                                            ctx.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)?.use { c ->
                                                val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                                                while (c.moveToNext()) {
                                                    val f = File(c.getString(id) ?: continue)
                                                    if (f.exists()) list.add(f)
                                                }
                                            }
                                            songs = list
                                            shuffleQueue = listOf()
                                            showMenu = false
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("📂 Todas as músicas")
                                    }
                                    Button(onClick = { showDirBrowser = true; showMenu = false }, modifier = Modifier.fillMaxWidth()) {
                                        Text("📁 Abrir Diretório")
                                    }
                                    Spacer(Modifier.weight(1f))
                                    TextButton(onClick = { showMenu = false }, modifier = Modifier.fillMaxWidth()) {
                                        Text("FECHAR", color = Color.Gray)
                                    }
                                }
                            }
                        }
                    }

                    // NAVEGADOR DE DIRETÓRIOS
                    if (showDirBrowser) {
                        Box(Modifier.fillMaxSize().background(Color(0xFF06070A))) {
                            Column(Modifier.fillMaxSize().padding(12.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("📁 ${currentDir.absolutePath.takeLast(35)}", color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    IconButton(onClick = { showDirBrowser = false }) { Text("✕", color = Color.White) }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { currentDir.parentFile?.let { currentDir = it } }) { Text("⬆ Voltar") }
                                    Button(onClick = {
                                        val musicFiles = dirFiles.filter { !it.isDirectory && it.extension.lowercase() in listOf("mp3", "flac", "wav", "m4a") }
                                        songs = musicFiles
                                        showDirBrowser = false
                                        shuffleQueue = listOf()
                                    }) { Text("▶ Tocar esta pasta") }
                                }
                                LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
                                    items(dirFiles) { f ->
                                        TextButton(
                                            onClick = {
                                                if (f.isDirectory) currentDir = f
                                                else {
                                                    songs = listOf(f)
                                                    playAt(0)
                                                    showDirBrowser = false
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text(if (f.isDirectory) "📁" else "🎵")
                                                Text(f.name, color = if (f.isDirectory) Color(0xFFFFE082) else Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { viz?.enabled = false } catch (_: Exception) {}
        eq?.release()
        loud?.release()
        viz?.release()
        player.release()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MarqueeRender(text: String) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = Modifier
            .fillMaxWidth()
            .basicMarquee(
                iterations = Int.MAX_VALUE,
                velocity = 40.dp
            )
    )
}

@Composable
fun VUMeterRender(level: Float, label: String) {
    val animLevel by animateFloatAsState(targetValue = level, animationSpec = tween(80), label = "vu")
    Box(
        Modifier.size(132.dp)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(Color(0xFF2A2D36), Color(0xFF0F1117)), radius = 200f))
            .border(3.dp, Color(0xFFC9A84C), CircleShape)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val centerOffset = Offset(size.width / 2, size.height / 2 + 10)
            val radius = size.minDimension / 2 - 14
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFF8E1), Color(0xFFFFE082).copy(alpha = 0.8f), Color(0xFF8D6E63)), center = centerOffset, radius = radius), radius = radius - 2, center = centerOffset)

            for (db in -20..3 step 2) {
                val ang = -115 + ((db + 20) / 23f) * 230f
                val rad = Math.toRadians(ang.toDouble() - 90)
                val r1 = radius - 4
                val r2 = if (db % 10 == 0) radius - 16 else radius - 10
                val col = if (db >= 0) Color.Red else Color.Black
                drawLine(col, centerOffset + Offset(cos(rad).toFloat() * r2, sin(rad).toFloat() * r2), centerOffset + Offset(cos(rad).toFloat() * r1, sin(rad).toFloat() * r1), if (db % 10 == 0) 1.6.dp.toPx() else 1.dp.toPx())
            }

            val ang = -115 + animLevel.coerceIn(0f, 1f) * 230f
            val rad = Math.toRadians(ang.toDouble() - 90)
            val x = centerOffset.x + cos(rad).toFloat() * (radius - 12)
            val y = centerOffset.y + sin(rad).toFloat() * (radius - 12)
            drawLine(Color.Black, centerOffset, Offset(x, y), strokeWidth = 2.8.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(Color(0xFF101010), 10.dp.toPx(), centerOffset)
            drawCircle(Color(0xFF2A2D36), 10.dp.toPx(), centerOffset, style = Stroke(1.dp.toPx()))
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("VU", color = Color.Black.copy(alpha = 0.6f), fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Text(label, color = Color(0xFF8D6E63), fontSize = 10.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
fun BargraphSegmented(level: Float, label: String) {
    val db = (20 * log10(level.coerceAtLeast(0.001f).toDouble())).toFloat().coerceIn(-40f, 4f)
    val percent = ((db + 40) / 44f).coerceIn(0f, 1f)
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color(0xFF00E5FF), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(52.dp))
            Box(Modifier.weight(1f).height(18.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF0A0E15))) {
                Canvas(Modifier.fillMaxSize()) {
                    val segments = 42
                    val segW = size.width / segments
                    val activeSegs = (segments * percent).toInt()
                    for (i in 0 until segments) {
                        val x = i * segW
                        val isActive = i < activeSegs
                        val col = when {
                            !isActive -> Color(0xFF1A2330)
                            i < segments * 0.55 -> Color(0xFF00E676)
                            i < segments * 0.75 -> Color(0xFFFFEB3B)
                            i < segments * 0.88 -> Color(0xFFFF9800)
                            else -> Color(0xFFFF1744)
                        }
                        drawRect(col, topLeft = Offset(x + 1, 2f), size = Size(segW - 2, size.height - 4f))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 52.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("-40", "-20", "-10", "0", "+3").forEach { Text(it, color = Color.Gray, fontSize = 7.sp) }
        }
    }
}
