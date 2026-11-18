package com.mrjdm2007.analog

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun AudioEffectsScreen(outputRoute: String, audioSessionId: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("analog_library", Context.MODE_PRIVATE) }
    var liveSession by remember { mutableIntStateOf(audioSessionId) }
    var bandCount by remember { mutableIntStateOf(prefs.getInt("eq_band_count", 0)) }
    var centers by remember { mutableStateOf(emptyList<Int>()) }
    var bands by remember { mutableStateOf(emptyList<Float>()) }
    var bandwidths by remember { mutableStateOf(prefs.getString("eq_band_bandwidths", null)?.split(",")?.mapNotNull { it.toFloatOrNull() } ?: List(10) { 1f }) }
    var crossfeedEnabled by remember { mutableStateOf(prefs.getBoolean("eq_crossfeed_enabled", false)) }
    var crossfeedAmount by remember { mutableFloatStateOf(prefs.getFloat("eq_crossfeed_amount", 0.12f).coerceIn(0f, 0.35f)) }
    var peakGuardEnabled by remember { mutableStateOf(prefs.getBoolean("eq_peak_guard_enabled", false)) }
    var peakGuardThresholdDb by remember { mutableFloatStateOf(prefs.getFloat("eq_peak_guard_threshold_db", -1f).coerceIn(-12f, -0.1f)) }
    var stereoBalance by remember { mutableFloatStateOf(prefs.getFloat("eq_stereo_balance", 0f).coerceIn(-1f, 1f)) }
    var autoMatch by remember { mutableStateOf(prefs.getBoolean("eq_auto_match_device", true)) }
    var detectedOutput by remember { mutableStateOf("Detecting output…") }
    var profiles by remember { mutableStateOf(HeadphoneEqProfiles.read(context)) }
    var activeProfile by remember { mutableStateOf(prefs.getString("eq_active_device_profile", null)) }
    var showSaveProfile by remember { mutableStateOf(false) }
    var newProfileName by remember { mutableStateOf("") }
    val exportProfilesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(HeadphoneEqProfiles.exportJson(profiles)) }
                ?: throw IllegalStateException("Could not write profile file")
        }.onSuccess { Toast.makeText(context, "EQ profiles exported", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(context, "Profile export failed", Toast.LENGTH_SHORT).show() }
    }
    val importProfilesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: throw IllegalStateException("Could not read profile file")
        }.onSuccess { raw ->
            val imported = HeadphoneEqProfiles.importJson(raw)
            if (imported.isNotEmpty()) {
                profiles = (profiles.filterNot { existing -> imported.any { it.name.equals(existing.name, true) } } + imported).sortedBy { it.name.lowercase() }
                HeadphoneEqProfiles.write(context, profiles)
                runCatching { context.startService(Intent(context, PlaybackService::class.java).setAction(PlaybackService.ACTION_EQ_MATCH_DEVICE)) }
                Toast.makeText(context, "Imported " + imported.size + " EQ profiles", Toast.LENGTH_SHORT).show()
            } else Toast.makeText(context, "No valid EQ profiles found", Toast.LENGTH_SHORT).show()
        }.onFailure { Toast.makeText(context, "Profile import failed", Toast.LENGTH_SHORT).show() }
    }
    var bassLevel by remember { mutableFloatStateOf(prefs.getFloat("eq_bass", 0f)) }
    var spaceLevel by remember { mutableFloatStateOf(prefs.getFloat("eq_space", 0f)) }
    var preampDb by remember { mutableFloatStateOf(prefs.getFloat("eq_preamp_db", 0f)) }
    var snapshotA by remember { mutableStateOf(prefs.getString("eq_curve_a", null)?.split(",")?.mapNotNull { it.toFloatOrNull() } ?: List(10) { 0f }) }
    var snapshotB by remember { mutableStateOf(prefs.getString("eq_curve_b", null)?.split(",")?.mapNotNull { it.toFloatOrNull() } ?: List(10) { 0f }) }
    var enabled by remember { mutableStateOf(prefs.getBoolean("eq_enabled", true)) }
    var available by remember { mutableStateOf(prefs.getBoolean("eq_available", false)) }
    var error by remember { mutableStateOf(prefs.getString("eq_error", "").orEmpty()) }
    var outputPeak by remember { mutableFloatStateOf(prefs.getFloat("eq_output_peak", 0f)) }
    var outputRms by remember { mutableFloatStateOf(prefs.getFloat("eq_output_rms", 0f)) }

    fun send(action: String, build: Intent.() -> Unit = {}) {
        val intent = Intent(context, PlaybackService::class.java).setAction(action).apply(build)
        runCatching { context.startService(intent) }
    }
    fun refresh() {
        val id = prefs.getInt("audio_session_id", 0)
        if (id > 0) liveSession = id
        bandCount = prefs.getInt("eq_band_count", 0)
        centers = prefs.getString("eq_band_centers", "").orEmpty().split(",").mapNotNull { it.toIntOrNull() }
        val saved = prefs.getString("eq_band_levels", null)?.split(",")?.mapNotNull { it.toFloatOrNull() }
        bands = List(bandCount) { i -> saved?.getOrNull(i)?.coerceIn(-1f, 1f) ?: 0f }
        bassLevel = prefs.getFloat("eq_bass", 0f)
        spaceLevel = prefs.getFloat("eq_space", 0f)
        preampDb = prefs.getFloat("eq_preamp_db", 0f).coerceIn(-12f, 12f)
        val widths = prefs.getString("eq_band_bandwidths", null)?.split(",")?.mapNotNull { it.toFloatOrNull() }
        bandwidths = List(10) { i -> widths?.getOrNull(i)?.coerceIn(0.1f, 4f) ?: 1f }
        crossfeedEnabled = prefs.getBoolean("eq_crossfeed_enabled", false)
        crossfeedAmount = prefs.getFloat("eq_crossfeed_amount", 0.12f).coerceIn(0f, 0.35f)
        peakGuardEnabled = prefs.getBoolean("eq_peak_guard_enabled", false)
        peakGuardThresholdDb = prefs.getFloat("eq_peak_guard_threshold_db", -1f).coerceIn(-12f, -0.1f)
        stereoBalance = prefs.getFloat("eq_stereo_balance", 0f).coerceIn(-1f, 1f)
        enabled = prefs.getBoolean("eq_enabled", true)
        available = prefs.getBoolean("eq_available", false) && prefs.getInt("eq_session_id", 0) == prefs.getInt("audio_session_id", 0)
        error = prefs.getString("eq_error", "").orEmpty()
        outputPeak = prefs.getFloat("eq_output_peak", 0f).coerceIn(0f, 1f)
        outputRms = prefs.getFloat("eq_output_rms", 0f).coerceAtLeast(0f)
        activeProfile = prefs.getString("eq_active_device_profile", null)
    }
    fun applyDeviceProfile(profile: HeadphoneEqProfile) {
        bands = profile.gainsDb.map { (it / 12f).coerceIn(-1f, 1f) }
        bandwidths = profile.bandwidths
        preampDb = profile.preampDb
        enabled = profile.eqEnabled
        crossfeedEnabled = profile.crossfeedEnabled
        crossfeedAmount = profile.crossfeedAmount
        peakGuardEnabled = profile.peakGuardEnabled
        peakGuardThresholdDb = profile.peakGuardThresholdDb
        stereoBalance = profile.stereoBalance
        activeProfile = profile.name
        prefs.edit().putString("eq_band_levels", bands.joinToString(","))
            .putString("eq_band_bandwidths", bandwidths.joinToString(","))
            .putFloat("eq_preamp_db", preampDb).putBoolean("eq_enabled", enabled)
            .putBoolean("eq_crossfeed_enabled", crossfeedEnabled).putFloat("eq_crossfeed_amount", crossfeedAmount)
            .putBoolean("eq_peak_guard_enabled", peakGuardEnabled).putFloat("eq_peak_guard_threshold_db", peakGuardThresholdDb)
            .putFloat("eq_stereo_balance", stereoBalance)
            .putString("eq_active_device_profile", profile.name).apply()
        send(PlaybackService.ACTION_EQ_PROFILE) { putExtra(PlaybackService.EXTRA_PROFILE, bands.joinToString(",")) }
        bandwidths.forEachIndexed { index, value -> send(PlaybackService.ACTION_EQ_BANDWIDTH) {
            putExtra(PlaybackService.EXTRA_BAND_INDEX, index); putExtra(PlaybackService.EXTRA_BANDWIDTH, value)
        } }
        send(PlaybackService.ACTION_EQ_PREAMP) { putExtra(PlaybackService.EXTRA_STRENGTH, preampDb) }
        send(PlaybackService.ACTION_EQ_ENABLED) { putExtra(PlaybackService.EXTRA_ENABLED, enabled) }
        send(PlaybackService.ACTION_EQ_CROSSFEED) {
            putExtra(PlaybackService.EXTRA_CROSSFEED_ENABLED, crossfeedEnabled)
            putExtra(PlaybackService.EXTRA_CROSSFEED_AMOUNT, crossfeedAmount)
        }
        send(PlaybackService.ACTION_EQ_PEAK_GUARD) {
            putExtra(PlaybackService.EXTRA_ENABLED, peakGuardEnabled)
            putExtra(PlaybackService.EXTRA_THRESHOLD_DB, peakGuardThresholdDb)
        }
        send(PlaybackService.ACTION_EQ_STEREO_BALANCE) {
            putExtra(PlaybackService.EXTRA_STEREO_BALANCE, stereoBalance)
        }
    }
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    DisposableEffect(audioManager, autoMatch, profiles, activeProfile) {
        fun refreshOutput() {
            val selection = OutputDeviceResolver.resolve(audioManager)
            val name = selection.name
            detectedOutput = name
            if (autoMatch && !selection.ambiguous) {
                val match = HeadphoneEqProfiles.findExact(profiles, name)
                if (match != null && activeProfile != match.name) applyDeviceProfile(match)
                else if (match == null && activeProfile != null) {
                    applyDeviceProfile(HeadphoneEqProfile("Flat", List(10) { 0f }, List(10) { 1f }, 0f, true, false, 0.12f))
                    activeProfile = null
                    prefs.edit().remove("eq_active_device_profile").apply()
                }
            } else if (selection.ambiguous && activeProfile != null) {
                applyDeviceProfile(HeadphoneEqProfile("Flat", List(10) { 0f }, List(10) { 1f }, 0f, true, false, 0.12f))
                activeProfile = null
                prefs.edit().remove("eq_active_device_profile").apply()
            }
        }
        refreshOutput()
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refreshOutput()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refreshOutput()
        }
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        onDispose { audioManager.unregisterAudioDeviceCallback(callback) }
    }
    LaunchedEffect(Unit) {
        send(PlaybackService.ACTION_EQ_RETRY)
        while (true) {
            refresh()
            if (!available) send(PlaybackService.ACTION_EQ_RETRY)
            delay(900L)
        }
    }

    val text = Color(0xFFE8E1CF)
    val accent = Color(0xFFFFB45B)
    val green = Color(0xFFB5D69B)
    val panel = Color(0xFF1A1C18)
    Column(Modifier.fillMaxSize().background(Color(0xFF10110F)).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = text) }
            Column(Modifier.weight(1f)) {
                Text("ANALOG / SOUND", color = accent, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                Text("IN-PIPELINE SOFTWARE EQ", color = green, fontSize = 9.sp, letterSpacing = 1.5.sp)
            }
            Switch(checked = enabled, onCheckedChange = { value ->
                enabled = value
                send(PlaybackService.ACTION_EQ_ENABLED) { putExtra(PlaybackService.EXTRA_ENABLED, value) }
            })
        }
        Text("CONNECTED OUTPUT CANDIDATE • " + detectedOutput + "  /  " + outputRoute, color = text.copy(alpha = .65f), fontSize = 9.sp, maxLines = 1)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("AUTOMATIC DAC / HEADPHONE MATCHING", color = green, fontSize = 9.sp, letterSpacing = 1.sp)
                Text("Exact-name profiles; automatic matching pauses if multiple external outputs are connected", color = text.copy(alpha = .5f), fontSize = 8.sp)
            }
            Switch(checked = autoMatch, onCheckedChange = {
                autoMatch = it
                prefs.edit().putBoolean("eq_auto_match_device", it).apply()
                send(PlaybackService.ACTION_EQ_MATCH_DEVICE)
            })
        }
        Text(if (activeProfile == null) "NO DEVICE PROFILE ACTIVE" else "ACTIVE PROFILE • " + activeProfile, color = if (activeProfile == null) accent else green, fontSize = 8.sp)
        if (!available || bandCount <= 0) {
            Text(
                if (liveSession <= 0) "Start a song. The equaliser will attach to the active player session automatically."
                else if (error.isNotBlank()) "Equaliser unavailable on this audio session: " + error
                else "Connecting the equaliser to the active playback session…",
                color = accent, fontSize = 12.sp, modifier = Modifier.padding(vertical = 16.dp)
            )
        } else {
            Text("EQUALIZER • " + bandCount + " BANDS", color = green, fontSize = 10.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(82.dp)) {
                    Text("PREAMP", color = text, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("%+.1f dB".format(java.util.Locale.US, preampDb), color = green, fontSize = 9.sp)
                }
                Slider(value = preampDb, onValueChange = { value ->
                    preampDb = value
                    send(PlaybackService.ACTION_EQ_PREAMP) { putExtra(PlaybackService.EXTRA_STRENGTH, value) }
                }, valueRange = -12f..12f, enabled = available,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent))
            }
            Text("Negative preamp creates headroom when boosted bands would otherwise clip.", color = text.copy(alpha = .5f), fontSize = 9.sp)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(8.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("POST-DSP SIGNAL MONITOR", color = green, fontSize = 9.sp, letterSpacing = 1.sp)
                    Text("PEAK %.1f dBFS".format(java.util.Locale.US, 20.0 * kotlin.math.log10(outputPeak.coerceAtLeast(0.00001f).toDouble())), color = text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("RMS", color = text.copy(alpha = .5f), fontSize = 8.sp)
                    Text("%.4f".format(java.util.Locale.US, outputRms), color = green, fontSize = 10.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().background(panel).padding(16.dp)) {
                Text("ESTIMATED FREQUENCY RESPONSE", color = green, fontSize = 9.sp, letterSpacing = 1.4.sp)
                Canvas(Modifier.fillMaxWidth().height(112.dp).padding(top = 8.dp, bottom = 6.dp)) {
                    val left = 4.dp.toPx()
                    val right = size.width - 4.dp.toPx()
                    val top = 6.dp.toPx()
                    val bottom = size.height - 6.dp.toPx()
                    val centerY = (top + bottom) / 2f
                    val amplitude = (bottom - top) / 2f
                    for (step in 0..4) {
                        val y = top + (bottom - top) * step / 4f
                        drawLine(Color.White.copy(alpha = if (step == 2) .3f else .09f), androidx.compose.ui.geometry.Offset(left, y), androidx.compose.ui.geometry.Offset(right, y), strokeWidth = 1.dp.toPx())
                    }
                    val response = Path()
                    val minLog = kotlin.math.ln(20.0)
                    val maxLog = kotlin.math.ln(20000.0)
                    val points = 160
                    for (point in 0..points) {
                        val xFraction = point.toFloat() / points
                        val hz = kotlin.math.exp(minLog + (maxLog - minLog) * xFraction)
                        var db = 0.0
                        centers.forEachIndexed { index, centerMilliHz ->
                            val centerHz = (centerMilliHz / 1000.0).coerceAtLeast(20.0)
                            val distance = kotlin.math.ln(hz / centerHz) / kotlin.math.ln(2.0)
                            db += bands.getOrNull(index)?.times(12f)?.toDouble()?.times(kotlin.math.exp(-0.5 * distance * distance / 0.72)) ?: 0.0
                        }
                        val y = (centerY - (db.coerceIn(-12.0, 12.0) / 12.0 * amplitude)).toFloat()
                        val x = left + (right - left) * xFraction
                        if (point == 0) response.moveTo(x, y) else response.lineTo(x, y)
                    }
                    drawPath(response, accent, style = Stroke(width = 2.dp.toPx()))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("20 Hz", "100 Hz", "1 kHz", "10 kHz", "20 kHz").forEach { Text(it, color = text.copy(alpha = .5f), fontSize = 8.sp) }
                }
                Spacer(Modifier.height(10.dp))
                bands.forEachIndexed { index, value ->
                    val hz = centers.getOrNull(index)?.div(1000f) ?: 0f
                    val label = if (hz >= 1000f) "%.1f kHz".format(java.util.Locale.US, hz / 1000f) else hz.toInt().toString() + " Hz"
                    Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, color = text, fontSize = 10.sp, modifier = Modifier.width(62.dp))
                        Slider(value = value, onValueChange = { v ->
                            bands = bands.toMutableList().also { it[index] = v }
                            send(PlaybackService.ACTION_EQ_BAND) {
                                putExtra(PlaybackService.EXTRA_BAND_INDEX, index)
                                putExtra(PlaybackService.EXTRA_BAND_LEVEL, v)
                            }
                        }, valueRange = -1f..1f, enabled = enabled && available,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent))
                        Text("%+.1f".format(java.util.Locale.US, value * 12f), color = green, fontSize = 9.sp, modifier = Modifier.width(34.dp))
                    }
                    if (index in 1..8) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("BW", color = text.copy(alpha = .5f), fontSize = 8.sp, modifier = Modifier.width(62.dp))
                            Slider(value = bandwidths.getOrElse(index) { 1f }, onValueChange = { v ->
                                bandwidths = bandwidths.toMutableList().also { while (it.size < 10) it.add(1f); it[index] = v }
                                prefs.edit().putString("eq_band_bandwidths", bandwidths.joinToString(",")).apply()
                                send(PlaybackService.ACTION_EQ_BANDWIDTH) { putExtra(PlaybackService.EXTRA_BAND_INDEX, index); putExtra(PlaybackService.EXTRA_BANDWIDTH, v) }
                            }, valueRange = 0.1f..4f, enabled = enabled && available, modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(thumbColor = green, activeTrackColor = green))
                            Text("%.1f".format(java.util.Locale.US, bandwidths.getOrElse(index) { 1f }), color = green, fontSize = 8.sp, modifier = Modifier.width(30.dp))
                        }
                    }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("QUICK PROFILES", color = green, fontSize = 10.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(8.dp))
            val profileNames = listOf("Flat", "Bass", "Treble", "Vocal", "Rock", "Classical", "Jazz", "Electronic", "Acoustic", "Podcast", "Night", "R&B", "Hip-Hop", "Metal", "Small Speakers", "Headphones")
            profileNames.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    row.forEach { name ->
                        OutlinedButton(onClick = {
                            val values = List(bandCount) { i ->
                                val x = if (bandCount <= 1) 0f else i.toFloat() / (bandCount - 1)
                                when (name) {
                                    "Bass" -> when { x < .25f -> .85f; x < .48f -> .45f; x < .72f -> .05f; else -> -.08f }
                                    "Treble" -> when { x < .25f -> -.08f; x < .5f -> .05f; x < .75f -> .45f; else -> .85f }
                                    "Vocal" -> (.48f - kotlin.math.abs(x - .5f) * 1.35f).coerceIn(-.35f, .48f)
                                    "Rock" -> when { x < .22f -> .55f; x < .45f -> .12f; x < .68f -> -.12f; else -> .48f }
                                    "Classical" -> when { x < .2f -> .32f; x < .45f -> .12f; x < .7f -> .18f; else -> .38f }
                                    "Jazz" -> when { x < .2f -> .3f; x < .45f -> -.08f; x < .7f -> .16f; else -> .32f }
                                    "Electronic" -> when { x < .2f -> .62f; x < .42f -> .08f; x < .65f -> -.2f; else -> .58f }
                                    "Acoustic" -> when { x < .18f -> .18f; x < .42f -> .12f; x < .7f -> .28f; else -> .2f }
                                    "Podcast" -> when { x < .18f -> -.4f; x < .42f -> .12f; x < .7f -> .35f; else -> .12f }
                                    "Night" -> when { x < .2f -> -.22f; x < .5f -> .08f; else -> -.3f }
                                    "Metal" -> when { x < .2f -> .48f; x < .42f -> -.08f; x < .65f -> .22f; else -> .55f }
                                    "R&B" -> when { x < .2f -> .33f; x < .42f -> .08f; x < .65f -> -.08f; else -> .33f }
                                    "Hip-Hop" -> when { x < .2f -> .5f; x < .42f -> .17f; x < .65f -> -.08f; else -> .25f }
                                    "Small Speakers" -> when { x < .2f -> .58f; x < .42f -> .08f; x < .65f -> .17f; else -> 0f }
                                    "Headphones" -> when { x < .2f -> .17f; x < .42f -> .08f; x < .65f -> .08f; else -> .08f }
                                    else -> 0f
                                }
                            }
                            bands = values
                            send(PlaybackService.ACTION_EQ_PROFILE) { putExtra(PlaybackService.EXTRA_PROFILE, values.joinToString(",")) }
                        }, enabled = enabled && available, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) {
                            Text(name, fontSize = 10.sp)
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("CUSTOM CURVE SNAPSHOTS", color = green, fontSize = 10.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(7.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                OutlinedButton(onClick = {
                    snapshotA = bands.toList()
                    prefs.edit().putString("eq_curve_a", snapshotA.joinToString(",")).apply()
                }, enabled = available, modifier = Modifier.weight(1f)) { Text("STORE A", fontSize = 9.sp) }
                OutlinedButton(onClick = {
                    val values = List(bandCount) { i -> snapshotA.getOrElse(i) { 0f }.coerceIn(-1f, 1f) }
                    bands = values
                    send(PlaybackService.ACTION_EQ_PROFILE) { putExtra(PlaybackService.EXTRA_PROFILE, values.joinToString(",")) }
                }, enabled = enabled && available, modifier = Modifier.weight(1f)) { Text("LOAD A", fontSize = 9.sp) }
                OutlinedButton(onClick = {
                    snapshotB = bands.toList()
                    prefs.edit().putString("eq_curve_b", snapshotB.joinToString(",")).apply()
                }, enabled = available, modifier = Modifier.weight(1f)) { Text("STORE B", fontSize = 9.sp) }
                OutlinedButton(onClick = {
                    val values = List(bandCount) { i -> snapshotB.getOrElse(i) { 0f }.coerceIn(-1f, 1f) }
                    bands = values
                    send(PlaybackService.ACTION_EQ_PROFILE) { putExtra(PlaybackService.EXTRA_PROFILE, values.joinToString(",")) }
                }, enabled = enabled && available, modifier = Modifier.weight(1f)) { Text("LOAD B", fontSize = 9.sp) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val flat = List(bandCount) { 0f }
                    bands = flat
                    preampDb = 0f
                    send(PlaybackService.ACTION_EQ_PROFILE) { putExtra(PlaybackService.EXTRA_PROFILE, flat.joinToString(",")) }
                    send(PlaybackService.ACTION_EQ_PREAMP) { putExtra(PlaybackService.EXTRA_STRENGTH, 0f) }
                }, enabled = enabled && available, modifier = Modifier.weight(1f)) { Text("RESET FLAT", fontSize = 9.sp) }
                Text("A/B snapshots are saved locally on this device.", color = text.copy(alpha = .5f), fontSize = 9.sp, modifier = Modifier.weight(1f).align(Alignment.CenterVertically))
            }
            Spacer(Modifier.height(14.dp))
            Text("STEREO CROSSFEED DSP", color = green, fontSize = 10.sp, letterSpacing = 2.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Headphone crossfeed", color = text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("Blends a controlled amount of the opposite stereo channel in the PCM chain", color = text.copy(alpha = .5f), fontSize = 8.sp)
                }
                Switch(checked = crossfeedEnabled, onCheckedChange = { value ->
                    crossfeedEnabled = value
                    prefs.edit().putBoolean("eq_crossfeed_enabled", value).apply()
                    send(PlaybackService.ACTION_EQ_CROSSFEED) { putExtra(PlaybackService.EXTRA_CROSSFEED_ENABLED, value); putExtra(PlaybackService.EXTRA_CROSSFEED_AMOUNT, crossfeedAmount) }
                })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("BLEND", color = text.copy(alpha = .6f), fontSize = 8.sp, modifier = Modifier.width(42.dp))
                Slider(value = crossfeedAmount, onValueChange = { value ->
                    crossfeedAmount = value
                    prefs.edit().putFloat("eq_crossfeed_amount", value).apply()
                    send(PlaybackService.ACTION_EQ_CROSSFEED) { putExtra(PlaybackService.EXTRA_CROSSFEED_ENABLED, crossfeedEnabled); putExtra(PlaybackService.EXTRA_CROSSFEED_AMOUNT, value) }
                }, valueRange = 0f..0.35f, enabled = enabled, modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent))
                Text("%.0f%%".format(java.util.Locale.US, crossfeedAmount * 100), color = green, fontSize = 9.sp)
            }
            Spacer(Modifier.height(14.dp))
            Text("DYNAMIC PEAK GUARD", color = green, fontSize = 10.sp, letterSpacing = 2.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Prevent EQ boosts from clipping", color = text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("8:1 soft-knee-style peak compression above the selected ceiling", color = text.copy(alpha = .5f), fontSize = 8.sp)
                }
                Switch(checked = peakGuardEnabled, onCheckedChange = { value ->
                    peakGuardEnabled = value
                    prefs.edit().putBoolean("eq_peak_guard_enabled", value).apply()
                    send(PlaybackService.ACTION_EQ_PEAK_GUARD) {
                        putExtra(PlaybackService.EXTRA_ENABLED, value)
                        putExtra(PlaybackService.EXTRA_THRESHOLD_DB, peakGuardThresholdDb)
                    }
                })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("CEILING", color = text.copy(alpha = .6f), fontSize = 8.sp, modifier = Modifier.width(58.dp))
                Slider(value = peakGuardThresholdDb, onValueChange = { value ->
                    peakGuardThresholdDb = value
                    prefs.edit().putFloat("eq_peak_guard_threshold_db", value).apply()
                    send(PlaybackService.ACTION_EQ_PEAK_GUARD) {
                        putExtra(PlaybackService.EXTRA_ENABLED, peakGuardEnabled)
                        putExtra(PlaybackService.EXTRA_THRESHOLD_DB, value)
                    }
                }, valueRange = -12f..-0.1f, enabled = peakGuardEnabled, modifier = Modifier.weight(1f))
                Text("%+.1f dB".format(java.util.Locale.US, peakGuardThresholdDb), color = green, fontSize = 9.sp)
            }
            Spacer(Modifier.height(14.dp))
            Text("STEREO BALANCE", color = green, fontSize = 10.sp, letterSpacing = 2.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("L", color = text, fontSize = 9.sp)
                Slider(value = stereoBalance, onValueChange = { value ->
                    stereoBalance = value
                    prefs.edit().putFloat("eq_stereo_balance", value).apply()
                    send(PlaybackService.ACTION_EQ_STEREO_BALANCE) { putExtra(PlaybackService.EXTRA_STEREO_BALANCE, value) }
                }, valueRange = -1f..1f, modifier = Modifier.weight(1f))
                Text("R", color = text, fontSize = 9.sp)
            }
            Text(if (kotlin.math.abs(stereoBalance) < 0.01f) "CENTER" else if (stereoBalance < 0f) "LEFT %.0f%%".format(java.util.Locale.US, -stereoBalance * 100) else "RIGHT %.0f%%".format(java.util.Locale.US, stereoBalance * 100), color = green, fontSize = 8.sp)
            Spacer(Modifier.height(14.dp))
            Text("HEADPHONE / DAC PROFILES", color = green, fontSize = 10.sp, letterSpacing = 2.sp)
            OutlinedButton(onClick = { newProfileName = detectedOutput.takeUnless { it == "No active output detected" || it == "Detecting output…" } ?: ""; showSaveProfile = true }, modifier = Modifier.fillMaxWidth()) { Text("SAVE CURRENT EQ AS DEVICE PROFILE", fontSize = 9.sp) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportProfilesLauncher.launch("Analog-Headphone-EQ-Profiles.json") }, modifier = Modifier.weight(1f)) { Text("EXPORT", fontSize = 9.sp) }
                OutlinedButton(onClick = { importProfilesLauncher.launch(arrayOf("application/json", "text/json")) }, modifier = Modifier.weight(1f)) { Text("IMPORT", fontSize = 9.sp) }
            }
            profiles.forEach { profile ->
                Row(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(8.dp)).clickable { applyDeviceProfile(profile) }.padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(profile.name, color = text, fontSize = 10.sp, modifier = Modifier.weight(1f))
                    Text(if (activeProfile == profile.name) "ACTIVE" else "APPLY", color = green, fontSize = 8.sp)
                    IconButton(onClick = { profiles = profiles.filterNot { it.name == profile.name }; HeadphoneEqProfiles.write(context, profiles) }) {
                        Icon(Icons.Default.Delete, null, tint = text.copy(alpha = .6f))
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Text("BASS BOOST", color = green, fontSize = 10.sp, letterSpacing = 2.sp)
            Slider(value = bassLevel, onValueChange = { value ->
                bassLevel = value
                send(PlaybackService.ACTION_EQ_BASS) { putExtra(PlaybackService.EXTRA_STRENGTH, value) }
            }, valueRange = 0f..100f, enabled = enabled && available,
                colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent))
            Text("STEREO WIDTH", color = green, fontSize = 10.sp, letterSpacing = 2.sp)
            Slider(value = spaceLevel, onValueChange = { value ->
                spaceLevel = value
                send(PlaybackService.ACTION_EQ_SPACE) { putExtra(PlaybackService.EXTRA_STRENGTH, value) }
            }, valueRange = 0f..100f, enabled = enabled && available,
                colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent))
        }
        Text("The 10-band EQ uses low/high shelves, parametric mids, preamp, bandwidth, crossfeed, stereo balance and peak protection in Analog's PCM playback pipeline. Android exposes connected outputs rather than the exact active AudioTrack route; automatic matching pauses when multiple external outputs are connected.", color = text.copy(alpha = .55f), fontSize = 9.sp, modifier = Modifier.padding(top = 18.dp, bottom = 28.dp))
    }
    if (showSaveProfile) {
        AlertDialog(
            onDismissRequest = { showSaveProfile = false },
            title = { Text("Save headphone / DAC profile") },
            text = { OutlinedTextField(value = newProfileName, onValueChange = { newProfileName = it }, label = { Text("Exact output name") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    val name = newProfileName.trim()
                    if (name.isNotEmpty()) {
                        val profile = HeadphoneEqProfile(name, bands.map { it * 12f }, List(10) { bandwidths.getOrElse(it) { 1f } }, preampDb, enabled, crossfeedEnabled, crossfeedAmount, peakGuardEnabled, peakGuardThresholdDb, stereoBalance)
                        profiles = (profiles.filterNot { it.name.equals(name, true) } + profile).sortedBy { it.name.lowercase() }
                        HeadphoneEqProfiles.write(context, profiles)
                        send(PlaybackService.ACTION_EQ_MATCH_DEVICE)
                        activeProfile = name
                        prefs.edit().putString("eq_active_device_profile", name).apply()
                    }
                    showSaveProfile = false
                }) { Text("SAVE") }
            },
            dismissButton = { TextButton(onClick = { showSaveProfile = false }) { Text("CANCEL") } }
        )
    }
}
