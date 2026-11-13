package com.mrjdm2007.analog

import android.content.Intent
import android.view.KeyEvent
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).build()
        val callback = object : MediaSession.Callback {
            override fun onMediaButtonEvent(
                session: MediaSession,
                controllerInfo: MediaSession.ControllerInfo,
                intent: Intent
            ): Boolean {
                val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
                if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return true
                val prefs = getSharedPreferences("analog_library", MODE_PRIVATE)
                val configured = when (event.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE ->
                        prefs.getString("headset_play_pause", "Play/Pause") ?: "Play/Pause"
                    KeyEvent.KEYCODE_MEDIA_NEXT -> prefs.getString("headset_next", "Next track") ?: "Next track"
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> prefs.getString("headset_previous", "Previous track") ?: "Previous track"
                    else -> return false
                }
                when (configured) {
                    "Play/Pause" -> if (player.isPlaying) player.pause() else player.play()
                    "Next track" -> player.seekToNext()
                    "Previous track" -> player.seekToPrevious()
                    "No action" -> Unit
                    else -> return false
                }
                return true
            }
        }
        mediaSession = MediaSession.Builder(this, player).setCallback(callback).build()
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
