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
            
            var fftValues by remember { mutableStateOf(FloatArray(15) { 0.01f }) }
            var vuLeft by remember { mutableStateOf(0.01f) }
            var vuRight by remember { mutableStateOf(0.01f) }
            var volume by remember { mutableStateOf(0.8f) }
            var highGain by remember { mutableStateOf(false) }
            var showEq by remember { mutableStateOf(false) }
            var showMenu by remember { mutableStateOf(false) }
            var showDirBrowser by remember { mutableStateOf(false) }
            var showQueueView by remember { mutableStateOf(true) }
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
                                        val rmsL = (Math.sqrt(sumL / half) / 128.0).toFloat().coerceIn(0f, 1f)
                                        val rmsR = (Math.sqrt(sumR / half) / 128.0).toFloat().coerceIn(0f, 1f)
                                        
                                        vuLeft = vuLeft + 0.25f * (rmsL - vuLeft)
                                        vuRight = vuRight + 0.25f * (rmsR - vuRight)
                                    }
                                }

                                override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) {
                                    fft?.let { bytes ->
                                        if (bytes.size < 64) return
                                        val bands15 = FloatArray(15)
                                        
                                        for (i in 0 until 15) {
                                            val r = bytes[2 * i].toInt()
                                            val im = bytes[2 * i + 1].toInt()
                                            val magnitude = hypot(r.toDouble(), im.toDouble()).toFloat()
                                            
                                            // Aumento do ganho nos graves (i < 4) para resposta imediata ao bumbo/bass
                                            val boost = when (i) {
                                                0 -> 2.8f
                                                1 -> 2.4f
                                                2 -> 2.0f
                                                3 -> 1.6f
                                                else -> 1.2f
                                            }
                                            
                                            val target = ((magnitude * boost) / 45f).coerceIn(0.01f, 1.0f)
                                            val current = fftValues.getOrElse(i) { 0.01f }
                                            
                                            // Ataque rápido e queda suave dos LEDs
                                            bands15[i] = if (target > current) {
                                                current + 0.65f * (target - current)
                                            } else {
                                                current - 0.20f * (current - target)
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
            val borderNeon = Color(0xFF1E2A3A)

            MaterialTheme {
                Box(Modifier.fillMaxSize().background(bgDark)) {
                    Column(Modifier.fillMaxSize().padding(8.dp)) {
                        // Top Bar
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

                        // Faixa Info Banner
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

                        // VU Meters (Alternável entre Analógico e Bargraph LED)
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
                                    Text(
                                        if (vuModeAnalog) "ANALOG STEREO VU METER" else "BARGRAPH LED VU MODE",
                                        color = cyanNeon,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                    Text("Toque p/ alternar", color = Color.Gray, fontSize = 9.sp)
                                }
                                Spacer(Modifier.height(10.dp))
                                if (vuModeAnalog) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                        VUMeterRender(vuLeft, "LEFT")
                                        VUMeterRender(vuRight, "RIGHT")
                                    }
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        BargraphSegmented(vuLeft, "L")
                                        BargraphSegmented(vuRight, "R")
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // Espectro de Frequências Reativo (Bargraph LED rápido)
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(cardBg)
                                .border(1.dp, borderNeon, RoundedCornerShape(12.dp))
                                .padding(8.dp)
                        ) {
                            Column {
                                Text("MICROLED SPECTRUM ANALYZER (RÁPIDO / GRAVES)", color = cyanNeon, fontSize = 10.sp, fontWeight = FontWeight.Black)
                                Spacer(Modifier.height(6.dp))
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(95.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFF05070B))
                                ) {
                                    Canvas(Modifier.fillMaxSize()) {
                                        val bands = fftValues.size
                                        val totalLedsHeight = 16
                                        val colWidth = size.width / bands
                                        val ledSpacingY = 2f
                                        val ledSpacingX = 2.5f
                                        val ledHeight = (size.height - (totalLedsHeight * ledSpacingY)) / totalLedsHeight

                                        fftValues.forEachIndexed { bandIdx, amp ->
                                            val x = bandIdx * colWidth
                                            val activeLeds = (amp * totalLedsHeight).toInt()

                                            for (ledIdx in 0 until totalLedsHeight) {
                                                val y = size.height - ((ledIdx + 1) * (ledHeight + ledSpacingY))
                                                val ledPercent = (ledIdx + 1).toFloat() / totalLedsHeight.toFloat()
                                                val isActive = ledIdx < activeLeds

                                                val ledColor = when {
                                                    !isActive -> Color(0xFF0F1520)
                                                    ledPercent <= 0.45f -> Color(0xFF00E676)
                                                    ledPercent <= 0.75f -> Color(0xFFFF9100)
                                                    else -> Color(0xFFFF1744)
                                                }

                                                drawRoundRect(
                                                    color = ledColor,
                                                    topLeft = Offset(x + ledSpacingX, y),
                                                    size = Size(colWidth - (ledSpacingX * 2), ledHeight),
                                                    cornerRadius = CornerRadius(1.5f, 1.5f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(6.dp))

                        // Modos de Reprodução & Botões
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
                        }

                        Spacer(Modifier.height(6.dp))

                        // Barra de Progresso
                        Column(Modifier.fillMaxWidth()) {
                            Slider(
                                value = if (dur > 0) pos.toFloat() / dur else 0f,
                                onValueChange = { percent ->
                                    val target = (percent * dur).toLong()
                                    player.seekTo(target)
                                    pos = target
                                },
                                colors = SliderDefaults.colors(
                                    thumbColor = cyanNeon,
                                    activeTrackColor = cyanNeon,
                                    inactiveTrackColor = Color(0xFF1E2A3A)
                                )
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(formatMs(pos), color = Color.Gray, fontSize = 10.sp)
                                Text(formatMs(dur), color = Color.Gray, fontSize = 10.sp)
                            }
                        }

                        Spacer(Modifier.height(4.dp))

                        // Volume
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("🔊", color = cyanNeon, fontSize = 12.sp)
                            Slider(
                                value = volume,
                                onValueChange = { volume = it },
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = cyanNeon,
                                    activeTrackColor = cyanNeon,
                                    inactiveTrackColor = Color(0xFF1E2A3A)
                                )
                            )
                            Text("${(volume * 100).toInt()}%", color = cyanNeon, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }

                        Spacer(Modifier.height(6.dp))

                        // Fila de Reprodução Integrada
                        if (showQueueView) {
                            Text("FILA DE REPRODUÇÃO (${songs.size})", color = cyanNeon, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            
                            LazyColumn(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(cardBg)
                                    .border(1.dp, borderNeon, RoundedCornerShape(8.dp))
                            ) {
                                itemsIndexed(songs) { index, song ->
                                    val isSelected = index == idx
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(if (isSelected) Color(0xFF192535) else Color.Transparent)
                                            .clickable { playAt(index) }
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                song.nameWithoutExtension,
                                                color = if (isSelected) cyanNeon else Color.White,
                                                fontSize = 12.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                song.parentFile?.name ?: "Mídia",
                                                color = Color.Gray,
                                                fontSize = 10.sp,
                                                maxLines = 1
                                            )
                                        }
                                        if (isSelected && isPlay) {
                                            Text("♫", color = cyanNeon, fontSize = 14.sp)
                                        }
                                    }
                                    Divider(color = Color(0xFF18202C), thickness = 0.5.dp)
                                }
                            }
                        }
                    }

                    // Dialog de Equalizador
                    if (showEq) {
                        AlertDialog(
                            onDismissRequest = { showEq = false },
                            containerColor = Color(0xFF0F141C),
                            title = { Text("EQUALIZADOR HIFI", color = cyanNeon, fontWeight = FontWeight.Black) },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Ganho Alto (Loudness)", color = Color.White, fontSize = 12.sp)
                                        Switch(
                                            checked = highGain,
                                            onCheckedChange = {
                                                highGain = it
                                                try { loud?.enabled = highGain } catch (_: Exception) {}
                                            },
                                            colors = SwitchDefaults.colors(checkedThumbColor = cyanNeon)
                                        )
                                    }
                                    Divider(color = borderNeon)
                                    val bandsLabel = listOf("60Hz", "230Hz", "910Hz", "3.6kHz", "14kHz")
                                    eqLevels.forEachIndexed { index, level ->
                                        Column {
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text(bandsLabel.getOrElse(index) { "Banda $index" }, color = Color.Gray, fontSize = 11.sp)
                                                Text("${level / 100} dB", color = cyanNeon, fontSize = 11.sp)
                                            }
                                            Slider(
                                                value = level.toFloat(),
                                                onValueChange = { newValue ->
                                                    eqLevels[index] = newValue.toInt()
                                                    try { eq?.setBandLevel(index.toShort(), newValue.toInt().toShort()) } catch (_: Exception) {}
                                                },
                                                valueRange = -1500f..1500f,
                                                colors = SliderDefaults.colors(
                                                    thumbColor = cyanNeon,
                                                    activeTrackColor = cyanNeon
                                                )
                                            )
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { showEq = false }) {
                                    Text("FECHAR", color = cyanNeon, fontWeight = FontWeight.Bold)
                                }
                            }
                        )
                    }

                    // Navegador de Arquivos
                    if (showDirBrowser) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(bgDark)
                                .padding(8.dp)
                        ) {
                            Column(Modifier.fillMaxSize()) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("EXPLORADOR DE ARQUIVOS", color = cyanNeon, fontWeight = FontWeight.Black, fontSize = 14.sp)
                                    Text(
                                        "✕",
                                        color = Color.Red,
                                        fontSize = 18.sp,
                                        modifier = Modifier.clickable { showDirBrowser = false }
                                    )
                                }
                                Text(currentDir.absolutePath, color = Color.Gray, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(8.dp))

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (currentDir.parentFile != null) {
                                        Button(
                                            onClick = {
                                                currentDir = currentDir.parentFile!!
                                                dirFiles = currentDir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E2A3A))
                                        ) {
                                            Text("⬆ Voltar Pasta", fontSize = 11.sp, color = cyanNeon)
                                        }
                                    }
                                    Button(
                                        onClick = {
                                            val audioFiles = dirFiles.filter { it.isFile && isAudioFile(it) }
                                            if (audioFiles.isNotEmpty()) {
                                                songs = audioFiles
                                                playAt(0)
                                                showDirBrowser = false
                                                showQueueView = true
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))
                                    ) {
                                        Text("▶ Tocar Toda Pasta", fontSize = 11.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                                    }
                                }

                                Spacer(Modifier.height(8.dp))

                                LazyColumn(
                                    Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                ) {
                                    items(dirFiles) { file ->
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    if (file.isDirectory) {
                                                        currentDir = file
                                                        dirFiles = file.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
                                                    } else if (isAudioFile(file)) {
                                                        val audioFiles = dirFiles.filter { it.isFile && isAudioFile(it) }
                                                        songs = audioFiles
                                                        val selectedIdx = audioFiles.indexOf(file)
                                                        playAt(if (selectedIdx != -1) selectedIdx else 0)
                                                        showDirBrowser = false
                                                        showQueueView = true
                                                    }
                                                }
                                                .padding(vertical = 10.dp, horizontal = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(if (file.isDirectory) "📁 " else "🎵 ", fontSize = 16.sp)
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                file.name,
                                                color = if (file.isDirectory) Color.White else cyanNeon,
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Divider(color = Color(0xFF141C28), thickness = 0.5.dp)
                                    }
                                }
                            }
                        }
                    }

                    // Menu Lateral
                    if (showMenu) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.7f))
                                .clickable { showMenu = false }
                        ) {
                            Column(
                                Modifier
                                    .fillMaxHeight()
                                    .width(250.dp)
                                    .background(Color(0xFF0C1017))
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Text("MENU HIFI", color = cyanNeon, fontSize = 16.sp, fontWeight = FontWeight.Black)
                                Divider(color = borderNeon)
                                Text(
                                    "📁 Arquivos & Pastas",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    modifier = Modifier.clickable {
                                        showMenu = false
                                        showDirBrowser = true
                                    }
                                )
                                Text(
                                    "📜 Fila de Reprodução (${songs.size})",
                                    color = cyanNeon,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable {
                                        showMenu = false
                                        showQueueView = !showQueueView
                                    }
                                )
                                Text(
                                    "⚙ Equalizador",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    modifier = Modifier.clickable {
                                        showMenu = false
                                        showEq = true
                                    }
                                )
                                Text(
                                    "🔄 Alternar Modo VU",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    modifier = Modifier.clickable {
                                        showMenu = false
                                        vuModeAnalog = !vuModeAnalog
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

fun isAudioFile(file: File): Boolean {
    val ext = file.extension.lowercase()
    return ext in setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus")
}

fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%02d:%02d".format(min, sec)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MarqueeRender(text: String) {
    Text(
        text = text,
        color = Color(0xFF00E5FF),
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = Modifier.basicMarquee()
    )
}

@Composable
fun VUMeterRender(level: Float, label: String) {
    val animatedLevel by animateFloatAsState(
        targetValue = level.coerceIn(0.01f, 1.0f),
        animationSpec = tween(durationMillis = 80)
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Box(
            Modifier
                .size(width = 110.dp, height = 65.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF030508))
                .border(1.dp, Color(0xFF1E2A3A), RoundedCornerShape(6.dp))
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height * 1.15f)
                val radius = size.height * 0.95f
                
                // Mostrador do VU
                drawArc(
                    color = Color(0xFF00E676),
                    startAngle = 210f,
                    sweepAngle = 80f,
                    useCenter = false,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
                )
                drawArc(
                    color = Color(0xFFFF1744),
                    startAngle = 290f,
                    sweepAngle = 40f,
                    useCenter = false,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
                )

                // Agulha Analógica Reativa
                val angleDeg = 210f + (animatedLevel * 120f)
                val angleRad = Math.toRadians(angleDeg.toDouble())
                val end = Offset(
                    x = center.x + (radius * cos(angleRad)).toFloat(),
                    y = center.y + (radius * sin(angleRad)).toFloat()
                )
                drawLine(
                    color = Color(0xFFFF5252),
                    start = center,
                    end = end,
                    strokeWidth = 2.5f,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

@Composable
fun BargraphSegmented(level: Float, label: String) {
    val segments = 20
    val activeCount = (level.coerceIn(0f, 1f) * segments).toInt()
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(label, color = Color(0xFF00E5FF), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(20.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (i in 0 until segments) {
                val isActive = i < activeCount
                val color = when {
                    !isActive -> Color(0xFF121822)
                    i < 13 -> Color(0xFF00E676)
                    i < 17 -> Color(0xFFFF9100)
                    else -> Color(0xFFFF1744)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .height(10.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(color)
                )
            }
        }
    }
}
