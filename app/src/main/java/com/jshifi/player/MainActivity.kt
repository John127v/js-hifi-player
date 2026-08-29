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
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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

data class Playlist(val name: String, val filePaths: MutableList<String>)

// Busca as tags de áudio de maneira segura sem travar o app
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

// Lê arquivos do diretório de forma ordenada e depois entra nos subdiretórios
fun criarFilaDeReproducao(diretorioAtual: File): List<File> {
    val filaDeReproducao = mutableListOf<File>()
    val itens = diretorioAtual.listFiles() ?: return emptyList()

    val arquivosDeMusica = mutableListOf<File>()
    val subdiretorios = mutableListOf<File>()
    val formatosSuportados = setOf("mp3", "flac", "wav", "m4a", "ogg")

    for (item in itens) {
        if (item.name.startsWith(".")) continue
        if (item.isDirectory) {
            subdiretorios.add(item)
        } else if (formatosSuportados.contains(item.extension.lowercase())) {
            arquivosDeMusica.add(item)
        }
    }

    arquivosDeMusica.sortBy { it.name.lowercase() }
    subdiretorios.sortBy { it.name.lowercase() }

    filaDeReproducao.addAll(arquivosDeMusica)

    for (subPasta in subdiretorios) {
        filaDeReproducao.addAll(criarFilaDeReproducao(subPasta))
    }

    return filaDeReproducao
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

            var currentPlaylistView by remember { mutableStateOf(listOf<File>()) }
            var idx by remember { mutableStateOf(-1) }
            var isPlay by remember { mutableStateOf(false) }
            var pos by remember { mutableStateOf(0L) }
            var dur by remember { mutableStateOf(0L) }
            
            val fftValues by remember { mutableStateOf(FloatArray(15) { 0.02f }) }
            var currentTags by remember { mutableStateOf(SongTags("JS HIFI PLAYER", "Selecione uma pasta/faixa", "-", "-", "-", "-", "-")) }
            
            val currentDir by remember { mutableStateOf(Environment.getExternalStorageDirectory()) }

            LaunchedEffect(player) {
                while (true) {
                    if (player.isPlaying) {
                        pos = player.currentPosition
                        dur = player.duration.coerceAtLeast(0L)
                    }
                    delay(500)
                }
            }

            DisposableEffect(player) {
                val listener = object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        isPlay = isPlaying
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) {
                            if (idx < currentPlaylistView.lastIndex && idx >= 0) {
                                idx++
                            }
                        }
                    }
                }
                player.addListener(listener)
                onDispose { player.removeListener(listener) }
            }

            LaunchedEffect(idx) {
                if (idx in currentPlaylistView.indices) {
                    val targetFile = currentPlaylistView[idx]
                    currentTags = getTags(targetFile)
                    
                    val mediaItem = MediaItem.fromUri(targetFile.absolutePath)
                    player.setMediaItem(mediaItem)
                    player.prepare()
                    player.play()
                    
                    try {
                        val audioSessionId = player.audioSessionId
                        if (audioSessionId != 0) {
                            eq = Equalizer(0, audioSessionId).apply { enabled = true }
                            loud = LoudnessEnhancer(audioSessionId).apply { enabled = true }
                            
                            viz = Visualizer(audioSessionId).apply {
                                captureSize = Visualizer.getCaptureSizeRange()[1]
                                enabled = true
                            }
                        }
                    } catch (_: Exception) {}
                }
            }

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { permissions ->
                if (permissions.values.all { it }) {
                    scope.launch(Dispatchers.IO) {
                        currentPlaylistView = criarFilaDeReproducao(currentDir)
                    }
                }
            }

            fun verificarEBuscarMusicas() {
                val permissoes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
                } else {
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                }

                val todasConcedidas = permissoes.all {
                    ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
                }

                if (todasConcedidas) {
                    scope.launch(Dispatchers.IO) {
                        currentPlaylistView = criarFilaDeReproducao(currentDir)
                        if (currentPlaylistView.isNotEmpty() && idx == -1) {
                            idx = 0
                        }
                    }
                } else {
                    permissionLauncher.launch(permissoes)
                }
            }

            LaunchedEffect(Unit) {
                verificarEBuscarMusicas()
            }

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFF121212)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Diretório: ${currentDir.name}",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = currentTags.title,
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${currentTags.artist}  •  ${currentTags.format}  •  ${currentTags.bitrate}",
                            color = Color.Cyan,
                            fontSize = 14.sp,
                            maxLines = 1
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val barWidth = size.width / (fftValues.size * 2f)
                            val space = barWidth
                            for (i in fftValues.indices) {
                                val x = i * (barWidth + space) + space
                                val barHeight = size.height * 0.7f
                                drawRoundRect(
                                    brush = Brush.verticalGradient(listOf(Color.Cyan, Color.Blue)),
                                    topLeft = Offset(x, size.height - barHeight),
                                    size = Size(barWidth, barHeight),
                                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                                )
                            }
                        }
                    }

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(vertical = 12.dp)
                    ) {
                        itemsIndexed(currentPlaylistView) { index, file ->
                            val isSelected = index == idx
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) Color(0xFF252525) else Color.Transparent)
                                    .clickable { idx = index }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = String.format("%02d.", index + 1),
                                    color = if (isSelected) Color.Cyan else Color.Gray,
                                    modifier = Modifier.width(32.dp)
                                )
                                Column {
                                    Text(
                                        text = file.nameWithoutExtension,
                                        color = if (isSelected) Color.Cyan else Color.White,
                                        fontSize = 16.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = file.parentFile?.name ?: "Raiz",
                                        color = Color.Gray,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }

                    Column(modifier = Modifier.fillMaxWidth()) {
                        Slider(
                            value = if (dur > 0) pos.toFloat() / dur else 0f,
                            onValueChange = {
                                if (dur > 0) player.seekTo((it * dur).toLong())
                            },
                            colors = SliderDefaults.colors(
                                thumbColor = Color.Cyan,
                                activeTrackColor = Color.Cyan
                            )
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = formatarTempo(pos), color = Color.Gray, fontSize = 12.sp)
                            Text(text = formatarTempo(dur), color = Color.Gray, fontSize = 12.sp)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { if (idx > 0) idx-- },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF222222))
                        ) {
                            Text("Anterior", color = Color.White)
                        }
                        Button(
                            onClick = { if (player.isPlaying) player.pause() else player.play() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Cyan)
                        ) {
                            Text(if (isPlay) "Pausar" else "Tocar", color = Color.Black)
                        }
                        Button(
                            onClick = { if (idx < currentPlaylistView.lastIndex) idx++ },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF222222))
                        ) {
                            Text("Próxima", color = Color.White)
                        }
                    }
                }
            }
        }
    }

    private fun formatarTempo(ms: Long): String {
        val totalSegundos = ms / 1000
        val minutos = totalSegundos / 60
        val segundos = totalSegundos % 60
        return String.format("%02d:%02d", minutos, segundos)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            eq?.release()
            loud?.release()
            viz?.release()
            player.release()
        } catch (_: Exception) {}
    }
}
