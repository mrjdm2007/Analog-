package com.mrjdm2007.analog

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.getSystemService

private val SettingsInk = Color(0xFF10110F)
private val SettingsPanel = Color(0xFF1A1C18)
private val SettingsCream = Color(0xFFE8E1CF)
private val SettingsAmber = Color(0xFFFFB45B)
private val SettingsGreen = Color(0xFFB5D69B)

@Composable
fun rememberHeadphoneOutput(autoDetect: Boolean): String {
    val context = LocalContext.current
    var route by remember { mutableStateOf("Detection is off") }

    DisposableEffect(context, autoDetect) {
        if (!autoDetect) {
            route = "Detection is off"
            onDispose { }
        } else {
            val audioManager = context.getSystemService<AudioManager>()
            fun readRoute(): String {
                val devices = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
                val bluetooth = devices.any {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_HEARING_AID
                }
                val wired = devices.any {
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_USB_DEVICE
                }
                return when {
                    bluetooth -> "Bluetooth headphones connected"
                    wired -> "Wired headphones connected"
                    else -> "No headphones detected"
                }
            }
            val callback = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { route = readRoute() }
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { route = readRoute() }
            }
            route = readRoute()
            audioManager?.registerAudioDeviceCallback(callback, null)
            onDispose { audioManager?.unregisterAudioDeviceCallback(callback) }
        }
    }
    return route
}

@Composable
fun HeadphoneSettingsScreen(
    autoDetect: Boolean,
    detectedOutput: String,
    onAutoDetectChange: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(SettingsInk).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = SettingsCream) }
            Column(Modifier.weight(1f)) {
                Text("ANALOG / SETTINGS", color = SettingsAmber, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                Text("PLAYBACK & OUTPUT", color = SettingsGreen, fontSize = 9.sp, letterSpacing = 1.5.sp)
            }
        }

        Text("HEADPHONE DETECTION", color = SettingsGreen, fontSize = 10.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().background(SettingsPanel, RoundedCornerShape(14.dp)).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Auto-detect headphones", color = SettingsCream, fontWeight = FontWeight.SemiBold)
                Text("Listen for wired and Bluetooth connection changes", color = SettingsCream.copy(alpha = .55f), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Switch(checked = autoDetect, onCheckedChange = onAutoDetectChange,
                colors = SwitchDefaults.colors(checkedThumbColor = SettingsInk, checkedTrackColor = SettingsGreen))
        }

        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth().background(SettingsPanel, RoundedCornerShape(14.dp)).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (detectedOutput.startsWith("Bluetooth")) Icons.Default.Bluetooth else Icons.Default.Headphones,
                contentDescription = null,
                tint = if (detectedOutput.contains("connected")) SettingsAmber else SettingsGreen,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("CURRENT OUTPUT", color = SettingsGreen, fontSize = 9.sp, letterSpacing = 1.5.sp)
                Text(detectedOutput, color = SettingsCream, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 5.dp))
            }
            if (autoDetect) Text("LIVE", color = SettingsAmber, fontSize = 9.sp, letterSpacing = 1.sp)
        }

        Spacer(Modifier.height(18.dp))
        Text(
            "Android continues to manage the actual audio route. Analog listens for system device-change events and updates this status without polling in the background.",
            color = SettingsCream.copy(alpha = .55f), fontSize = 11.sp, lineHeight = 17.sp
        )
        Spacer(Modifier.height(18.dp))
        Text("LIGHTWEIGHT BY DESIGN", color = SettingsGreen, fontSize = 10.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "Headphone detection is event-driven and active only while Analog is open. The visualizer releases its audio capture as soon as you leave that screen.",
            color = SettingsCream.copy(alpha = .55f), fontSize = 11.sp, lineHeight = 17.sp
        )
    }
}
