package com.mrjdm2007.analog

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SettingsInk = Color(0xFF10110F)
private val SettingsCream = Color(0xFFE8E1CF)
private val SettingsAmber = Color(0xFFFFB45B)
private val SettingsGreen = Color(0xFFB5D69B)

@Composable
fun SettingsPage(
    folders: List<String>,
    selectedFolders: Set<String>,
    onFoldersChanged: (Set<String>) -> Unit,
    playerStyle: String,
    onPlayerStyleChanged: (String) -> Unit,
    playPause: String,
    next: String,
    previous: String,
    onHeadsetSetting: (String, String) -> Unit,
    onEffects: () -> Unit,
    onVisualizer: () -> Unit,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(SettingsInk).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = SettingsCream) }
            Column(Modifier.weight(1f)) {
                Text("ANALOG / SETTINGS", color = SettingsAmber, fontWeight = FontWeight.Black, letterSpacing = 1.8.sp)
                Text("PERSONALIZE YOUR PLAYER", color = SettingsGreen, fontSize = 9.sp, letterSpacing = 1.4.sp)
            }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("PLAYER APPEARANCE", color = SettingsGreen, fontSize = 10.sp, letterSpacing = 2.sp, modifier = Modifier.padding(vertical = 8.dp))
                Text("Choose the Now Playing interface. This changes the player layout only; playback and your library stay the same.", color = SettingsCream.copy(alpha = .65f), fontSize = 12.sp)
                ActionDropdown("Player style", playerStyle, listOf("Analog Classic", "iPod Inspired"), onPlayerStyleChanged)
            }
            item {
                Text("MUSIC LIBRARY", color = SettingsGreen, fontSize = 10.sp, letterSpacing = 2.sp, modifier = Modifier.padding(vertical = 8.dp))
                Text("Choose which folders appear in the library. If none are selected, all folders are included.", color = SettingsCream.copy(alpha = .65f), fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onFoldersChanged(emptySet()) }) { Text("ALL FOLDERS", color = SettingsAmber) }
                    TextButton(onClick = { onFoldersChanged(folders.toSet()) }) { Text("SELECT ALL", color = SettingsAmber) }
                }
            }
            items(folders, key = { it }) { folder ->
                Row(Modifier.fillMaxWidth().background(Color(0xFF1A1C18)).padding(horizontal = 12.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(folder, color = SettingsCream, modifier = Modifier.weight(1f), maxLines = 1)
                    Checkbox(checked = selectedFolders.isEmpty() || folder in selectedFolders, onCheckedChange = { checked ->
                        val current = if (selectedFolders.isEmpty()) folders.toSet() else selectedFolders
                        onFoldersChanged(if (checked) current + folder else current - folder)
                    }, colors = CheckboxDefaults.colors(checkedColor = SettingsAmber))
                }
            }
            item {
                Spacer(Modifier.height(12.dp))
                Text("HEADPHONE / EARPHONE BUTTONS", color = SettingsGreen, fontSize = 10.sp, letterSpacing = 1.7.sp)
                Text("Choose the action assigned to media-button presses. Support can vary by headset and Android.", color = SettingsCream.copy(alpha = .6f), fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
                ActionDropdown("Play / pause button", playPause, listOf("Play/Pause", "Next track", "Previous track", "No action")) { onHeadsetSetting("headset_play_pause", it) }
                ActionDropdown("Next button", next, listOf("Next track", "Play/Pause", "Previous track", "No action")) { onHeadsetSetting("headset_next", it) }
                ActionDropdown("Previous button", previous, listOf("Previous track", "Play/Pause", "Next track", "No action")) { onHeadsetSetting("headset_previous", it) }
            }
            item {
                Spacer(Modifier.height(12.dp))
                Text("MORE", color = SettingsGreen, fontSize = 10.sp, letterSpacing = 1.7.sp)
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1C18)), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column {
                        TextButton(onClick = onEffects, modifier = Modifier.fillMaxWidth().padding(8.dp)) { Icon(Icons.Default.GraphicEq, null); Spacer(Modifier.width(8.dp)); Text("Audio effects & equalizer") }
                        TextButton(onClick = onVisualizer, modifier = Modifier.fillMaxWidth().padding(8.dp)) { Icon(Icons.Default.Visibility, null); Spacer(Modifier.width(8.dp)); Text("Visualizer") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionDropdown(label: String, selected: String, options: List<String>, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = SettingsCream.copy(alpha = .7f), fontSize = 11.sp)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selected, color = SettingsCream, modifier = Modifier.weight(1f))
                Text("⌄", color = SettingsAmber)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(text = { Text(option) }, onClick = { onSelected(option); expanded = false })
                }
            }
        }
    }
}
