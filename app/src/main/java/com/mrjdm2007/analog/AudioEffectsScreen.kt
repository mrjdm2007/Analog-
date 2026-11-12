package com.mrjdm2007.analog

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.media.audiofx.LoudnessEnhancer
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
fun AudioEffectsScreen(onBack: () -> Unit) {
    var eq by remember { mutableStateOf<Equalizer?>(null) }
    var bass by remember { mutableStateOf<BassBoost?>(null) }
    var virtualizer by remember { mutableStateOf<Virtualizer?>(null) }
    var loudness by remember { mutableStateOf<LoudnessEnhancer?>(null) }
    var loudnessGain by remember { mutableFloatStateOf(0f) }
    var available by remember { mutableStateOf(false) }
    var bands by remember { mutableStateOf(listOf(0f, 0f, 0f, 0f, 0f)) }
    var bassLevel by remember { mutableFloatStateOf(0f) }
    var spaceLevel by remember { mutableFloatStateOf(0f) }
    var enabled by remember { mutableStateOf(true) }
    var presets by remember { mutableStateOf(emptyList<String>()) }

    DisposableEffect(Unit) {
        runCatching {
            eq = Equalizer(0, 0).apply { enabled = true }
            bass = BassBoost(0, 0).apply { enabled = true }
            virtualizer = Virtualizer(0, 0).apply { enabled = true }
            loudness = LoudnessEnhancer(0).apply { enabled = true }
            available = eq != null
            val e = eq
            if (e != null) {
                val range = e.bandLevelRange
                bands = (0 until e.numberOfBands.toInt()).map { i ->
                    val level = e.getBandLevel(i.toShort()).toFloat()
                    if (range[1] == range[0]) 0f else (level - range[0]) / (range[1] - range[0]) * 2f - 1f
                }
                presets = (0 until e.numberOfPresets.toInt()).map { e.getPresetName(it.toShort()) }
            }
        }
        onDispose {
            runCatching { eq?.release(); bass?.release(); virtualizer?.release(); loudness?.release() }
            eq = null; bass = null; virtualizer = null; loudness = null
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
                runCatching { eq?.enabled = value; bass?.enabled = value; virtualizer?.enabled = value; loudness?.enabled = value }
            })
        }
        if (!available) {
            Text("Audio effects are not available on this device or output route.", color = Color(0xFFE8E1CF))
        } else {
            Text("EQUALIZER", color = Color(0xFFB5D69B), fontSize = 10.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().background(Color(0xFF1A1C18)).padding(16.dp)) {
                bands.forEachIndexed { index, value ->
                    val centerHz = runCatching { (eq?.getCenterFreq(index.toShort()) ?: 0) / 1000 }.getOrDefault(0)
                    val label = if (centerHz >= 1000) "%.1f kHz".format(centerHz / 1000f) else "$centerHz Hz"
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, color = Color(0xFFE8E1CF), fontSize = 10.sp, modifier = Modifier.width(54.dp))
                        Slider(value = value, onValueChange = { v ->
                            bands = bands.toMutableList().also { it[index] = v }
                            runCatching {
                                val effect = eq ?: return@runCatching
                                val range = effect.bandLevelRange
                                val level = (range[0] + ((v + 1f) / 2f * (range[1] - range[0]))).toInt().toShort()
                                if (index < effect.numberOfBands.toInt()) effect.setBandLevel(index.toShort(), level)
                            }
                        }, valueRange = -1f..1f, enabled = enabled, modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(thumbColor = Color(0xFFFFB45B), activeTrackColor = Color(0xFFFFB45B)))
                    }
                }
            }
            Text("Showing all ${eq?.numberOfBands ?: 0} equalizer bands exposed by this Android device.", color = Color(0xFF8E9687), fontSize = 10.sp)
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
                                bands = (0 until e.numberOfBands.toInt()).map { band ->
                                    val level = e.getBandLevel(band.toShort()).toFloat()
                                    if (range[1] == range[0]) 0f else
                                        ((level - range[0]) / (range[1] - range[0]) * 2f - 1f).coerceIn(-1f, 1f)
                                }
                            }
                        }, enabled = enabled, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(label, fontSize = 10.sp) }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("BASS BOOST", color = Color(0xFFB5D69B), fontSize = 10.sp, letterSpacing = 2.sp)
            Slider(value = bassLevel, onValueChange = { bassLevel = it; runCatching { bass?.setStrength((it * 10).toInt().toShort()) } },
                valueRange = 0f..100f, enabled = enabled, colors = SliderDefaults.colors(thumbColor = Color(0xFFFFB45B), activeTrackColor = Color(0xFFFFB45B)))
            Text("VIRTUALIZER", color = Color(0xFFB5D69B), fontSize = 10.sp, letterSpacing = 2.sp)
            Slider(value = spaceLevel, onValueChange = { spaceLevel = it; runCatching { virtualizer?.setStrength((it * 10).toInt().toShort()) } },
                valueRange = 0f..100f, enabled = enabled, colors = SliderDefaults.colors(thumbColor = Color(0xFFFFB45B), activeTrackColor = Color(0xFFFFB45B)))
            Text("LOUDNESS", color = Color(0xFFB5D69B), fontSize = 10.sp, letterSpacing = 2.sp)
            Text("Extra gain can cause clipping. Keep this low.", color = Color(0xFF8E9687), fontSize = 10.sp)
            Slider(value = loudnessGain, onValueChange = {
                loudnessGain = it
                runCatching { loudness?.setTargetGain((it * 100f).toInt()) }
            }, valueRange = 0f..10f, enabled = enabled,
                colors = SliderDefaults.colors(thumbColor = Color(0xFFFFB45B), activeTrackColor = Color(0xFFFFB45B)))
            Text("Effects availability and processing depend on the Android device and audio route.", color = Color(0xFF8E9687), fontSize = 10.sp, modifier = Modifier.padding(top = 12.dp, bottom = 28.dp))
        }
    }
}
