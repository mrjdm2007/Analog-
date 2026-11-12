package com.mrjdm2007.analog

import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.session.MediaController
import kotlinx.coroutines.delay
import kotlin.math.sqrt

private val VisBg = Color(0xFF10110F)
private val VisPanel = Color(0xFF1A1C18)
private val VisAmber = Color(0xFFFFB45B)
private val VisGreen = Color(0xFFB5D69B)
private val VisCream = Color(0xFFE8E1CF)
private val VisMuted = Color(0xFF89877D)

@Composable
fun RetroVisualizerScreen(onBack: () -> Unit, audioPermissionGranted: Boolean, player: MediaController?, tracks: List<Track>) {
    val bars = remember { mutableStateListOf<Float>().apply { repeat(48) { add(.035f) } } }
    var title by remember { mutableStateOf("Nothing playing") }
    var artist by remember { mutableStateOf("Choose a track from your library") }
    var album by remember { mutableStateOf("") }
    var art by remember { mutableStateOf<android.net.Uri?>(null) }
    var playing by remember { mutableStateOf(false) }
    val resumed = rememberAppResumed()
    LaunchedEffect(player, resumed) {
        while (player != null && resumed) {
            title = player.mediaMetadata.title?.toString() ?: "Nothing playing"
            artist = player.mediaMetadata.artist?.toString() ?: "Unknown artist"
            album = player.mediaMetadata.albumTitle?.toString().orEmpty()
            art = player.mediaMetadata.artworkUri ?: tracks.firstOrNull { it.title == title && it.artist == artist }?.artworkUri
            playing = player.isPlaying
            delay(650)
        }
    }
    DisposableEffect(audioPermissionGranted, resumed) {
        var capture: Visualizer? = null
        val handler = Handler(Looper.getMainLooper())
        if (audioPermissionGranted && resumed) runCatching {
            capture = Visualizer(0).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                scalingMode = Visualizer.SCALING_MODE_NORMALIZED
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, data: ByteArray?, rate: Int) = Unit
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) {
                        if (fft == null || fft.size < 8) return
                        val values = (0 until 48).map { i ->
                            val bin = (2 + i * i * (fft.size - 4) / (48 * 48)).coerceAtMost(fft.size - 2)
                            val re = fft[bin].toInt()
                            val im = fft[bin + 1].toInt()
                            (sqrt((re * re + im * im).toFloat()) / 170f).coerceIn(.025f, 1f)
                        }
                        handler.post { values.indices.forEach { bars[it] = maxOf(values[it], bars[it] * .82f) } }
                    }
                }, minOf(Visualizer.getMaxCaptureRate() / 5, 6_000), false, true)
                enabled = true
            }
        }
        onDispose {
            handler.removeCallbacksAndMessages(null)
            runCatching { capture?.enabled = false; capture?.setDataCaptureListener(null, 0, false, false); capture?.release() }
        }
    }

    Column(Modifier.fillMaxSize().background(VisBg).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = VisCream) }
            Column(Modifier.weight(1f)) {
                Text("ANALOG / VISUALIZER", color = VisAmber, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
                Text("LIVE FREQUENCY DISPLAY", color = VisMuted, fontSize = 9.sp, letterSpacing = 1.2.sp)
            }
            Icon(Icons.Default.GraphicEq, null, tint = VisGreen)
        }
        Row(Modifier.fillMaxWidth().background(VisPanel, RoundedCornerShape(18.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            AlbumArtwork(art, Modifier.size(76.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("NOW PLAYING", color = VisGreen, fontSize = 9.sp, letterSpacing = 1.5.sp)
                Text(title, color = VisCream, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                Text(artist, color = VisMuted, fontSize = 12.sp, maxLines = 1)
                if (album.isNotBlank()) Text(album, color = VisMuted, fontSize = 10.sp, maxLines = 1)
            }
            Text(if (playing) "●" else "Ⅱ", color = VisAmber, fontSize = 15.sp)
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SPECTRUM", color = VisGreen, fontSize = 10.sp, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
            Text(if (playing) "LIVE" else "PAUSED", color = VisAmber, fontSize = 9.sp, letterSpacing = 1.sp)
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().weight(1f).background(
            Brush.verticalGradient(listOf(Color(0xFF1D2A20), Color(0xFF0D130F), Color(0xFF111711))),
            RoundedCornerShape(20.dp)).padding(14.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val grid = VisGreen.copy(alpha = .12f)
                for (i in 0..8) {
                    val x = size.width * i / 8f
                    drawLine(grid, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                }
                for (i in 0..8) {
                    val y = size.height * i / 8f
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                }
                val gap = size.width * .009f
                val bw = (size.width - gap * (bars.size - 1)) / bars.size
                bars.forEachIndexed { i, level ->
                    val h = (level * size.height * .82f).coerceAtLeast(3.dp.toPx())
                    val x = i * (bw + gap)
                    drawRoundRect(VisAmber.copy(alpha = .16f), Offset(x - 1.dp.toPx(), size.height - h - 3.dp.toPx()),
                        Size(bw + 2.dp.toPx(), h + 3.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()))
                    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFFFD99A), VisAmber, VisGreen)),
                        Offset(x, size.height - h), Size(bw, h), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
                    drawLine(VisCream.copy(alpha = .7f), Offset(x, size.height - h - 4.dp.toPx()),
                        Offset(x + bw, size.height - h - 4.dp.toPx()), 1.5.dp.toPx())
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("LOW     〰     MID     〰     HIGH", color = VisMuted, fontSize = 9.sp, letterSpacing = 1.6.sp,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().background(VisPanel, RoundedCornerShape(14.dp)).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("ANALOG SPECTRUM", color = VisCream, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("Real-time audio frequency response", color = VisMuted, fontSize = 9.sp)
            }
            Text("48 BANDS", color = VisAmber, fontSize = 10.sp, letterSpacing = 1.sp)
        }
        Spacer(Modifier.height(12.dp))
    }
}
