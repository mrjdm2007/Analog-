package com.mrjdm2007.analog

import androidx.media3.exoplayer.ExoPlayer
import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Metadata
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).build().apply {
            // Let Android manage audio focus; leave the decoded source signal unmodified by default.
            setAudioAttributes(AudioAttributes.DEFAULT, true)
            setHandleAudioBecomingNoisy(true)
        }
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                player.volume = 1f
                AnalogWidgetProvider.refresh(this@PlaybackService)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                AnalogWidgetProvider.refresh(this@PlaybackService)
            }

            override fun onMetadata(metadata: Metadata) {
                val enabled = getSharedPreferences("analog_audio", MODE_PRIVATE)
                    .getBoolean("replaygain_enabled", true)
                if (!enabled) {
                    player.volume = 1f
                    return
                }
                val gain = metadata.entries.firstNotNullOfOrNull { entry ->
                    val value = when (entry) {
                        is TextInformationFrame ->
                            if (entry.description.equals("REPLAYGAIN_TRACK_GAIN", true)) entry.values.firstOrNull() else null
                        is VorbisComment ->
                            if (entry.key.equals("REPLAYGAIN_TRACK_GAIN", true)) entry.value else null
                        else -> null
                    }
                    value?.let { Regex("""[-+]?(?:\d+(?:\.\d*)?|\.\d+)""").find(it)?.value?.toFloatOrNull() }
                }
                // Attenuation-only ReplayGain avoids boosting tagged tracks beyond unity.
                player.volume = gain?.let {
                    Math.pow(10.0, (it.coerceAtMost(0f) / 20f).toDouble()).toFloat().coerceIn(0f, 1f)
                } ?: 1f
            }
        })
        val sessionActivity = PendingIntent.getActivity(
            this, 100, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        mediaSession = MediaSession.Builder(this, player).setSessionActivity(sessionActivity).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
