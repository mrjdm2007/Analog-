package com.mrjdm2007.analog

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder

class RecordingService : Service() {
    @Volatile private var monitorThread: Thread? = null
    @Volatile private var monitorGeneration = 0L
    companion object {
        const val ACTION_START = "com.mrjdm2007.analog.record.START"
        const val ACTION_PAUSE = "com.mrjdm2007.analog.record.PAUSE"
        const val ACTION_RESUME = "com.mrjdm2007.analog.record.RESUME"
        const val ACTION_STOP = "com.mrjdm2007.analog.record.STOP"
        const val EXTRA_RATE = "sample_rate"
        const val EXTRA_CHANNELS = "channels"
        const val EXTRA_HIGH_PASS = "high_pass"
        const val EXTRA_LIMITER = "limiter"
        const val EXTRA_GAIN = "gain"
        const val EXTRA_INPUT_DEVICE_ID = "input_device_id"
        const val EXTRA_LOW_GAIN_DB = "low_gain_db"
        const val EXTRA_MID_GAIN_DB = "mid_gain_db"
        const val EXTRA_HIGH_GAIN_DB = "high_gain_db"
        const val EXTRA_COMPRESSOR = "compressor"
        const val EXTRA_COMPRESSOR_THRESHOLD = "compressor_threshold"
        const val EXTRA_BIT_DEPTH = "bit_depth"
        const val EXTRA_RAW_MIC = "raw_mic"
        const val EXTRA_HIGH_PASS_HZ = "high_pass_hz"
        const val EXTRA_LIMITER_CEILING_DB = "limiter_ceiling_db"
        const val EXTRA_COMPRESSOR_RATIO = "compressor_ratio"
        const val EXTRA_COMPRESSOR_ATTACK_MS = "compressor_attack_ms"
        const val EXTRA_COMPRESSOR_RELEASE_MS = "compressor_release_ms"
        private const val CHANNEL_ID = "analog_recording"
        private const val NOTIFICATION_ID = 4102
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                createChannel()
                val notification = buildNotification()
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                RecordingEngine.start(
                    applicationContext,
                    intent.getIntExtra(EXTRA_RATE, 48000),
                    intent.getIntExtra(EXTRA_CHANNELS, 1),
                    intent.getBooleanExtra(EXTRA_HIGH_PASS, false),
                    intent.getBooleanExtra(EXTRA_LIMITER, true),
                    intent.getFloatExtra(EXTRA_GAIN, 1f),
                    intent.getIntExtra(EXTRA_INPUT_DEVICE_ID, -1),
                    intent.getFloatExtra(EXTRA_LOW_GAIN_DB, 0f),
                    intent.getFloatExtra(EXTRA_MID_GAIN_DB, 0f),
                    intent.getFloatExtra(EXTRA_HIGH_GAIN_DB, 0f),
                    intent.getBooleanExtra(EXTRA_COMPRESSOR, false),
                    intent.getFloatExtra(EXTRA_COMPRESSOR_THRESHOLD, -18f),
                    intent.getIntExtra(EXTRA_BIT_DEPTH, 16),
                    intent.getBooleanExtra(EXTRA_RAW_MIC, false),
                    intent.getFloatExtra(EXTRA_HIGH_PASS_HZ, 80f),
                    intent.getFloatExtra(EXTRA_LIMITER_CEILING_DB, -1f),
                    intent.getFloatExtra(EXTRA_COMPRESSOR_RATIO, 3f),
                    intent.getFloatExtra(EXTRA_COMPRESSOR_ATTACK_MS, 10f),
                    intent.getFloatExtra(EXTRA_COMPRESSOR_RELEASE_MS, 120f)
                )
                // A quick stop→start can overlap the previous monitor thread. Generation
                // tokens prevent an old monitor from stopping the service for a new take.
                val generation = ++monitorGeneration
                monitorThread = Thread({
                    while (generation == monitorGeneration &&
                        RecordingEngine.state.value.status in listOf("STARTING", "RECORDING", "PAUSED", "FINALIZING")) {
                        try { Thread.sleep(150) } catch (_: InterruptedException) { break }
                    }
                    Handler(Looper.getMainLooper()).post {
                        if (generation == monitorGeneration &&
                            RecordingEngine.state.value.status !in listOf("STARTING", "RECORDING", "PAUSED", "FINALIZING")) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                }, "AnalogRecordingServiceMonitor").apply { isDaemon = true; start() }
            }
            ACTION_PAUSE -> RecordingEngine.pause()
            ACTION_RESUME -> RecordingEngine.resume()
            ACTION_STOP -> RecordingEngine.stop()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        monitorGeneration++
        monitorThread?.interrupt()
        if (RecordingEngine.state.value.status in listOf("STARTING", "RECORDING", "PAUSED", "FINALIZING")) RecordingEngine.stop()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Analog recording", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Shown while Analog is recording audio."
            channel.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Analog is recording")
                .setContentText("Microphone capture is active")
                .setContentIntent(pending)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Analog is recording")
                .setContentText("Microphone capture is active")
                .setContentIntent(pending)
                .setOngoing(true)
                .build()
        }
    }
}
