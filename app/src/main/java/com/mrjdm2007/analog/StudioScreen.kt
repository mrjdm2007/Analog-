package com.mrjdm2007.analog

import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.media.MediaPlayer
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.os.Environment
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.util.Locale

private val StudioBg = Color(0xFF090D0B)
private val StudioPanel = Color(0xFF141B16)
private val StudioAmber = Color(0xFFFFB45B)
private val StudioGreen = Color(0xFFB5D69B)
private val StudioText = Color(0xFFE8E1CF)
private val StudioMuted = Color(0xFF849184)

@Composable
fun StudioScreen(audioPermissionGranted: Boolean, onRequestPermission: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val snapshot by RecordingEngine.state.collectAsState()
    var sampleRate by remember { mutableIntStateOf(context.getSharedPreferences("analog_library", Context.MODE_PRIVATE).getInt("studio_sample_rate", 48000)) }
    var bitDepth by remember { mutableIntStateOf(context.getSharedPreferences("analog_library", Context.MODE_PRIVATE).getInt("studio_bit_depth", 16)) }
    var channels by remember { mutableIntStateOf(context.getSharedPreferences("analog_library", Context.MODE_PRIVATE).getInt("studio_channels", 1)) }
    var highPass by remember { mutableStateOf(context.getSharedPreferences("analog_library", Context.MODE_PRIVATE).getBoolean("studio_high_pass", false)) }
    var limiter by remember { mutableStateOf(context.getSharedPreferences("analog_library", Context.MODE_PRIVATE).getBoolean("studio_limiter", true)) }
    var rawMicMode by remember { mutableStateOf(context.getSharedPreferences("analog_library", Context.MODE_PRIVATE).getBoolean("studio_raw_mic", false)) }
    var gain by remember { mutableFloatStateOf(context.getSharedPreferences("analog_library", Context.MODE_PRIVATE).getFloat("studio_gain", 1f)) }
    var selectedInputDeviceId by remember { mutableIntStateOf(-1) }
    var inputMenuExpanded by remember { mutableStateOf(false) }
    var inputRefresh by remember { mutableIntStateOf(0) }
    var lowGainDb by remember { mutableFloatStateOf(context.getSharedPreferences("analog_library", Context.MODE_PRIVATE).getFloat("studio_low_gain", 0f)) }
    var midGainDb by remember { mutableFloatStateOf(context.getSharedPreferences("analog_library", Context.MODE_PRIVATE).getFloat("studio_mid_gain", 0f)) }
    var highGainDb by remember { mutableFloatStateOf(context.getSharedPreferences("analog_library", Context.MODE_PRIVATE).getFloat("studio_high_gain", 0f)) }
    var compressorEnabled by remember { mutableStateOf(context.getSharedPreferences("analog_library", Context.MODE_PRIVATE).getBoolean("studio_compressor", false)) }
    var compressorThresholdDb by remember { mutableFloatStateOf(context.getSharedPreferences("analog_library", Context.MODE_PRIVATE).getFloat("studio_compressor_threshold", -18f)) }
    val inputDevices = remember(inputRefresh) { context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS).toList() }
    val selectedInputLabel = inputDevices.firstOrNull { it.id == selectedInputDeviceId }?.productName?.toString() ?: "System default input"
    var showSettings by remember { mutableStateOf(true) }
    var takes by remember { mutableStateOf(listTakes(context)) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var playingFile by remember { mutableStateOf<File?>(null) }
    var renameTarget by remember { mutableStateOf<File?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<File?>(null) }

    // Keep the complete capture chain as the user's last-used studio setup.
    SideEffect {
        context.getSharedPreferences("analog_library", Context.MODE_PRIVATE).edit()
            .putInt("studio_sample_rate", sampleRate)
            .putInt("studio_bit_depth", bitDepth)
            .putInt("studio_channels", channels)
            .putBoolean("studio_high_pass", highPass)
            .putBoolean("studio_limiter", limiter)
            .putBoolean("studio_raw_mic", rawMicMode)
            .putFloat("studio_gain", gain)
            .putFloat("studio_low_gain", lowGainDb)
            .putFloat("studio_mid_gain", midGainDb)
            .putFloat("studio_high_gain", highGainDb)
            .putBoolean("studio_compressor", compressorEnabled)
            .putFloat("studio_compressor_threshold", compressorThresholdDb)
            .apply()
    }

    fun sendCommand(action: String) {
        context.startService(Intent(context, RecordingService::class.java).setAction(action))
    }

    DisposableEffect(Unit) {
        onDispose { runCatching { mediaPlayer?.release() }; mediaPlayer = null }
    }
    LaunchedEffect(snapshot.status) {
        if (snapshot.status == "SAVED" || snapshot.status == "ERROR") takes = listTakes(context)
    }

    Column(Modifier.fillMaxSize().background(StudioBg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = StudioText) }
            Column(Modifier.weight(1f)) {
                Text("ANALOG / STUDIO", color = StudioAmber, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                Text("FIELD RECORDING • PCM WORKSTATION", color = StudioGreen, fontSize = 9.sp, letterSpacing = 1.2.sp)
            }
            Icon(Icons.Default.GraphicEq, null, tint = StudioGreen)
        }

        if (!audioPermissionGranted) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.FiberManualRecord, null, tint = StudioAmber, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(14.dp))
                Text("MICROPHONE ACCESS REQUIRED", color = StudioText, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("Analog needs microphone permission to capture and visualize live audio. Nothing is recorded until you press Record.", color = StudioMuted, fontSize = 12.sp)
                Spacer(Modifier.height(18.dp))
                Button(onClick = onRequestPermission) { Text("ALLOW MICROPHONE") }
            }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                        Column {
                            Text("TAKE IN PROGRESS", color = StudioMuted, fontSize = 9.sp, letterSpacing = 1.8.sp)
                            Text(formatDuration(snapshot.elapsedMs), color = StudioText, fontSize = 34.sp, fontWeight = FontWeight.Light)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(snapshot.status, color = if (snapshot.status == "RECORDING") StudioAmber else StudioGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(formatBytes(snapshot.bytesWritten), color = StudioMuted, fontSize = 11.sp)
                        }
                    }
                    Text("REQUESTED INPUT: " + selectedInputLabel, color = StudioMuted, fontSize = 9.sp, maxLines = 1)
                    Text(
                        "ACTIVE ROUTE: " + (snapshot.activeInput ?: if (snapshot.status in listOf("RECORDING", "PAUSED")) "Resolving Android route…" else "Not recording"),
                        color = StudioGreen, fontSize = 9.sp, maxLines = 1
                    )
                }
                item {
                    Column(Modifier.fillMaxWidth().background(StudioPanel, RoundedCornerShape(14.dp)).padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("LIVE WAVEFORM", color = StudioGreen, fontSize = 10.sp, letterSpacing = 1.6.sp, modifier = Modifier.weight(1f))
                            Text("PCM • " + (sampleRate / 1000) + " kHz", color = StudioMuted, fontSize = 9.sp)
                        }
                        Spacer(Modifier.height(12.dp))
                        Canvas(Modifier.fillMaxWidth().height(116.dp)) {
                            for (i in 0..4) {
                                val y = size.height * i / 4f
                                drawLine(StudioGreen.copy(alpha = .16f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                            }
                            val mid = size.height / 2f
                            val values = snapshot.waveform
                            if (values.size > 1) {
                                val step = size.width / (values.size - 1)
                                for (i in 0 until values.lastIndex) {
                                    val a = values[i].coerceIn(0f, 1f) * size.height * .46f
                                    val b = values[i + 1].coerceIn(0f, 1f) * size.height * .46f
                                    drawLine(StudioAmber, Offset(i * step, mid - a), Offset((i + 1) * step, mid - b), 2.dp.toPx(), StrokeCap.Round)
                                    drawLine(StudioAmber.copy(alpha = .65f), Offset(i * step, mid + a), Offset((i + 1) * step, mid + b), 2.dp.toPx(), StrokeCap.Round)
                                }
                            }
                        }
                    }
                }
                item {
                    Column(Modifier.fillMaxWidth().background(StudioPanel, RoundedCornerShape(14.dp)).padding(14.dp)) {
                        Text("48-BAND FFT SPECTRUM", color = StudioGreen, fontSize = 10.sp, letterSpacing = 1.6.sp)
                        Spacer(Modifier.height(12.dp))
                        Canvas(Modifier.fillMaxWidth().height(118.dp)) {
                            for (i in 0..4) {
                                val y = size.height * i / 4f
                                drawLine(StudioGreen.copy(alpha = .14f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                            }
                            val values = snapshot.spectrum
                            val gap = size.width * .006f
                            val width = (size.width - gap * (values.size - 1)) / values.size
                            values.forEachIndexed { index, value ->
                                val height = value.coerceIn(.01f, 1f) * size.height
                                val x = index * (width + gap)
                                drawLine(StudioGreen, Offset(x + width / 2, size.height), Offset(x + width / 2, size.height - height), width.coerceAtLeast(1f), StrokeCap.Round)
                            }
                        }
                        Text("LIVE INPUT • LOG-FREQUENCY BANDS", color = StudioMuted, fontSize = 8.sp, letterSpacing = 1.2.sp)
                        Spacer(Modifier.height(14.dp))
                        Text("LIVE SPECTROGRAM • RECENT 64 FRAMES", color = StudioGreen, fontSize = 9.sp, letterSpacing = 1.2.sp)
                        Spacer(Modifier.height(8.dp))
                        Canvas(Modifier.fillMaxWidth().height(104.dp)) {
                            val rows = snapshot.spectrogram
                            if (rows.isNotEmpty()) {
                                val cellWidth = size.width / 48f
                                val cellHeight = size.height / rows.size
                                rows.forEachIndexed { row, bands ->
                                    bands.forEachIndexed { column, level ->
                                        drawRect(color = StudioGreen.copy(alpha = level.coerceIn(0.04f, 1f)), topLeft = Offset(column * cellWidth, row * cellHeight), size = Size(cellWidth + .5f, cellHeight + .5f))
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    Column(Modifier.fillMaxWidth().background(StudioPanel, RoundedCornerShape(14.dp)).padding(14.dp)) {
                        Text("INPUT METERS", color = StudioGreen, fontSize = 10.sp, letterSpacing = 1.6.sp)
                        Spacer(Modifier.height(10.dp))
                        MeterRow("PEAK", snapshot.peakDbfs)
                        MeterRow("RMS", snapshot.rmsDbfs)
                        Text("dBFS • mono/stereo input channels are hardware dependent", color = StudioMuted, fontSize = 8.sp)
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                val intent = Intent(context, RecordingService::class.java).apply {
                                    action = RecordingService.ACTION_START
                                    putExtra(RecordingService.EXTRA_RATE, sampleRate)
                                    putExtra(RecordingService.EXTRA_CHANNELS, channels)
                                    putExtra(RecordingService.EXTRA_HIGH_PASS, highPass)
                                    putExtra(RecordingService.EXTRA_LIMITER, limiter)
                                    putExtra(RecordingService.EXTRA_GAIN, gain)
                                    putExtra(RecordingService.EXTRA_INPUT_DEVICE_ID, selectedInputDeviceId)
                                    putExtra(RecordingService.EXTRA_LOW_GAIN_DB, lowGainDb)
                                    putExtra(RecordingService.EXTRA_MID_GAIN_DB, midGainDb)
                                    putExtra(RecordingService.EXTRA_HIGH_GAIN_DB, highGainDb)
                                    putExtra(RecordingService.EXTRA_COMPRESSOR, compressorEnabled)
                                    putExtra(RecordingService.EXTRA_COMPRESSOR_THRESHOLD, compressorThresholdDb)
                                    putExtra(RecordingService.EXTRA_BIT_DEPTH, bitDepth)
                                    putExtra(RecordingService.EXTRA_RAW_MIC, rawMicMode)
                                }
                                ContextCompat.startForegroundService(context, intent)
                            },
                            enabled = snapshot.status !in listOf("STARTING", "RECORDING", "PAUSED"),
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = StudioAmber, contentColor = StudioBg)
                        ) {
                            Icon(Icons.Default.FiberManualRecord, null)
                            Spacer(Modifier.width(5.dp))
                            Text("RECORD")
                        }
                        FilledTonalButton(
                            onClick = { sendCommand(if (snapshot.status == "PAUSED") RecordingService.ACTION_RESUME else RecordingService.ACTION_PAUSE) },
                            enabled = snapshot.status == "RECORDING" || snapshot.status == "PAUSED",
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(if (snapshot.status == "PAUSED") Icons.Default.PlayArrow else Icons.Default.Pause, null)
                            Spacer(Modifier.width(4.dp))
                            Text(if (snapshot.status == "PAUSED") "RESUME" else "PAUSE")
                        }
                        FilledTonalButton(
                            onClick = { sendCommand(RecordingService.ACTION_STOP) },
                            enabled = snapshot.status == "RECORDING" || snapshot.status == "PAUSED"
                        ) { Icon(Icons.Default.Stop, null) }
                        FilledTonalButton(
                            onClick = { RecordingEngine.addMarker() },
                            enabled = snapshot.status == "RECORDING" || snapshot.status == "PAUSED"
                        ) { Icon(Icons.Default.BookmarkAdd, null) }
                    }
                    if (snapshot.error != null) Text(snapshot.error ?: "", color = StudioAmber, fontSize = 11.sp)
                    if (snapshot.file != null && snapshot.status == "SAVED") Text("SAVED: " + snapshot.file!!.name, color = StudioGreen, fontSize = 9.sp)
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("RECORDING SETUP", color = StudioGreen, fontSize = 10.sp, letterSpacing = 1.6.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { showSettings = !showSettings }) { Text(if (showSettings) "HIDE" else "EDIT") }
                    }
                    if (showSettings) {
                        Column(Modifier.fillMaxWidth().background(StudioPanel, RoundedCornerShape(14.dp)).padding(14.dp)) {
                            Text("QUICK RECORDING PRESETS", color = StudioGreen, fontSize = 9.sp)
                            Text("Starting points only — check the live meters and input level before recording.", color = StudioMuted, fontSize = 9.sp)
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    FilterChip(modifier = Modifier.weight(1f), selected = false, onClick = { sampleRate = 48000; bitDepth = if (Build.VERSION.SDK_INT >= 31) 24 else 16; channels = 1; highPass = true; limiter = true; gain = 1f; lowGainDb = 0f; midGainDb = 0f; highGainDb = 0f; compressorEnabled = true; compressorThresholdDb = -20f }, label = { Text("DIALOGUE") }, enabled = snapshot.status !in listOf("RECORDING", "PAUSED", "STARTING"))
                                    FilterChip(modifier = Modifier.weight(1f), selected = false, onClick = { sampleRate = 48000; bitDepth = if (Build.VERSION.SDK_INT >= 31) 24 else 16; channels = 2; highPass = false; limiter = true; gain = 1f; lowGainDb = 0f; midGainDb = 0f; highGainDb = 0f; compressorEnabled = false; compressorThresholdDb = -18f }, label = { Text("MUSIC / STEREO") }, enabled = snapshot.status !in listOf("RECORDING", "PAUSED", "STARTING"))
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    FilterChip(modifier = Modifier.weight(1f), selected = false, onClick = { sampleRate = 48000; bitDepth = if (Build.VERSION.SDK_INT >= 31) 24 else 16; channels = 2; highPass = true; limiter = true; gain = 0.8f; lowGainDb = 0f; midGainDb = 0f; highGainDb = 0f; compressorEnabled = false; compressorThresholdDb = -18f }, label = { Text("AMBIENCE") }, enabled = snapshot.status !in listOf("RECORDING", "PAUSED", "STARTING"))
                                    FilterChip(modifier = Modifier.weight(1f), selected = false, onClick = { sampleRate = 48000; bitDepth = 16; channels = 1; highPass = true; limiter = true; gain = 1f; lowGainDb = 0f; midGainDb = 0f; highGainDb = 0f; compressorEnabled = true; compressorThresholdDb = -18f }, label = { Text("INTERVIEW") }, enabled = snapshot.status !in listOf("RECORDING", "PAUSED", "STARTING"))
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Text("INPUT DEVICE", color = StudioMuted, fontSize = 9.sp)
                            Box {
                                OutlinedButton(onClick = { inputMenuExpanded = true }, enabled = snapshot.status !in listOf("RECORDING", "PAUSED", "STARTING")) { Text(selectedInputLabel, maxLines = 1) }
                                DropdownMenu(expanded = inputMenuExpanded, onDismissRequest = { inputMenuExpanded = false }) {
                                    DropdownMenuItem(text = { Text("System default input") }, onClick = { selectedInputDeviceId = -1; inputMenuExpanded = false })
                                    inputDevices.forEach { device ->
                                        DropdownMenuItem(text = { Text(device.productName.toString() + " • " + device.typeLabel()) }, onClick = { selectedInputDeviceId = device.id; inputMenuExpanded = false })
                                    }
                                }
                            }
                            TextButton(onClick = { inputRefresh++ }, enabled = snapshot.status !in listOf("RECORDING", "PAUSED", "STARTING")) { Text("REFRESH INPUT DEVICES") }
                            Spacer(Modifier.height(8.dp))
                            Text("SAMPLE RATE", color = StudioMuted, fontSize = 9.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(44100, 48000, 96000).forEach { rate ->
                                    FilterChip(selected = sampleRate == rate, onClick = { sampleRate = rate }, label = { Text((rate / 1000).toString() + " kHz") }, enabled = snapshot.status !in listOf("RECORDING", "PAUSED", "STARTING"))
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("WAV ENCODING", color = StudioMuted, fontSize = 9.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                FilterChip(selected = bitDepth == 16, onClick = { bitDepth = 16 }, label = { Text("16-BIT PCM") }, enabled = snapshot.status !in listOf("RECORDING", "PAUSED", "STARTING"))
                                FilterChip(selected = bitDepth == 24, onClick = { bitDepth = 24 }, label = { Text("24-BIT PCM") }, enabled = Build.VERSION.SDK_INT >= 31 && snapshot.status !in listOf("RECORDING", "PAUSED", "STARTING"))
                                FilterChip(selected = bitDepth == 32, onClick = { bitDepth = 32 }, label = { Text("32-BIT FLOAT") }, enabled = snapshot.status !in listOf("RECORDING", "PAUSED", "STARTING"))
                            }
                            if (Build.VERSION.SDK_INT < 31) Text("24-bit packed capture requires Android 12 or newer. 32-bit float input is requested from Android and may not be supported by every microphone route.", color = StudioMuted, fontSize = 9.sp)
                            else Text("32-bit float preserves digital headroom through processing when the input route supports float capture; it cannot increase the microphone ADC's native dynamic range.", color = StudioMuted, fontSize = 9.sp)
                            Spacer(Modifier.height(8.dp))
                            Text("INPUT CHANNELS", color = StudioMuted, fontSize = 9.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = channels == 1, onClick = { channels = 1 }, label = { Text("MONO") }, enabled = snapshot.status !in listOf("RECORDING", "PAUSED", "STARTING"))
                                FilterChip(selected = channels == 2, onClick = { channels = 2 }, label = { Text("STEREO") }, enabled = snapshot.status !in listOf("RECORDING", "PAUSED", "STARTING"))
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("RAW MICROPHONE CAPTURE", color = StudioAmber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text("Bypass Analog gain, EQ, compressor, high-pass and limiter. Android/device DSP may still remain.", color = StudioMuted, fontSize = 9.sp)
                                }
                                Switch(checked = rawMicMode, onCheckedChange = { rawMicMode = it }, enabled = snapshot.status !in listOf("RECORDING", "PAUSED", "STARTING"))
                            }
                            if (rawMicMode) Text("RAW MODE: use UNPROCESSED input when supported. If Android falls back to MIC, system processing may still apply. Integer WAV formats still require normal quantization.", color = StudioAmber, fontSize = 9.sp)
                            Spacer(Modifier.height(8.dp))
                            Text("DIGITAL INPUT GAIN • " + String.format(Locale.US, "%.1f", gain) + "×", color = StudioMuted, fontSize = 9.sp)
                            Slider(value = gain, onValueChange = { gain = it }, valueRange = .25f..2f, enabled = !rawMicMode && snapshot.status !in listOf("RECORDING", "PAUSED", "STARTING"))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("80 Hz high-pass", color = StudioText, fontSize = 12.sp)
                                    Text("Reduces low-frequency rumble", color = StudioMuted, fontSize = 9.sp)
                                }
                                Switch(checked = highPass, onCheckedChange = { highPass = it }, enabled = !rawMicMode && snapshot.status !in listOf("RECORDING", "PAUSED", "STARTING"))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Peak limiter", color = StudioText, fontSize = 12.sp)
                                    Text("Hard safety ceiling before PCM encoding", color = StudioMuted, fontSize = 9.sp)
                                }
                                Switch(checked = limiter, onCheckedChange = { limiter = it }, enabled = !rawMicMode && snapshot.status !in listOf("RECORDING", "PAUSED", "STARTING"))
                            }
                            Spacer(Modifier.height(12.dp))
                            Text("THREE-BAND TONE CONTROL", color = StudioGreen, fontSize = 9.sp)
                            Text("LOW 250 Hz • " + String.format(Locale.US, "%+.0f dB", lowGainDb), color = StudioMuted, fontSize = 9.sp)
                            Slider(value = lowGainDb, onValueChange = { lowGainDb = it }, valueRange = -12f..12f, enabled = !rawMicMode && snapshot.status !in listOf("RECORDING", "PAUSED", "STARTING"))
                            Text("MID BAND • " + String.format(Locale.US, "%+.0f dB", midGainDb), color = StudioMuted, fontSize = 9.sp)
                            Slider(value = midGainDb, onValueChange = { midGainDb = it }, valueRange = -12f..12f, enabled = !rawMicMode && snapshot.status !in listOf("RECORDING", "PAUSED", "STARTING"))
                            Text("HIGH 4 kHz • " + String.format(Locale.US, "%+.0f dB", highGainDb), color = StudioMuted, fontSize = 9.sp)
                            Slider(value = highGainDb, onValueChange = { highGainDb = it }, valueRange = -12f..12f, enabled = !rawMicMode && snapshot.status !in listOf("RECORDING", "PAUSED", "STARTING"))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Compressor", color = StudioText, fontSize = 12.sp)
                                    Text("3:1 ratio • 10 ms attack • 120 ms release", color = StudioMuted, fontSize = 9.sp)
                                }
                                Switch(checked = compressorEnabled, onCheckedChange = { compressorEnabled = it }, enabled = !rawMicMode && snapshot.status !in listOf("RECORDING", "PAUSED", "STARTING"))
                            }
                            Text("COMPRESSOR THRESHOLD • " + String.format(Locale.US, "%.0f dBFS", compressorThresholdDb), color = StudioMuted, fontSize = 9.sp)
                            Slider(value = compressorThresholdDb, onValueChange = { compressorThresholdDb = it }, valueRange = -36f..-3f, enabled = compressorEnabled && snapshot.status !in listOf("RECORDING", "PAUSED", "STARTING"))
                            Text("FORMAT: WAV • " + (if (bitDepth == 32) "32-BIT IEEE FLOAT" else "$bitDepth-BIT PCM") + " • UNCOMPRESSED", color = StudioAmber, fontSize = 9.sp, letterSpacing = .8.sp)
                            Text("Input routing is requested from Android and may be overridden by device policy. Sample rates and stereo depend on connected hardware.", color = StudioMuted, fontSize = 9.sp)
                        }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("RECENT TAKES", color = StudioGreen, fontSize = 10.sp, letterSpacing = 1.6.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { takes = listTakes(context) }) { Text("REFRESH") }
                    }
                }
                if (takes.isEmpty()) {
                    item { Text("No recordings yet. Your WAV takes will appear here.", color = StudioMuted, fontSize = 11.sp) }
                } else {
                    items(takes, key = { it.absolutePath }) { file ->
                        Row(
                            Modifier.fillMaxWidth().background(StudioPanel, RoundedCornerShape(10.dp)).clickable {
                                runCatching {
                                    mediaPlayer?.release()
                                    mediaPlayer = MediaPlayer().apply {
                                        setDataSource(file.absolutePath)
                                        setOnCompletionListener { playingFile = null; mediaPlayer?.release(); mediaPlayer = null }
                                        prepare()
                                        start()
                                    }
                                    playingFile = file
                                }
                            }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.PlayArrow, null, tint = StudioAmber)
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(file.name, color = StudioText, fontSize = 10.sp, maxLines = 1)
                                Text(formatBytes(file.length()) + " • " + file.lastModified(), color = StudioMuted, fontSize = 8.sp)
                            }
                            if (playingFile == file) Text("PLAYING", color = StudioGreen, fontSize = 8.sp)
                            IconButton(onClick = {
                                runCatching {
                                    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
                                    val share = Intent(Intent.ACTION_SEND).apply {
                                        type = "audio/wav"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(share, "Share Analog recording"))
                                }
                            }) { Icon(Icons.Default.Share, "Share recording", tint = StudioMuted) }
                            IconButton(onClick = {
                                val exported = exportTakeToMusic(context, file)
                                Toast.makeText(context, if (exported) "Exported to Music/Analog/Recordings" else "Export failed or requires Android 10+", Toast.LENGTH_SHORT).show()
                            }) { Icon(Icons.Default.Download, "Export to Music", tint = StudioGreen) }
                            IconButton(onClick = { renameTarget = file; renameText = file.nameWithoutExtension }) {
                                Icon(Icons.Default.Edit, "Rename recording", tint = StudioMuted)
                            }
                            IconButton(onClick = { deleteTarget = file }) {
                                Icon(Icons.Default.Delete, "Delete recording", tint = StudioAmber)
                            }
                        }
                    }
                }
                item {
                    Text("SAVED IN APP MUSIC STORAGE / Analog / Recordings", color = StudioMuted, fontSize = 8.sp)
                }
            }
        }
    }
    renameTarget?.let { file ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            containerColor = StudioPanel,
            title = { Text("RENAME TAKE", color = StudioAmber) },
            text = { OutlinedTextField(value = renameText, onValueChange = { renameText = it.take(64) }, singleLine = true, label = { Text("Recording name") }) },
            confirmButton = {
                TextButton(onClick = {
                    val safeName = renameText.trim().replace(Regex("[^A-Za-z0-9 _-]"), "").take(64)
                    if (safeName.isNotBlank()) {
                        val renamed = File(file.parentFile, safeName + ".wav")
                        if (!renamed.exists() && file.renameTo(renamed)) {
                            val oldMarkers = File(file.parentFile, file.nameWithoutExtension + ".markers.txt")
                            if (oldMarkers.exists()) oldMarkers.renameTo(File(file.parentFile, safeName + ".markers.txt"))
                            takes = listTakes(context)
                        }
                    }
                    renameTarget = null
                }) { Text("SAVE") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("CANCEL") } }
        )
    }
    deleteTarget?.let { file ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = StudioPanel,
            title = { Text("DELETE RECORDING?", color = StudioAmber) },
            text = { Text("This permanently deletes " + file.name + ".", color = StudioText) },
            confirmButton = {
                TextButton(onClick = {
                    if (playingFile == file) {
                        runCatching { mediaPlayer?.stop() }
                        runCatching { mediaPlayer?.release() }
                        mediaPlayer = null
                        playingFile = null
                    }
                    file.delete()
                    File(file.parentFile, file.nameWithoutExtension + ".markers.txt").delete()
                    takes = listTakes(context)
                    deleteTarget = null
                }) { Text("DELETE", color = StudioAmber) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("CANCEL") } }
        )
    }
}

