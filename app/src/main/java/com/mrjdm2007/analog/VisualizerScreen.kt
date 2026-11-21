package com.mrjdm2007.analog

import android.media.MediaMetadataRetriever
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import java.io.File

private val VisualInk = Color(0xFF10110F)
private val VisualPanel = Color(0xFF1A1C18)
private val VisualRaised = Color(0xFF252923)
private val VisualCream = Color(0xFFE8E1CF)
private val VisualMuted = Color(0xFF85877E)
private val VisualAmber = Color(0xFFFFB05A)
private val VisualGreen = Color(0xFFB3D398)
private val VisualOrange = Color(0xFFE66D45)

private data class VisualFileDetails(
    val format: String = "—",
    val size: String = "—",
    val bitrate: String = "—",
    val sampleRate: String = "—",
    val channels: String = "—"
)

private fun visualFileSize(bytes: Long): String = when {
    bytes <= 0L -> "—"
    bytes >= 1_000_000_000L -> "%.2f GB".format(bytes / 1_000_000_000.0)
    else -> "%.1f MB".format(bytes / 1_000_000.0)
}

@Composable
fun VisualizerScreen(
    player: MediaController?,
    tracks: List<Track>,
    audioPermissionGranted: Boolean,
    onRequestPermission: () -> Unit,
    onBack: () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    var resumed by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var title by remember { mutableStateOf("Nothing playing") }
    var artist by remember { mutableStateOf("Choose a song from your library") }
    var album by remember { mutableStateOf("—") }
    var playing by remember { mutableStateOf(false) }
    var trackId by remember { mutableStateOf<Long?>(null) }
    var duration by remember { mutableLongStateOf(0L) }
    var position by remember { mutableLongStateOf(0L) }
    var isSeeking by remember { mutableStateOf(false) }
    var sessionId by remember { mutableIntStateOf(0) }
    var levels by remember { mutableStateOf(FloatArray(36)) }
    var peaks by remember { mutableStateOf(FloatArray(36)) }
    var captureReady by remember { mutableStateOf(false) }
    var captureError by remember { mutableStateOf<String?>(null) }
    var details by remember { mutableStateOf(VisualFileDetails()) }
    val currentTrack = tracks.firstOrNull { it.id == trackId }

    val syncPlayer = {
        title = player?.mediaMetadata?.title?.toString()?.takeIf { it.isNotBlank() } ?: "Nothing playing"
        artist = player?.mediaMetadata?.artist?.toString()?.takeIf { it.isNotBlank() } ?: "Unknown artist"
        album = player?.mediaMetadata?.albumTitle?.toString()?.takeIf { it.isNotBlank() } ?: "—"
        playing = player?.isPlaying == true
        trackId = player?.currentMediaItem?.mediaId?.toLongOrNull()
        duration = player?.duration?.takeIf { it > 0L } ?: 0L
        position = (player?.currentPosition ?: 0L).coerceAtLeast(0L)
        sessionId = context.getSharedPreferences("analog_library", android.content.Context.MODE_PRIVATE).getInt("audio_session_id", 0)
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) = syncPlayer()
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(playbackState: Int) = syncPlayer()
            override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) = syncPlayer()
        }
        player?.addListener(listener)
        syncPlayer()
        onDispose { player?.removeListener(listener) }
    }

    LaunchedEffect(player, resumed) {
        while (player != null && resumed) {
            if (!isSeeking) position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.takeIf { it > 0L } ?: 0L
            playing = player.isPlaying
            sessionId = context.getSharedPreferences("analog_library", android.content.Context.MODE_PRIVATE).getInt("audio_session_id", 0)
            delay(if (player.isPlaying) 250L else 900L)
        }
    }

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

    LaunchedEffect(trackId, currentTrack?.uri) {
        details = VisualFileDetails()
        val track = currentTrack ?: return@LaunchedEffect
        details = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, track.uri)
                val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()
                val sampleRate = if (android.os.Build.VERSION.SDK_INT >= 31) retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() else null
                val bytes = track.path.takeIf { it.isNotBlank() }?.let { runCatching { File(it).length() }.getOrNull() } ?: 0L
                VisualFileDetails(
                    format = track.path.substringAfterLast('.', "").uppercase().ifBlank { "—" },
                    size = visualFileSize(bytes),
                    bitrate = bitrate?.let { if (it >= 1_000_000) "%.2f Mbps".format(it / 1_000_000.0) else "${it / 1000} kbps" } ?: "—",
                    sampleRate = sampleRate?.let { "%.1f kHz".format(it / 1000.0) } ?: "—",
                    channels = "—"
                )
            } catch (_: Exception) { VisualFileDetails(format = track.path.substringAfterLast('.', "").uppercase().ifBlank { "—" }) }
            finally { runCatching { retriever.release() } }
        }
    }

    val shouldCapture = audioPermissionGranted && playing && resumed && sessionId > 0
    DisposableEffect(sessionId, shouldCapture) {
        levels = FloatArray(36)
        peaks = FloatArray(36)
        if (!shouldCapture) {
            captureReady = false
            captureError = when {
                !audioPermissionGranted -> "Allow audio analysis to enable the live spectrum."
                !playing -> "Start playback to activate the spectrum analyser."
                sessionId <= 0 -> "Connecting to the playback audio session…"
                else -> null
            }
            onDispose { }
        } else {
            val handler = Handler(Looper.getMainLooper())
            var active = true
            var effect: Visualizer? = null
            val smoothed = FloatArray(36)
            val held = FloatArray(36)
            val created = runCatching {
                Visualizer(sessionId).apply {
                    val range = Visualizer.getCaptureSizeRange()
                    val captureSize = 1024.coerceIn(range[0], range[1])
                    this.captureSize = captureSize
                    setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                        override fun onFftDataCapture(visualizer: Visualizer, fft: ByteArray, samplingRate: Int) {
                            if (!active || fft.size < 8) return
                            val binCount = fft.size / 2
                            val binHz = (samplingRate / 1000.0) / captureSize
                            val upper = min(16_000.0, samplingRate / 1000.0 * 0.45)
                            val next = FloatArray(36)
                            for (index in 0 until 36) {
                                val lowHz = 35.0 * (upper / 35.0).pow(index / 36.0)
                                val highHz = 35.0 * (upper / 35.0).pow((index + 1) / 36.0)
                                val start = (lowHz / binHz).toInt().coerceIn(1, binCount - 1)
                                val end = (highHz / binHz).toInt().coerceIn(start + 1, binCount)
                                var magnitude = 0.0
                                for (bin in start until end) {
                                    val real = fft[bin * 2].toInt()
                                    val imaginary = fft[bin * 2 + 1].toInt()
                                    magnitude = max(magnitude, kotlin.math.hypot(real.toDouble(), imaginary.toDouble()))
                                }
                                val target = ((ln(1.0 + magnitude) / ln(128.0) - 0.035) * 1.18).coerceIn(0.0, 1.0).toFloat()
                                smoothed[index] = if (target > smoothed[index]) smoothed[index] * 0.42f + target * 0.58f else smoothed[index] * 0.78f + target * 0.22f
                                held[index] = max(held[index] - 0.012f, smoothed[index])
                                next[index] = smoothed[index]
                            }
                            val peakFrame = held.copyOf()
                            handler.post { if (active) { levels = next; peaks = peakFrame; captureReady = true; captureError = null } }
                        }
                    }, min(20_000, Visualizer.getMaxCaptureRate()), true, true)
                    enabled = true
                }
            }
            effect = created.getOrNull()
            captureReady = effect != null
            captureError = if (effect == null) "Live analysis is unavailable for this audio output." else null
            onDispose {
                active = false
                runCatching { effect?.setDataCaptureListener(null, 0, false, false); effect?.enabled = false; effect?.release() }
                handler.removeCallbacksAndMessages(null)
                levels = FloatArray(36)
                peaks = FloatArray(36)
                captureReady = false
            }
        }
    }

    Column(Modifier.fillMaxSize().background(VisualInk).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(38.dp)) { Icon(Icons.Default.ArrowBack, null, tint = VisualCream) }
            Column(Modifier.weight(1f)) {
                Text("ANALOG  /  LIVE SPECTRUM", color = VisualAmber, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.8.sp)
                Text("POST-EQ AUDIO ANALYSIS  •  36 BANDS", color = VisualMuted, fontSize = 8.sp, letterSpacing = 1.1.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val live = captureReady && playing
                val liveAlpha by animateFloatAsState(if (live) 1f else .38f, animationSpec = tween(360), label = "visualizer-live-indicator")
                Box(Modifier.size(6.dp).background(VisualGreen.copy(alpha = liveAlpha), RoundedCornerShape(50)))
                Text(if (live) "LIVE" else "STANDBY", color = if (live) VisualGreen else VisualMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Column(Modifier.weight(1.48f).fillMaxHeight().background(VisualPanel.copy(alpha = .72f), RoundedCornerShape(12.dp)).padding(horizontal = 6.dp, vertical = 5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("LIVE AUDIO ANALYSER", color = VisualCream, fontSize = 14.sp, fontWeight = FontWeight.Black, letterSpacing = .7.sp)
                        Text("Real-time signal from the playback chain", color = VisualMuted, fontSize = 9.sp)
                    }
                    Text("36 BANDS", color = VisualAmber, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF070807), RoundedCornerShape(12.dp)).border(1.dp, VisualCream.copy(alpha = .10f), RoundedCornerShape(10.dp)).padding(horizontal = 6.dp, vertical = 4.dp)) {
                    Canvas(Modifier.fillMaxSize()) {
                        for (fraction in listOf(.25f, .5f, .75f)) {
                            val y = size.height * (1f - fraction)
                            drawLine(Color.White.copy(alpha = .075f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                        }
                        val gap = 3.dp.toPx()
                        val barWidth = (size.width - gap * 35) / 36f
                        for (index in 0 until 36) {
                            val value = levels[index].coerceIn(0f, 1f)
                            val peak = peaks[index].coerceIn(0f, 1f)
                            val height = max(2.dp.toPx(), value * (size.height - 12.dp.toPx()))
                            val x = index * (barWidth + gap)
                            val color = when { index < 10 -> VisualGreen; index < 24 -> VisualAmber; else -> VisualOrange }
                            drawRoundRect(Brush.verticalGradient(listOf(color.copy(alpha = .58f), color)), Offset(x, size.height - height), Size(barWidth, height), androidx.compose.ui.geometry.CornerRadius(2.5.dp.toPx()))
                            if (peak > .02f) {
                                val py = size.height - peak * (size.height - 12.dp.toPx()) - 2.dp.toPx()
                                drawLine(VisualCream.copy(alpha = .92f), Offset(x, py), Offset(x + barWidth, py), 1.3.dp.toPx())
                            }
                        }
                    }
                    if (!playing || !captureReady) {
                        Column(Modifier.align(Alignment.Center).background(VisualInk.copy(alpha = .78f), RoundedCornerShape(9.dp)).padding(horizontal = 14.dp, vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (!playing) "PLAYBACK PAUSED" else "CONNECTING ANALYSER", color = VisualCream, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            if (!audioPermissionGranted) TextButton(onClick = onRequestPermission) { Text("ENABLE AUDIO ANALYSIS", color = VisualAmber, fontSize = 9.sp) }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("35 Hz", color = VisualMuted, fontSize = 8.sp)
                    Spacer(Modifier.weight(1f))
                    Text("POST-EQ  •  LOGARITHMIC", color = VisualMuted, fontSize = 8.sp, letterSpacing = .7.sp)
                    Spacer(Modifier.weight(1f))
                    Text("16 kHz", color = VisualMuted, fontSize = 8.sp)
                }
                Row(Modifier.fillMaxWidth().padding(top = 1.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    VisualMetric("SOURCE", "POST-EQ", Modifier.weight(1f))
                    VisualMetric("BANDS", "36", Modifier.weight(1f))
                    VisualMetric("RANGE", "35 Hz–16 kHz", Modifier.weight(1f))
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().background(VisualPanel.copy(alpha = .72f), RoundedCornerShape(12.dp)).padding(horizontal = 6.dp, vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("NOW PLAYING", color = VisualMuted, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.7.sp)
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.size(6.dp).background(if (playing) VisualGreen else VisualMuted, RoundedCornerShape(50)))
                }
                Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(.82f).fillMaxHeight().aspectRatio(1f).shadow(12.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(VisualRaised, Color.Black))), contentAlignment = Alignment.Center) {
                        if (currentTrack != null && currentTrack.albumId > 0L) AsyncImage(model = currentTrack.artUri(), contentDescription = currentTrack.album, modifier = Modifier.fillMaxSize().padding(3.dp), contentScale = ContentScale.Crop)
                        else Text("A", color = VisualAmber, fontSize = 38.sp, fontWeight = FontWeight.Black)
                    }
                    Column(Modifier.weight(1.2f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(title, color = VisualCream, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                        Text(artist, color = VisualMuted, fontSize = 9.sp, maxLines = 1)
                        Text("ALBUM  •  $album", color = VisualMuted, fontSize = 8.sp, maxLines = 1)
                        Spacer(Modifier.height(1.dp))
                        Text("FILE DETAILS", color = VisualMuted, fontSize = 7.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            VisualDetail("FORMAT", details.format, Modifier.weight(1f))
                            VisualDetail("SIZE", details.size, Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            VisualDetail("AVG. BITRATE", details.bitrate, Modifier.weight(1f))
                            VisualDetail("SAMPLE RATE", details.sampleRate, Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            VisualDetail("DURATION", if (duration > 0) visualTime(duration) else "—", Modifier.weight(1f))
                            VisualDetail("CHANNELS", details.channels, Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(10.dp))
                        VisualMiniSpectrum(levels, playing && captureReady)
                        Spacer(Modifier.height(8.dp))
                        Text("POST-EQ  •  35 Hz–16 kHz  •  36 BANDS", color = VisualMuted, fontSize = 7.sp, fontWeight = FontWeight.Bold, letterSpacing = .8.sp)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 3.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(visualTime(position), color = VisualMuted, fontSize = 9.sp)
            Slider(
                value = position.toFloat().coerceIn(0f, duration.coerceAtLeast(1L).toFloat()),
                onValueChange = { isSeeking = true; position = it.toLong() },
                onValueChangeFinished = {
                    player?.seekTo(position)
                    isSeeking = false
                },
                valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
                modifier = Modifier.weight(1f).height(26.dp),
                colors = SliderDefaults.colors(thumbColor = VisualCream, activeTrackColor = VisualAmber, inactiveTrackColor = VisualMuted.copy(alpha = .28f))
            )
            Text(visualTime(duration), color = VisualMuted, fontSize = 9.sp)
            IconButton(onClick = { player?.seekToPrevious() }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.SkipPrevious, null, tint = VisualCream, modifier = Modifier.size(27.dp)) }
            FilledIconButton(onClick = { if (playing) player?.pause() else if (player?.mediaItemCount == 0) Unit else player?.play() }, modifier = Modifier.size(52.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = VisualAmber)) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = VisualInk, modifier = Modifier.size(30.dp)) }
            IconButton(onClick = { player?.seekToNext() }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.SkipNext, null, tint = VisualCream, modifier = Modifier.size(27.dp)) }
        }
    }
}

@Composable
private fun VisualMiniSpectrum(levels: FloatArray, active: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("FREQUENCY PROFILE", color = VisualMuted, fontSize = 7.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            Spacer(Modifier.weight(1f))
            Text("36-BAND FFT", color = VisualMuted, fontSize = 6.sp, fontWeight = FontWeight.Bold)
        }
        Canvas(Modifier.fillMaxWidth().height(48.dp).background(Color(0xFF080908), RoundedCornerShape(8.dp))) {
            val gap = 2.dp.toPx()
            val width = (size.width - gap * (levels.size - 1)) / levels.size.coerceAtLeast(1)
            levels.forEachIndexed { index, raw ->
                val height = (raw.coerceIn(0f, 1f) * size.height * .88f).coerceAtLeast(1.dp.toPx())
                val x = index * (width + gap)
                val color = when {
                    index < 10 -> VisualGreen
                    index < 24 -> VisualAmber
                    else -> VisualOrange
                }
                drawRoundRect(
                    brush = Brush.verticalGradient(listOf(color.copy(alpha = if (active) .55f else .18f), color.copy(alpha = if (active) 1f else .28f))),
                    topLeft = Offset(x, size.height - height),
                    size = Size(width, height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                )
            }
        }
    }
}

@Composable
private fun VisualMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.heightIn(min = 22.dp).background(VisualRaised.copy(alpha = .72f), RoundedCornerShape(7.dp)).padding(horizontal = 5.dp, vertical = 1.dp)) {
        Text(value, color = VisualCream, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = VisualMuted, fontSize = 6.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
    }
}

@Composable
private fun VisualDetail(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.heightIn(min = 25.dp).background(VisualRaised.copy(alpha = .72f), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 2.dp)) {
        Text(label, color = VisualMuted, fontSize = 6.sp, fontWeight = FontWeight.Black, letterSpacing = .8.sp, maxLines = 1)
        Text(value, color = VisualCream, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

private fun visualTime(milliseconds: Long): String {
    val seconds = (milliseconds.coerceAtLeast(0L) / 1000L).toInt()
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

