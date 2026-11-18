package com.mrjdm2007.analog

import android.media.AudioDeviceInfo
import android.media.AudioManager

data class OutputDeviceSelection(val name: String, val ambiguous: Boolean = false)

/**
 * Resolves a single connected external playback candidate. Android's public AudioManager
 * device-list API reports connected sinks, not the exact AudioTrack route; when more than
 * one external sink is connected we deliberately avoid guessing and applying the wrong EQ.
 */
object OutputDeviceResolver {
    fun resolve(audioManager: AudioManager): OutputDeviceSelection {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        val external = devices.filter { device ->
            when (device.type) {
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER,
                AudioDeviceInfo.TYPE_HDMI,
                AudioDeviceInfo.TYPE_HDMI_ARC -> true
                else -> false
            }
        }
        if (external.size > 1) {
            return OutputDeviceSelection("Multiple external outputs connected", ambiguous = true)
        }
        val selected = external.singleOrNull()
            ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        val name = selected?.productName?.toString()?.trim().orEmpty()
        return OutputDeviceSelection(name.ifBlank { "No output device detected" })
    }
}