@Composable
private fun MeterRow(label: String, dbfs: Float) {
    val fraction = ((dbfs + 60f) / 60f).coerceIn(0f, 1f)
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = StudioAmber, fontSize = 9.sp, modifier = Modifier.width(42.dp))
        Box(Modifier.weight(1f).height(12.dp).background(Color(0xFF263126), RoundedCornerShape(3.dp))) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(if (dbfs > -6f) Color(0xFFD66B4D) else StudioGreen, RoundedCornerShape(3.dp)))
        }
        Text(String.format(Locale.US, "%.1f dB", dbfs), color = StudioText, fontSize = 9.sp, modifier = Modifier.width(65.dp).padding(start = 6.dp))
    }
}

private fun listTakes(context: Context): List<File> {
    val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir, "Analog/Recordings")
    return directory.listFiles { file -> file.isFile && file.extension.equals("wav", true) }?.sortedByDescending { it.lastModified() } ?: emptyList()
}

private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    return "%02d:%02d:%02d".format(seconds / 3600, (seconds / 60) % 60, seconds % 60)
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1073741824L -> String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
    bytes >= 1048576L -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> bytes.toString() + " B"
}

private fun AudioDeviceInfo.typeLabel(): String = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in mic"
    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB audio"
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
    else -> "Audio input"
}

private fun exportTakeToMusic(context: Context, source: File): Boolean {
    if (Build.VERSION.SDK_INT < 29) return false
    val values = ContentValues().apply {
        put(MediaStore.Audio.Media.DISPLAY_NAME, source.name)
        put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
        put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Analog/Recordings")
        put(MediaStore.Audio.Media.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    return try {
        resolver.openOutputStream(uri)?.use { output -> source.inputStream().use { input -> input.copyTo(output) } }
            ?: throw IllegalStateException("Could not open exported file.")
        val ready = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
        resolver.update(uri, ready, null, null)
        true
    } catch (_: Throwable) {
        resolver.delete(uri, null, null)
        false
    }
}
