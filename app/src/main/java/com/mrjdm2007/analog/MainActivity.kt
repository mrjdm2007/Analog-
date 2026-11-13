package com.mrjdm2007.analog

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.content.ContentUris
import android.graphics.drawable.BitmapDrawable
import androidx.palette.graphics.Palette
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import coil.compose.AsyncImage
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.platform.LocalView
import android.view.HapticFeedbackConstants
import kotlin.math.atan2
import kotlin.math.PI
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

private val Ink = Color(0xFF10110F)
private val DefaultAmber = Color(0xFFFFB45B)
private val Panel = Color(0xFF1A1C18)
private val Cream = Color(0xFFE8E1CF)
private var Amber by mutableStateOf(DefaultAmber)
private val Green = Color(0xFFB5D69B)

data class Track(val id: Long, val title: String, val artist: String, val album: String, val uri: Uri, val duration: Long, val folder: String, val albumId: Long)
fun Track.artUri(): Uri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)
private data class Group(val title: String, val subtitle: String, val tracks: List<Track>)

class MainActivity : ComponentActivity() {
    private var future: ListenableFuture<MediaController>? = null
    private var controller by mutableStateOf<MediaController?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        future = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        future?.addListener({ runCatching { controller = future?.get() } }, ContextCompat.getMainExecutor(this))

