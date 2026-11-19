package com.mrjdm2007.analog

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Temporarily switches the phone's ring/notification stream to vibration while
 * a selected external audio output is connected. Media playback is not changed.
 *
 * The original ringer mode is restored only if the user has not changed it
 * manually while the external device was connected.
 */
object AudioFocusModeController {
    private const val PREFS = "analog_library"
    private const val KEY_ENABLED = "audio_focus_auto_vibrate"
    private const val KEY_WIRED = "audio_focus_wired"
    private const val KEY_USB = "audio_focus_usb"
    private const val KEY_BLUETOOTH = "audio_focus_bluetooth"
    private const val KEY_RESTORE_PENDING = "audio_focus_restore_pending"
    private const val KEY_PREVIOUS_MODE = "audio_focus_previous_ringer_mode"

    fun refresh(context: Context, audioManager: AudioManager = context.getSystemService(AudioManager::class.java)) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val shouldVibrate = prefs.getBoolean(KEY_ENABLED, false) &&
            hasSelectedExternalOutput(audioManager, prefs)

        if (shouldVibrate) {
            val currentMode = audioManager.ringerMode
            if (currentMode != AudioManager.RINGER_MODE_VIBRATE) {
                // Save exactly once. Never overwrite the original mode on repeated
                // device callbacks or when multiple external outputs are connected.
                if (!prefs.getBoolean(KEY_RESTORE_PENDING, false)) {
                    prefs.edit()
                        .putInt(KEY_PREVIOUS_MODE, currentMode)
                        .putBoolean(KEY_RESTORE_PENDING, true)
                        .commit()
                }
                runCatching { audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE }
            }
        } else {
            restoreIfStillOwned(audioManager, prefs)
        }
    }

    private fun hasSelectedExternalOutput(audioManager: AudioManager, prefs: android.content.SharedPreferences): Boolean {
        val includeWired = prefs.getBoolean(KEY_WIRED, true)
        val includeUsb = prefs.getBoolean(KEY_USB, true)
        val includeBluetooth = prefs.getBoolean(KEY_BLUETOOTH, true)
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
            when (device.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> includeWired

                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET -> includeUsb

                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> includeBluetooth

                else -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    device.type == AudioDeviceInfo.TYPE_BLE_HEADSET && includeBluetooth
            }
        }
    }

    private fun restoreIfStillOwned(
        audioManager: AudioManager,
        prefs: android.content.SharedPreferences
    ) {
        if (!prefs.getBoolean(KEY_RESTORE_PENDING, false)) return

        val previousMode = prefs.getInt(KEY_PREVIOUS_MODE, AudioManager.RINGER_MODE_NORMAL)
        // If the user changed the ringer mode themselves while connected, respect
        // that choice rather than overwriting it on disconnect.
        if (audioManager.ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
            runCatching { audioManager.ringerMode = previousMode }
        }
        prefs.edit()
            .remove(KEY_PREVIOUS_MODE)
            .putBoolean(KEY_RESTORE_PENDING, false)
            .commit()
    }
}
