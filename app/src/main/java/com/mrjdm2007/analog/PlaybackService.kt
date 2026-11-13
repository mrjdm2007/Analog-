package com.mrjdm2007.analog

import android.content.Intent
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.widget.RemoteViews
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import java.util.concurrent.Executors
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {
    companion object {
        const val ACTION_SLEEP_TIMER = "com.mrjdm2007.analog.SLEEP_TIMER"
        const val ACTION_CANCEL_SLEEP_TIMER = "com.mrjdm2007.analog.CANCEL_SLEEP_TIMER"
        const val ACTION_SLEEP_TIMER_END_TRACK = "com.mrjdm2007.analog.SLEEP_TIMER_END_TRACK"
        const val EXTRA_MINUTES = "minutes"
        const val ACTION_WIDGET_PLAY_PAUSE = "com.mrjdm2007.analog.WIDGET_PLAY_PAUSE"
        const val ACTION_WIDGET_NEXT = "com.mrjdm2007.analog.WIDGET_NEXT"
        const val ACTION_WIDGET_PREVIOUS = "com.mrjdm2007.analog.WIDGET_PREVIOUS"
        const val ACTION_REAPPLY_REPLAY_GAIN = "com.mrjdm2007.analog.REAPPLY_REPLAY_GAIN"
        const val ACTION_SET_CROSSFADE = "com.mrjdm2007.analog.SET_CROSSFADE"
        const val EXTRA_CROSSFADE_SECONDS = "crossfade_seconds"
    }
    private var mediaSession: MediaSession? = null
    private val timerHandler = Handler(Looper.getMainLooper())
    private var sleepTimer: Runnable? = null
    private var sleepEndListener: Player.Listener? = null
    private val replayGainExecutor = Executors.newSingleThreadExecutor()
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var crossfadePlayer: ExoPlayer? = null
    private var crossfadeStartMs = 0L
    private var crossfadeTargetIndex = -1
    private var crossfadeInProgress = false
    private var crossfadeForIndex = -1
    private val crossfadeTick = object : Runnable { override fun run() { runCrossfadeTick() } }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
        }
        crossfadePlayer = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
            volume = 0f
        }
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
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                if (crossfadeInProgress && mediaSession?.player?.currentMediaItemIndex != crossfadeTargetIndex) {
                    cancelCrossfade()
                    mediaSession?.player?.volume = 1f
                }
                updateWidget()
                applyReplayGain()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateWidget()
                if (isPlaying) {
                    timerHandler.removeCallbacks(crossfadeTick)
                    timerHandler.post(crossfadeTick)
                } else {
                    timerHandler.removeCallbacks(crossfadeTick)
                    cancelCrossfade()
                }
            }
            override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) { updateWidget() }
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (audioSessionId > 0) {
                    runCatching { loudnessEnhancer?.release(); loudnessEnhancer = LoudnessEnhancer(audioSessionId).apply { enabled = true } }
                    applyReplayGain()
                }
            }
        })
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val sessionActivity = launchIntent?.let { PendingIntent.getActivity(this, 90, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        val builder = MediaSession.Builder(this, player).setCallback(callback)
        if (sessionActivity != null) builder.setSessionActivity(sessionActivity)
        mediaSession = builder.build()
        updateWidget()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SLEEP_TIMER -> {
                sleepTimer?.let(timerHandler::removeCallbacks)
                val minutes = intent.getIntExtra(EXTRA_MINUTES, 30).coerceIn(1, 240)
                sleepTimer = Runnable { mediaSession?.player?.pause(); sleepTimer = null }
                timerHandler.postDelayed(sleepTimer!!, minutes * 60_000L)
            }
            ACTION_CANCEL_SLEEP_TIMER -> {
                sleepTimer?.let(timerHandler::removeCallbacks)
                sleepTimer = null
                sleepEndListener?.let { mediaSession?.player?.removeListener(it) }
                sleepEndListener = null
            }
            ACTION_SLEEP_TIMER_END_TRACK -> {
                sleepTimer?.let(timerHandler::removeCallbacks)
                sleepTimer = null
                sleepEndListener?.let { mediaSession?.player?.removeListener(it) }
                val activePlayer = mediaSession?.player
                if (activePlayer != null) {
                    val listener = object : Player.Listener {
                        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                                activePlayer.pause()
                                activePlayer.removeListener(this)
                                sleepEndListener = null
                            }
                        }
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_ENDED) {
                                activePlayer.pause()
                                activePlayer.removeListener(this)
                                sleepEndListener = null
                            }
                        }
                    }
                    sleepEndListener = listener
                    activePlayer.addListener(listener)
                }
            }
            ACTION_WIDGET_PLAY_PAUSE -> mediaSession?.player?.let { if (it.isPlaying) it.pause() else it.play() }
            ACTION_WIDGET_NEXT -> mediaSession?.player?.seekToNext()
            ACTION_WIDGET_PREVIOUS -> mediaSession?.player?.seekToPrevious()
            ACTION_REAPPLY_REPLAY_GAIN -> applyReplayGain()
            ACTION_SET_CROSSFADE -> {
                val seconds = intent.getIntExtra(EXTRA_CROSSFADE_SECONDS, 0).coerceIn(0, 10)
                getSharedPreferences("analog_library", MODE_PRIVATE).edit().putInt("crossfade_seconds", seconds).apply()
                cancelCrossfade()
                mediaSession?.player?.volume = 1f
                if (mediaSession?.player?.isPlaying == true) timerHandler.post(crossfadeTick)
            }
        }
        updateWidget()
        return super.onStartCommand(intent, flags, startId)
    }

    private fun runCrossfadeTick() {
        val primary = mediaSession?.player ?: return
        val secondary = crossfadePlayer ?: return
        val durationMs = getSharedPreferences("analog_library", MODE_PRIVATE).getInt("crossfade_seconds", 0) * 1000L
        if (!primary.isPlaying || durationMs <= 0L) {
            cancelCrossfade()
            primary.volume = 1f
            return
        }
        if (!crossfadeInProgress) {
            val nextIndex = primary.nextMediaItemIndex
            val currentDuration = primary.duration
            if (nextIndex != C.INDEX_UNSET && currentDuration > 0L && currentDuration - primary.currentPosition <= durationMs && crossfadeForIndex != primary.currentMediaItemIndex) {
                runCatching {
                    secondary.stop()
                    secondary.clearMediaItems()
                    secondary.setMediaItem(primary.getMediaItemAt(nextIndex))
                    secondary.prepare()
                    secondary.volume = 0f
                    secondary.play()
                    crossfadeTargetIndex = nextIndex
                    crossfadeForIndex = primary.currentMediaItemIndex
                    crossfadeStartMs = SystemClock.elapsedRealtime()
                    crossfadeInProgress = true
                }
            }
        } else {
            val progress = ((SystemClock.elapsedRealtime() - crossfadeStartMs).toFloat() / durationMs).coerceIn(0f, 1f)
            primary.volume = 1f - progress
            secondary.volume = progress
            if (progress >= 1f) {
                val nextPosition = secondary.currentPosition
                primary.volume = 0f
                primary.seekTo(crossfadeTargetIndex, nextPosition)
                primary.play()
                primary.volume = 1f
                secondary.volume = 0f
                secondary.pause()
                secondary.clearMediaItems()
                crossfadeInProgress = false
                crossfadeTargetIndex = -1
            }
        }
        if (primary.isPlaying) timerHandler.postDelayed(crossfadeTick, 50L)
    }

    private fun cancelCrossfade() {
        crossfadeInProgress = false
        crossfadeTargetIndex = -1
        crossfadePlayer?.let { runCatching { it.volume = 0f; it.pause(); it.clearMediaItems() } }
    }

    private fun applyReplayGain() {
        val player = mediaSession?.player ?: return
        val uri: Uri = player.currentMediaItem?.localConfiguration?.uri ?: return
        val uriKey = uri.toString()
        if (!getSharedPreferences("analog_library", MODE_PRIVATE).getBoolean("replay_gain_enabled", true)) {
            runCatching { loudnessEnhancer?.setTargetGain(0) }
            return
        }
        replayGainExecutor.execute {
            val gain = ReplayGainReader.trackGainMilliBel(contentResolver, uri) ?: 0
            timerHandler.post {
                val currentUri = mediaSession?.player?.currentMediaItem?.localConfiguration?.uri?.toString()
                if (currentUri == uriKey) runCatching {
                    val sessionId = (player as? ExoPlayer)?.audioSessionId ?: 0
                    if (loudnessEnhancer == null && sessionId > 0) {
                        loudnessEnhancer = LoudnessEnhancer(sessionId).apply { enabled = true }
                    }
                    loudnessEnhancer?.setTargetGain(gain)
                }
            }
        }
    }

    private fun updateWidget() {
        val player = mediaSession?.player
        val title = player?.mediaMetadata?.title?.toString()?.takeIf { it.isNotBlank() } ?: "Choose a track"
        val artist = player?.mediaMetadata?.artist?.toString()?.takeIf { it.isNotBlank() } ?: "ANALOG • OFFLINE"
        AnalogWidgetProvider.update(this, title, artist, player?.isPlaying == true, player?.mediaMetadata?.artworkUri)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        sleepTimer?.let(timerHandler::removeCallbacks)
        sleepTimer = null
        sleepEndListener?.let { mediaSession?.player?.removeListener(it) }
        sleepEndListener = null
        replayGainExecutor.shutdownNow()
        timerHandler.removeCallbacks(crossfadeTick)
        cancelCrossfade()
        runCatching { crossfadePlayer?.release() }
        crossfadePlayer = null
        runCatching { loudnessEnhancer?.release() }
        loudnessEnhancer = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
