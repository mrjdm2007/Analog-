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
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
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
        const val ACTION_REFRESH_AUDIO_FOCUS_MODE = "com.mrjdm2007.analog.REFRESH_AUDIO_FOCUS_MODE"
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
        const val ACTION_EQ_MATCH_DEVICE = "com.mrjdm2007.analog.EQ_MATCH_DEVICE"
        const val ACTION_EQ_PEAK_GUARD = "com.mrjdm2007.analog.EQ_PEAK_GUARD"
        const val ACTION_EQ_STEREO_BALANCE = "com.mrjdm2007.analog.EQ_STEREO_BALANCE"
        const val EXTRA_THRESHOLD_DB = "threshold_db"
        const val EXTRA_STEREO_BALANCE = "stereo_balance"
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
    private val coldStartExecutor = Executors.newSingleThreadExecutor()
    private var coldStartLoadInProgress = false
    private val coldStartLoadCallbacks = mutableListOf<(List<Pair<Long, androidx.media3.common.MediaItem>>) -> Unit>()
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private val softwareEqualizer = TenBandEqualizerProcessor()
    private val crossfadeEqualizer = TenBandEqualizerProcessor()
    private var optionalFxSessionId = 0
    private var outputDeviceCallback: AudioDeviceCallback? = null
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
                val prefs = getSharedPreferences("analog_library", MODE_PRIVATE)
                val trackId = player.currentMediaItem?.mediaId?.toLongOrNull()
                if (trackId != null) {
                    val monthKey = java.text.SimpleDateFormat("yyyyMM", java.util.Locale.US).format(java.util.Date())
                    val dayKey = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
                    prefs.edit()
                        .putLong("listened_ms:$monthKey:$trackId", prefs.getLong("listened_ms:$monthKey:$trackId", 0L) + 1000L)
                        .putLong("listened_day:$dayKey", prefs.getLong("listened_day:$dayKey", 0L) + 1000L)
                        .apply()
                }
                prefs.edit()
                    .putFloat("eq_output_peak", softwareEqualizer.outputPeak)
                    .putFloat("eq_output_rms", softwareEqualizer.outputRms)
                    .apply()
                updateWidget()
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
            "Play/Pause" -> if (player.isPlaying) player.pause() else resumeOrRestart(player)
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
        val audioManager = getSystemService(AudioManager::class.java)
        outputDeviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                AudioFocusModeController.refresh(this@PlaybackService, audioManager)
                refreshOutputDeviceProfile()
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                AudioFocusModeController.refresh(this@PlaybackService, audioManager)
                refreshOutputDeviceProfile()
            }
        }.also { audioManager.registerAudioDeviceCallback(it, Handler(Looper.getMainLooper())) }
        AudioFocusModeController.refresh(this, audioManager)
        refreshOutputDeviceProfile()
        val eqPrefs = getSharedPreferences("analog_library", MODE_PRIVATE)
        eqPrefs.edit()
            .putInt("eq_band_count", 10)
            .putInt("eq_band_min", -1200)
            .putInt("eq_band_max", 1200)
            .putString("eq_band_centers", listOf(32, 64, 125, 250, 500, 1000, 2000, 4000, 8000, 16000).joinToString(",") { (it * 1000).toString() })
            .putBoolean("eq_available", false)
            .putInt("eq_session_id", eqPrefs.getInt("audio_session_id", 0))
            .putString("eq_error", "Equaliser is initialising")
            .apply()
        runCatching { applySoftwareEqualizer() }
            .onSuccess {
                eqPrefs.edit().putBoolean("eq_available", true).putString("eq_error", "").apply()
            }
            .onFailure { error ->
                eqPrefs.edit().putBoolean("eq_available", false)
                    .putString("eq_error", error.message ?: "Equaliser initialisation failed").apply()
            }
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
                    KeyEvent.KEYCODE_MEDIA_PLAY -> { resumeOrRestart(player); return true }
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
                getSharedPreferences("analog_library", MODE_PRIVATE)
                    .edit().putBoolean("quick_tile_playing", isPlaying).apply()
                runCatching {
                    android.service.quicksettings.TileService.requestListeningState(
                        this@PlaybackService,
                        ComponentName(this@PlaybackService, AnalogQuickSettingsTile::class.java)
                    )
                }
                updateWidget()
                if (isPlaying) {
                    // ExoPlayer may allocate its audio session after the listener is
                    // attached. Do not rely only on onAudioSessionIdChanged: publish
                    // the live ID when playback actually starts as well.
                    val activeSessionId = (player as? ExoPlayer)?.audioSessionId ?: 0
                    if (activeSessionId > 0) {
                        getSharedPreferences("analog_library", MODE_PRIVATE)
                            .edit().putInt("audio_session_id", activeSessionId).apply()
                        if (optionalFxSessionId != activeSessionId) initializeOptionalEffects(activeSessionId)
                    }
                    timerHandler.removeCallbacks(listeningTick)
                    timerHandler.post(listeningTick)
                    timerHandler.removeCallbacks(crossfadeTick)
                    timerHandler.post(crossfadeTick)
                } else {
                    timerHandler.removeCallbacks(listeningTick)
                    timerHandler.removeCallbacks(crossfadeTick)
                    cancelCrossfade()
                    getSharedPreferences("analog_library", MODE_PRIVATE).edit()
                        .putFloat("eq_output_peak", 0f)
                        .putFloat("eq_output_rms", 0f)
                        .apply()
                    // A pause during a crossfade must never leave the primary deck attenuated.
                    mediaSession?.player?.volume = 1f
                }
            }
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK && crossfadeInProgress) {
                    cancelCrossfade()
                    crossfadeForIndex = -1
                    player.volume = 1f
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
                if (player.isPlaying) player.pause()
                else if (player.currentMediaItem == null) startRandomLibraryTrack()
                else resumeOrRestart(player)
            }
            ACTION_WIDGET_NEXT -> mediaSession?.player?.seekToNext()
            ACTION_WIDGET_PREVIOUS -> mediaSession?.player?.seekToPrevious()
            ACTION_REAPPLY_REPLAY_GAIN -> applyReplayGain()
            ACTION_EQ_RETRY -> {
                val prefs = getSharedPreferences("analog_library", MODE_PRIVATE)
                val result = runCatching { applySoftwareEqualizer() }
                if (result.isSuccess) {
                    prefs.edit()
                        .putBoolean("eq_available", true)
                        .putInt("eq_session_id", prefs.getInt("audio_session_id", 0))
                        .putString("eq_error", "")
                        .apply()
                } else {
                    prefs.edit()
                        .putBoolean("eq_available", false)
                        .putString("eq_error", result.exceptionOrNull()?.message ?: "Equaliser initialisation failed")
                        .apply()
                }
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
            ACTION_EQ_MATCH_DEVICE -> refreshOutputDeviceProfile()
            ACTION_REFRESH_AUDIO_FOCUS_MODE -> AudioFocusModeController.refresh(this)
            ACTION_EQ_PEAK_GUARD -> {
                val enabled = intent.getBooleanExtra(EXTRA_ENABLED, false)
                val threshold = intent.getFloatExtra(EXTRA_THRESHOLD_DB, -1f).coerceIn(-12f, -0.1f)
                getSharedPreferences("analog_library", MODE_PRIVATE).edit()
                    .putBoolean("eq_peak_guard_enabled", enabled)
                    .putFloat("eq_peak_guard_threshold_db", threshold).apply()
                applySoftwareEqualizer()
            }
            ACTION_EQ_STEREO_BALANCE -> {
                val balance = intent.getFloatExtra(EXTRA_STEREO_BALANCE, 0f).coerceIn(-1f, 1f)
                getSharedPreferences("analog_library", MODE_PRIVATE).edit()
                    .putFloat("eq_stereo_balance", balance).apply()
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

    private fun resumeOrRestart(player: Player) {
        if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
        player.play()
    }

    private fun clearSleepTimer(restoreVolume: Boolean) {
        sleepTimer?.let(timerHandler::removeCallbacks)
        sleepFade?.let(timerHandler::removeCallbacks)
        sleepTimer = null
        sleepFade = null
        if (restoreVolume) mediaSession?.player?.volume = 1f
    }

    /**
     * Rebuild the cold-start queue from both MediaStore and the user's persisted
     * Storage Access Framework folders. This keeps widget/tile playback consistent
     * with the library shown by MainActivity, even when files are not indexed.
     */
    private fun coldStartLibraryItems(): List<Pair<Long, androidx.media3.common.MediaItem>> {
        val result = LinkedHashMap<String, Pair<Long, androidx.media3.common.MediaItem>>()
        val knownPaths = HashSet<String>()
        val columns = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DATA
        )
        runCatching {
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                columns,
                null,
                null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val path = if (dataCol >= 0) cursor.getString(dataCol).orEmpty() else ""
                    val folder = path.substringBeforeLast("/", "Music").substringAfterLast("/")
                    if (folder.equals("Ringtones", true) ||
                        folder.equals("Notifications", true) ||
                        folder.equals("Alarms", true)
                    ) continue
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
                    val uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())
                    val media = androidx.media3.common.MediaItem.Builder()
                        .setMediaId(id.toString())
                        .setUri(uri)
                        .setMediaMetadata(metadata)
                        .build()
                    result[uri.toString()] = id to media
                    if (path.isNotBlank()) knownPaths.add(path.replace('\\', '/').trimEnd('/').lowercase(java.util.Locale.ROOT))
                }
            }
        }

        val knownUris = result.keys.toMutableSet()
        val prefs = getSharedPreferences("analog_library", MODE_PRIVATE)
        val treeUris = prefs.getStringSet("saf_music_trees", emptySet())?.toSet().orEmpty()
        if (treeUris.isNotEmpty()) {
            runCatching { SafAudioLibrary.scan(this, treeUris) }
                .getOrDefault(emptyList())
                .forEach { track ->
                    val key = track.uri.toString()
                    val normalizedPath = track.path.replace('\\', '/').trimEnd('/').lowercase(java.util.Locale.ROOT)
                    if (key !in knownUris && (normalizedPath.isBlank() || normalizedPath.startsWith("content://") || knownPaths.add(normalizedPath))) {
                        val metadata = androidx.media3.common.MediaMetadata.Builder()
                            .setTitle(track.title)
                            .setArtist(track.artist)
                            .setAlbumTitle(track.album)
                            .build()
                        val media = androidx.media3.common.MediaItem.Builder()
                            .setMediaId(track.id.toString())
                            .setUri(track.uri)
                            .setMediaMetadata(metadata)
                            .build()
                        result[key] = track.id to media
                        knownUris.add(key)
                    }
                }
        }
        return result.values.toList()
    }

    /**
     * SAF providers may need to enumerate thousands of documents and extract
     * metadata. Never do that on MediaSessionService's main thread: a widget or
     * Quick Settings tap is a foreground-service start and must return promptly.
     */
    private fun loadColdStartLibrary(
        onLoaded: (List<Pair<Long, androidx.media3.common.MediaItem>>) -> Unit
    ) {
        coldStartLoadCallbacks += onLoaded
        if (coldStartLoadInProgress) return
        coldStartLoadInProgress = true
        coldStartExecutor.execute {
            val items = runCatching { coldStartLibraryItems() }.getOrDefault(emptyList())
            timerHandler.post {
                coldStartLoadInProgress = false
                val callbacks = coldStartLoadCallbacks.toList()
                coldStartLoadCallbacks.clear()
                callbacks.forEach { callback -> callback(items) }
            }
        }
    }

    private fun startLastPlayedFromTile() {
        val player = mediaSession?.player ?: return
        if (player.mediaItemCount == 0) {
            loadColdStartLibrary { items ->
                val activePlayer = mediaSession?.player ?: return@loadColdStartLibrary
                if (activePlayer.mediaItemCount == 0) {
                    if (items.isEmpty()) {
                        updateWidget()
                        return@loadColdStartLibrary
                    }
                    val prefs = getSharedPreferences("analog_library", MODE_PRIVATE)
                    val lastPlayedId = items.maxByOrNull { prefs.getLong("last_played:${it.first}", 0L) }
                        ?.takeIf { prefs.getLong("last_played:${it.first}", 0L) > 0L }?.first
                    val startIndex = items.indexOfFirst { it.first == lastPlayedId }.takeIf { it >= 0 } ?: 0
                    activePlayer.setMediaItems(items.map { it.second }, startIndex, 0L)
                    activePlayer.prepare()
                } else if (activePlayer.playbackState == Player.STATE_ENDED) {
                    activePlayer.seekTo(0L)
                } else if (activePlayer.playbackState == Player.STATE_IDLE) {
                    activePlayer.prepare()
                }
                activePlayer.play()
                updateWidget()
            }
            return
        }
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0L)
        } else if (player.playbackState == Player.STATE_IDLE) {
            player.prepare()
        }
        player.play()
        updateWidget()
    }

    private fun startRandomLibraryTrack() {
        val player = mediaSession?.player as? ExoPlayer ?: return
        if (player.currentMediaItem != null && player.mediaItemCount > 0) {
            resumeOrRestart(player)
            return
        }
        loadColdStartLibrary { items ->
            val activePlayer = mediaSession?.player as? ExoPlayer ?: return@loadColdStartLibrary
            if (activePlayer.currentMediaItem != null && activePlayer.mediaItemCount > 0) {
                resumeOrRestart(activePlayer)
                return@loadColdStartLibrary
            }
            if (items.isEmpty()) {
                updateWidget()
                return@loadColdStartLibrary
            }
            val shuffled = items.shuffled()
            activePlayer.setMediaItems(shuffled.map { it.second }, 0, 0L)
            activePlayer.prepare()
            activePlayer.play()
            updateWidget()
        }
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
            val targetStillMatches = crossfadeTargetIndex in 0 until primary.mediaItemCount &&
                primary.getMediaItemAt(crossfadeTargetIndex).mediaId == secondary.currentMediaItem?.mediaId
            if (!targetStillMatches || secondary.playerError != null) {
                // The queue may have been edited while the second decoder prepared.
                // Never jump to a stale queue index or crossfade into the wrong track.
                cancelCrossfade()
                primary.volume = 1f
                crossfadeForIndex = -1
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
        val peakGuardEnabled = prefs.getBoolean("eq_peak_guard_enabled", false)
        val peakGuardThreshold = prefs.getFloat("eq_peak_guard_threshold_db", -1f).coerceIn(-12f, -0.1f)
        val stereoBalance = prefs.getFloat("eq_stereo_balance", 0f).coerceIn(-1f, 1f)
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
        softwareEqualizer.setPeakGuard(peakGuardEnabled, peakGuardThreshold)
        crossfadeEqualizer.setPeakGuard(peakGuardEnabled, peakGuardThreshold)
        softwareEqualizer.setStereoBalance(stereoBalance)
        crossfadeEqualizer.setStereoBalance(stereoBalance)
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

    private fun refreshOutputDeviceProfile() {
        val prefs = getSharedPreferences("analog_library", MODE_PRIVATE)
        if (!prefs.getBoolean("eq_auto_match_device", true)) return
        val manager = getSystemService(AudioManager::class.java)
        val selection = OutputDeviceResolver.resolve(manager)
        val outputName = selection.name
        val activeName = prefs.getString("eq_active_device_profile", null)
        // Do not guess when Android reports multiple connected external sinks.
        val profile = if (selection.ambiguous) null else HeadphoneEqProfiles.findExact(HeadphoneEqProfiles.read(this), outputName)
        if (selection.ambiguous) {
            // An unknown route must not erase a user's manual EQ. Only clear a
            // previously auto-applied device profile, which might otherwise leak
            // onto a different output.
            if (activeName != null) {
                prefs.edit()
                    .putString("eq_band_levels", List(10) { "0" }.joinToString(","))
                    .putString("eq_band_bandwidths", List(10) { "1" }.joinToString(","))
                    .putFloat("eq_preamp_db", 0f)
                    .putBoolean("eq_enabled", true)
                    .putBoolean("eq_crossfeed_enabled", false)
                    .putFloat("eq_crossfeed_amount", 0.12f)
                    .putBoolean("eq_peak_guard_enabled", false)
                    .putFloat("eq_peak_guard_threshold_db", -1f)
                    .putFloat("eq_stereo_balance", 0f)
                    .remove("eq_active_device_profile")
                    .apply()
                applySoftwareEqualizer()
            }
            return
        }
        if (profile != null) {
            // Reapply even when the profile name is unchanged: imported/edited profiles may
            // have replaced its contents while keeping the same output-name key.
            prefs.edit()
                .putString("eq_band_levels", profile.gainsDb.map { (it / 12f).coerceIn(-1f, 1f) }.joinToString(","))
                .putString("eq_band_bandwidths", profile.bandwidths.joinToString(","))
                .putFloat("eq_preamp_db", profile.preampDb)
                .putBoolean("eq_enabled", profile.eqEnabled)
                .putBoolean("eq_crossfeed_enabled", profile.crossfeedEnabled)
                .putFloat("eq_crossfeed_amount", profile.crossfeedAmount)
                .putBoolean("eq_peak_guard_enabled", profile.peakGuardEnabled)
                .putFloat("eq_peak_guard_threshold_db", profile.peakGuardThresholdDb)
                .putFloat("eq_stereo_balance", profile.stereoBalance)
                .putString("eq_active_device_profile", profile.name)
                .apply()
            applySoftwareEqualizer()
        } else if (activeName != null) {
            prefs.edit()
                .putString("eq_band_levels", List(10) { "0" }.joinToString(","))
                .putString("eq_band_bandwidths", List(10) { "1" }.joinToString(","))
                .putFloat("eq_preamp_db", 0f)
                .putBoolean("eq_enabled", true)
                .putBoolean("eq_crossfeed_enabled", false)
                .putFloat("eq_crossfeed_amount", 0.12f)
                .putBoolean("eq_peak_guard_enabled", false)
                .putFloat("eq_peak_guard_threshold_db", -1f)
                .putFloat("eq_stereo_balance", 0f)
                .remove("eq_active_device_profile")
                .apply()
            applySoftwareEqualizer()
        }
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
        AnalogWidgetProvider.update(this, title, artist, player?.isPlaying == true, player?.mediaMetadata?.artworkUri,
            player?.currentPosition ?: 0L, player?.duration?.takeIf { it > 0L } ?: 0L)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        clearSleepTimer(restoreVolume = false)
        sleepEndListener?.let { mediaSession?.player?.removeListener(it) }
        sleepEndListener = null
        replayGainExecutor.shutdownNow()
        coldStartExecutor.shutdownNow()
        coldStartLoadCallbacks.clear()
        getSharedPreferences("analog_library", MODE_PRIVATE).edit()
            .putBoolean("quick_tile_playing", false).apply()
        runCatching {
            android.service.quicksettings.TileService.requestListeningState(
                this,
                ComponentName(this, AnalogQuickSettingsTile::class.java)
            )
        }
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
        outputDeviceCallback?.let { callback ->
            runCatching { getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(callback) }
        }
        outputDeviceCallback = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