        setContent {
            val readPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
            var libraryAllowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(this, readPermission) == PackageManager.PERMISSION_GRANTED) }
            val askLibrary = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { libraryAllowed = it }
            var audioAllowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
            val askAudio = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { audioAllowed = it }
            var tracks by remember { mutableStateOf(emptyList<Track>()) }
            var tab by remember { mutableStateOf("Songs") }
            var search by remember { mutableStateOf("") }
            var favorites by remember { mutableStateOf(readIds("favorites")) }
            var playlists by remember { mutableStateOf(readPlaylists()) }
            var activePlaylist by remember { mutableStateOf<String?>(null) }
            var pendingTrack by remember { mutableStateOf<Long?>(null) }
            var playlistDialog by remember { mutableStateOf(false) }
            var playlistName by remember { mutableStateOf("") }
            var nowPlaying by remember { mutableStateOf(false) }
            var visualizer by remember { mutableStateOf(false) }
            var effects by remember { mutableStateOf(false) }
            var recap by remember { mutableStateOf(false) }
            var outputRoute by remember { mutableStateOf(currentAudioRoute()) }
            var headphoneDetectionEnabled by remember { mutableStateOf(prefs().getBoolean("headphone_detection_enabled", true)) }
            var replayGainEnabled by remember { mutableStateOf(prefs().getBoolean("replay_gain_enabled", true)) }
            var crossfadeSeconds by remember { mutableIntStateOf(prefs().getInt("crossfade_seconds", 0)) }
            var settings by remember { mutableStateOf(false) }
            var playerStyle by remember { mutableStateOf(this@MainActivity.getSharedPreferences("analog_library", Context.MODE_PRIVATE).getString("player_style", "Analog Classic") ?: "Analog Classic") }
            var selectedFolders by remember { mutableStateOf(this@MainActivity.getSharedPreferences("analog_library", Context.MODE_PRIVATE).getStringSet("selected_folders", emptySet())?.toSet() ?: emptySet()) }
            var sortMode by remember { mutableStateOf(this@MainActivity.getSharedPreferences("analog_library", Context.MODE_PRIVATE).getString("sort_mode", "Title (A–Z)") ?: "Title (A–Z)") }
            var playCountVersion by remember { mutableIntStateOf(0) }
            var headsetSingle by remember { mutableStateOf(prefs().getString("headset_single", "Play/Pause") ?: "Play/Pause") }
            var headsetDouble by remember { mutableStateOf(prefs().getString("headset_double", "Next track") ?: "Next track") }
            var headsetTriple by remember { mutableStateOf(prefs().getString("headset_triple", "Previous track") ?: "Previous track") }
            val librarySections = listOf("Songs", "Albums", "Artists", "Folders", "Favorites", "Playlists")
            var visibleSections by remember { mutableStateOf(prefs().getStringSet("visible_sections", librarySections.toSet())?.toSet() ?: librarySections.toSet()) }

            LaunchedEffect(visibleSections) { if (tab !in visibleSections) tab = visibleSections.firstOrNull() ?: "Songs" }
            LaunchedEffect(libraryAllowed) { if (libraryAllowed) tracks = withContext(Dispatchers.IO) { scanLibrary() } }
            DisposableEffect(libraryAllowed) {
                if (!libraryAllowed) return@DisposableEffect onDispose { }
                val refreshHandler = Handler(Looper.getMainLooper())
                val refreshTask = Runnable {
                    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                        val updated = scanLibrary()
                        withContext(Dispatchers.Main) { tracks = updated }
                    }
                }
                val observer = object : ContentObserver(refreshHandler) {
                    override fun onChange(selfChange: Boolean) {
                        refreshHandler.removeCallbacks(refreshTask)
                        refreshHandler.postDelayed(refreshTask, 450L)
                    }
                }
                contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
                onDispose {
                    refreshHandler.removeCallbacks(refreshTask)
                    contentResolver.unregisterContentObserver(observer)
                }
            }
            DisposableEffect(headphoneDetectionEnabled) {
                val manager = getSystemService(AudioManager::class.java)
                if (headphoneDetectionEnabled) {
                    outputRoute = currentAudioRoute()
                    val callback = object : AudioDeviceCallback() {
                        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { outputRoute = currentAudioRoute() }
                        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { outputRoute = currentAudioRoute() }
                    }
                    manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
                    onDispose { manager.unregisterAudioDeviceCallback(callback) }
                } else {
                    outputRoute = "Automatic detection disabled"
                    onDispose { }
                }
            }
            LaunchedEffect(Unit) { WeeklyLibraryScanWorker.schedule(this@MainActivity) }
            BackHandler(enabled = settings || visualizer || effects || recap || nowPlaying || (tab == "Playlists" && activePlaylist != null)) {
                when {
                    settings -> settings = false
                    visualizer -> visualizer = false
                    effects -> effects = false
                    recap -> recap = false
                    nowPlaying -> nowPlaying = false
                    tab == "Playlists" && activePlaylist != null -> activePlaylist = null
                }
            }
            MaterialTheme(colorScheme = darkColorScheme(primary = Amber, onPrimary = Ink, background = Ink, surface = Panel, onBackground = Cream, onSurface = Cream, secondary = Green)) {
                when {
                    !libraryAllowed -> PermissionPage { askLibrary.launch(readPermission) }
                    visualizer -> RetroVisualizerScreen(onBack = { visualizer = false }, audioPermissionGranted = audioAllowed, audioSessionId = prefs().getInt("audio_session_id", 0), player = controller, tracks = tracks)
                    effects -> AudioEffectsScreen(outputRoute = outputRoute, audioSessionId = prefs().getInt("audio_session_id", 0), onBack = { effects = false })
                    recap -> ListeningRecapScreen(tracks = tracks, monthlyCounts = currentMonthPlayCounts(), monthlyListeningMs = currentMonthListeningMs(), listeningStreakDays = currentListeningStreak(), onBack = { recap = false })
                    settings -> SettingsPage(
                        folders = tracks.map { it.folder }.distinct().sorted(), selectedFolders = selectedFolders,
                        playerStyle = playerStyle, onPlayerStyleChanged = { playerStyle = it; getSharedPreferences("analog_library", Context.MODE_PRIVATE).edit().putString("player_style", it).apply() },
                        onFoldersChanged = { selectedFolders = it; getSharedPreferences("analog_library", Context.MODE_PRIVATE).edit().putStringSet("selected_folders", it).apply() },
                        singlePress = headsetSingle, doublePress = headsetDouble, triplePress = headsetTriple,
                        visibleSections = visibleSections, onVisibleSectionsChanged = { visibleSections = it; prefs().edit().putStringSet("visible_sections", it).apply() },
                        outputRoute = if (headphoneDetectionEnabled) outputRoute else "Automatic detection disabled",
                        headphoneDetectionEnabled = headphoneDetectionEnabled, onHeadphoneDetectionChanged = { enabled -> headphoneDetectionEnabled = enabled; prefs().edit().putBoolean("headphone_detection_enabled", enabled).apply(); outputRoute = currentAudioRoute() },
                        replayGainEnabled = replayGainEnabled, onReplayGainChanged = { enabled -> replayGainEnabled = enabled; prefs().edit().putBoolean("replay_gain_enabled", enabled).apply(); startService(Intent(this@MainActivity, PlaybackService::class.java).setAction(PlaybackService.ACTION_REAPPLY_REPLAY_GAIN)) },
                        crossfadeSeconds = crossfadeSeconds, onCrossfadeChanged = { seconds -> crossfadeSeconds = seconds; prefs().edit().putInt("crossfade_seconds", seconds).apply(); startService(Intent(this@MainActivity, PlaybackService::class.java).setAction(PlaybackService.ACTION_SET_CROSSFADE).putExtra(PlaybackService.EXTRA_CROSSFADE_SECONDS, seconds)) },
                        onHeadsetSetting = { key, value -> prefs().edit().putString(key, value).apply(); when(key) { "headset_single" -> headsetSingle = value; "headset_double" -> headsetDouble = value; "headset_triple" -> headsetTriple = value } },
                        onEffects = { settings = false; effects = true }, onVisualizer = { settings = false; visualizer = true; if (!audioAllowed) askAudio.launch(Manifest.permission.RECORD_AUDIO) },
                        onSleepTimer = { minutes -> startService(Intent(this@MainActivity, PlaybackService::class.java).setAction(PlaybackService.ACTION_SLEEP_TIMER).putExtra(PlaybackService.EXTRA_MINUTES, minutes)) },
                        onSleepTimerEndTrack = { startService(Intent(this@MainActivity, PlaybackService::class.java).setAction(PlaybackService.ACTION_SLEEP_TIMER_END_TRACK)) },
                        onCancelSleepTimer = { startService(Intent(this@MainActivity, PlaybackService::class.java).setAction(PlaybackService.ACTION_CANCEL_SLEEP_TIMER)) },
                        onMonthlyRecap = { settings = false; recap = true }, onRescan = { tracks = scanLibrary() }, onBack = { settings = false })

                    nowPlaying -> if (playerStyle == "iPod Inspired") IpodPlayerPage(controller, tracks, favorites, onBack = { nowPlaying = false },
                        onVisualizer = { nowPlaying = false; visualizer = true; if (!audioAllowed) askAudio.launch(Manifest.permission.RECORD_AUDIO) },
                        onFavorite = { id -> favorites = favorites.toMutableSet().apply { if (contains(id)) remove(id) else add(id) }; storeIds("favorites", favorites) })
                    else PlayerPage(controller, tracks, favorites, onBack = { nowPlaying = false },
                        onVisualizer = { nowPlaying = false; visualizer = true; if (!audioAllowed) askAudio.launch(Manifest.permission.RECORD_AUDIO) },
                        onFavorite = { id -> favorites = favorites.toMutableSet().apply { if (contains(id)) remove(id) else add(id) }; storeIds("favorites", favorites) })
                    else -> {
                        val libraryTracks = if (selectedFolders.isEmpty()) tracks else tracks.filter { it.folder in selectedFolders }
                        val currentTracks = when (tab) {
                            "Favorites" -> libraryTracks.filter { it.id in favorites }
                            "Playlists" -> activePlaylist?.let { name ->
                                if (name.startsWith("smart:")) smartPlaylistTracks(name.removePrefix("smart:"), libraryTracks, favorites)
                                else libraryTracks.filter { it.id in (playlists[name] ?: emptySet()) }
                            } ?: emptyList()
                            else -> libraryTracks
                        }.filter { search.isBlank() || it.title.contains(search, true) || it.artist.contains(search, true) || it.album.contains(search, true) }
                        val playCounts = remember(tracks, playCountVersion) { prefs().all.filterKeys { it.startsWith("plays:") }.mapKeys { it.key.removePrefix("plays:").toLongOrNull() ?: -1L }.mapValues { (it.value as? Int) ?: 0 } }
                        val sortedTracks = when (sortMode) {
                            "Title (Z–A)" -> currentTracks.sortedByDescending { it.title.lowercase() }
                            "Artist" -> currentTracks.sortedBy { it.artist.lowercase() }
                            "Album" -> currentTracks.sortedBy { it.album.lowercase() }
                            "Least played" -> currentTracks.sortedWith(compareBy<Track> { playCounts[it.id] ?: 0 }.thenBy { it.title.lowercase() })
                            "Most played" -> currentTracks.sortedWith(compareByDescending<Track> { playCounts[it.id] ?: 0 }.thenBy { it.title.lowercase() })
                            else -> currentTracks.sortedBy { it.title.lowercase() }
                        }
                        val swipeTabs = librarySections.filter { it in visibleSections }.ifEmpty { listOf("Songs") }
                        Column(Modifier.fillMaxSize().background(Ink).animateContentSize(tween(280)).pointerInput(tab) {
                            var drag = 0f
                            detectHorizontalDragGestures(onHorizontalDrag = { _, amount -> drag += amount }, onDragEnd = {
                                val current = swipeTabs.indexOf(tab).coerceAtLeast(0)
                                if (drag < -70f && current < swipeTabs.lastIndex) { tab = swipeTabs[current + 1]; activePlaylist = null }
                                else if (drag > 70f && current > 0) { tab = swipeTabs[current - 1]; activePlaylist = null }
                                drag = 0f
                            }, onDragCancel = { drag = 0f })
                        }) {
                            TopHeader(onSettings = { settings = true })
                            SearchBox(search) { search = it }
                            val tabs = swipeTabs
                            ScrollableTabRow(selectedTabIndex = tabs.indexOf(tab).coerceAtLeast(0), containerColor = Ink, contentColor = Amber, edgePadding = 12.dp) {
                                tabs.forEach { name -> Tab(selected = tab == name, onClick = { tab = name; activePlaylist = null }, text = { Text(name, fontSize = 11.sp) }) }
                            }
                            if (tab in listOf("Albums", "Artists", "Folders")) {
                                val groups = when (tab) {
                                    "Albums" -> libraryTracks.groupBy { it.album }
                                    "Artists" -> libraryTracks.groupBy { it.artist }
                                    else -> libraryTracks.groupBy { it.folder }
                                }.map { (name, list) -> Group(name, "${list.size} tracks • ${list.firstOrNull()?.artist ?: "Local files"}", list) }
                                    .filter { search.isBlank() || it.title.contains(search, true) }
                                    .sortedBy { it.title.lowercase() }
                                LazyColumn(Modifier.weight(1f)) {
                                    items(groups) { group -> GroupRow(group) { play(group.tracks, 0); playCountVersion++ } }
                                }
                            } else if (tab == "Playlists" && activePlaylist == null) {
                                Column(Modifier.weight(1f)) {
                                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("YOUR PLAYLISTS", color = Green, fontSize = 10.sp, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
                                        TextButton(onClick = { pendingTrack = null; playlistDialog = true }) { Icon(Icons.Default.Add, null); Text("NEW") }
                                    }
                                    LazyColumn {
                                        item { Text("SMART MIXES", color = Green, fontSize = 10.sp, letterSpacing = 2.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) }
                                        items(listOf("Most played this month", "Favourites", "Quick Listen", "Long Listening", "Surprise Me")) { name ->
                                            val smartTracks = smartPlaylistTracks(name, libraryTracks, favorites)
                                            GroupRow(Group(name, "${smartTracks.size} tracks • automatically updated", smartTracks)) { activePlaylist = "smart:$name" }
                                        }
                                        item { Text("YOUR PLAYLISTS", color = Green, fontSize = 10.sp, letterSpacing = 2.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) }
                                        items(playlists.keys.sorted()) { name ->
                                            val playlistTracks = libraryTracks.filter { it.id in (playlists[name] ?: emptySet()) }
                                            GroupRow(Group(name, "${playlistTracks.size} saved tracks", playlistTracks)) { activePlaylist = name }
                                        }
                                    }
                                }
                            } else {
                                if (tab == "Playlists" && activePlaylist != null) {
                                    TextButton(onClick = { activePlaylist = null }, modifier = Modifier.padding(start = 12.dp)) { Icon(Icons.Default.ArrowBack, null); Text("ALL PLAYLISTS") }
                                }
                                if (currentTracks.isEmpty()) {
                                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        Text(if (tracks.isEmpty()) "No local audio found" else "Nothing here yet", color = Cream.copy(alpha = .5f))
                                    }
                                } else {
                                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("${sortedTracks.size} SONGS", color = Cream.copy(alpha = .45f), fontSize = 10.sp, modifier = Modifier.weight(1f))
                                        SortDropdown(sortMode) { sortMode = it; prefs().edit().putString("sort_mode", it).apply() }
                                    }
                                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp)) {
                                        itemsIndexed(sortedTracks, key = { _, t -> t.id }) { i, track ->
                                            SongRow(track, i + 1, track.id in favorites,
                                                onPlay = { play(sortedTracks, i); playCountVersion++ },
                                                onFavorite = { favorites = favorites.toMutableSet().apply { if (contains(track.id)) remove(track.id) else add(track.id) }; storeIds("favorites", favorites) },
                                                onMore = { pendingTrack = track.id; playlistDialog = true })
                                        }
                                    }
                                }
                            }
                            MiniPlayer(controller) { nowPlaying = true }
                        }
                        if (playlistDialog) {
                            AlertDialog(
                                onDismissRequest = { playlistDialog = false; pendingTrack = null },
                                containerColor = Panel,
                                title = { Text(if (pendingTrack == null) "Create playlist" else "Add to playlist", color = Cream) },
                                text = { Column {
                                    if (pendingTrack != null) {
                                        Text("Add this song to:", color = Cream.copy(alpha = .65f), fontSize = 12.sp)
                                        playlists.keys.sorted().forEach { name ->
                                            TextButton(onClick = {
                                                val id = pendingTrack
                                                if (id != null) {
                                                    playlists = playlists.toMutableMap().apply { put(name, (get(name) ?: emptySet()) + id) }
                                                    storePlaylists(playlists)
                                                }
                                                playlistDialog = false; pendingTrack = null
                                            }) { Text(name, color = Amber) }
                                        }
                                    }
                                    OutlinedTextField(value = playlistName, onValueChange = { playlistName = it }, label = { Text("New playlist name") }, singleLine = true)
                                } },
                                confirmButton = { TextButton(onClick = {
                                    val name = playlistName.trim()
                                    if (name.isNotEmpty()) {
                                        playlists = playlists.toMutableMap().apply { put(name, pendingTrack?.let { setOf(it) } ?: emptySet()) }
                                        storePlaylists(playlists); activePlaylist = name; tab = "Playlists"
                                    }
                                    playlistName = ""; pendingTrack = null; playlistDialog = false
                                }) { Text("CREATE") } },
                                dismissButton = { TextButton(onClick = { playlistDialog = false; pendingTrack = null }) { Text("CANCEL") } }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun play(list: List<Track>, index: Int) {
        val c = controller ?: return
        val selected = list.getOrNull(index) ?: return
        c.setMediaItems(list.map { track ->
            MediaItem.Builder().setMediaId(track.id.toString()).setUri(track.uri).setMediaMetadata(MediaMetadata.Builder()
                .setTitle(track.title).setArtist(track.artist).setAlbumTitle(track.album).setArtworkUri(track.artUri()).build()).build()
        }, index, 0L)
        c.prepare(); c.play()
    }

    private fun scanLibrary(): List<Track> {
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val columns = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.ALBUM_ID)
        val found = mutableListOf<Track>()
        contentResolver.query(uri, columns, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val title = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val album = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val duration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val data = c.getColumnIndex(MediaStore.Audio.Media.DATA)
            val albumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            while (c.moveToNext()) {
                val path = if (data >= 0) c.getString(data).orEmpty() else ""
                found += Track(c.getLong(id), c.getString(title) ?: "Unknown title", c.getString(artist) ?: "Unknown artist",
                    c.getString(album) ?: "Unknown album", Uri.withAppendedPath(uri, c.getLong(id).toString()),
                    c.getLong(duration), path.substringBeforeLast("/", "Music").substringAfterLast("/").ifBlank { "Music" }, c.getLong(albumId))
            }
        }
        return found
    }

    private fun prefs() = getSharedPreferences("analog_library", Context.MODE_PRIVATE)
    private fun currentAudioRoute(): String {
        val manager = getSystemService(AudioManager::class.java)
        val device = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type in setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET) }
        return device?.productName?.toString()?.takeIf { it.isNotBlank() }?.let { "Connected: $it" } ?: "Phone speaker / other output"
    }
    private fun smartPlaylistTracks(name: String, source: List<Track>, favorites: Set<Long>): List<Track> {
        val counts = currentMonthPlayCounts()
        val recentCutoff = System.currentTimeMillis() - 30L * 24L * 60L * 60L * 1000L
        return when (name) {
            "Most played this month" -> source.filter { (counts[it.id] ?: 0) > 0 }.sortedByDescending { counts[it.id] ?: 0 }
            "Favourites" -> source.filter { it.id in favorites }.sortedByDescending { prefs().getLong("last_played:${it.id}", 0L) }
            "Quick Listen" -> source.filter { it.duration in 1L..240_000L }.sortedByDescending { prefs().getLong("last_played:${it.id}", 0L) }
            "Long Listening" -> source.filter { it.duration >= 300_000L }.sortedByDescending { prefs().getLong("last_played:${it.id}", 0L) }
            "Surprise Me" -> source.shuffled()
            else -> source.filter { prefs().getLong("last_played:${it.id}", 0L) >= recentCutoff }.sortedByDescending { prefs().getLong("last_played:${it.id}", 0L) }
        }
    }

    private fun currentMonthPlayCounts(): Map<Long, Int> {
        val monthKey = java.text.SimpleDateFormat("yyyyMM", java.util.Locale.US).format(java.util.Date())
        return prefs().all.filterKeys { it.startsWith("month:$monthKey:") }.mapNotNull { (key, value) -> key.substringAfterLast(":").toLongOrNull()?.let { it to ((value as? Int) ?: 0) } }.toMap()
    }
    private fun currentMonthListeningMs(): Map<Long, Long> {
        val monthKey = java.text.SimpleDateFormat("yyyyMM", java.util.Locale.US).format(java.util.Date())
        return prefs().all.filterKeys { it.startsWith("listened_ms:$monthKey:") }
            .mapNotNull { (key, value) -> key.substringAfterLast(":").toLongOrNull()?.let { it to ((value as? Long) ?: 0L) } }.toMap()
    }
    private fun currentListeningStreak(): Int {
        val activeDays = prefs().all.keys.filter { it.startsWith("listened_day:") }
            .mapNotNull { it.removePrefix("listened_day:").toLongOrNull() }.toSet()
        val formatter = java.time.format.DateTimeFormatter.BASIC_ISO_DATE
        var day = java.time.LocalDate.now()
        if (day.format(formatter).toLong() !in activeDays) day = day.minusDays(1)
        var streak = 0
        while (day.format(formatter).toLong() in activeDays) {
            streak++
            day = day.minusDays(1)
        }
        return streak
    }
    private fun readIds(key: String) = prefs().getStringSet(key, emptySet())?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
    private fun storeIds(key: String, ids: Set<Long>) { prefs().edit().putStringSet(key, ids.map { it.toString() }.toSet()).apply() }
    private fun readPlaylists(): Map<String, Set<Long>> = prefs().all.filterKeys { it.startsWith("playlist:") }
        .mapKeys { it.key.removePrefix("playlist:") }.mapValues { (_, v) -> (v as? Set<*>)?.mapNotNull { it.toString().toLongOrNull() }?.toSet() ?: emptySet() }
    private fun storePlaylists(data: Map<String, Set<Long>>) { val e = prefs().edit(); prefs().all.keys.filter { it.startsWith("playlist:") }.forEach { e.remove(it) }; data.forEach { (n, ids) -> e.putStringSet("playlist:$n", ids.map { it.toString() }.toSet()) }; e.apply() }

    override fun onDestroy() { future?.let { MediaController.releaseFuture(it) }; controller = null; super.onDestroy() }
}

