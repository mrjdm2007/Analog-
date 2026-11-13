package com.mrjdm2007.analog

import android.media.audiofx.Visualizer
import androidx.media3.session.MediaController
import coil.compose.AsyncImage
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs

@Composable
fun RetroVisualizerScreen(onBack: () -> Unit, audioPermissionGranted: Boolean, audioSessionId: Int, player: MediaController?, tracks: List<Track>) {
    val bars = remember { mutableStateListOf<Float>().apply { repeat(48) { add(0.035f) } } }
    var peak by remember { mutableFloatStateOf(0.08f) }
    var title by remember { mutableStateOf("Nothing playing") }
    var artist by remember { mutableStateOf("Choose a song from your library") }
    var album by remember { mutableStateOf("ANALOG • OFFLINE") }
    var currentTrackId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(player) {
        while (player != null) {
            title = player.mediaMetadata.title?.toString() ?: "Nothing playing"
            artist = player.mediaMetadata.artist?.toString() ?: "Unknown artist"
            album = player.mediaMetadata.albumTitle?.toString() ?: "Unknown album"
            currentTrackId = tracks.firstOrNull { it.title == title && it.artist == artist }?.id
            delay(500)
        }
    }
    val currentTrack = tracks.firstOrNull { it.id == currentTrackId }

    DisposableEffect(audioPermissionGranted, audioSessionId) {
        var visualizer: Visualizer? = null
        if (audioPermissionGranted) {
            runCatching {
                if (audioSessionId > 0) visualizer = Visualizer(audioSessionId).apply {
                    captureSize = Visualizer.getCaptureSizeRange()[1]
                    scalingMode = Visualizer.SCALING_MODE_NORMALIZED
                    setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) = Unit

                        override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                            if (fft == null) return
                            val next = (0 until 48).map { i ->
                                val bin = (2 + i * 2).coerceAtMost(fft.size - 2)
                                val real = fft[bin].toInt()
                                val imaginary = fft[bin + 1].toInt()
                                ((abs(real) + abs(imaginary)) / 150f).coerceIn(0.035f, 1f)
                            }
                            val level = next.maxOrNull() ?: 0.08f
                            Handler(Looper.getMainLooper()).post {
                                bars.indices.forEach { i ->
                                    bars[i] = maxOf(next[i], bars[i] * 0.72f)
                                }
                                peak = maxOf(level, peak * 0.88f)
                            }
                        }
                    }, Visualizer.getMaxCaptureRate() / 2, false, true)
                    enabled = true
                }
            }
        }
        onDispose {
            runCatching {
                visualizer?.enabled = false
                visualizer?.release()
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF080B08)).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color(0xFFE8E1CF)) }
            Column(Modifier.weight(1f)) {
                Text("ANALOG / VISUALIZER", color = Color(0xFFFFB45B), fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                Text("REAL-TIME AUDIO DISPLAY", color = Color(0xFF91A987), fontSize = 9.sp, letterSpacing = 1.5.sp)
            }
            Icon(Icons.Default.GraphicEq, contentDescription = null, tint = Color(0xFFB5D69B))
        }
        Row(Modifier.fillMaxWidth().background(Color(0xFF101610), RoundedCornerShape(10.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (currentTrack != null) {
                AsyncImage(model = currentTrack.artUri(), contentDescription = currentTrack.album, modifier = Modifier.size(58.dp))
            } else {
                Icon(Icons.Default.GraphicEq, null, tint = Color(0xFFFFB45B), modifier = Modifier.size(58.dp).padding(10.dp))
            }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(title, color = Color(0xFFE8E1CF), fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(artist, color = Color(0xFFB5D69B), fontSize = 10.sp, maxLines = 1)
                Text(album, color = Color(0xFFE8E1CF).copy(alpha = .5f), fontSize = 9.sp, maxLines = 1)
            }
        }
        Spacer(Modifier.height(20.dp))
        if (!audioPermissionGranted) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("Allow audio visualizer access to animate with your music. Analog uses this only to display playback levels; it does not record or save audio.", color = Color(0xFFE8E1CF), fontSize = 13.sp)
            }
        } else if (audioSessionId <= 0) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("Start playback, then reopen the visualizer to connect to Analog's active audio session.", color = Color(0xFFE8E1CF), fontSize = 13.sp)
            }
        } else {
            Text("48-BAND FREQUENCY ANALYZER", color = Color(0xFF91A987), fontSize = 10.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF101610), RoundedCornerShape(12.dp)).padding(14.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val count = bars.size
                    val gap = size.width * 0.009f
                    val barWidth = (size.width - gap * (count - 1)) / count
                    for (i in 0 until 5) {
                        val y = size.height * i / 5f
                        drawLine(Color(0xFF31402E).copy(alpha = .45f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                    }
                    bars.forEachIndexed { index, value ->
                        val h = value * size.height * .92f
                        val x = index * (barWidth + gap)
                        drawLine(Color(0xFFFFB45B), Offset(x + barWidth / 2, size.height), Offset(x + barWidth / 2, size.height - h), barWidth, StrokeCap.Round)
                        if (h > 5.dp.toPx()) {
                            drawLine(Color(0xFFFFD39A), Offset(x + barWidth / 2, size.height - h), Offset(x + barWidth / 2, size.height - h - 2.dp.toPx()), barWidth, StrokeCap.Round)
                        }
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
            Text("OUTPUT LEVEL", color = Color(0xFF91A987), fontSize = 10.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("OUT", color = Color(0xFFFFB45B), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(32.dp))
                Box(Modifier.weight(1f).height(12.dp).background(Color(0xFF263126), RoundedCornerShape(3.dp))) {
                    Box(Modifier.fillMaxWidth(peak.coerceIn(.02f, 1f)).fillMaxHeight().background(Color(0xFFB5D69B), RoundedCornerShape(3.dp)))
                }
            }
            Text("LIVE OUTPUT MIX  •  48-BAND FFT", color = Color(0xFF71806B), fontSize = 9.sp, letterSpacing = 1.2.sp, modifier = Modifier.padding(top = 18.dp, bottom = 20.dp))
        }
    }
}
