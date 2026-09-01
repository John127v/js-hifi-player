package com.jshifi.player

import android.Manifest
import android.content.Context
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
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
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
    var mmr: MediaMetadataRetriever? = null
    try {
        mmr = MediaMetadataRetriever()
        mmr.setDataSource(file.absolutePath)
        val title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: file.nameWithoutExtension
        val artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: file.parentFile?.name ?: "Desconhecido"
        val album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "Álbum Desconhecido"
        val bitrate = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.let { "${it.toInt() / 1000} kbps" } ?: "1411 kbps"
        val sr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE) ?: "44100"
        SongTags(title, artist, album, bitrate, "${sr}Hz", "%.1f MB".format(file.length() / 1024f / 1024f), file.extension.uppercase())
    } catch (_: Exception) {
        SongTags(file.nameWithoutExtension, file.parentFile?.name ?: "Desconhecido", "Desconhecido", "1411 kbps", "44100Hz", "%.1f MB".format(file.length() / 1024f / 1024f), file.extension.uppercase())
    } finally {
        try { mmr?.release() } catch (_: Exception) {}
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

        val prefs = getSharedPreferences("JS_HIFI_PREFS", Context.MODE_PRIVATE)

        setContent {
            val ctx = LocalContext.current
            val scope = rememberCoroutineScope()

            var songs by remember { mutableStateOf(listOf<File>()) }
            var idx by remember { mutableStateOf(-1) }
            var isPlay by remember { mutableStateOf(false) }
            var pos by remember { mutableStateOf(0L) }
            var dur by remember { mutableStateOf(0L) }
            
            var fftValues by remember { mutableStateOf(FloatArray(15) { 0.02f }) }
            var vuLeft by remember { mutableStateOf(0.02f) }
            var vuRight by remember { mutableStateOf(0.02f) }
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
            var currentTags by remember { mutableStateOf(SongTags("JS HIFI PLAYER", "Selecione uma faixa", "-", "-", "-", "-", "-")) }

            fun releaseAudioEffects() {
                try {
                    viz?.enabled = false
                    viz?.release()
                    viz = null
                    eq?.release()
                    eq = null
                    loud?.release()
                    loud = null
                } catch (_: Exception) {}
            }

            fun setupAudioEffects(sessionId: Int) {
                if (sessionId <= 0) return
                releaseAudioEffects()

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
                                        val rmsL = (Math.sqrt(sumL / half) / 128.0).toFloat()
                                        val rmsR = (Math.sqrt(sumR / half) / 128.0).toFloat()
                                        
                                        // Filtro de amortecimento do VU (reduz resposta brusca)
                                        vuLeft = vuLeft + 0.08f * (rmsL - vuLeft)
                                        vuRight = vuRight + 0.08f * (rmsR - vuRight)
                                    }
                                }

                                override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) {
                                    fft?.let { bytes ->
                                        if (bytes.size < 64) return
                                        val bands15 = FloatArray(15)
                                        for (i in 0 until 15) {
                                            val r = bytes[2 * i].toInt()
                                            val im = bytes[2 * i + 1].toInt()
                                            val mag = hypot(r.toDouble(), im.toDouble()).toFloat()
                                            
                                            // Reduzido o ganho e atenuação para evitar saturação do espectro
                                            val multiplier = if (i < 3) 0.6f else if (i < 8) 0.8f else 1.0f
                                            val rawTarget = ((mag * multiplier) / 60f).coerceIn(0.01f, 1.0f)
                                            
                                            // Suavização da queda (decay) para efeito suave estilo LED
                                            val currentVal = fftValues.getOrElse(i) { 0.01f }
                                            bands15[i] = if (rawTarget > currentVal) {
                                                currentVal + 0.25f * (rawTarget - currentVal)
                                            } else {
                                                currentVal - 0.08f * (currentVal - rawTarget)
                                            }.coerceIn(0.01f, 1.0f)
                                        }
                                        fftValues = bands15
                                    }
                                }
                            }, Visualizer.getMaxCaptureRate(), true, true)
                            enabled = true
                        }
                    } catch (_: Exception) {}
                }

                try {
                    eq = Equalizer(0, sessionId).apply { enabled = true }
                    loud = LoudnessEnhancer(sessionId).apply { enabled = highGain }
                    val bands = eq?.numberOfBands ?: 0
                    for (i in eqLevels.indices) {
                        if (i < bands) eq?.setBandLevel(i.toShort(), eqLevels[i].toShort())
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

                if (currentDir.exists() && currentDir.isDirectory) {
                    dirFiles = currentDir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
                }

                val savedPath = prefs.getString("LAST_SONG_PATH", null)
                val savedPos = prefs.getLong("LAST_SONG_POS", 0L)
                if (savedPath != null) {
                    val file = File(savedPath)
                    if (file.exists()) {
                        songs = listOf(file)
                        idx = 0
                        currentTags = getTags(file)
                        player.setMediaItem(MediaItem.fromUri(file.toURI().toString()), savedPos)
                        player.prepare()
                    }
                }
            }

            fun buildShuffleQueue(startIdx: Int = idx) {
                if (songs.isEmpty()) return
                val list = songs.indices.toMutableList()
                list.remove(startIdx)
                list.shuffle()
                shuffleQueue = listOf(startIdx) + list
                shufflePos = 0
            }

            fun playAt(i: Int, startPosition: Long = 0L) {
                if (i in songs.indices) {
                    idx = i
                    val targetFile = songs[i]
                    prefs.edit().putString("LAST_SONG_PATH", targetFile.absolutePath).apply()
                    scope.launch { currentTags = getTags(targetFile) }
                    
                    if (shuffleMode && shuffleQueue.isEmpty()) buildShuffleQueue(i)
                    
                    player.clearMediaItems()
                    player.setMediaItem(MediaItem.fromUri(targetFile.toURI().toString()), startPosition)
                    player.prepare()
                    player.play()
                }
            }

            fun playNext() {
                if (songs.isEmpty()) return
                when {
                    repeatMode == RepeatMode.ONE -> { 
                        player.seekTo(0)
                        player.play() 
                    }
                    shuffleMode -> {
                        if (shufflePos + 1 < shuffleQueue.size) {
                            shufflePos++
                            playAt(shuffleQueue[shufflePos])
                        } else if (repeatMode == RepeatMode.ALL) {
                            buildShuffleQueue()
                            playAt(shuffleQueue[0])
                        } else {
                            player.pause()
                        }
                    }
                    else -> {
                        val next = idx + 1
                        if (next < songs.size) playAt(next) else if (repeatMode == RepeatMode.ALL) playAt(0)
                    }
                }
            }

            fun playPrevious() {
                if (songs.isEmpty()) return
                if (player.currentPosition > 3000L) {
                    player.seekTo(0)
                    return
                }
                when {
                    shuffleMode -> {
                        if (shufflePos - 1 >= 0) {
                            shufflePos--
                            playAt(shuffleQueue[shufflePos])
                        } else {
                            playAt(shuffleQueue.last())
                        }
                    }
                    else -> {
                        val prev = if (idx - 1 >= 0) idx - 1 else songs.size - 1
                        playAt(prev)
                    }
                }
            }

            LaunchedEffect(volume) { player.volume = volume }

            DisposableEffect(player) {
                val listener = object : Player.Listener {
                    override fun onAudioSessionIdChanged(audioSessionId: Int) {
                        if (audioSessionId != 0) {
                            setupAudioEffects(audioSessionId)
                        }
                    }

                    override fun onIsPlayingChanged(p: Boolean) {
                        isPlay = p
                    }

                    override fun onPlaybackStateChanged(s: Int) {
                        dur = player.duration.coerceAtLeast(0L)
                        if (s == Player.STATE_ENDED) {
                            playNext()
                        }
                    }
                }
                player.addListener(listener)
                onDispose {
                    player.removeListener(listener)
                    releaseAudioEffects()
                }
            }

            LaunchedEffect(isPlay) {
                while (isPlay) {
                    pos = player.currentPosition
                    dur = player.duration.coerceAtLeast(0L)
                    prefs.edit().putLong("LAST_SONG_POS", pos).apply()
                    delay(200)
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
                        // Header Bar com botão de Fechar App
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF0F1219))
                                .border(1.dp, borderNeon, RoundedCornerShape(14.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(
                                        "☰",
                                        color = cyanNeon,
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.clickable { showMenu = true }
                                    )
                                    Text("JS HIFI PLAYER", color = cyanNeon, fontSize = 18.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("⚙", color = cyanNeon, fontSize = 18.sp, modifier = Modifier.clickable { showEq = !showEq })
                                    // Botão Fechar App no cabeçalho
                                    Text(
                                        "✕",
                                        color = Color(0xFFFF5252),
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.clickable { finishAffinity() }
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // Banner Metadata
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF0D1118))
                                .border(1.dp, cyanNeon.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                                .padding(10.dp)
                        ) {
                            Column {
                                MarqueeRender(currentTags.title.ifEmpty { songs.getOrNull(idx)?.name ?: "JS HIFI - Selecione uma faixa" })
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "${currentTags.artist} • ${currentTags.album} • ${currentTags.format} • ${currentTags.sampleRate}",
                                    color = cyanNeon,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // Painel VU Meters
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(cardBg)
                                .border(1.dp, borderNeon, RoundedCornerShape(12.dp))
                                .padding(8.dp)
                                .clickable { vuModeAnalog = !vuModeAnalog }
                        ) {
                            Column {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(if (vuModeAnalog) "ANALOG STEREO VU" else "BARGRAPH VU MODE (-20dB a +3dB)", color = cyanNeon, fontSize = 10.sp, fontWeight = FontWeight.Black)
                                }
                                Spacer(Modifier.height(10.dp))
                                if (vuModeAnalog) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                        VUMeterRender(vuLeft, "LEFT")
                                        VUMeterRender(vuRight, "RIGHT")
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        BargraphSegmented(vuLeft, "L")
                                        BargraphSegmented(vuRight, "R")
                                    }
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        BargraphSegmented(vuLeft, "L")
                                        BargraphSegmented(vuRight, "R")
                                        BargraphSegmented((vuLeft + vuRight) / 2, "MASTER")
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // Espectro estilo MicroLED Matrix com cores parametrizadas
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(cardBg)
                                .border(1.dp, borderNeon, RoundedCornerShape(12.dp))
                                .padding(8.dp)
                        ) {
                            Column {
                                Text("MICROLED SPECTRUM ANALYZER", color = cyanNeon, fontSize = 10.sp, fontWeight = FontWeight.Black)
                                Spacer(Modifier.height(6.dp))
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(100.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFF05070B))
                                ) {
                                    Canvas(Modifier.fillMaxSize()) {
                                        val bands = fftValues.size
                                        val totalLedsHeight = 20 // 20 segmentos de MicroLED por coluna
                                        val colWidth = size.width / bands
                                        val ledSpacingY = 2f
                                        val ledSpacingX = 3f
                                        val ledHeight = (size.height - (totalLedsHeight * ledSpacingY)) / totalLedsHeight

                                        fftValues.forEachIndexed { bandIdx, amp ->
                                            val x = bandIdx * colWidth
                                            val activeLeds = (amp.coerceIn(0.01f, 1.0f) * totalLedsHeight).toInt()

                                            for (ledIdx in 0 until totalLedsHeight) {
                                                val y = size.height - ((ledIdx + 1) * (ledHeight + ledSpacingY))
                                                val ledPercent = (ledIdx + 1).toFloat() / totalLedsHeight.toFloat()
                                                val isActive = ledIdx < activeLeds

                                                // Definindo as cores com base na porcentagem de altura pedida
                                                val ledColor = when {
                                                    !isActive -> Color(0xFF101620) // LED desligado
                                                    ledPercent <= 0.40f -> Color(0xFF00E676) // Verde (0 - 40%)
                                                    ledPercent <= 0.709f -> Color(0xFFFF9100) // Laranja (40.1% - 70.9%)
                                                    else -> Color(0xFFFF1744) // Vermelho (71% - 100%)
                                                }

                                                drawRoundRect(
                                                    color = ledColor,
                                                    topLeft = Offset(x + ledSpacingX, y),
                                                    size = Size(colWidth - (ledSpacingX * 2), ledHeight),
                                                    cornerRadius = CornerRadius(2f, 2f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(6.dp))

                        // Modos de Reprodução & Botões de Controle
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = if (shuffleMode) "🔀 ON" else "🔀 OFF",
                                    color = if (shuffleMode) cyanNeon else Color.Gray,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable {
                                        shuffleMode = !shuffleMode
                                        if (shuffleMode) buildShuffleQueue()
                                    }
                                )
                                Text(
                                    text = when (repeatMode) {
                                        RepeatMode.OFF -> "🔁 OFF"
                                        RepeatMode.ALL -> "🔁 ALL"
                                        RepeatMode.ONE -> "🔂 1"
                                    },
                                    color = if (repeatMode != RepeatMode.OFF) cyanNeon else Color.Gray,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable {
                                        repeatMode = when (repeatMode) {
                                            RepeatMode.OFF -> RepeatMode.ALL
                                            RepeatMode.ALL -> RepeatMode.ONE
                                            RepeatMode.ONE -> RepeatMode.OFF
                                        }
                                    }
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "⏮",
                                    color = cyanNeon,
                                    fontSize = 22.sp,
                                    modifier = Modifier.clickable { playPrevious() }
                                )
                                Box(
                                    Modifier
                                        .size(42.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF0B1724))
                                        .border(1.5.dp, cyanNeon, CircleShape)
                                        .clickable {
                                            if (player.isPlaying) {
                                                player.pause()
                                            } else {
                                                if (idx == -1 && songs.isNotEmpty()) playAt(0) else player.play()
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(if (isPlay) "⏸" else "▶", color = cyanNeon, fontSize = 18.sp)
                                }
                                Text(
                                    text = "⏭",
                                    color = cyanNeon,
                                    fontSize = 22.sp,
                                    modifier = Modifier.clickable { playNext() }
                                )
                            }

                            Slider(
                                value = volume,
                                onValueChange = { volume = it },
                                modifier = Modifier.width(85.dp),
                                colors = SliderDefaults.colors(activeTrackColor = cyanNeon, thumbColor = cyanNeon)
                            )
                        }

                        fun formatTime(ms: Long) = "%02d:%02d".format((ms / 1000 / 60).toInt(), (ms / 1000 % 60).toInt())
                        Text(
                            text = "${formatTime(pos)} / ${formatTime(dur)}",
                            color = cyanNeon.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 2.dp)
                        )

                        // Lista de reprodução
                        LazyColumn(Modifier.weight(1f).padding(top = 4.dp)) {
                            itemsIndexed(songs) { i, f ->
                                val sel = i == idx
                                TextButton(
                                    onClick = { playAt(i) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (sel) Color(0xFF102030) else Color.Transparent)
                                        .padding(vertical = 2.dp)
                                ) {
                                    Text(
                                        f.name,
                                        color = if (sel) cyanNeon else Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    // Popup Equalizador
                    if (showEq) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.7f))
                                .clickable { showEq = false },
                            contentAlignment = Alignment.Center
                        ) {
                            Card(
                                Modifier
                                    .fillMaxWidth(0.9f)
                                    .padding(16.dp)
                                    .clickable(enabled = false) {},
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF121620))
                            ) {
                                Column(Modifier.padding(16.dp)) {
                                    Text("EQUALIZADOR HIFI", color = cyanNeon, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    Spacer(Modifier.height(12.dp))
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("High Gain / Loudness", color = Color.White, fontSize = 14.sp)
                                        Switch(
                                            checked = highGain,
                                            onCheckedChange = {
                                                highGain = it
                                                try { loud?.enabled = highGain } catch (_: Exception) {}
                                            }
                                        )
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    val freqLabels = listOf("60Hz", "230Hz", "910Hz", "4kHz", "14kHz")
                                    eqLevels.forEachIndexed { i, level ->
                                        Column {
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text(freqLabels.getOrElse(i) { "Band $i" }, color = Color.Gray, fontSize = 12.sp)
                                                Text("${level / 100} dB", color = cyanNeon, fontSize = 12.sp)
                                            }
                                            Slider(
                                                value = level.toFloat(),
                                                onValueChange = { v ->
                                                    eqLevels[i] = v.toInt()
                                                    try {
                                                        eq?.setBandLevel(i.toShort(), v.toInt().toShort())
                                                    } catch (_: Exception) {}
                                                },
                                                valueRange = -1500f..1500f,
                                                colors = SliderDefaults.colors(activeTrackColor = cyanNeon, thumbColor = cyanNeon)
                                            )
                                        }
                                    }
                                    Button(onClick = { showEq = false }, modifier = Modifier.align(Alignment.End)) {
                                        Text("Concluído")
                                    }
                                }
                            }
                        }
                    }

                    // Menu Lateral
                    if (showMenu) {
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f))) {
                            Card(
                                Modifier
                                    .fillMaxWidth(0.85f)
                                    .fillMaxHeight()
                                    .align(Alignment.CenterStart),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF10131A))
                            ) {
                                Column(Modifier.padding(16.dp)) {
                                    Text("MENU JS HIFI V10", color = goldDial, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(16.dp))
                                    Button(
                                        onClick = {
                                            val list = mutableListOf<File>()
                                            ctx.contentResolver.query(
                                                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                                                arrayOf(MediaStore.Audio.Media.DATA),
                                                null,
                                                null,
                                                null
                                            )?.use { c ->
                                                val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                                                while (c.moveToNext()) {
                                                    val path = c.getString(id) ?: continue
                                                    val f = File(path)
                                                    if (f.exists()) list.add(f)
                                                }
                                            }
                                            songs = list
                                            if (shuffleMode) buildShuffleQueue()
                                            showMenu = false
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("📂 Todas as músicas", fontSize = 14.sp)
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Button(
                                        onClick = {
                                            showDirBrowser = true
                                            showMenu = false
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("📁 Abrir Diretório", fontSize = 14.sp)
                                    }
                                    
                                    Spacer(Modifier.weight(1f))
                                    
                                    // Botão de Fechar Aplicativo no Menu
                                    Button(
                                        onClick = { finishAffinity() },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("🚪 Sair do Aplicativo", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    }
                                    
                                    Spacer(Modifier.height(8.dp))
                                    TextButton(onClick = { showMenu = false }, modifier = Modifier.fillMaxWidth()) {
                                        Text("FECHAR MENU", color = Color.Gray, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }

                    // Navegador de Pastas
                    if (showDirBrowser) {
                        Box(Modifier.fillMaxSize().background(Color(0xFF06070A))) {
                            Column(Modifier.fillMaxSize().padding(12.dp)) {
                                Text("📁 ${currentDir.absolutePath.takeLast(35)}", color = Color.White, fontSize = 12.sp)
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { 
                                        currentDir.parentFile?.let { 
                                            currentDir = it 
                                            dirFiles = currentDir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
                                        } 
                                    }) {
                                        Text("⬆ Voltar", fontSize = 14.sp)
                                    }
                                    Button(
                                        onClick = {
                                            songs = dirFiles.filter { !it.isDirectory && it.extension.lowercase() in listOf("mp3", "flac", "wav", "m4a") }
                                            if (shuffleMode) buildShuffleQueue()
                                            showDirBrowser = false
                                        }
                                    ) {
                                        Text("▶ Tocar pasta", fontSize = 14.sp)
                                    }
                                }
                                LazyColumn(Modifier.weight(1f)) {
                                    items(dirFiles) { f ->
                                        TextButton(
                                            onClick = {
                                                if (f.isDirectory) {
                                                    currentDir = f
                                                    dirFiles = currentDir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
                                                } else {
                                                    songs = listOf(f)
                                                    playAt(0)
                                                    showDirBrowser = false
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                        ) {
                                            Text(
                                                (if (f.isDirectory) "📁 " else "🎵 ") + f.name,
                                                color = Color.White,
                                                fontSize = 14.sp,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                                Button(onClick = { showDirBrowser = false }, modifier = Modifier.fillMaxWidth()) {
                                    Text("Fechar", fontSize = 14.sp)
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
        try {
            viz?.enabled = false
            viz?.release()
            eq?.release()
            loud?.release()
            player.release()
        } catch (_: Exception) {}
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
            .basicMarquee(iterations = Int.MAX_VALUE, velocity = 40.dp)
    )
}

@Composable
fun VUMeterRender(level: Float, label: String) {
    val animLevel by animateFloatAsState(targetValue = level, animationSpec = tween(60), label = "vu")
    Box(
        Modifier
            .size(132.dp)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(Color(0xFF2A2D36), Color(0xFF0F1117)), radius = 200f))
            .border(3.dp, Color(0xFFC9A84C), CircleShape)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val centerOffset = Offset(size.width / 2, size.height / 2 + 10)
            val radius = size.minDimension / 2 - 14
            drawCircle(
                Brush.radialGradient(
                    listOf(Color(0xFFFFF8E1), Color(0xFFFFE082).copy(alpha = 0.8f), Color(0xFF8D6E63)),
                    center = centerOffset,
                    radius = radius
                ),
                radius = radius - 2,
                center = centerOffset
            )
            for (db in -20..3 step 2) {
                val ang = -115 + ((db + 20) / 23f) * 230f
                val rad = Math.toRadians(ang.toDouble() - 90)
                val r1 = radius - 4
                val r2 = if (db % 10 == 0) radius - 16 else radius - 10
                val col = if (db >= 0) Color.Red else Color.Black
                drawLine(
                    col,
                    centerOffset + Offset(cos(rad).toFloat() * r2, sin(rad).toFloat() * r2),
                    centerOffset + Offset(cos(rad).toFloat() * r1, sin(rad).toFloat() * r1),
                    if (db % 10 == 0) 1.6.dp.toPx() else 1.dp.toPx()
                )
            }
            val ang = -115 + animLevel.coerceIn(0f, 1f) * 230f
            val rad = Math.toRadians(ang.toDouble() - 90)
            val x = centerOffset.x + cos(rad).toFloat() * (radius - 12)
            val y = centerOffset.y + sin(rad).toFloat() * (radius - 12)
            drawLine(Color.Black, centerOffset, Offset(x, y), strokeWidth = 2.8.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(Color(0xFF101010), 10.dp.toPx(), centerOffset)
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("VU", color = Color.Black.copy(alpha = 0.6f), fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Text(label, color = Color(0xFF8D6E63), fontSize = 10.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
fun BargraphSegmented(level: Float, label: String) {
    val db = (20 * log10(level.coerceAtLeast(0.0001f).toDouble())).toFloat().coerceIn(-10f, 3f)
    val percent = ((db + 10f) / 13f).coerceIn(0f, 1f)
    
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = Color(0xFF00E5FF), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(52.dp))
            Box(
                Modifier
                    .weight(1f)
                    .height(18.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFF0A0E15))
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val segments = 26
                    val segW = size.width / segments
                    val activeSegs = (segments * percent).toInt()
                    for (i in 0 until segments) {
                        val x = i * segW
                        val isActive = i < activeSegs
                        val segPercent = (i + 1).toFloat() / segments.toFloat()
                        
                        val col = when {
                            !isActive -> Color(0xFF141A24)
                            segPercent <= 0.40f -> Color(0xFF00E676)
                            segPercent <= 0.709f -> Color(0xFFFF9100)
                            else -> Color(0xFFFF1744)
                        }
                        drawRect(col, topLeft = Offset(x + 1, 2f), size = Size(segW - 2, size.height - 4f))
                    }
                }
            }
        }
    }
}
