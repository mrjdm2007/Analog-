package com.mrjdm2007.analog

import android.content.Intent
import android.content.Context
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.widget.RemoteViews
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.BassBoost
import android.media.audiofx.Virtualizer
import android.net.Uri
import android.provider.MediaStore
import java.util.concurrent.Executors
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    companion object {
        const val ACTION_SLEEP_TIMER = "com.mrjdm2007.analog.SLEEP_TIMER"
        const val ACTION_CANCEL_SLEEP_TIMER = "com.mrjdm2007.analog.CANCEL_SLEEP_TIMER"
        const val ACTION_SLEEP_TIMER_END_TRACK = "com.mrjdm2007.analog.SLEEP_TIMER_END_TRACK"
        const val EXTRA_MINUTES = "minutes"
        const val ACTION_WIDGET_PLAY_PAUSE = "com.mrjdm2007.analog.WIDGET_PLAY_PAUSE"
        const val ACTION_WIDGET_NEXT = "com.mrjdm2007.analog.WIDGET_NEXT"
        const val ACTION_WIDGET_PREVIOUS = "com.mrjdm2007.analog.WIDGET_PREVIOUS"
        const val ACTION_TILE_PLAY = "com.mrjdm2007.analog.TILE_PLAY"
        const val ACTION_REAPPLY_REPLAY_GAIN = "com.mrjdm2007.analog.REAPPLY_REPLAY_GAIN"
        const val ACTION_SET_CROSSFADE = "com.mrjdm2007.analog.SET_CROSSFADE"
        const val EXTRA_CROSSFADE_SECONDS = "crossfade_seconds"
        const val ACTION_EQ_ENABLED = "com.mrjdm2007.analog.EQ_ENABLED"
        const val ACTION_EQ_BAND = "com.mrjdm2007.analog.EQ_BAND"
        const val ACTION_EQ_PROFILE = "com.mrjdm2007.analog.EQ_PROFILE"
        const val ACTION_EQ_BASS = "com.mrjdm2007.analog.EQ_BASS"
        const val ACTION_EQ_SPACE = "com.mrjdm2007.analog.EQ_SPACE"
        const val ACTION_EQ_RETRY = "com.mrjdm2007.analog.EQ_RETRY"
        const val ACTION_EQ_PREAMP = "com.mrjdm2007.analog.EQ_PREAMP"
        const val ACTION_EQ_BANDWIDTH = "com.mrjdm2007.analog.EQ_BANDWIDTH"
        const val ACTION_EQ_CROSSFEED = "com.mrjdm2007.analog.EQ_CROSSFEED"
        const val EXTRA_BANDWIDTH = "bandwidth"
        const val EXTRA_CROSSFEED_ENABLED = "crossfeed_enabled"
        const val EXTRA_CROSSFEED_AMOUNT = "crossfeed_amount"
        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_BAND_INDEX = "band_index"
        const val EXTRA_BAND_LEVEL = "band_level"
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_STRENGTH = "strength"
    }
    private var mediaSession: MediaSession? = null
    private val timerHandler = Handler(Looper.getMainLooper())
    private var sleepTimer: Runnable? = null
    private var sleepFade: Runnable? = null
    private var sleepEndListener: Player.Listener? = null
    private val replayGainExecutor = Executors.newSingleThreadExecutor()
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private val softwareEqualizer = TenBandEqualizerProcessor()
    private val crossfadeEqualizer = TenBandEqualizerProcessor()
    private var optionalFxSessionId = 0
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var crossfadePlayer: ExoPlayer? = null
    private var crossfadeStartMs = 0L
    private var crossfadeTargetIndex = -1
    private var crossfadeInProgress = false
    private var crossfadeForIndex = -1
    private val listeningTick = object : Runnable {
        override fun run() {
            val player = mediaSession?.player
            if (player?.isPlaying == true) {
                val trackId = player.currentMediaItem?.mediaId?.toLongOrNull()
                if (trackId != null) {
                    val monthKey = java.text.SimpleDateFormat("yyyyMM", java.util.Locale.US).format(java.util.Date())
                    val prefs = getSharedPreferences("analog_library", MODE_PRIVATE)
                    val dayKey = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
                    prefs.edit()
                        .putLong("listened_ms:$monthKey:$trackId", prefs.getLong("listened_ms:$monthKey:$trackId", 0L) + 1000L)
                        .putLong("listened_day:$dayKey", prefs.getLong("listened_day:$dayKey", 0L) + 1000L)
                        .apply()
                }
                timerHandler.postDelayed(this, 1000L)
            }
        }
    }
    private val crossfadeTick = object : Runnable { override fun run() { runCrossfadeTick() } }
    private var headsetPressCount = 0
    private var headsetPressPending: Runnable? = null
    private val headsetPressWindowMs = 420L

    private fun dispatchHeadsetAction(action: String, player: ExoPlayer) {
        when (action) {
            "Play/Pause" -> if (player.isPlaying) player.pause() else player.play()
            "Next track" -> player.seekToNext()
            "Previous track" -> player.seekToPrevious()
        }
    }

    private fun flushHeadsetPresses(player: ExoPlayer) {
        val count = headsetPressCount
        headsetPressCount = 0
        headsetPressPending = null
        val prefs = getSharedPreferences("analog_library", MODE_PRIVATE)
        val key = when (count) { 1 -> "headset_single"; 2 -> "headset_double"; 3 -> "headset_triple"; else -> null }
        key?.let { dispatchHeadsetAction(prefs.getString(it, when(count) { 1 -> "Play/Pause"; 2 -> "Next track"; else -> "Previous track" }) ?: "No action", player) }
    }

    private fun recordTrackStart(mediaItem: androidx.media3.common.MediaItem?) {
        val trackId = mediaItem?.mediaId?.toLongOrNull() ?: return
        val monthKey = java.text.SimpleDateFormat("yyyyMM", java.util.Locale.US).format(java.util.Date())
        val prefs = getSharedPreferences("analog_library", MODE_PRIVATE)
        prefs.edit()
            .putInt("plays:$trackId", prefs.getInt("plays:$trackId", 0) + 1)
            .putInt("month:$monthKey:$trackId", prefs.getInt("month:$monthKey:$trackId", 0) + 1)
            .putLong("last_played:$trackId", System.currentTimeMillis())
            .apply()
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val eqPrefs = getSharedPreferences("analog_library", MODE_PRIVATE)
        eqPrefs.edit()
            .putInt("eq_band_count", 10)
            .putInt("eq_band_min", -1200)
            .putInt("eq_band_max", 1200)
            .putString("eq_band_centers", listOf(32, 64, 125, 250, 500, 1000, 2000, 4000, 8000, 16000).joinToString(",") { (it * 1000).toString() })
            .putBoolean("eq_available", true)
            .putInt("eq_session_id", eqPrefs.getInt("audio_session_id", 0))
            .putString("eq_error", "")
            .apply()
        applySoftwareEqualizer()
        val player = ExoPlayer.Builder(this, createEqualizedRenderersFactory(softwareEqualizer)).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
        }
        crossfadePlayer = ExoPlayer.Builder(this, createEqualizedRenderersFactory(crossfadeEqualizer)).build().apply {
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
                when (event.keyCode) {
                    KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        headsetPressCount = (headsetPressCount + 1).coerceAtMost(3)
                        headsetPressPending?.let(timerHandler::removeCallbacks)
                        val pending = Runnable { flushHeadsetPresses(player) }
                        headsetPressPending = pending
                        timerHandler.postDelayed(pending, headsetPressWindowMs)
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY -> { player.play(); return true }
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> { player.pause(); return true }
                    KeyEvent.KEYCODE_MEDIA_NEXT -> { player.seekToNext(); return true }
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { player.seekToPrevious(); return true }
                    else -> return false
                }
            }
        }
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                if (crossfadeInProgress && mediaSession?.player?.currentMediaItemIndex != crossfadeTargetIndex) {
                    cancelCrossfade()
                    mediaSession?.player?.volume = 1f
                }
                recordTrackStart(mediaItem)
                updateWidget()
                applyReplayGain()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateWidget()
                if (isPlaying) {
                    val activeSessionId = (player as? ExoPlayer)?.audioSessionId ?: 0
                    if (activeSessionId > 0 && optionalFxSessionId != activeSessionId) initializeOptionalEffects(activeSessionId)
                    timerHandler.removeCallbacks(listeningTick)
                    timerHandler.post(listeningTick)
                    timerHandler.removeCallbacks(crossfadeTick)
                    timerHandler.post(crossfadeTick)
                } else {
                    timerHandler.removeCallbacks(listeningTick)
                    timerHandler.removeCallbacks(crossfadeTick)
                    cancelCrossfade()
                    // A pause during a crossfade must never leave the primary deck attenuated.
                    mediaSession?.player?.volume = 1f
                }
            }
            override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) { updateWidget() }
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (audioSessionId > 0) {
                    getSharedPreferences("analog_library", MODE_PRIVATE).edit().putInt("audio_session_id", audioSessionId).apply()
                    runCatching { loudnessEnhancer?.release(); loudnessEnhancer = LoudnessEnhancer(audioSessionId).apply { enabled = true } }
                    initializeOptionalEffects(audioSessionId)
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
                clearSleepTimer(restoreVolume = true)
                sleepEndListener?.let { mediaSession?.player?.removeListener(it) }
                sleepEndListener = null
                val minutes = intent.getIntExtra(EXTRA_MINUTES, 30).coerceIn(1, 240)
                val totalMs = minutes * 60_000L
                val fadeSeconds = getSharedPreferences("analog_library", MODE_PRIVATE)
                    .getInt("sleep_fade_seconds", 15).coerceIn(0, 30)
                val fadeMs = fadeSeconds * 1000L
                sleepTimer = Runnable {
                    mediaSession?.player?.let { it.pause(); it.volume = 1f }
                    sleepTimer = null
                    sleepFade = null
                }
                timerHandler.postDelayed(sleepTimer!!, totalMs)
                if (fadeMs > 0L && totalMs > fadeMs) {
                    val fadeStart = SystemClock.elapsedRealtime() + totalMs - fadeMs
                    sleepFade = object : Runnable {
                        override fun run() {
                            val elapsed = SystemClock.elapsedRealtime() - fadeStart
                            val volume = SleepFade.volumeAt(elapsed, fadeMs)
                            mediaSession?.player?.volume = volume
                            if (elapsed < fadeMs) timerHandler.postDelayed(this, 100L)
                        }
                    }
                    timerHandler.postDelayed(sleepFade!!, totalMs - fadeMs)
                }
            }
            ACTION_CANCEL_SLEEP_TIMER -> {
                clearSleepTimer(restoreVolume = true)
                sleepEndListener?.let { mediaSession?.player?.removeListener(it) }
                sleepEndListener = null
            }
            ACTION_SLEEP_TIMER_END_TRACK -> {
                clearSleepTimer(restoreVolume = true)
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
            ACTION_TILE_PLAY -> startLastPlayedFromTile()
            ACTION_WIDGET_PLAY_PAUSE -> mediaSession?.player?.let { player ->
                if (player.isPlaying) player.pause() else if (player.mediaItemCount == 0) startRandomLibraryTrack() else player.play()
            }
            ACTION_WIDGET_NEXT -> mediaSession?.player?.seekToNext()
            ACTION_WIDGET_PREVIOUS -> mediaSession?.player?.seekToPrevious()
            ACTION_REAPPLY_REPLAY_GAIN -> applyReplayGain()
            ACTION_EQ_RETRY -> {
                applySoftwareEqualizer()
                val prefs = getSharedPreferences("analog_library", MODE_PRIVATE)
                prefs.edit().putBoolean("eq_available", true)
                    .putInt("eq_session_id", prefs.getInt("audio_session_id", 0))
                    .putString("eq_error", "").apply()
            }
            ACTION_EQ_PREAMP -> {
                val gain = intent.getFloatExtra(EXTRA_STRENGTH, 0f).coerceIn(-12f, 12f)
                getSharedPreferences("analog_library", MODE_PRIVATE).edit().putFloat("eq_preamp_db", gain).apply()
                applySoftwareEqualizer()
            }
            ACTION_EQ_BANDWIDTH -> {
                val index = intent.getIntExtra(EXTRA_BAND_INDEX, -1)
                val value = intent.getFloatExtra(EXTRA_BANDWIDTH, 1f).coerceIn(0.1f, 4f)
                if (index in 0 until 10) {
                    val prefs = getSharedPreferences("analog_library", MODE_PRIVATE)
                    val values = prefs.getString("eq_band_bandwidths", null)?.split(",")?.mapNotNull { it.toFloatOrNull() }?.toMutableList() ?: MutableList(10) { 1f }
                    while (values.size < 10) values.add(1f)
                    values[index] = value
                    prefs.edit().putString("eq_band_bandwidths", values.joinToString(",")).apply()
                    applySoftwareEqualizer()
                }
            }
            ACTION_EQ_CROSSFEED -> {
                val enabled = intent.getBooleanExtra(EXTRA_CROSSFEED_ENABLED, false)
                val amount = intent.getFloatExtra(EXTRA_CROSSFEED_AMOUNT, 0.12f).coerceIn(0f, 0.35f)
                getSharedPreferences("analog_library", MODE_PRIVATE).edit()
                    .putBoolean("eq_crossfeed_enabled", enabled)
                    .putFloat("eq_crossfeed_amount", amount).apply()
                applySoftwareEqualizer()
            }
            ACTION_EQ_ENABLED -> {
                val enabled = intent.getBooleanExtra(EXTRA_ENABLED, true)
                getSharedPreferences("analog_library", MODE_PRIVATE).edit().putBoolean("eq_enabled", enabled).apply()
                softwareEqualizer.setEnabled(enabled)
                crossfadeEqualizer.setEnabled(enabled)
                runCatching { bassBoost?.enabled = enabled; virtualizer?.enabled = enabled }
            }
            ACTION_EQ_BAND -> {
                val index = intent.getIntExtra(EXTRA_BAND_INDEX, -1)
                val level = intent.getFloatExtra(EXTRA_BAND_LEVEL, 0f).coerceIn(-1f, 1f)
                if (index in 0 until 10) {
                    val prefs = getSharedPreferences("analog_library", MODE_PRIVATE)
                    val values = prefs.getString("eq_band_levels", null)?.split(",")?.mapNotNull { it.toFloatOrNull() }?.toMutableList()
                        ?: MutableList(10) { 0f }
                    while (values.size < 10) values.add(0f)
                    values[index] = level
                    prefs.edit().putString("eq_band_levels", values.joinToString(",")).apply()
                    applySoftwareEqualizer()
                }
            }
            ACTION_EQ_PROFILE -> {
                val values = intent.getStringExtra(EXTRA_PROFILE).orEmpty()
                getSharedPreferences("analog_library", MODE_PRIVATE).edit().putString("eq_band_levels", values).apply()
                applySoftwareEqualizer()
            }
            ACTION_EQ_BASS -> {
                val strength = intent.getFloatExtra(EXTRA_STRENGTH, 0f).coerceIn(0f, 100f)
                getSharedPreferences("analog_library", MODE_PRIVATE).edit().putFloat("eq_bass", strength).apply()
                runCatching { bassBoost?.setStrength((strength * 10).toInt().toShort()) }
            }
            ACTION_EQ_SPACE -> {
                val strength = intent.getFloatExtra(EXTRA_STRENGTH, 0f).coerceIn(0f, 100f)
                getSharedPreferences("analog_library", MODE_PRIVATE).edit().putFloat("eq_space", strength).apply()
                runCatching { virtualizer?.setStrength((strength * 10).toInt().toShort()) }
            }
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

    private fun clearSleepTimer(restoreVolume: Boolean) {
        sleepTimer?.let(timerHandler::removeCallbacks)
        sleepFade?.let(timerHandler::removeCallbacks)
        sleepTimer = null
        sleepFade = null
        if (restoreVolume) mediaSession?.player?.volume = 1f
    }

    private fun startLastPlayedFromTile() {
        val player = mediaSession?.player ?: return
        if (player.mediaItemCount == 0) {
            val prefs = getSharedPreferences("analog_library", MODE_PRIVATE)
            val columns = arrayOf(
                MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID
            )
            val items = mutableListOf<Pair<Long, androidx.media3.common.MediaItem>>()
            runCatching {
                contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, columns,
                    "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
                    "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val albumId = cursor.getLong(albumIdCol)
                        val artwork = if (albumId > 0L) Uri.withAppendedPath(
                            Uri.parse("content://media/external/audio/albumart"), albumId.toString()
                        ) else null
                        val metadata = androidx.media3.common.MediaMetadata.Builder()
                            .setTitle(cursor.getString(titleCol) ?: "Unknown title")
                            .setArtist(cursor.getString(artistCol) ?: "Unknown artist")
                            .setAlbumTitle(cursor.getString(albumCol) ?: "Unknown album")
                            .setArtworkUri(artwork)
                            .build()
                        val media = androidx.media3.common.MediaItem.Builder()
                            .setMediaId(id.toString())
                            .setUri(Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString()))
                            .setMediaMetadata(metadata)
                            .build()
                        items += id to media
                    }
                }
            }
            if (items.isEmpty()) return
            val lastPlayedId = items.maxByOrNull { prefs.getLong("last_played:${it.first}", 0L) }
                ?.takeIf { prefs.getLong("last_played:${it.first}", 0L) > 0L }?.first
            val startIndex = items.indexOfFirst { it.first == lastPlayedId }.takeIf { it >= 0 } ?: 0
            player.setMediaItems(items.map { it.second }, startIndex, 0L)
            player.prepare()
        } else if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0L)
        } else if (player.playbackState == Player.STATE_IDLE) {
            player.prepare()
        }
        player.play()
        updateWidget()
    }

    private fun startRandomLibraryTrack() {
        val player = mediaSession?.player as? ExoPlayer ?: return
        if (player.mediaItemCount > 0) { player.play(); return }
        val columns = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID)
        val items = mutableListOf<androidx.media3.common.MediaItem>()
        runCatching {
            contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, columns, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val albumId = cursor.getLong(albumIdCol)
                    val art = if (albumId > 0L) Uri.withAppendedPath(Uri.parse("content://media/external/audio/albumart"), albumId.toString()) else null
                    val metadata = androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(cursor.getString(titleCol) ?: "Unknown title")
                        .setArtist(cursor.getString(artistCol) ?: "Unknown artist")
                        .setAlbumTitle(cursor.getString(albumCol) ?: "Unknown album")
                        .setArtworkUri(art).build()
                    items += androidx.media3.common.MediaItem.Builder().setMediaId(id.toString())
                        .setUri(Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString()))
                        .setMediaMetadata(metadata).build()
                }
            }
        }
        if (items.isEmpty()) return
        val order = items.shuffled()
        player.setMediaItems(order, 0, 0L)
        player.prepare()
        player.play()
        updateWidget()
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
            if (nextIndex != C.INDEX_UNSET && nextIndex != primary.currentMediaItemIndex &&
                currentDuration > 0L && currentDuration - primary.currentPosition <= durationMs &&
                crossfadeForIndex != primary.currentMediaItemIndex) {
                runCatching {
                    secondary.stop()
                    secondary.clearMediaItems()
                    secondary.setMediaItem(primary.getMediaItemAt(nextIndex))
                    secondary.prepare()
                    secondary.volume = 0f
                    secondary.play()
                    crossfadeTargetIndex = nextIndex
                    crossfadeForIndex = primary.currentMediaItemIndex
                    crossfadeStartMs = 0L // Start the fade only once the next decoder is actually playing.
                    crossfadeInProgress = true
                }.onFailure {
                    cancelCrossfade()
                    primary.volume = 1f
                }
            }
        } else {
            if (secondary.playerError != null) {
                cancelCrossfade()
                primary.volume = 1f
                return
            }
            if (!secondary.isPlaying) {
                // Preparing the next item can take time (especially from slow storage).
                // Never fade the current song down before the next deck has audio ready.
                timerHandler.postDelayed(crossfadeTick, 50L)
                return
            }
            if (crossfadeStartMs == 0L) crossfadeStartMs = SystemClock.elapsedRealtime()
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


    private fun createEqualizedRenderersFactory(processor: TenBandEqualizerProcessor): DefaultRenderersFactory =
        object : DefaultRenderersFactory(this) {
            @OptIn(UnstableApi::class)
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false)
                    .setAudioProcessors(arrayOf(processor))
                    .build()
            }
        }

    private fun applySoftwareEqualizer() {
        val prefs = getSharedPreferences("analog_library", MODE_PRIVATE)
        val normalized = prefs.getString("eq_band_levels", null)
            ?.split(",")?.mapNotNull { it.toFloatOrNull() }.orEmpty()
        val gainsDb = FloatArray(10) { index -> (normalized.getOrNull(index) ?: 0f).coerceIn(-1f, 1f) * 12f }
        val enabled = prefs.getBoolean("eq_enabled", true)
        val preamp = prefs.getFloat("eq_preamp_db", 0f).coerceIn(-12f, 12f)
        val bandwidths = prefs.getString("eq_band_bandwidths", null)?.split(",")?.mapNotNull { it.toFloatOrNull() }.orEmpty()
        val bandWidths = FloatArray(10) { i -> bandwidths.getOrNull(i)?.coerceIn(0.1f, 4f) ?: 1f }
        val crossfeedEnabled = prefs.getBoolean("eq_crossfeed_enabled", false)
        val crossfeedAmount = prefs.getFloat("eq_crossfeed_amount", 0.12f).coerceIn(0f, 0.35f)
        softwareEqualizer.setBands(gainsDb)
        crossfadeEqualizer.setBands(gainsDb)
        softwareEqualizer.setBandwidths(bandWidths)
        crossfadeEqualizer.setBandwidths(bandWidths)
        softwareEqualizer.setPreamp(preamp)
        crossfadeEqualizer.setPreamp(preamp)
        softwareEqualizer.setEnabled(enabled)
        crossfadeEqualizer.setEnabled(enabled)
        softwareEqualizer.setCrossfeed(crossfeedEnabled, crossfeedAmount)
        crossfadeEqualizer.setCrossfeed(crossfeedEnabled, crossfeedAmount)
    }

    private fun initializeOptionalEffects(sessionId: Int) {
        runCatching { bassBoost?.release(); virtualizer?.release() }
        bassBoost = null
        virtualizer = null
        val prefs = getSharedPreferences("analog_library", MODE_PRIVATE)
        val enabled = prefs.getBoolean("eq_enabled", true)
        bassBoost = runCatching { BassBoost(0, sessionId).apply { this.enabled = enabled } }.getOrNull()
        virtualizer = runCatching { Virtualizer(0, sessionId).apply { this.enabled = enabled } }.getOrNull()
        runCatching { bassBoost?.setStrength((prefs.getFloat("eq_bass", 0f).coerceIn(0f, 100f) * 10).toInt().toShort()) }
        runCatching { virtualizer?.setStrength((prefs.getFloat("eq_space", 0f).coerceIn(0f, 100f) * 10).toInt().toShort()) }
        optionalFxSessionId = sessionId
        prefs.edit().putInt("eq_session_id", sessionId).putBoolean("eq_available", true).putString("eq_error", "").apply()
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
        clearSleepTimer(restoreVolume = false)
        sleepEndListener?.let { mediaSession?.player?.removeListener(it) }
        sleepEndListener = null
        replayGainExecutor.shutdownNow()
        timerHandler.removeCallbacks(crossfadeTick)
        timerHandler.removeCallbacks(listeningTick)
        cancelCrossfade()
        runCatching { crossfadePlayer?.release() }
        crossfadePlayer = null
        runCatching { loudnessEnhancer?.release() }
        loudnessEnhancer = null
        runCatching { bassBoost?.release(); virtualizer?.release() }
        bassBoost = null
        virtualizer = null
        optionalFxSessionId = 0
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