@Composable private fun PermissionPage(onAllow: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(28.dp)) {
            Text("ANALOG", color = Amber, fontSize = 30.sp, fontWeight = FontWeight.Black, letterSpacing = 5.sp)
            Spacer(Modifier.height(12.dp)); Text("Your music. Your device. No signal required.", color = Cream)
            Spacer(Modifier.height(22.dp)); Button(onClick = onAllow) { Text("ALLOW MUSIC LIBRARY") }
        }
    }
}
@Composable private fun TopHeader(onSettings: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 22.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ANALOG", color = Amber, fontSize = 25.sp, fontWeight = FontWeight.Black, letterSpacing = 5.sp)
            Spacer(Modifier.weight(1f)); IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings", tint = Cream) }
        }
        Text("LOCAL MUSIC SYSTEM", color = Cream.copy(alpha = .48f), fontSize = 9.sp, letterSpacing = 2.sp)
    }
}

@Composable private fun SortDropdown(selected: String, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Icon(Icons.Default.Sort, null, tint = Amber); Spacer(Modifier.width(4.dp)); Text(selected, color = Amber, fontSize = 11.sp) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf("Title (A–Z)", "Title (Z–A)", "Artist", "Album", "Least played", "Most played").forEach { mode ->
                DropdownMenuItem(text = { Text(mode) }, onClick = { onSelected(mode); expanded = false })
            }
        }
    }
}

