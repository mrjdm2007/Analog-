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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private val DeckBg = Color(0xFF080B08)
private val DeckPanel = Color(0xFF111811)
private val DeckAmber = Color(0xFFFFB45B)
private val DeckGold = Color(0xFFFFD99A)
private val DeckGreen = Color(0xFFB5D69B)
private val DeckMuted = Color(0xFF71806B)
private val DeckCream = Color(0xFFE8E1CF)

@Composable
fun RetroVisualizerScreen(onBack: () -> Unit, audioPermissionGranted: Boolean) {
    val bars = remember { mutableStateListOf<Float>().apply { repeat(40) { add(0.025f) } } }
    val waveform = remember { mutableStateListOf<Float>().apply { repeat(128) { add(0f) } } }
    var peak by remember { mutableFloatStateOf(0.025f) }
    var mode by remember { mutableStateOf("SPECTRUM") }
    var signalSeen by remember { mutableStateOf(false) }
    val appResumed = rememberAppResumed()

    DisposableEffect(audioPermissionGranted, appResumed) {
        var capture: Visualizer? = null
        val mainHandler = Handler(Looper.getMainLooper())
        if (audioPermissionGranted && appResumed) {
            runCatching {
                capture = Visualizer(0).apply {
                    captureSize = Visualizer.getCaptureSizeRange()[1]
                    scalingMode = Visualizer.SCALING_MODE_NORMALIZED
                    val rate = minOf(Visualizer.getMaxCaptureRate() / 4, 8_000)
                    setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(v: Visualizer?, data: ByteArray?, samplingRate: Int) {
                            if (data == null || data.isEmpty()) return
                            val nextWave = (0 until 128).map { index ->
                                val source = index * data.size / 128
                                (((data[source].toInt() and 0xFF) - 128) / 128f).coerceIn(-1f, 1f)
                            }
                            mainHandler.post {
                                waveform.indices.forEach { waveform[it] = nextWave[it] }
                                if (nextWave.any { kotlin.math.abs(it) > 0.035f }) signalSeen = true
                            }
                        }

                        override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                            if (fft == null || fft.size < 8) return
                            val next = (0 until 40).map { i ->
                                val bin = (2 + (i * i * (fft.size - 4) / (40 * 40))).coerceAtMost(fft.size - 2)
                                val real = fft[bin].toInt()
                                val imaginary = fft[bin + 1].toInt()
                                (sqrt((real * real + imaginary * imaginary).toFloat()) / 180f).coerceIn(0.025f, 1f)
                            }
                            val level = next.maxOrNull() ?: 0.025f
                            mainHandler.post {
                                bars.indices.forEach { i -> bars[i] = maxOf(next[i], bars[i] * 0.78f) }
                                peak = maxOf(level, peak * 0.90f)
                                if (level > 0.06f) signalSeen = true
                            }
                        }
                    }, rate, true, true)
                    enabled = true
                }
            }
        }
        onDispose {
            mainHandler.removeCallbacksAndMessages(null)
            runCatching {
                capture?.enabled = false
                capture?.setDataCaptureListener(null, 0, false, false)
                capture?.release()
            }
        }
    }

    Column(Modifier.fillMaxSize().background(DeckBg).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = DeckCream) }
            Column(Modifier.weight(1f)) {
                Text("ANALOG / VISUALIZER", color = DeckAmber, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                Text("LIVE ANALOG SOUND DECK", color = DeckMuted, fontSize = 9.sp, letterSpacing = 1.7.sp)
            }
            Icon(Icons.Default.GraphicEq, null, tint = DeckGreen)
        }

        Row(Modifier.fillMaxWidth().background(DeckPanel, RoundedCornerShape(12.dp)).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("SPECTRUM", "WAVEFORM", "VU DECK").forEach { item ->
                val selected = mode == item
                Surface(
                    onClick = { mode = item },
                    color = if (selected) Color(0xFF303A2C) else Color.Transparent,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(item, color = if (selected) DeckAmber else DeckMuted, fontSize = 9.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = .5.sp,
                        modifier = Modifier.padding(vertical = 12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(when (mode) {
                "SPECTRUM" -> "FREQUENCY ANALYZER"
                "WAVEFORM" -> "OSCILLOSCOPE"
                else -> "ANALOG VU INSTRUMENT"
            }, color = DeckGreen, fontSize = 10.sp, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
            Text(if (signalSeen) "● LIVE" else "○ WAITING", color = if (signalSeen) DeckAmber else DeckMuted, fontSize = 9.sp, letterSpacing = 1.sp)
        }
        Spacer(Modifier.height(10.dp))

        Box(
            Modifier.fillMaxWidth().weight(1f).background(
                Brush.verticalGradient(listOf(Color(0xFF172219), Color(0xFF0C120D), Color(0xFF101710))),
                RoundedCornerShape(16.dp)
            ).padding(12.dp)
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val grid = Color(0xFF42533D).copy(alpha = .42f)
                for (i in 0..8) {
                    val x = size.width * i / 8f
                    drawLine(grid, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                }
                for (i in 0..8) {
                    val y = size.height * i / 8f
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                }

                when (mode) {
                    "SPECTRUM" -> {
                        val gap = size.width * .012f
                        val barWidth = (size.width - gap * (bars.size - 1)) / bars.size
                        bars.forEachIndexed { index, value ->
                            val h = (value * size.height * .88f).coerceAtLeast(3.dp.toPx())
                            val x = index * (barWidth + gap)
                            drawRoundRect(
                                brush = Brush.verticalGradient(listOf(DeckGold, DeckAmber, DeckGreen)),
                                topLeft = Offset(x, size.height - h),
                                size = Size(barWidth, h),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f, barWidth / 2f)
                            )
                            val capY = size.height - h - 5.dp.toPx()
                            drawLine(DeckGold.copy(alpha = .8f), Offset(x, capY), Offset(x + barWidth, capY), 2.dp.toPx(), StrokeCap.Round)
                        }
                    }
                    "WAVEFORM" -> {
                        drawLine(DeckGreen.copy(alpha = .5f), Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), 1.dp.toPx())
                        val path = Path()
                        waveform.forEachIndexed { index, value ->
                            val x = index * size.width / (waveform.size - 1).toFloat()
                            val y = size.height / 2f - value * size.height * .40f
                            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(path, DeckGreen.copy(alpha = .16f), style = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round))
                        drawPath(path, DeckGold, style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round))
                    }
                    else -> {
                        val arcSize = Size(size.width * .84f, size.height * .76f)
                        val arcTop = Offset(size.width * .08f, size.height * .13f)
                        drawArc(DeckGreen.copy(alpha = .8f), 180f, 180f, false, arcTop, arcSize,
                            style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round))
                        val center = Offset(size.width / 2f, size.height * .76f)
                        val radius = size.width * .39f
                        for (tick in 0..20) {
                            val angle = Math.toRadians((180f - tick * 9f).toDouble())
                            val inner = radius * if (tick % 5 == 0) .78f else .86f
                            val outer = radius * .98f
                            drawLine(if (tick >= 17) DeckAmber else DeckCream.copy(alpha = .75f),
                                Offset(center.x + cos(angle).toFloat() * inner, center.y - sin(angle).toFloat() * inner),
                                Offset(center.x + cos(angle).toFloat() * outer, center.y - sin(angle).toFloat() * outer),
                                if (tick % 5 == 0) 2.dp.toPx() else 1.dp.toPx())
                        }
                        val needleAngle = Math.toRadians((180f - peak.coerceIn(0f, 1f) * 180f).toDouble())
                        drawLine(DeckAmber, center,
                            Offset(center.x + cos(needleAngle).toFloat() * radius * .80f, center.y - sin(needleAngle).toFloat() * radius * .80f),
                            3.dp.toPx(), StrokeCap.Round)
                        drawCircle(DeckCream, 7.dp.toPx(), center)
                        drawCircle(DeckAmber, 4.dp.toPx(), center)
                        drawLine(DeckMuted.copy(alpha = .7f), Offset(size.width * .16f, size.height * .88f), Offset(size.width * .84f, size.height * .88f), 1.dp.toPx())
                    }
                }

                for (line in 0..30) {
                    val y = size.height * line / 30f
                    drawLine(Color.Black.copy(alpha = .10f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("OUTPUT LEVEL", color = DeckMuted, fontSize = 9.sp, letterSpacing = 1.7.sp)
                Spacer(Modifier.height(7.dp))
                Box(Modifier.fillMaxWidth().height(8.dp).background(Color(0xFF273328), RoundedCornerShape(5.dp))) {
                    Box(Modifier.fillMaxWidth(peak.coerceIn(.02f, 1f)).fillMaxHeight()
                        .background(Brush.horizontalGradient(listOf(DeckGreen, DeckAmber)), RoundedCornerShape(5.dp)))
                }
            }
            Spacer(Modifier.width(16.dp))
            Text("${(peak * 100).toInt().coerceIn(0, 100).toString().padStart(2, '0')}", color = DeckAmber,
                fontSize = 23.sp, fontWeight = FontWeight.Light, letterSpacing = 1.sp)
        }
        Text("FFT ANALYSIS  •  LOCAL OUTPUT CAPTURE  •  NO AUDIO STORED",
            color = DeckMuted, fontSize = 8.sp, letterSpacing = .8.sp,
            modifier = Modifier.padding(top = 14.dp, bottom = 18.dp))
    }
}
