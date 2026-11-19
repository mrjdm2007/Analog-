package com.mrjdm2007.analog

import android.media.audiofx.Visualizer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private enum class AnalogVisualMode { SPECTRUM, WAVEFORM, VU }

@Composable
fun VisualizerScreen(
    audioSessionId: Int,
    audioPermissionGranted: Boolean,
    playbackActive: Boolean,
    onRequestPermission: () -> Unit,
    onBack: () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var mode by remember { mutableStateOf(AnalogVisualMode.SPECTRUM) }
    var bars by remember { mutableIntStateOf(48) }
    var sensitivity by remember { mutableFloatStateOf(1f) }
    var fft by remember { mutableStateOf(ByteArray(0)) }
    var waveform by remember { mutableStateOf(ByteArray(0)) }
    var captureError by remember { mutableStateOf<String?>(null) }
    var activeVisualizer by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumed = true
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> resumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val shouldCapture = audioPermissionGranted && playbackActive && resumed && audioSessionId > 0
    DisposableEffect(audioSessionId, shouldCapture) {
        if (!shouldCapture) {
            fft = ByteArray(0)
            waveform = ByteArray(0)
            captureError = if (!audioPermissionGranted) "Android requires audio permission for playback visualization."
                else if (audioSessionId <= 0) "Waiting for the audio session…"
                else if (!playbackActive) "Start playback to activate the visualizer."
                else null
            activeVisualizer = false
            return@DisposableEffect onDispose { }
        }

        var effect: Visualizer? = null
        try {
            val created = Visualizer(audioSessionId)
            val range = Visualizer.getCaptureSizeRange()
            created.captureSize = 1024.coerceIn(range[0], range[1])
            created.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(visualizer: Visualizer, data: ByteArray, samplingRate: Int) {
                    waveform = data.copyOf()
                }
                override fun onFftDataCapture(visualizer: Visualizer, data: ByteArray, samplingRate: Int) {
                    fft = data.copyOf()
                }
            }, min(20_000, Visualizer.getMaxCaptureRate()), true, true)
            created.enabled = true
            effect = created
            captureError = null
            activeVisualizer = true
        } catch (error: Exception) {
            captureError = error.message?.takeIf { it.isNotBlank() } ?: "Audio visualizer is unavailable for this output."
            activeVisualizer = false
        }
        onDispose {
            runCatching {
                effect?.enabled = false
                effect?.setDataCaptureListener(null, 0, false, false)
                effect?.release()
            }
            activeVisualizer = false
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF10110F)).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color(0xFFE8E1CF)) }
            Column(Modifier.weight(1f)) {
                Text("VISUALIZER", color = Color(0xFFFFB45B), fontSize = 11.sp, letterSpacing = 2.sp)
                Text("LIVE AUDIO ANALYSIS", color = Color(0xFFE8E1CF).copy(alpha = .55f), fontSize = 9.sp, letterSpacing = 1.sp)
            }
            Text(if (activeVisualizer) "LIVE" else "IDLE", color = if (activeVisualizer) Color(0xFFB5D69B) else Color.Gray, fontSize = 10.sp)
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            AnalogVisualMode.values().forEach { item ->
                FilterChip(selected = mode == item, onClick = { mode = item }, label = {
                    Text(when (item) { AnalogVisualMode.SPECTRUM -> "SPECTRUM"; AnalogVisualMode.WAVEFORM -> "WAVEFORM"; AnalogVisualMode.VU -> "VU METER" }, fontSize = 9.sp)
                })
            }
        }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF171A16), RoundedCornerShape(16.dp)).padding(12.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val grid = Color(0xFFB5D69B).copy(alpha = .10f)
                for (i in 1..4) {
                    val y = size.height * i / 5f
                    drawLine(grid, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), 1.dp.toPx())
                }
                when (mode) {
                    AnalogVisualMode.SPECTRUM -> {
                        val data = fft
                        val gap = 3.dp.toPx()
                        val barWidth = (size.width - gap * (bars - 1)) / bars
                        for (i in 0 until bars) {
                            val start = 2 + (i.toFloat() / bars * max(1, data.size / 2 - 2)).toInt()
                            val end = max(start + 1, 2 + ((i + 1f) / bars * max(1, data.size / 2 - 2)).toInt())
                            var magnitude = 0f
                            for (bin in start until min(end, data.size / 2)) {
                                val real = data.getOrNull(bin * 2)?.toInt()?.and(0xff) ?: 0
                                val imag = data.getOrNull(bin * 2 + 1)?.toInt()?.and(0xff) ?: 0
                                magnitude = max(magnitude, sqrt((real * real + imag * imag).toFloat()) / 180f)
                            }
                            val level = (magnitude * sensitivity * 1.9f).coerceIn(.025f, 1f)
                            val h = size.height * level
                            val x = i * (barWidth + gap)
                            drawRoundRect(Color(0xFFFFB45B).copy(alpha = .45f + .55f * level), androidx.compose.ui.geometry.Offset(x, size.height - h), androidx.compose.ui.Size(barWidth, h), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
                        }
                    }
                    AnalogVisualMode.WAVEFORM -> {
                        val data = waveform
                        if (data.isNotEmpty()) {
                            val path = Path()
                            data.forEachIndexed { index, sample ->
                                val x = index.toFloat() / (data.size - 1).coerceAtLeast(1) * size.width
                                val y = size.height / 2f - ((sample.toInt() and 0xff) - 128) / 128f * size.height * .43f * sensitivity
                                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            }
                            drawPath(path, Color(0xFFB5D69B), style = Stroke(width = 2.dp.toPx()))
                        }
                    }
                    AnalogVisualMode.VU -> {
                        val data = waveform
                        val rms = if (data.isEmpty()) 0f else sqrt((data.sumOf { val v = ((it.toInt() and 0xff) - 128) / 128.0; v * v } / data.size).toFloat())
                        val level = (rms * sensitivity * 1.8f).coerceIn(0f, 1f)
                        val width = size.width * level
                        drawRoundRect(Color(0xFFB5D69B).copy(alpha = .9f), androidx.compose.ui.geometry.Offset(0f, size.height * .43f), androidx.compose.ui.Size(width, size.height * .14f), androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
                        drawLine(Color(0xFFFFB45B), androidx.compose.ui.geometry.Offset(width, size.height * .30f), androidx.compose.ui.geometry.Offset(width, size.height * .70f), 2.dp.toPx())
                    }
                }
            }
            if (captureError != null) Text(captureError!!, color = Color(0xFFE8E1CF).copy(alpha = .7f), fontSize = 12.sp)
        }
        Spacer(Modifier.height(16.dp))
        Text("SENSITIVITY  ${(sensitivity * 100).toInt()}%", color = Color(0xFFE8E1CF).copy(alpha = .7f), fontSize = 10.sp)
        Slider(value = sensitivity, onValueChange = { sensitivity = it }, valueRange = .5f..2f)
        if (mode == AnalogVisualMode.SPECTRUM) {
            Text("BARS  $bars", color = Color(0xFFE8E1CF).copy(alpha = .7f), fontSize = 10.sp)
            Slider(value = bars.toFloat(), onValueChange = { bars = it.toInt() }, valueRange = 24f..72f, steps = 11)
        }
        if (!audioPermissionGranted) Button(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth()) { Text("ALLOW AUDIO PERMISSION") }
        Text("Capture runs only while this screen is visible and audio is playing.", color = Color(0xFFE8E1CF).copy(alpha = .4f), fontSize = 9.sp)
    }
}