@Composable private fun SearchBox(value: String, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).background(Panel, RoundedCornerShape(9.dp)).padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Search, null, tint = Green); Spacer(Modifier.width(8.dp))
        BasicTextField(value, onChange, modifier = Modifier.weight(1f), singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = Cream, fontSize = 14.sp),
            decorationBox = { inner -> if (value.isEmpty()) Text("Search songs, artists, albums", color = Cream.copy(alpha = .4f), fontSize = 14.sp); inner() })
        if (value.isNotEmpty()) IconButton(onClick = { onChange("") }, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.Close, null, tint = Cream) }
    }
}
@Composable private fun GroupRow(group: Group, onClick: () -> Unit) {
    val hapticView = LocalView.current
    Row(Modifier.fillMaxWidth().animateContentSize(tween(220)).clickable { hapticView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onClick() }.padding(horizontal = 20.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).background(Panel, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            val art = group.tracks.firstOrNull()?.artUri()
            if (art != null) AsyncImage(model = art, contentDescription = group.title, modifier = Modifier.fillMaxSize()) else Icon(Icons.Default.Album, null, tint = Amber)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) { Text(group.title, color = Cream, fontWeight = FontWeight.SemiBold, maxLines = 1); Text(group.subtitle, color = Cream.copy(alpha = .5f), fontSize = 11.sp) }
        Icon(Icons.Default.ChevronRight, null, tint = Green)
    }
}
@Composable private fun SongRow(track: Track, number: Int, favorite: Boolean, onPlay: () -> Unit, onFavorite: () -> Unit, onMore: () -> Unit) {
    val hapticView = LocalView.current
    Row(Modifier.fillMaxWidth().animateContentSize(tween(180)).clickable { hapticView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onPlay() }.padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).background(Panel, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Album, null, tint = Amber.copy(alpha = .35f), modifier = Modifier.size(20.dp)); AsyncImage(model = track.artUri(), contentDescription = track.album, modifier = Modifier.fillMaxSize()); }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) { Text(track.title, color = Cream, fontWeight = FontWeight.SemiBold, maxLines = 1); Text("${track.artist} • ${track.album}", color = Cream.copy(alpha = .48f), fontSize = 11.sp, maxLines = 1) }
        Text(timeText(track.duration), color = Cream.copy(alpha = .45f), fontSize = 10.sp)
        IconButton(onClick = { hapticView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onFavorite() }) { Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (favorite) Amber else Cream.copy(alpha = .5f)) }
        IconButton(onClick = { hapticView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onMore() }) { Icon(Icons.Default.MoreVert, null, tint = Cream.copy(alpha = .6f)) }
    }
}
@Composable private fun MiniPlayer(player: MediaController?, onOpen: () -> Unit) {
    val hapticView = LocalView.current
    var title by remember { mutableStateOf("Choose a track") }
    var artist by remember { mutableStateOf("ANALOG • OFFLINE") }
    var playing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(player) { while (player != null) {
        title = player.mediaMetadata.title?.toString() ?: "Choose a track"
        artist = player.mediaMetadata.artist?.toString() ?: "ANALOG • OFFLINE"
        playing = player.isPlaying
        progress = if (player.duration > 0) player.currentPosition.toFloat() / player.duration else 0f
        delay(300)
    } }
    Column(Modifier.fillMaxWidth().background(Panel).clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 9.dp)) {
        LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(2.dp), color = Amber, trackColor = Cream.copy(alpha = .12f))
        Row(Modifier.fillMaxWidth().padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(title, color = Cream, fontWeight = FontWeight.Bold, maxLines = 1); Text(artist, color = Green, fontSize = 10.sp, maxLines = 1) }
            IconButton(onClick = { hapticView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); player?.seekToPrevious() }) { Icon(Icons.Default.SkipPrevious, null, tint = Cream) }
            IconButton(onClick = { hapticView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); if (playing) player?.pause() else player?.play() }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Amber) }
            IconButton(onClick = { hapticView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); player?.seekToNext() }) { Icon(Icons.Default.SkipNext, null, tint = Cream) }
        }
    }
}
@Composable private fun PlayerPage(player: MediaController?, tracks: List<Track>, favorites: Set<Long>, onBack: () -> Unit, onVisualizer: () -> Unit, onFavorite: (Long) -> Unit) {
    val hapticView = LocalView.current
    var title by remember { mutableStateOf("Nothing playing") }
    var artist by remember { mutableStateOf("Choose a song from your library") }
    var playing by remember { mutableStateOf(false) }
    var duration by remember { mutableFloatStateOf(1f) }
    var position by remember { mutableFloatStateOf(0f) }
    var trackId by remember { mutableStateOf<Long?>(null) }
    var showQueue by remember { mutableStateOf(false) }
    var artAccent by remember { mutableStateOf(Amber) }
    LaunchedEffect(trackId) { artAccent = DefaultAmber; Amber = DefaultAmber }
    LaunchedEffect(player) { while (player != null) {
        title = player.mediaMetadata.title?.toString() ?: "Nothing playing"
        artist = player.mediaMetadata.artist?.toString() ?: "Unknown artist"
        playing = player.isPlaying
        duration = player.duration.coerceAtLeast(1).toFloat()
        position = player.currentPosition.toFloat().coerceIn(0f, duration)
        trackId = tracks.firstOrNull { it.title == title && it.artist == artist }?.id
        delay(500)
    } }
    Column(Modifier.fillMaxSize().background(Ink).padding(horizontal = 22.dp), verticalArrangement = Arrangement.Center) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = Cream) }
            Text("NOW PLAYING", color = Amber, fontSize = 12.sp, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = { showQueue = true }) { Icon(Icons.Default.QueueMusic, "Playback queue", tint = Cream) }
            IconButton(onClick = onVisualizer) { Icon(Icons.Default.GraphicEq, null, tint = Green) }
        }
        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(artAccent.copy(alpha = .14f), RoundedCornerShape(18.dp)).animateContentSize(tween(350)), contentAlignment = Alignment.Center) {
            val currentTrack = tracks.firstOrNull { it.id == trackId }
            if (currentTrack != null) AsyncImage(model = currentTrack.artUri(), contentDescription = currentTrack.album, modifier = Modifier.fillMaxSize(),
                onSuccess = { state ->
                    artAccent = DefaultAmber
                },
                onError = { artAccent = DefaultAmber })
            else Icon(Icons.Default.Album, null, tint = DefaultAmber, modifier = Modifier.size(104.dp))
        }
        Spacer(Modifier.height(26.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(title, color = Cream, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2); Text(artist, color = Cream.copy(alpha = .55f), fontSize = 13.sp) }
            IconButton(onClick = { hapticView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); trackId?.let(onFavorite) }) { Icon(if (trackId in favorites) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = Amber) }
        }
        Slider(value = position, onValueChange = { position = it }, onValueChangeFinished = { player?.seekTo(position.toLong()) }, valueRange = 0f..duration, colors = SliderDefaults.colors(thumbColor = artAccent, activeTrackColor = artAccent))
        Row { Text(timeText(position.toLong()), color = Cream.copy(alpha = .5f), fontSize = 10.sp); Spacer(Modifier.weight(1f)); Text(timeText(duration.toLong()), color = Cream.copy(alpha = .5f), fontSize = 10.sp) }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { hapticView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); player?.shuffleModeEnabled = !(player?.shuffleModeEnabled ?: false) }) { Icon(Icons.Default.Shuffle, null, tint = Green) }
            IconButton(onClick = { hapticView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); player?.seekToPrevious() }, modifier = Modifier.size(54.dp)) { Icon(Icons.Default.SkipPrevious, null, tint = Cream, modifier = Modifier.size(36.dp)) }
            FilledIconButton(onClick = { hapticView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); if (playing) player?.pause() else player?.play() }, modifier = Modifier.size(68.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = artAccent)) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Ink, modifier = Modifier.size(38.dp))
            }
            IconButton(onClick = { hapticView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); player?.seekToNext() }, modifier = Modifier.size(54.dp)) { Icon(Icons.Default.SkipNext, null, tint = Cream, modifier = Modifier.size(36.dp)) }
            IconButton(onClick = { hapticView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); player?.repeatMode = ((player?.repeatMode ?: 0) + 1) % 3 }) { Icon(Icons.Default.Repeat, null, tint = Green) }
        }
        Spacer(Modifier.height(14.dp))
    }
    if (showQueue) {
        AlertDialog(
            onDismissRequest = { showQueue = false },
            containerColor = Panel,
            title = { Text("PLAYBACK QUEUE", color = Amber, fontSize = 14.sp, letterSpacing = 1.5.sp) },
            text = {
                if (player == null || player.mediaItemCount == 0) {
                    Text("The queue is empty.", color = Cream)
                } else {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(player.mediaItemCount) { index ->
                            val item = player.getMediaItemAt(index)
                            Row(Modifier.fillMaxWidth().clickable { player.seekToDefaultPosition(index); showQueue = false }.padding(vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(if (index == player.currentMediaItemIndex) "▶" else "${index + 1}", color = Amber, modifier = Modifier.width(30.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(item.mediaMetadata.title?.toString() ?: "Unknown title", color = Cream, maxLines = 1)
                                    Text(item.mediaMetadata.artist?.toString() ?: "Unknown artist", color = Cream.copy(alpha = .5f), fontSize = 10.sp)
                                }
                                IconButton(onClick = { player.removeMediaItem(index) }) { Icon(Icons.Default.Close, "Remove from queue", tint = Cream.copy(alpha = .6f)) }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showQueue = false }) { Text("DONE") } }
        )
    }
}

@Composable private fun IpodPlayerPage(player: MediaController?, tracks: List<Track>, favorites: Set<Long>, onBack: () -> Unit, onVisualizer: () -> Unit, onFavorite: (Long) -> Unit) {
    var title by remember { mutableStateOf("Nothing playing") }
    var artist by remember { mutableStateOf("Choose a song from your library") }
    var playing by remember { mutableStateOf(false) }
    var duration by remember { mutableFloatStateOf(1f) }
    var position by remember { mutableFloatStateOf(0f) }
    var trackId by remember { mutableStateOf<Long?>(null) }
    var artColor by remember { mutableStateOf(DefaultAmber) }
    LaunchedEffect(player) {
        while (player != null) {
            title = player.mediaMetadata.title?.toString() ?: "Nothing playing"
            artist = player.mediaMetadata.artist?.toString() ?: "Unknown artist"
            playing = player.isPlaying
            duration = player.duration.coerceAtLeast(1).toFloat()
            position = player.currentPosition.toFloat().coerceIn(0f, duration)
            trackId = tracks.firstOrNull { it.title == title && it.artist == artist }?.id
            delay(250)
        }
    }
    val currentTrack = tracks.firstOrNull { it.id == trackId }
    val wheelView = LocalView.current
    Column(Modifier.fillMaxSize().background(Color(0xFF090A09)).padding(horizontal = 18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = Cream) }
            Text("iPod-inspired mode", color = Cream, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onVisualizer) { Icon(Icons.Default.GraphicEq, null, tint = Green) }
        }
        Card(Modifier.fillMaxWidth().weight(1f).padding(top = 10.dp, bottom = 14.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFC9D0C5)), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth().background(Color(0xFF5C7C63), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("ANALOG", color = Color(0xFFF1F4E9), fontWeight = FontWeight.Black, fontSize = 11.sp, letterSpacing = 1.5.sp)
                    Spacer(Modifier.weight(1f)); Icon(Icons.Default.BatteryFull, null, tint = Color(0xFFF1F4E9), modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("NOW PLAYING", color = Color(0xFF344A38), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text(title, color = Color(0xFF17251B), fontSize = 15.sp, fontWeight = FontWeight.Black, maxLines = 3)
                        Text(artist, color = Color(0xFF344A38), fontSize = 11.sp, maxLines = 3)
                        Spacer(Modifier.height(8.dp))
                        Text(if (playing) "▶ PLAYING" else "Ⅱ PAUSED", color = Color(0xFF344A38), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                    Box(Modifier.size(112.dp).background(Color(0xFF9DAA9B), RoundedCornerShape(3.dp)), contentAlignment = Alignment.Center) {
                        if (currentTrack != null) AsyncImage(model = currentTrack.artUri(), contentDescription = currentTrack.album, modifier = Modifier.fillMaxSize(),
                            onSuccess = { artColor = DefaultAmber }, onError = { artColor = DefaultAmber })
                        else Icon(Icons.Default.Album, null, tint = artColor, modifier = Modifier.size(48.dp))
                    }
                }
                Slider(value = position, onValueChange = { position = it }, onValueChangeFinished = { player?.seekTo(position.toLong()) }, valueRange = 0f..duration,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFF344A38), activeTrackColor = Color(0xFF5C7C63), inactiveTrackColor = Color(0xFF9DAA9B)))
                Row(Modifier.fillMaxWidth()) { Text(timeText(position.toLong()), color = Color(0xFF344A38), fontSize = 9.sp); Spacer(Modifier.weight(1f)); Text(timeText(duration.toLong()), color = Color(0xFF344A38), fontSize = 9.sp) }
            }
        }
        var lastWheelAngle by remember { mutableFloatStateOf(Float.NaN) }
        var wheelAngleAccumulator by remember { mutableFloatStateOf(0f) }
        Box(Modifier.padding(bottom = 22.dp).size(232.dp)
            .background(Color(0xFFE2E4E0), androidx.compose.foundation.shape.CircleShape)
            .pointerInput(player) {
                detectDragGestures(
                    onDragStart = { offset ->
                        lastWheelAngle = atan2(offset.y - size.height / 2f, offset.x - size.width / 2f)
                        wheelAngleAccumulator = 0f
                    },
                    onDrag = { change, _ ->
                        val angle = atan2(change.position.y - size.height / 2f, change.position.x - size.width / 2f)
                        if (!lastWheelAngle.isNaN()) {
                            var delta = angle - lastWheelAngle
                            while (delta > PI.toFloat()) delta -= 2f * PI.toFloat()
                            while (delta < -PI.toFloat()) delta += 2f * PI.toFloat()
                            wheelAngleAccumulator += delta
                            val tickAngle = 15f * PI.toFloat() / 180f
                            if (kotlin.math.abs(wheelAngleAccumulator) >= tickAngle) {
                                val direction = if (wheelAngleAccumulator > 0f) 1 else -1
                                val ticks = (kotlin.math.abs(wheelAngleAccumulator) / tickAngle).toInt().coerceAtMost(4)
                                repeat(ticks) {
                                    player?.let { p -> p.seekTo((p.currentPosition + direction * 2000L).coerceIn(0L, p.duration.coerceAtLeast(0L))) }
                                    wheelView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                }
                                wheelAngleAccumulator -= direction * ticks * tickAngle
                            }
                        }
                        lastWheelAngle = angle
                        change.consume()
                    },
                    onDragEnd = { lastWheelAngle = Float.NaN; wheelAngleAccumulator = 0f },
                    onDragCancel = { lastWheelAngle = Float.NaN; wheelAngleAccumulator = 0f }
                )
            }.background(Color.Transparent), contentAlignment = Alignment.Center) {
            Box(Modifier.size(180.dp).background(Color(0xFFD1D4D0), androidx.compose.foundation.shape.CircleShape))
            Text("MENU", color = Color(0xFF303530), fontSize = 13.sp, fontWeight = FontWeight.Black, modifier = Modifier.align(Alignment.TopCenter).padding(top = 26.dp).clickable { wheelView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onBack() })
            IconButton(onClick = { wheelView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); player?.seekToPrevious() }, modifier = Modifier.align(Alignment.CenterStart).padding(start = 22.dp)) { Icon(Icons.Default.SkipPrevious, null, tint = Color(0xFF303530), modifier = Modifier.size(30.dp)) }
            IconButton(onClick = { wheelView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); player?.seekToNext() }, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 22.dp)) { Icon(Icons.Default.SkipNext, null, tint = Color(0xFF303530), modifier = Modifier.size(30.dp)) }
            IconButton(onClick = { wheelView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); player?.seekTo(((player?.currentPosition ?: 0L) - 10000L).coerceAtLeast(0L)) }, modifier = Modifier.align(Alignment.TopCenter).padding(top = 56.dp)) { Icon(Icons.Default.KeyboardArrowUp, null, tint = Color(0xFF303530), modifier = Modifier.size(28.dp)) }
            IconButton(onClick = { wheelView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); player?.seekTo((player?.currentPosition ?: 0L) + 10000L) }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp)) { Icon(Icons.Default.KeyboardArrowDown, null, tint = Color(0xFF303530), modifier = Modifier.size(28.dp)) }
            FilledIconButton(onClick = { wheelView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); if (playing) player?.pause() else player?.play() }, modifier = Modifier.size(72.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFBEC3BC))) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color(0xFF303530), modifier = Modifier.size(32.dp))
            }
        }
        currentTrack?.let { track -> TextButton(onClick = { onFavorite(track.id) }) { Icon(if (track.id in favorites) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = artColor); Text(if (track.id in favorites) "FAVORITED" else "FAVORITE", color = artColor) } }
    }
}

private fun timeText(ms: Long): String { val sec = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(sec / 60, sec % 60) }
