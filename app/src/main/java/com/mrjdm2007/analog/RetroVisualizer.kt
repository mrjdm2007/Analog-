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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

@Composable
fun RetroVisualizerScreen(onBack: () -> Unit, audioPermissionGranted: Boolean, player: MediaController?, tracks: List<Track>) {
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

    DisposableEffect(audioPermissionGranted) {
        var visualizer: Visualizer? = null
        if (audioPermissionGranted) {
            runCatching {
                visualizer = Visualizer(0).apply {
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("SPECTRUM", "VU METER").forEach { item ->
                FilterChip(selected = mode == item, onClick = { mode = item }, label = { Text(item, fontSize = 10.sp) })
            }
        }
        Spacer(Modifier.height(26.dp))
        if (!audioPermissionGranted) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("Allow audio visualizer access to animate with your music. Analog uses this only to display playback levels; it does not record or save audio.", color = Color(0xFFE8E1CF), fontSize = 13.sp)
            }
        } else {
            Text(if (mode == "SPECTRUM") "FREQUENCY ANALYZER" else "OUTPUT LEVEL", color = Color(0xFF91A987), fontSize = 10.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF101610), RoundedCornerShape(12.dp)).padding(14.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    if (mode == "SPECTRUM") {
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
                    } else {
                        val arcSize = Size(size.width * .84f, size.height * .72f)
                        val arcTop = Offset(size.width * .08f, size.height * .12f)
                        drawArc(Color(0xFFB5D69B), 180f, 180f, false, arcTop, arcSize, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 5.dp.toPx()))
                        val center = Offset(size.width / 2f, arcTop.y + arcSize.height * .78f)
                        val radius = arcSize.width * .48f
                        for (tick in 0..10) {
                            val angle = Math.toRadians((180f - tick * 18f).toDouble())
                            val inner = radius * .84f
                            val outer = radius * .98f
                            drawLine(
                                Color(0xFFFFB45B),
                                Offset(center.x + cos(angle).toFloat() * inner, center.y - sin(angle).toFloat() * inner),
                                Offset(center.x + cos(angle).toFloat() * outer, center.y - sin(angle).toFloat() * outer),
                                2.dp.toPx()
                            )
                        }
                        val needleAngle = Math.toRadians((180f - peak * 180f).toDouble())
                        drawLine(Color(0xFFFFB45B), center,
                            Offset(center.x + cos(needleAngle).toFloat() * radius * .82f, center.y - sin(needleAngle).toFloat() * radius * .82f),
                            4.dp.toPx(), StrokeCap.Round)
                        drawCircle(Color(0xFFE8E1CF), 7.dp.toPx(), center)
                        drawCircle(Color(0xFFFFB45B), 4.dp.toPx(), center)
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
            Text("ANALOG LEVEL", color = Color(0xFF91A987), fontSize = 10.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("OUT", color = Color(0xFFFFB45B), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(32.dp))
                Box(Modifier.weight(1f).height(12.dp).background(Color(0xFF263126), RoundedCornerShape(3.dp))) {
                    Box(Modifier.fillMaxWidth(peak.coerceIn(.02f, 1f)).fillMaxHeight().background(Color(0xFFB5D69B), RoundedCornerShape(3.dp)))
                }
            }
            Text("LIVE OUTPUT MIX  •  FFT", color = Color(0xFF71806B), fontSize = 9.sp, letterSpacing = 1.2.sp, modifier = Modifier.padding(top = 18.dp, bottom = 20.dp))
        }
    }
}
