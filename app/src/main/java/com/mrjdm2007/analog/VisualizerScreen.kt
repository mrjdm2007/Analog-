package com.mrjdm2007.analog

import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

private val VizBg = Color(0xFF090D0B)
private val VizPanel = Color(0xFF141B16)
private val VizCream = Color(0xFFE8E1CF)
private val VizMuted = Color(0xFF849184)
private val VizAmber = Color(0xFFFFB45B)
private val VizGreen = Color(0xFFB5D69B)

private enum class VisualMode(val label: String) {
    SPECTRUM("Spectrum"), WAVEFORM("Oscilloscope"), VU("VU meters")
}

@Composable
fun VisualizerScreen(
    audioSessionId: Int,
    audioPermissionGranted: Boolean,
    onRequestPermission: () -> Unit,
    onBack: () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var mode by remember { mutableStateOf(VisualMode.SPECTRUM) }
    var sensitivity by remember { mutableFloatStateOf(1.25f) }
    var barCount by remember { mutableIntStateOf(48) }
    var colorIndex by remember { mutableIntStateOf(0) }
    var waveform by remember { mutableStateOf(FloatArray(0)) }
    var spectrum by remember { mutableStateOf(FloatArray(0)) }
    var leftLevel by remember { mutableFloatStateOf(0f) }
    var rightLevel by remember { mutableFloatStateOf(0f) }
    var captureAvailable by remember { mutableStateOf(false) }
    var captureError by remember { mutableStateOf<String?>(null) }

    val palettes = listOf(
        listOf(Color(0xFFFFB45B), Color(0xFFFFD99A)),
        listOf(Color(0xFFB5D69B), Color(0xFFE8E1CF)),
        listOf(Color(0xFF65D8C2), Color(0xFFB5F4E8)),
        listOf(Color(0xFFB69CFF), Color(0xFFE2D8FF))
    )
    val colors = palettes[colorIndex]

    DisposableEffect(lifecycleOwner, audioSessionId, audioPermissionGranted) {
        if (!audioPermissionGranted || audioSessionId <= 0) {
            captureAvailable = false
            captureError = if (!audioPermissionGranted) "Microphone permission is required for Android's playback visualizer API." else "Start playback to connect the visualizer."
            onDispose { }
        } else {
            val mainHandler = Handler(Looper.getMainLooper())
            var visualizer: Visualizer? = null
            var resumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        resumed = true
                        runCatching { visualizer?.enabled = true }
                    }
                    Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                        resumed = false
                        runCatching { visualizer?.enabled = false }
                    }
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            val created = runCatching {
                Visualizer(audioSessionId).apply {
                    val range = Visualizer.getCaptureSizeRange()
                    captureSize = min(1024, range[1]).coerceAtLeast(range[0])
                    setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(effect: Visualizer?, bytes: ByteArray?, samplingRate: Int) {
                            if (!resumed || bytes == null) return
                            val values = FloatArray(bytes.size) { i -> ((bytes[i].toInt() and 0xFF) - 128) / 128f }
                            mainHandler.post {
                                if (resumed) waveform = values
                            }
                        }
                        override fun onFftDataCapture(effect: Visualizer?, bytes: ByteArray?, samplingRate: Int) {
                            if (!resumed || bytes == null || bytes.size < 4) return
                            val bins = FloatArray(bytes.size / 2)
                            for (i in 1 until bins.size) {
                                val real = bytes[i * 2].toInt()
                                val imag = bytes[i * 2 + 1].toInt()
                                val magnitude = hypot(real.toDouble(), imag.toDouble()).toFloat()
                                bins[i] = (ln(1f + magnitude) / ln(128f)).coerceIn(0f, 1f)
                            }
                            val low = bins.take(bins.size / 2).toFloatArray()
                            mainHandler.post {
                                if (resumed) {
                                    spectrum = low
                                    val half = max(1, low.size / 2)
                                    leftLevel = low.take(half).average().toFloat().coerceIn(0f, 1f)
                                    rightLevel = low.drop(half).average().toFloat().coerceIn(0f, 1f)
                                }
                            }
                        }
                    }, 20_000, true, true)
                    enabled = resumed
                }
            }
            visualizer = created.getOrNull()
            captureAvailable = visualizer != null
            captureError = created.exceptionOrNull()?.let { "Playback capture unavailable on this device: ${it.message ?: "unsupported audio session"}" }
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                resumed = false
                runCatching {
                    visualizer?.setDataCaptureListener(null, 0, false, false)
                    visualizer?.enabled = false
                    visualizer?.release()
                }
                visualizer = null
                mainHandler.removeCallbacksAndMessages(null)
                waveform = FloatArray(0)
                spectrum = FloatArray(0)
                leftLevel = 0f
                rightLevel = 0f
                captureAvailable = false
            }
        }
    }

    // A session can be created after this screen opens; keep the UI responsive without polling the audio engine.
    Column(Modifier.fillMaxSize().background(VizBg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = VizCream) }
            Column(Modifier.weight(1f)) {
                Text("ANALOG / VISUALIZER", color = VizGreen, fontSize = 10.sp, letterSpacing = 2.sp)
                Text("Live audio instruments", color = VizCream, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(if (captureAvailable) "LIVE" else "IDLE", color = if (captureAvailable) VizGreen else VizMuted, fontSize = 10.sp, letterSpacing = 1.4.sp)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            VisualMode.values().forEach { item ->
                FilterChip(selected = mode == item, onClick = { mode = item }, label = { Text(item.label, fontSize = 11.sp) })
            }
        }
        Box(
            Modifier.fillMaxWidth().weight(1f).padding(16.dp)
                .background(VizPanel, RoundedCornerShape(22.dp))
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                // Static CRT grid is cheap and only drawn while this composable is on screen.
                for (i in 1..4) {
                    val y = h * i / 5f
                    drawLine(VizMuted.copy(alpha = .13f), Offset(0f, y), Offset(w, y), 1.dp.toPx())
                }
                for (i in 1..7) {
                    val x = w * i / 8f
                    drawLine(VizMuted.copy(alpha = .10f), Offset(x, 0f), Offset(x, h), 1.dp.toPx())
                }
                when (mode) {
                    VisualMode.SPECTRUM -> {
                        val count = barCount.coerceIn(16, 96)
                        val gap = 3.dp.toPx()
                        val barWidth = ((w - gap * (count - 1)) / count).coerceAtLeast(1f)
                        for (i in 0 until count) {
                            val start = (i.toFloat() / count * spectrum.size).toInt().coerceIn(0, max(0, spectrum.lastIndex))
                            val end = (((i + 1f) / count * spectrum.size).toInt()).coerceIn(start + 1, max(start + 1, spectrum.size))
                            val raw = if (spectrum.isNotEmpty()) spectrum.slice(start until min(end, spectrum.size)).maxOrNull() ?: 0f else 0f
                            val shaped = (raw * sensitivity).coerceIn(0f, 1f)
                            val barHeight = max(2.dp.toPx(), shaped * h * .88f)
                            val x = i * (barWidth + gap)
                            drawRoundRect(
                                brush = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(colors[1], colors[0])),
                                topLeft = Offset(x, h - barHeight),
                                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f)
                            )
                        }
                    }
                    VisualMode.WAVEFORM -> {
                        if (waveform.size > 1) {
                            val path = Path()
                            waveform.forEachIndexed { i, sample ->
                                val x = i.toFloat() / (waveform.size - 1) * w
                                val y = h / 2f - sample * h * .43f * sensitivity
                                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            }
                            drawPath(path, colors[0], style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                        } else {
                            drawLine(colors[0].copy(alpha = .65f), Offset(0f, h / 2f), Offset(w, h / 2f), 2.dp.toPx())
                        }
                    }
                    VisualMode.VU -> {
                        val meterWidth = w * .34f
                        listOf(leftLevel, rightLevel).forEachIndexed { index, level ->
                            val top = h * (.27f + index * .34f)
                            val x = (w - meterWidth) / 2f
                            drawRoundRect(VizMuted.copy(alpha = .18f), Offset(x, top), androidx.compose.ui.geometry.Size(meterWidth, h * .12f), androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
                            drawRoundRect(colors[0], Offset(x, top), androidx.compose.ui.geometry.Size(meterWidth * (level * sensitivity).coerceIn(0f, 1f), h * .12f), androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
                        }
                    }
                }
            }
            if (!captureAvailable) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("WAITING FOR AUDIO", color = VizCream, fontSize = 12.sp, letterSpacing = 1.5.sp)
                    Text(captureError ?: "Play a track to activate the visualizer.", color = VizMuted, fontSize = 11.sp)
                    if (!audioPermissionGranted) Button(onClick = onRequestPermission) { Text("GRANT PERMISSION") }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("SENSITIVITY", color = VizMuted, fontSize = 10.sp, letterSpacing = 1.4.sp, modifier = Modifier.weight(1f))
                Text(String.format("%.2f×", sensitivity), color = VizCream, fontSize = 11.sp)
            }
            Slider(value = sensitivity, onValueChange = { sensitivity = it }, valueRange = .5f..3f)
            if (mode == VisualMode.SPECTRUM) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("BARS", color = VizMuted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                    Text("$barCount", color = VizCream, fontSize = 11.sp)
                }
                Slider(value = barCount.toFloat(), onValueChange = { barCount = it.toInt() }, valueRange = 16f..96f, steps = 9)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("CRT PHOSPHOR", color = VizMuted, fontSize = 10.sp, letterSpacing = 1.2.sp, modifier = Modifier.weight(1f))
                palettes.forEachIndexed { index, palette ->
                    Box(Modifier.padding(start = 8.dp).size(22.dp).background(palette[0], RoundedCornerShape(50)).clickable { colorIndex = index })
                }
            }
            Text("Analysis runs only while this screen is visible and the activity is resumed. Playback and EQ continue independently.", color = VizMuted, fontSize = 10.sp, lineHeight = 15.sp)
        }
        Spacer(Modifier.height(8.dp))
    }
}
