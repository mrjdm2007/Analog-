package com.mrjdm2007.analog

import android.media.audiofx.BassBoost
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
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

@Composable
fun AudioEffectsScreen(outputRoute: String, audioSessionId: Int, onBack: () -> Unit) {
    var eq by remember { mutableStateOf<Equalizer?>(null) }
    var bass by remember { mutableStateOf<BassBoost?>(null) }
    var virtualizer by remember { mutableStateOf<Virtualizer?>(null) }
    var available by remember { mutableStateOf(false) }
    var bands by remember { mutableStateOf(listOf(0f, 0f, 0f, 0f, 0f)) }
    var bassLevel by remember { mutableFloatStateOf(0f) }
    var spaceLevel by remember { mutableFloatStateOf(0f) }
    var enabled by remember { mutableStateOf(true) }
    var presets by remember { mutableStateOf(emptyList<String>()) }
    val context = LocalContext.current
    val deviceProfileKey = remember(context, outputRoute) {
        val route = outputRoute.removePrefix("Connected: ").takeIf { outputRoute.startsWith("Connected: ") } ?: "device-default"
        "eq_profile_" + route.replace(Regex("[^A-Za-z0-9]"), "_")
    }

    DisposableEffect(deviceProfileKey, audioSessionId) {
        runCatching {
            if (audioSessionId > 0) {
                eq = Equalizer(0, audioSessionId).apply { enabled = true }
                bass = BassBoost(0, audioSessionId).apply { enabled = true }
                virtualizer = Virtualizer(0, audioSessionId).apply { enabled = true }
                available = eq != null
            } else {
                available = false
            }
            val e = eq
            if (e != null) {
                val range = e.bandLevelRange
                val saved = context.getSharedPreferences("analog_library", Context.MODE_PRIVATE).getStringSet(deviceProfileKey, null)?.mapNotNull { it.toFloatOrNull() }
                bands = if (saved != null && saved.size >= 5) saved.take(5) else (0 until minOf(5, e.numberOfBands.toInt())).map { i ->
                    val level = e.getBandLevel(i.toShort()).toFloat()
                    if (range[1] == range[0]) 0f else (level - range[0]) / (range[1] - range[0]) * 2f - 1f
                }.let { if (it.size < 5) it + List(5 - it.size) { 0f } else it }
                bands.forEachIndexed { i, value -> if (i < e.numberOfBands.toInt()) {
                    val level = (range[0] + ((value + 1f) / 2f * (range[1] - range[0]))).toInt().toShort()
                    runCatching { e.setBandLevel(i.toShort(), level) }
                } }
                presets = (0 until e.numberOfPresets.toInt()).map { e.getPresetName(it.toShort()) }
            }
            val prefs = context.getSharedPreferences("analog_library", Context.MODE_PRIVATE)
            bassLevel = prefs.getFloat(deviceProfileKey + "_bass", 0f)
            spaceLevel = prefs.getFloat(deviceProfileKey + "_space", 0f)
            runCatching { bass?.setStrength((bassLevel * 10).toInt().toShort()); virtualizer?.setStrength((spaceLevel * 10).toInt().toShort()) }
        }
        onDispose {
            runCatching { eq?.release(); bass?.release(); virtualizer?.release() }
            eq = null; bass = null; virtualizer = null
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF10110F)).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = Color(0xFFE8E1CF)) }
            Column(Modifier.weight(1f)) {
                Text("ANALOG / SOUND", color = Color(0xFFFFB45B), fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                Text("AUDIO EFFECTS", color = Color(0xFFB5D69B), fontSize = 9.sp, letterSpacing = 1.5.sp)
            }
            Switch(checked = enabled, onCheckedChange = { value ->
                enabled = value
                runCatching { eq?.enabled = value; bass?.enabled = value; virtualizer?.enabled = value }
            })
        }
        if (!available) {
            Text(
                if (audioSessionId <= 0) "Waiting for playback session… Start a song and the equaliser will connect automatically."
                else "Android has not granted an audio-effects session for this route. The controls remain visible but inactive.",
                color = Color(0xFFE8E1CF), fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }
        Text("EQUALIZER", color = Color(0xFFB5D69B), fontSize = 10.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().background(Color(0xFF1A1C18)).padding(16.dp)) {
                bands.forEachIndexed { index, value ->
                    val label = listOf("60 Hz", "230 Hz", "910 Hz", "3.6 kHz", "14 kHz")[index]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, color = Color(0xFFE8E1CF), fontSize = 10.sp, modifier = Modifier.width(54.dp))
                        Slider(value = value, onValueChange = { v ->
                            bands = bands.toMutableList().also { it[index] = v }
                            context.getSharedPreferences("analog_library", Context.MODE_PRIVATE).edit().putStringSet(deviceProfileKey, bands.map { it.toString() }.toSet()).apply()
                            runCatching {
                                val effect = eq ?: return@runCatching
                                val range = effect.bandLevelRange
                                val level = (range[0] + ((v + 1f) / 2f * (range[1] - range[0]))).toInt().toShort()
                                if (index < effect.numberOfBands.toInt()) effect.setBandLevel(index.toShort(), level)
                            }
                        }, valueRange = -1f..1f, enabled = enabled && available, modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(thumbColor = Color(0xFFFFB45B), activeTrackColor = Color(0xFFFFB45B)))
                    }
                }
            }
            if (presets.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("PRESETS", color = Color(0xFFB5D69B), fontSize = 10.sp, letterSpacing = 2.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Normal", "Rock", "Jazz", "Classical").forEach { label ->
                        val index = presets.indexOfFirst { it.equals(label, true) }
                        if (index >= 0) OutlinedButton(onClick = {
                            runCatching { eq?.usePreset(index.toShort()) }
                            val e = eq
                            if (e != null) {
                                val range = e.bandLevelRange
                                bands = (0 until minOf(5, e.numberOfBands.toInt())).map { index ->
                                    val level = e.getBandLevel(index.toShort()).toFloat()
                                    if (range[1] == range[0]) 0f else ((level - range[0]) / (range[1] - range[0])) * 2f - 1f
                                }.let { if (it.size < 5) it + List(5 - it.size) { 0f } else it }
                                context.getSharedPreferences("analog_library", Context.MODE_PRIVATE).edit().putStringSet(deviceProfileKey, bands.map { it.toString() }.toSet()).apply()
                            }
                        }, enabled = enabled && available, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(label, fontSize = 10.sp) }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("BASS BOOST", color = Color(0xFFB5D69B), fontSize = 10.sp, letterSpacing = 2.sp)
            Slider(value = bassLevel, onValueChange = { bassLevel = it; context.getSharedPreferences("analog_library", Context.MODE_PRIVATE).edit().putFloat(deviceProfileKey + "_bass", it).apply(); runCatching { bass?.setStrength((it * 10).toInt().toShort()) } },
                valueRange = 0f..100f, enabled = enabled && available, colors = SliderDefaults.colors(thumbColor = Color(0xFFFFB45B), activeTrackColor = Color(0xFFFFB45B)))
            Text("VIRTUALIZER", color = Color(0xFFB5D69B), fontSize = 10.sp, letterSpacing = 2.sp)
            Slider(value = spaceLevel, onValueChange = { spaceLevel = it; context.getSharedPreferences("analog_library", Context.MODE_PRIVATE).edit().putFloat(deviceProfileKey + "_space", it).apply(); runCatching { virtualizer?.setStrength((it * 10).toInt().toShort()) } },
                valueRange = 0f..100f, enabled = enabled && available, colors = SliderDefaults.colors(thumbColor = Color(0xFFFFB45B), activeTrackColor = Color(0xFFFFB45B)))
            Text("Effects availability and processing depend on the Android device and audio route.", color = Color(0xFF8E9687), fontSize = 10.sp, modifier = Modifier.padding(top = 12.dp, bottom = 28.dp))
    }
}
