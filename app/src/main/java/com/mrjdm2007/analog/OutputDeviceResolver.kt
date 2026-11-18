package com.mrjdm2007.analog

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

data class OutputDeviceSelection(val name: String, val ambiguous: Boolean = false)

/**
 * Resolves the output anticipated for Analog's media AudioAttributes.
 *
 * Android 13+ exposes the routing decision for a given AudioAttributes instance.
 * Older releases only expose connected sinks, which cannot prove which sink is
 * selected; the fallback is therefore conservative when more than one candidate
 * exists and is explicitly less authoritative than the API 33+ result.
 */
object OutputDeviceResolver {
    fun resolve(audioManager: AudioManager): OutputDeviceSelection {
        if (Build.VERSION.SDK_INT >= 33) {
            val mediaAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val routed = runCatching {
                audioManager.getAudioDevicesForAttributes(mediaAttributes)
            }.getOrDefault(emptyList())

            if (routed.size != 1) {
                return OutputDeviceSelection(
                    if (routed.isEmpty()) "Unable to determine media output" else "Multiple media outputs",
                    ambiguous = true
                )
            }
            val device = routed.single()
            val name = device.productName?.toString()?.trim().orEmpty()
            return OutputDeviceSelection(name.ifBlank { "Unknown media output" })
        }

        // Before API 33, the public API only lists connected devices, not the
        // selected media route. Avoid choosing arbitrarily if several sinks exist.
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
