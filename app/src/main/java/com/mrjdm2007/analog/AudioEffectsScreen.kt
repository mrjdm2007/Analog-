package com.mrjdm2007.analog

import android.content.Context
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    var bassLevel by remember { mutableFloatStateOf(prefs.getFloat("eq_bass", 0f)) }
    var spaceLevel by remember { mutableFloatStateOf(prefs.getFloat("eq_space", 0f)) }
    var preampDb by remember { mutableFloatStateOf(prefs.getFloat("eq_preamp_db", 0f)) }
    var snapshotA by remember { mutableStateOf(prefs.getString("eq_curve_a", null)?.split(",")?.mapNotNull { it.toFloatOrNull() } ?: List(10) { 0f }) }
    var snapshotB by remember { mutableStateOf(prefs.getString("eq_curve_b", null)?.split(",")?.mapNotNull { it.toFloatOrNull() } ?: List(10) { 0f }) }
    var enabled by remember { mutableStateOf(prefs.getBoolean("eq_enabled", true)) }
    var available by remember { mutableStateOf(prefs.getBoolean("eq_available", false)) }
    var error by remember { mutableStateOf(prefs.getString("eq_error", "").orEmpty()) }

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
        preampDb = prefs.getFloat("eq_preamp_db", 0f).coerceIn(-12f, 6f)
        enabled = prefs.getBoolean("eq_enabled", true)
        available = prefs.getBoolean("eq_available", false) && prefs.getInt("eq_session_id", 0) == prefs.getInt("audio_session_id", 0)
        error = prefs.getString("eq_error", "").orEmpty()
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
        Text("OUTPUT • " + outputRoute, color = text.copy(alpha = .65f), fontSize = 9.sp, maxLines = 1)
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
                }, valueRange = -12f..6f, enabled = enabled && available,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent))
            }
            Text("Negative preamp creates headroom when boosted bands would otherwise clip.", color = text.copy(alpha = .5f), fontSize = 9.sp)
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().background(panel).padding(16.dp)) {
                bands.forEachIndexed { index, value ->
                    val hz = centers.getOrNull(index)?.div(1000f) ?: 0f
                    val label = if (hz >= 1000f) "%.1f kHz".format(java.util.Locale.US, hz / 1000f) else hz.toInt().toString() + " Hz"
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
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("QUICK PROFILES", color = green, fontSize = 10.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(8.dp))
            val profileNames = listOf("Flat", "Bass", "Treble", "Vocal", "Rock", "Classical", "Jazz", "Electronic", "Acoustic", "Podcast", "Night", "Metal")
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
        Text("The 10-band EQ is processed inside the player's PCM audio pipeline and stays active when you leave this screen. Bass boost and stereo width are optional device effects.", color = text.copy(alpha = .55f), fontSize = 10.sp, modifier = Modifier.padding(top = 18.dp, bottom = 28.dp))
    }
}
