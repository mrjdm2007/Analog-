package com.mrjdm2007.analog

import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

private val SpectrumInk = Color(0xFF090A08)
private val SpectrumCream = Color(0xFFE8E1CF)
private val SpectrumMuted = Color(0xFF85877E)
private val SpectrumAmber = Color(0xFFFFB05A)
private val SpectrumGreen = Color(0xFFB3D398)
private val SpectrumOrange = Color(0xFFE66D45)

@Composable
fun MacSpectrumPanel(
    audioSessionId: Int,
    playbackActive: Boolean,
    audioPermissionGranted: Boolean,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var levels by remember { mutableStateOf(FloatArray(36)) }
    var peaks by remember { mutableStateOf(FloatArray(36)) }
    var captureReady by remember { mutableStateOf(false) }
    var captureError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycleOwner, audioSessionId, playbackActive, audioPermissionGranted) {
        levels = FloatArray(36)
        peaks = FloatArray(36)
        if (!audioPermissionGranted || !playbackActive || audioSessionId <= 0) {
            captureReady = false
            captureError = when {
                !audioPermissionGranted -> "Allow audio analysis to enable the live spectrum."
                !playbackActive -> "Start playback to activate the spectrum analyser."
                else -> "Connecting to the playback audio session…"
            }
            onDispose { }
        } else {
            val handler = Handler(Looper.getMainLooper())
            var resumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            var effect: Visualizer? = null
            val smoothed = FloatArray(36)
            val held = FloatArray(36)
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        resumed = true
                        runCatching { effect?.enabled = true }
                    }
                    Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                        resumed = false
                        runCatching { effect?.enabled = false }
                    }
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            val result = runCatching {
                Visualizer(audioSessionId).apply {
                    val range = Visualizer.getCaptureSizeRange()
                    val fftCaptureSize = 1024.coerceIn(range[0], range[1])
                    captureSize = fftCaptureSize
                    setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(
                            visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int
                        ) = Unit

                        override fun onFftDataCapture(
                            visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int
                        ) {
                            if (!resumed || fft == null || fft.size < 8) return
                            val fftBins = fft.size / 2
                            val sampleRateHz = samplingRate / 1000.0
                            val binHz = sampleRateHz / fftCaptureSize.coerceAtLeast(1)
                            val upperFrequency = min(16_000.0, sampleRateHz * 0.45)
                            val next = FloatArray(36)
                            for (index in 0 until 36) {
                                val fractionStart = index.toDouble() / 36.0
                                val fractionEnd = (index + 1).toDouble() / 36.0
                                val startHz = 35.0 * (upperFrequency / 35.0).pow(fractionStart)
                                val endHz = 35.0 * (upperFrequency / 35.0).pow(fractionEnd)
                                val startBin = (startHz / binHz).toInt().coerceIn(1, fftBins - 1)
                                val endBin = (endHz / binHz).toInt().coerceIn(startBin + 1, fftBins)
                                var magnitude = 0.0
                                for (bin in startBin until endBin) {
                                    val real = fft[bin * 2].toInt()
                                    val imaginary = fft[bin * 2 + 1].toInt()
                                    magnitude = max(magnitude, kotlin.math.hypot(real.toDouble(), imaginary.toDouble()))
                                }
                                val normalised = ((ln(1.0 + magnitude) / ln(128.0) - 0.035) * 1.18)
                                    .coerceIn(0.0, 1.0)
                                val target = normalised.toFloat()
                                smoothed[index] = if (target > smoothed[index]) {
                                    smoothed[index] * 0.42f + target * 0.58f
                                } else {
                                    smoothed[index] * 0.78f + target * 0.22f
                                }
                                held[index] = max(held[index] - 0.012f, smoothed[index])
                                next[index] = smoothed[index]
                            }
                            val peakFrame = held.copyOf()
                            handler.post {
                                if (resumed) {
                                    levels = next
                                    peaks = peakFrame
                                    captureReady = true
                                    captureError = null
                                }
                            }
                        }
                    }, 20_000, false, true)
                    enabled = resumed
                }
            }
            effect = result.getOrNull()
            captureReady = effect != null
            captureError = result.exceptionOrNull()?.let {
                "Live analysis is unavailable for this audio output."
            }
            onDispose {
                resumed = false
                lifecycleOwner.lifecycle.removeObserver(observer)
                runCatching {
                    effect?.setDataCaptureListener(null, 0, false, false)
                    effect?.enabled = false
                    effect?.release()
                }
                effect = null
                handler.removeCallbacksAndMessages(null)
                levels = FloatArray(36)
                peaks = FloatArray(36)
                captureReady = false
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SpectrumInk.copy(alpha = 0.78f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "LIVE AUDIO ANALYSER",
                color = SpectrumCream,
                fontSize = 9.sp,
                letterSpacing = 1.55.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (captureReady && playbackActive) "● LIVE" else "○ STANDBY",
                color = if (captureReady && playbackActive) SpectrumGreen else SpectrumMuted,
                fontSize = 8.sp,
                letterSpacing = 1.sp
            )
        }
        Canvas(Modifier.fillMaxWidth().height(70.dp)) {
            val count = 36
            val gap = 3.dp.toPx()
            val barWidth = (size.width - gap * (count - 1)) / count
            for (fraction in listOf(0.25f, 0.5f, 0.75f)) {
                val y = size.height * (1f - fraction)
                drawLine(
                    SpectrumCream.copy(alpha = 0.075f),
                    Offset(0f, y), Offset(size.width, y), 1.dp.toPx()
                )
            }
            for (index in 0 until count) {
                val value = levels.getOrElse(index) { 0f }.coerceIn(0f, 1f)
                val peak = peaks.getOrElse(index) { 0f }.coerceIn(0f, 1f)
                val height = max(2.dp.toPx(), value * (size.height - 8.dp.toPx()))
                val x = index * (barWidth + gap)
                val color = when {
                    index < 10 -> SpectrumGreen
                    index < 24 -> SpectrumAmber
                    else -> SpectrumOrange
                }
                drawRoundRect(
                    color.copy(alpha = 0.92f),
                    Offset(x, size.height - height),
                    Size(barWidth, height),
                    androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                )
                if (peak > 0.015f) {
                    val peakY = size.height - peak * (size.height - 8.dp.toPx()) - 2.dp.toPx()
                    drawLine(
                        SpectrumCream.copy(alpha = 0.92f),
                        Offset(x, peakY),
                        Offset(x + barWidth, peakY),
                        1.5.dp.toPx()
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("35 Hz", color = SpectrumMuted, fontSize = 8.sp)
            Spacer(Modifier.weight(1f))
            Text("36 BANDS  •  POST-EQ", color = SpectrumMuted, fontSize = 8.sp, letterSpacing = 0.8.sp)
            Spacer(Modifier.weight(1f))
            Text("16 kHz", color = SpectrumMuted, fontSize = 8.sp)
        }
        if (!audioPermissionGranted) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Audio permission is needed for live analysis.",
                    color = SpectrumMuted,
                    fontSize = 9.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onRequestPermission, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text("ENABLE", color = SpectrumAmber, fontSize = 9.sp, letterSpacing = 1.sp)
                }
            }
        } else if (!playbackActive || !captureReady) {
            Text(
                captureError ?: "Waiting for playback…",
                color = SpectrumMuted,
                fontSize = 9.sp
            )
        }
    }
}
