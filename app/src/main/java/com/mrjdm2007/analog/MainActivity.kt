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
import android.media.MediaMetadataRetriever
import androidx.palette.graphics.Palette
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import coil.compose.AsyncImage
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import android.view.HapticFeedbackConstants
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.Spring
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.compose.ui.layout.ContentScale
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FastOutSlowInEasing
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

data class Track(val id: Long, val title: String, val artist: String, val album: String, val uri: Uri, val duration: Long, val folder: String, val albumId: Long, val path: String = "")
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
            var studio by remember { mutableStateOf(false) }
            var outputRoute by remember { mutableStateOf(currentAudioRoute()) }
            var headphoneDetectionEnabled by remember { mutableStateOf(prefs().getBoolean("headphone_detection_enabled", true)) }
            var replayGainEnabled by remember { mutableStateOf(prefs().getBoolean("replay_gain_enabled", true)) }
            var crossfadeSeconds by remember { mutableIntStateOf(prefs().getInt("crossfade_seconds", 0)) }
            var sleepFadeSeconds by remember { mutableIntStateOf(prefs().getInt("sleep_fade_seconds", 15).coerceIn(0, 30)) }
            var settings by remember { mutableStateOf(false) }
            var selectedFolders by remember { mutableStateOf(this@MainActivity.getSharedPreferences("analog_library", Context.MODE_PRIVATE).getStringSet("selected_folders", emptySet())?.toSet() ?: emptySet()) }
            var sortMode by remember { mutableStateOf(this@MainActivity.getSharedPreferences("analog_library", Context.MODE_PRIVATE).getString("sort_mode", "Title (A–Z)") ?: "Title (A–Z)") }
            var playCountVersion by remember { mutableIntStateOf(0) }
            var headsetSingle by remember { mutableStateOf(prefs().getString("headset_single", "Play/Pause") ?: "Play/Pause") }
            var headsetDouble by remember { mutableStateOf(prefs().getString("headset_double", "Next track") ?: "Next track") }
            var headsetTriple by remember { mutableStateOf(prefs().getString("headset_triple", "Previous track") ?: "Previous track") }
            val librarySections = listOf("Songs", "Albums", "Artists", "Folders", "Favorites", "Playlists")
            var visibleSections by remember { mutableStateOf(prefs().getStringSet("visible_sections", librarySections.toSet())?.toSet() ?: librarySections.toSet()) }
            var exportTracks by remember { mutableStateOf(emptyList<Track>()) }
            var exportName by remember { mutableStateOf("Analog Playlist") }
            var lyricsTargetTrackId by remember { mutableStateOf<Long?>(null) }
            var lyricsVersion by remember { mutableIntStateOf(0) }
            val exportPlaylistLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
                if (uri != null) {
                    val text = buildString {
                        append("#EXTM3U\n")
                        exportTracks.forEach { track ->
                            append("#EXTINF:")
                            append((track.duration / 1000L).coerceAtLeast(-1L))
                            append(",")
                            append((track.title + " - " + track.artist).replace("\n", " ").replace("\r", " "))
                            append("\n")
                            append(track.path.ifBlank { track.uri.toString() })
                            append("\n")
                        }
                    }
                    val saved = runCatching {
                        contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) }
                            ?: throw IllegalStateException("Could not open playlist destination.")
                    }.isSuccess
                    Toast.makeText(this@MainActivity, if (saved) "M3U playlist exported" else "Playlist export failed", Toast.LENGTH_SHORT).show()
                }
            }
            val importPlaylistLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    val importedLines = runCatching {
                        contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readLines() }
                            ?: throw IllegalStateException("Could not read playlist.")
                    }.getOrNull()
                    if (importedLines != null) {
                        val entries = importedLines.map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }
                        val resolved = entries.mapNotNull { entry ->
                            tracks.firstOrNull { it.path.equals(entry, true) || it.uri.toString() == entry }
                                ?: run {
                                    val fileName = entry.substringAfterLast('/').substringAfterLast('\\')
                                    val matches = tracks.filter { it.path.substringAfterLast('/').equals(fileName, true) }
                                    matches.singleOrNull()
                                }
                        }
                        if (resolved.isNotEmpty()) {
                            val baseName = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "Imported playlist"
                            var name = baseName
                            var suffix = 2
                            while (name in playlists) { name = "$baseName ($suffix)"; suffix++ }
                            playlists = playlists.toMutableMap().apply { put(name, resolved.map { it.id }.toSet()) }
                            storePlaylists(playlists)
                            Toast.makeText(this@MainActivity, "Imported $name • ${resolved.size} tracks", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MainActivity, "No playlist entries matched music in this library", Toast.LENGTH_LONG).show()
                        }
                    } else Toast.makeText(this@MainActivity, "Could not read this playlist file", Toast.LENGTH_LONG).show()
                }
            }

            val importLyricsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                val targetId = lyricsTargetTrackId
                if (uri != null && targetId != null) {
                    val raw = runCatching {
                        contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(256 * 1024) }
                    }.getOrNull()
                    val parsed = raw?.let { LrcParser.parse(it) }.orEmpty()
                    if (!raw.isNullOrBlank() && parsed.isNotEmpty()) {
                        prefs().edit().putString("lyrics:$targetId", raw).apply()
                        lyricsVersion++
                        Toast.makeText(this@MainActivity, "Imported ${parsed.size} synchronized lyric lines", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "No valid LRC timestamps were found", Toast.LENGTH_LONG).show()
                    }
                }
                lyricsTargetTrackId = null
            }

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
            BackHandler(enabled = settings || studio || visualizer || effects || recap || nowPlaying || (tab == "Playlists" && activePlaylist != null)) {
                when {
                    studio -> studio = false
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
                    studio -> StudioScreen(audioPermissionGranted = audioAllowed, onRequestPermission = { askAudio.launch(Manifest.permission.RECORD_AUDIO) }, onBack = { studio = false })
                    !libraryAllowed -> PermissionPage(
                        onAllow = { askLibrary.launch(readPermission) },
                        onOpenStudio = { studio = true; if (!audioAllowed) askAudio.launch(Manifest.permission.RECORD_AUDIO) }
                    )
                    visualizer -> RetroVisualizerScreen(onBack = { visualizer = false }, audioPermissionGranted = audioAllowed, audioSessionId = prefs().getInt("audio_session_id", 0), player = controller, tracks = tracks)
                    effects -> AudioEffectsScreen(outputRoute = outputRoute, audioSessionId = prefs().getInt("audio_session_id", 0), onBack = { effects = false })
                    recap -> ListeningRecapScreen(tracks = tracks, monthlyCounts = currentMonthPlayCounts(), monthlyListeningMs = currentMonthListeningMs(), listeningStreakDays = currentListeningStreak(), onBack = { recap = false })
                    settings -> SettingsPage(
                        folders = tracks.map { it.folder }.distinct().sorted(), selectedFolders = selectedFolders,
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
                        sleepFadeSeconds = sleepFadeSeconds, onSleepFadeSecondsChanged = { seconds -> sleepFadeSeconds = seconds; prefs().edit().putInt("sleep_fade_seconds", seconds).apply() },
                        onMonthlyRecap = { settings = false; recap = true }, onRescan = { tracks = scanLibrary() }, onBack = { settings = false })

                    nowPlaying -> PlayerPage(controller, tracks, favorites, lyricsRefreshKey = lyricsVersion, onImportLyrics = { id ->
                        lyricsTargetTrackId = id
                        importLyricsLauncher.launch(arrayOf("text/plain", "application/octet-stream", "*/*"))
                    }, onArtistRadio = { id ->
                        tracks.firstOrNull { it.id == id }?.let { seed ->
                            val radio = tracks.filter { it.artist.equals(seed.artist, true) }.shuffled()
                            if (radio.isNotEmpty()) { play(radio, 0); playCountVersion++ }
                        }
                    }, onAlbumShuffle = { id ->
                        tracks.firstOrNull { it.id == id }?.let { seed ->
                            val album = tracks.filter { it.album.equals(seed.album, true) && it.artist.equals(seed.artist, true) }.shuffled()
                            if (album.isNotEmpty()) { play(album, 0); playCountVersion++ }
                        }
                    }, onBack = { nowPlaying = false },
                        onVisualizer = { nowPlaying = false; visualizer = true; if (!audioAllowed) askAudio.launch(Manifest.permission.RECORD_AUDIO) },
                        onFavorite = { id -> favorites = favorites.toMutableSet().apply { if (contains(id)) remove(id) else add(id) }; storeIds("favorites", favorites) },
                        onSleepTimer = { minutes -> startService(Intent(this@MainActivity, PlaybackService::class.java).setAction(PlaybackService.ACTION_SLEEP_TIMER).putExtra(PlaybackService.EXTRA_MINUTES, minutes)) },
                        onSleepTimerEndTrack = { startService(Intent(this@MainActivity, PlaybackService::class.java).setAction(PlaybackService.ACTION_SLEEP_TIMER_END_TRACK)) },
                        onCancelSleepTimer = { startService(Intent(this@MainActivity, PlaybackService::class.java).setAction(PlaybackService.ACTION_CANCEL_SLEEP_TIMER)) })
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
                            "Recently played" -> currentTracks.sortedByDescending { prefs().getLong("last_played:${it.id}", 0L) }
                            "Highest rated" -> currentTracks.sortedWith(compareByDescending<Track> { prefs().getInt("rating:${it.id}", 0) }.thenBy { it.title.lowercase() })
                            "Longest first" -> currentTracks.sortedByDescending { it.duration }
                            "Shortest first" -> currentTracks.sortedBy { it.duration }
                            else -> currentTracks.sortedBy { it.title.lowercase() }
                        }
                        val swipeTabs = librarySections.filter { it in visibleSections }.ifEmpty { listOf("Songs") }
                        Column(Modifier.fillMaxSize().background(Ink).animateContentSize(tween(220)).pointerInput(tab) {
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
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("MUSIC LIBRARY", color = Green.copy(alpha = .8f), fontSize = 9.sp, letterSpacing = 1.6.sp, modifier = Modifier.weight(1f))
                                TextButton(onClick = { studio = true; if (!audioAllowed) askAudio.launch(Manifest.permission.RECORD_AUDIO) }) {
                                    Icon(Icons.Default.Mic, contentDescription = null, tint = Amber)
                                    Spacer(Modifier.width(5.dp))
                                    Text("STUDIO", color = Amber, fontSize = 10.sp, letterSpacing = 1.2.sp)
                                }
                            }
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
                                        TextButton(onClick = { importPlaylistLauncher.launch(arrayOf("audio/x-mpegurl", "application/vnd.apple.mpegurl", "text/plain", "*/*")) }) { Text("IMPORT M3U") }
                                        TextButton(onClick = { pendingTrack = null; playlistDialog = true }) { Icon(Icons.Default.Add, null); Text("NEW") }
                                    }
                                    LazyColumn {
                                        item { Text("SMART MIXES", color = Green, fontSize = 10.sp, letterSpacing = 2.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) }
                                        items(listOf("Most played this month", "Most played all time", "Recently played", "Never played", "Top rated", "Hidden gems", "Favourites", "Quick Listen", "Long Listening", "Short tracks", "Surprise Me")) { name ->
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
                                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        TextButton(onClick = { activePlaylist = null }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.ArrowBack, null); Text("ALL PLAYLISTS") }
                                        TextButton(onClick = {
                                            exportTracks = currentTracks
                                            exportName = (activePlaylist ?: "Analog Playlist").removePrefix("smart:")
                                            exportPlaylistLauncher.launch(exportName.replace(Regex("[^A-Za-z0-9 _-]"), "").ifBlank { "Analog Playlist" } + ".m3u")
                                        }) { Text("EXPORT M3U", color = Amber) }
                                    }
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
                                        val selectedTrack = tracks.firstOrNull { it.id == pendingTrack }
                                        Text("QUEUE ACTIONS", color = Green, fontSize = 9.sp, letterSpacing = 1.5.sp)
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            TextButton(onClick = {
                                                selectedTrack?.let { enqueueTrack(it, playNext = true) }
                                                playlistDialog = false; pendingTrack = null
                                            }, enabled = selectedTrack != null, modifier = Modifier.weight(1f)) { Text("PLAY NEXT", color = Amber, fontSize = 10.sp) }
                                            TextButton(onClick = {
                                                selectedTrack?.let { enqueueTrack(it, playNext = false) }
                                                playlistDialog = false; pendingTrack = null
                                            }, enabled = selectedTrack != null, modifier = Modifier.weight(1f)) { Text("ADD TO QUEUE", color = Amber, fontSize = 10.sp) }
                                        }
                                        Divider(color = Cream.copy(alpha = .12f))
                                        Text("ADD TO PLAYLIST", color = Cream.copy(alpha = .65f), fontSize = 12.sp)
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

    private fun mediaItem(track: Track): MediaItem =
        MediaItem.Builder().setMediaId(track.id.toString()).setUri(track.uri).setMediaMetadata(
            MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist)
                .setAlbumTitle(track.album).setArtworkUri(track.artUri()).build()
        ).build()

    private fun enqueueTrack(track: Track, playNext: Boolean) {
        val c = controller ?: return
        val item = mediaItem(track)
        if (c.mediaItemCount == 0) {
            c.setMediaItem(item)
            c.prepare()
            c.play()
        } else if (playNext) {
            c.addMediaItem((c.currentMediaItemIndex + 1).coerceIn(0, c.mediaItemCount), item)
        } else {
            c.addMediaItem(item)
        }
    }

    private fun play(list: List<Track>, index: Int) {
        val c = controller ?: return
        val selected = list.getOrNull(index) ?: return
        val dayKey = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
        val libraryPrefs = getSharedPreferences("analog_library", Context.MODE_PRIVATE)
        val dailyShuffleDue = libraryPrefs.getString("daily_shuffle_day", null) != dayKey
        val savedOrder = libraryPrefs.getString("daily_shuffle_order", null)
            ?.split(",")?.mapNotNull { it.toLongOrNull() }
        val tracksById = list.associateBy { it.id }
        val restoredOrder = savedOrder?.takeIf { ids ->
            ids.size == list.size && ids.toSet().size == ids.size && ids.all { it in tracksById }
        }?.mapNotNull { tracksById[it] }
        val orderedTracks = when {
            dailyShuffleDue && list.size > 1 -> list.filter { it.id != selected.id }.shuffled().let { listOf(selected) + it }
            restoredOrder != null -> restoredOrder
            else -> list
        }
        if (dailyShuffleDue) libraryPrefs.edit()
            .putString("daily_shuffle_day", dayKey)
            .putString("daily_shuffle_order", orderedTracks.joinToString(",") { it.id.toString() })
            .apply()
        val queueIndex = orderedTracks.indexOfFirst { it.id == selected.id }.coerceAtLeast(0)
        c.setMediaItems(orderedTracks.map(::mediaItem), queueIndex, 0L)
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
                    c.getLong(duration), path.substringBeforeLast("/", "Music").substringAfterLast("/").ifBlank { "Music" }, c.getLong(albumId), path)
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
        val allTime = source.associate { track -> track.id to prefs().getInt("plays:${track.id}", 0) }
        val lastPlayed: (Track) -> Long = { prefs().getLong("last_played:${it.id}", 0L) }
        val rating: (Track) -> Int = { prefs().getInt("rating:${it.id}", 0) }
        val recentCutoff = System.currentTimeMillis() - 30L * 24L * 60L * 60L * 1000L
        return when (name) {
            "Most played this month" -> source.filter { (counts[it.id] ?: 0) > 0 }.sortedByDescending { counts[it.id] ?: 0 }
            "Most played all time" -> source.filter { (allTime[it.id] ?: 0) > 0 }.sortedWith(compareByDescending<Track> { allTime[it.id] ?: 0 }.thenBy { it.title.lowercase() })
            "Recently played" -> source.filter { lastPlayed(it) >= recentCutoff }.sortedByDescending(lastPlayed)
            "Never played" -> source.filter { (allTime[it.id] ?: 0) == 0 }.sortedBy { it.title.lowercase() }
            "Top rated" -> source.filter { rating(it) >= 4 }.sortedWith(compareByDescending<Track> { rating(it) }.thenByDescending(lastPlayed))
            "Hidden gems" -> source.filter { rating(it) >= 4 && (allTime[it.id] ?: 0) in 1..5 }.sortedWith(compareBy<Track> { allTime[it.id] ?: 0 }.thenByDescending(rating))
            "Favourites" -> source.filter { it.id in favorites }.sortedByDescending(lastPlayed)
            "Quick Listen" -> source.filter { it.duration in 1L..240_000L }.sortedByDescending(lastPlayed)
            "Long Listening" -> source.filter { it.duration >= 300_000L }.sortedByDescending(lastPlayed)
            "Short tracks" -> source.filter { it.duration in 1L..180_000L }.sortedBy { it.duration }
            "Surprise Me" -> source.shuffled()
            else -> source.filter { lastPlayed(it) >= recentCutoff }.sortedByDescending(lastPlayed)
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

@Composable private fun PermissionPage(onAllow: () -> Unit, onOpenStudio: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(28.dp)) {
            Text("ANALOG", color = Amber, fontSize = 30.sp, fontWeight = FontWeight.Black, letterSpacing = 5.sp)
            Spacer(Modifier.height(12.dp)); Text("Your music. Your device. No signal required.", color = Cream)
            Spacer(Modifier.height(22.dp)); Button(onClick = onAllow) { Text("ALLOW MUSIC LIBRARY") }
            Spacer(Modifier.height(10.dp)); TextButton(onClick = onOpenStudio) { Text("OPEN RECORDING STUDIO", color = Amber) }
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
            listOf("Title (A–Z)", "Title (Z–A)", "Artist", "Album", "Least played", "Most played", "Recently played", "Highest rated", "Longest first", "Shortest first").forEach { mode ->
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
            decorationBox = { inner -> if (value.isEmpty()) Text("Search songs, artists, albums", color = Cream.copy(alpha = .48f), fontSize = 14.sp); inner() })
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
        Column(Modifier.weight(1f)) { Text(group.title, color = Cream, fontWeight = FontWeight.SemiBold, maxLines = 1); Text(group.subtitle, color = Cream.copy(alpha = .7f), fontSize = 11.sp) }
        Icon(Icons.Default.ChevronRight, null, tint = Green)
    }
}
@Composable private fun SongRow(track: Track, number: Int, favorite: Boolean, onPlay: () -> Unit, onFavorite: () -> Unit, onMore: () -> Unit) {
    val hapticView = LocalView.current
    Row(Modifier.fillMaxWidth().animateContentSize(tween(180)).clickable { hapticView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onPlay() }.padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).background(Panel, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Album, null, tint = Amber.copy(alpha = .35f), modifier = Modifier.size(20.dp)); AsyncImage(model = track.artUri(), contentDescription = track.album, modifier = Modifier.fillMaxSize()); }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) { Text(track.title, color = Cream, fontWeight = FontWeight.SemiBold, maxLines = 1); Text("${track.artist} • ${track.album}", color = Cream.copy(alpha = .7f), fontSize = 11.sp, maxLines = 1) }
        Text(timeText(track.duration), color = Cream.copy(alpha = .45f), fontSize = 10.sp)
        IconButton(onClick = { hapticView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onFavorite() }) { Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (favorite) Amber else Cream.copy(alpha = .5f)) }
        IconButton(onClick = { hapticView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onMore() }) { Icon(Icons.Default.MoreVert, null, tint = Cream.copy(alpha = .6f)) }
    }
}
@Composable private fun MiniPlayer(player: MediaController?, onOpen: () -> Unit) {
    val hapticView = LocalView.current
    val playerContext = LocalContext.current
    var title by remember { mutableStateOf("Choose a track") }
    var artist by remember { mutableStateOf("ANALOG • OFFLINE") }
    var playing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(player) { while (player != null) {
        title = player.mediaMetadata.title?.toString() ?: "Choose a track"
        artist = player.mediaMetadata.artist?.toString() ?: "ANALOG • OFFLINE"
        playing = player.isPlaying
        progress = if (player.duration > 0) player.currentPosition.toFloat() / player.duration else 0f
        delay(if (player.isPlaying) 300L else 1000L)
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
@Composable private fun PlayerPage(player: MediaController?, tracks: List<Track>, favorites: Set<Long>, lyricsRefreshKey: Int, onImportLyrics: (Long) -> Unit, onArtistRadio: (Long) -> Unit, onAlbumShuffle: (Long) -> Unit, onBack: () -> Unit, onVisualizer: () -> Unit, onFavorite: (Long) -> Unit, onSleepTimer: (Int) -> Unit, onSleepTimerEndTrack: () -> Unit, onCancelSleepTimer: () -> Unit) {
    val hapticView = LocalView.current
    val playerContext = LocalContext.current
    var title by remember { mutableStateOf("Nothing playing") }
    var artist by remember { mutableStateOf("Choose a song from your library") }
    var playing by remember { mutableStateOf(false) }
    var duration by remember { mutableFloatStateOf(1f) }
    var position by remember { mutableFloatStateOf(0f) }
    var trackId by remember { mutableStateOf<Long?>(null) }
    var showQueue by remember { mutableStateOf(false) }
    var showQueueSets by remember { mutableStateOf(false) }
    var queueSetName by remember { mutableStateOf("") }
    var queueSetVersion by remember { mutableIntStateOf(0) }
    var customSleepMinutes by remember { mutableStateOf("90") }
    val libraryPrefs = remember(playerContext) { playerContext.getSharedPreferences("analog_library", Context.MODE_PRIVATE) }
    var artAccent by remember { mutableStateOf(DefaultAmber) }
    var dynamicBackground by remember { mutableStateOf(Color(0xFF151713)) }
    val reflectionTransition = rememberInfiniteTransition(label = "artwork-reflection")
    val reflectionProgress by reflectionTransition.animateFloat(
        initialValue = -1.2f, targetValue = 1.8f,
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart),
        label = "reflection-progress"
    )
    val themedBackground by animateColorAsState(dynamicBackground, tween(650), label = "album-background")
    val themedAccent by animateColorAsState(artAccent, tween(500), label = "album-accent")
    val themedPanel = androidx.compose.ui.graphics.lerp(themedBackground, Color.White, .055f)
    val themedText = Cream
    val themedMuted = androidx.compose.ui.graphics.lerp(themedText, themedBackground, .34f)
    var currentQueueIndex by remember { mutableIntStateOf(-1) }
    var shuffleEnabled by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var swipeDistance by remember { mutableFloatStateOf(0f) }
    var loopStartMs by remember(trackId) { mutableStateOf<Long?>(null) }
    var loopEndMs by remember(trackId) { mutableStateOf<Long?>(null) }
    var bookmarkMs by remember(trackId) { mutableLongStateOf(trackId?.let { playerContextBookmark(playerContext, it) } ?: -1L) }
    val savedLyrics = remember(trackId, lyricsRefreshKey) {
        trackId?.let { playerContext.getSharedPreferences("analog_library", Context.MODE_PRIVATE).getString("lyrics:$it", "") }.orEmpty()
    }
    val parsedLyrics = remember(savedLyrics) { LrcParser.parse(savedLyrics) }
    val activeLyricIndex = remember(parsedLyrics, position) { LrcParser.activeIndex(parsedLyrics, position.toLong()) }
    var fileInfo by remember { mutableStateOf("Reading audio file details…") }
    LaunchedEffect(trackId) {
        val uri = tracks.firstOrNull { it.id == trackId }?.uri
        fileInfo = if (uri == null) "LOCAL AUDIO" else withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(playerContext, uri)
                val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()
                val sampleRate = if (Build.VERSION.SDK_INT >= 31) retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() else null
                val bitrateText = bitrate?.let { if (it >= 1_000_000) "%.2f Mbps".format(it / 1_000_000f) else "${it / 1000} kbps" }
                listOfNotNull(bitrateText, sampleRate?.let { "${it / 1000} kHz" }).joinToString("  •  ").ifBlank { "LOCAL AUDIO • DETAILS UNAVAILABLE" }
            } catch (_: Exception) {
                "LOCAL AUDIO • DETAILS UNAVAILABLE"
            } finally {
                runCatching { retriever.release() }
            }
        }
    }
    val syncPlayerState = {
        title = player?.mediaMetadata?.title?.toString() ?: "Nothing playing"
        artist = player?.mediaMetadata?.artist?.toString() ?: "Unknown artist"
        playing = player?.isPlaying ?: false
        duration = (player?.duration ?: 1L).coerceAtLeast(1L).toFloat()
        position = (player?.currentPosition ?: 0L).toFloat().coerceIn(0f, duration)
        trackId = player?.currentMediaItem?.mediaId?.toLongOrNull()
        currentQueueIndex = player?.currentMediaItemIndex ?: -1
        shuffleEnabled = player?.shuffleModeEnabled ?: false
        repeatMode = player?.repeatMode ?: Player.REPEAT_MODE_OFF
    }
    LaunchedEffect(trackId) {
        artAccent = DefaultAmber
        dynamicBackground = Color(0xFF151713)
    }
    DisposableEffect(player, tracks) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) = syncPlayerState()
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { shuffleEnabled = shuffleModeEnabled }
            override fun onRepeatModeChanged(repeatMode: Int) { syncPlayerState() }
            override fun onPlaybackStateChanged(playbackState: Int) = syncPlayerState()
        }
        player?.addListener(listener)
        syncPlayerState()
        onDispose { player?.removeListener(listener) }
    }
    LaunchedEffect(player) {
        while (player != null) {
            duration = player.duration.coerceAtLeast(1L).toFloat()
            position = player.currentPosition.toFloat().coerceIn(0f, duration)
            delay(if (player.isPlaying) 250L else 1000L)
        }
    }
    LaunchedEffect(player, loopStartMs, loopEndMs, trackId) {
        while (player != null && loopStartMs != null && loopEndMs != null) {
            val start = loopStartMs ?: break
            val end = loopEndMs ?: break
            if (player.isPlaying && PlaybackLoop.shouldWrap(player.currentPosition, start, end)) player.seekTo(start)
            delay(40L)
        }
    }
    Column(Modifier.fillMaxSize().background(themedBackground).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp), verticalArrangement = Arrangement.Center) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = themedText) }
            Text("NOW PLAYING", color = themedAccent, fontSize = 12.sp, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = { showQueue = true }) { Icon(Icons.Default.QueueMusic, "Playback queue", tint = themedText) }
            TextButton(onClick = { showQueueSets = true; queueSetVersion++ }) { Text("SETLISTS", color = themedAccent, fontSize = 9.sp, letterSpacing = .8.sp) }
            IconButton(onClick = onVisualizer) { Icon(Icons.Default.GraphicEq, null, tint = themedAccent) }
        }
        Spacer(Modifier.height(20.dp))
        val currentTrack = tracks.firstOrNull { it.id == trackId }
        val previousTrack = if (player != null && player.previousMediaItemIndex >= 0) tracks.firstOrNull { it.id == player.getMediaItemAt(player.previousMediaItemIndex).mediaId.toLongOrNull() } else null
        val nextTrack = if (player != null && player.nextMediaItemIndex >= 0) tracks.firstOrNull { it.id == player.getMediaItemAt(player.nextMediaItemIndex).mediaId.toLongOrNull() } else null
        BoxWithConstraints(
            Modifier.fillMaxWidth().aspectRatio(1f).clipToBounds()
                .pointerInput(trackId) {
                    detectHorizontalDragGestures(onHorizontalDrag = { _, amount -> swipeDistance += amount }, onDragEnd = {
                        if (swipeDistance < -55f) player?.seekToNext()
                        else if (swipeDistance > 55f) player?.seekToPrevious()
                        swipeDistance = 0f
                    }, onDragCancel = { swipeDistance = 0f })
                },
            contentAlignment = Alignment.Center
        ) {
            val sideWidth = maxWidth * .30f
            val centerWidth = maxWidth * .70f
            fun trackAt(index: Int): Track? {
                if (player == null || index !in 0 until player.mediaItemCount) return null
                val id = player.getMediaItemAt(index).mediaId.toLongOrNull() ?: return null
                return tracks.firstOrNull { it.id == id }
            }
            AnimatedContent(
                targetState = currentQueueIndex,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    val direction = if (targetState >= initialState) 1 else -1
                    (slideInHorizontally(
                        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
                        initialOffsetX = { width -> direction * width }
                    ) + fadeIn(tween(180))) togetherWith
                    (slideOutHorizontally(
                        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
                        targetOffsetX = { width -> -direction * width }
                    ) + fadeOut(tween(160)))
                },
                label = "classic-artwork-carousel"
            ) { animatedIndex ->
                val previous = if (animatedIndex == currentQueueIndex && player != null && player.previousMediaItemIndex >= 0) trackAt(player.previousMediaItemIndex) else trackAt(animatedIndex - 1)
                val center = trackAt(animatedIndex) ?: currentTrack
                val next = if (animatedIndex == currentQueueIndex && player != null && player.nextMediaItemIndex >= 0) trackAt(player.nextMediaItemIndex) else trackAt(animatedIndex + 1)
                var previousArtFailed by remember(previous?.albumId) { mutableStateOf(false) }
                var centerArtFailed by remember(center?.albumId) { mutableStateOf(false) }
                var nextArtFailed by remember(next?.albumId) { mutableStateOf(false) }
                Row(
                    Modifier.width(maxWidth * 1.34f).fillMaxHeight().offset(x = -maxWidth * .17f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(maxWidth * .02f)
                ) {
                    Box(
                        Modifier.width(sideWidth).height(sideWidth)
                            .background(themedPanel, RoundedCornerShape(13.dp))
                            .clip(RoundedCornerShape(13.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (previous != null && previous.albumId > 0L && !previousArtFailed) AsyncImage(
                            model = previous.artUri(), contentDescription = "Previous: ${previous.title}",
                            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit, alpha = .48f,
                            onError = { previousArtFailed = true }
                        ) else Icon(Icons.Default.Album, null, tint = themedText.copy(alpha = .22f))
                    }
                    BoxWithConstraints(
                        Modifier.width(centerWidth).height(centerWidth)
                            .shadow(24.dp, RoundedCornerShape(17.dp), clip = false)
                            .background(themedPanel, RoundedCornerShape(17.dp))
                            .clip(RoundedCornerShape(17.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (center != null && center.albumId > 0L && !centerArtFailed) AsyncImage(
                            model = center.artUri(), contentDescription = center.album,
                            modifier = Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit,
                            onError = { centerArtFailed = true },
                            onSuccess = { state ->
                                val bitmap = (state.result.drawable as? BitmapDrawable)?.bitmap
                                if (bitmap != null) Palette.from(bitmap).generate { palette ->
                                    val accent = palette?.vibrantSwatch?.rgb ?: palette?.lightVibrantSwatch?.rgb ?: palette?.mutedSwatch?.rgb
                                    val background = palette?.darkMutedSwatch?.rgb ?: palette?.darkVibrantSwatch?.rgb
                                    if (accent != null) artAccent = Color(accent)
                                    if (background != null) dynamicBackground = androidx.compose.ui.graphics.lerp(Color(background), Ink, .22f)
                                }
                            }
                        ) else Icon(Icons.Default.Album, null, tint = themedAccent, modifier = Modifier.size(104.dp))
                        Box(
                            Modifier.fillMaxWidth(.46f).fillMaxHeight().align(Alignment.CenterStart)
                                .graphicsLayer { translationX = maxWidth.toPx() * reflectionProgress }
                                .background(Brush.linearGradient(listOf(
                                    Color.Transparent, Color.White.copy(alpha = .025f),
                                    Color.White.copy(alpha = .23f), Color.White.copy(alpha = .055f),
                                    Color.Transparent
                                )))
                        )
                        Box(Modifier.fillMaxWidth().fillMaxHeight(.24f).align(Alignment.TopCenter)
                            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = .16f), Color.White.copy(alpha = .035f), Color.Transparent))))
                        Box(Modifier.fillMaxWidth(.72f).height(1.dp).align(Alignment.TopCenter)
                            .background(Color.White.copy(alpha = .17f)))
                    }
                    Box(
                        Modifier.width(sideWidth).fillMaxHeight(.72f)
                            .background(Color(0xFF252923), RoundedCornerShape(13.dp))
                            .clip(RoundedCornerShape(13.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (next != null && next.albumId > 0L && !nextArtFailed) AsyncImage(
                            model = next.artUri(), contentDescription = "Next: ${next.title}",
                            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = .48f,
                            onError = { nextArtFailed = true }
                        ) else Icon(Icons.Default.Album, null, tint = themedText.copy(alpha = .22f))
                    }
                }
            }
        }
        Spacer(Modifier.height(26.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(title, color = themedText, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2); Text(artist, color = themedMuted, fontSize = 13.sp); Text(fileInfo, color = themedAccent.copy(alpha = .88f), fontSize = 9.sp, letterSpacing = .6.sp, maxLines = 1) }
            IconButton(onClick = { hapticView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); trackId?.let(onFavorite) }) { Icon(if (trackId in favorites) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = themedAccent) }
        }
        Slider(value = position, onValueChange = { position = it }, onValueChangeFinished = { player?.seekTo(position.toLong()) }, valueRange = 0f..duration, colors = SliderDefaults.colors(thumbColor = themedAccent, activeTrackColor = themedAccent, inactiveTrackColor = themedMuted.copy(alpha = .30f)))
        Row { Text(timeText(position.toLong()), color = themedMuted, fontSize = 10.sp); Spacer(Modifier.weight(1f)); Text(timeText(duration.toLong()), color = themedMuted, fontSize = 10.sp) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { player?.let { it.seekTo((it.currentPosition - 10_000L).coerceAtLeast(0L)) } }) {
                Icon(Icons.Default.Replay10, null, tint = themedAccent)
                Text(" 10 SEC", color = themedMuted, fontSize = 9.sp)
            }
            Spacer(Modifier.width(24.dp))
            TextButton(onClick = { player?.let { p -> p.seekTo((p.currentPosition + 30_000L).coerceAtMost(p.duration.coerceAtLeast(0L))) } }) {
                Text("30 SEC ", color = themedMuted, fontSize = 9.sp)
                Icon(Icons.Default.Forward30, null, tint = themedAccent)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { hapticView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); player?.shuffleModeEnabled = !(player?.shuffleModeEnabled ?: false) }) { Icon(Icons.Default.Shuffle, null, tint = themedAccent) }
            IconButton(onClick = { hapticView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); player?.seekToPrevious() }, modifier = Modifier.size(54.dp)) { Icon(Icons.Default.SkipPrevious, null, tint = themedText, modifier = Modifier.size(36.dp)) }
            FilledIconButton(onClick = { hapticView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); if (playing) player?.pause() else player?.play() }, modifier = Modifier.size(68.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = themedAccent)) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = themedBackground, modifier = Modifier.size(38.dp))
            }
            IconButton(onClick = { hapticView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); player?.seekToNext() }, modifier = Modifier.size(54.dp)) { Icon(Icons.Default.SkipNext, null, tint = Cream, modifier = Modifier.size(36.dp)) }
            IconButton(onClick = { hapticView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); player?.repeatMode = ((player?.repeatMode ?: 0) + 1) % 3 }) { Icon(Icons.Default.Repeat, null, tint = themedAccent) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text(if (shuffleEnabled) "SHUFFLE ON" else "SHUFFLE OFF", color = if (shuffleEnabled) themedAccent else themedMuted, fontSize = 9.sp, letterSpacing = 1.sp)
            Spacer(Modifier.width(18.dp))
            Text("REPEAT • " + when (repeatMode) { Player.REPEAT_MODE_ONE -> "ONE"; Player.REPEAT_MODE_ALL -> "ALL"; else -> "OFF" }, color = if (repeatMode == Player.REPEAT_MODE_OFF) themedMuted else themedAccent, fontSize = 9.sp, letterSpacing = 1.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { loopStartMs = player?.currentPosition ?: 0L; loopEndMs = null }, modifier = Modifier.weight(1f), border = androidx.compose.foundation.BorderStroke(1.dp, themedAccent.copy(alpha = .55f))) {
                Text(if (loopStartMs == null) "A • SET START" else "A • ${timeText(loopStartMs!!)}", color = themedAccent, fontSize = 9.sp, maxLines = 1)
            }
            OutlinedButton(onClick = { val now = player?.currentPosition ?: 0L; val start = loopStartMs; if (start != null && PlaybackLoop.isValidRange(start, now)) loopEndMs = now }, enabled = loopStartMs != null, modifier = Modifier.weight(1f), border = androidx.compose.foundation.BorderStroke(1.dp, themedAccent.copy(alpha = .55f))) {
                Text(if (loopEndMs == null) "B • SET END" else "B • ${timeText(loopEndMs!!)}", color = themedAccent, fontSize = 9.sp, maxLines = 1)
            }
            TextButton(onClick = { loopStartMs = null; loopEndMs = null }) { Text("CLEAR", color = themedMuted, fontSize = 9.sp) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { trackId?.let(onArtistRadio) }, enabled = trackId != null, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.GraphicEq, null, tint = themedAccent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("ARTIST RADIO", color = themedText, fontSize = 9.sp, maxLines = 1)
            }
            OutlinedButton(onClick = { trackId?.let(onAlbumShuffle) }, enabled = trackId != null, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Shuffle, null, tint = themedAccent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("SHUFFLE ALBUM", color = themedText, fontSize = 9.sp, maxLines = 1)
            }
        }
        Column(Modifier.fillMaxWidth().background(themedPanel, RoundedCornerShape(14.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(8.dp))
                Text("SLEEP TIMER", color = themedAccent, fontSize = 10.sp, letterSpacing = 1.2.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onCancelSleepTimer) { Text("CANCEL", color = themedMuted, fontSize = 9.sp) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(15, 30, 45, 60).forEach { minutes ->
                    OutlinedButton(onClick = { onSleepTimer(minutes) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp)) {
                        Text("$minutes MIN", color = themedText, fontSize = 9.sp, maxLines = 1)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = customSleepMinutes,
                    onValueChange = { customSleepMinutes = it.filter(Char::isDigit).take(3) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("Custom minutes") },
                    textStyle = androidx.compose.ui.text.TextStyle(color = themedText, fontSize = 12.sp)
                )
                Button(onClick = { customSleepMinutes.toIntOrNull()?.takeIf { it in 1..720 }?.let(onSleepTimer) },
                    enabled = customSleepMinutes.toIntOrNull() in 1..720) { Text("SET", fontSize = 10.sp) }
            }
            OutlinedButton(onClick = onSleepTimerEndTrack, modifier = Modifier.fillMaxWidth()) {
                Text("STOP AFTER CURRENT TRACK", color = themedAccent, fontSize = 9.sp)
            }
        }
        PlayerJournalPanel(trackId = trackId, track = tracks.firstOrNull { it.id == trackId }, accent = themedAccent, panel = themedPanel, textColor = themedText, muted = themedMuted)
        SyncedLyricsPanel(lines = parsedLyrics, activeIndex = activeLyricIndex, accent = themedAccent, panel = themedPanel, textColor = themedText, muted = themedMuted, onImport = { trackId?.let(onImportLyrics) }, enabled = trackId != null)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                val id = trackId ?: return@TextButton
                bookmarkMs = player?.currentPosition ?: 0L
                playerContext.getSharedPreferences("analog_library", Context.MODE_PRIVATE).edit().putLong("bookmark:$id", bookmarkMs).apply()
            }, modifier = Modifier.weight(1f)) { Text("SAVE POSITION", color = themedAccent, fontSize = 9.sp) }
            TextButton(onClick = { val target = bookmarkMs; if (target >= 0L && target < (player?.duration ?: 0L)) player?.seekTo(target) }, enabled = bookmarkMs >= 0L, modifier = Modifier.weight(1f)) {
                Text(if (bookmarkMs >= 0L) "RESUME ${timeText(bookmarkMs)}" else "NO BOOKMARK", color = themedMuted, fontSize = 9.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
    }
    if (showQueueSets) {
        val savedNames = remember(queueSetVersion) {
            libraryPrefs.getStringSet("saved_queue_set_names", emptySet()).orEmpty().toList().sorted()
        }
        AlertDialog(
            onDismissRequest = { showQueueSets = false },
            containerColor = themedBackground,
            title = { Text("SAVED SETLISTS", color = themedAccent, fontSize = 14.sp, letterSpacing = 1.4.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Save and recall complete playback queues from your local library.", color = themedMuted, fontSize = 11.sp)
                    OutlinedTextField(value = queueSetName, onValueChange = { queueSetName = it.take(36) }, singleLine = true,
                        label = { Text("Setlist name") }, placeholder = { Text("Late night listening") })
                    Button(
                        onClick = {
                            val name = queueSetName.trim()
                            val current = player
                            if (name.isNotEmpty() && current != null && current.mediaItemCount > 0) {
                                val ids = (0 until current.mediaItemCount).mapNotNull { current.getMediaItemAt(it).mediaId.toLongOrNull() }
                                if (ids.isNotEmpty()) {
                                    val names = libraryPrefs.getStringSet("saved_queue_set_names", emptySet()).orEmpty().toMutableSet()
                                    names.add(name)
                                    libraryPrefs.edit().putStringSet("saved_queue_set_names", names)
                                        .putString("saved_queue_set:$name", ids.joinToString(",")).apply()
                                    queueSetName = ""
                                    queueSetVersion++
                                }
                            }
                        },
                        enabled = !queueSetName.isBlank() && (player?.mediaItemCount ?: 0) > 0,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("SAVE CURRENT QUEUE") }
                    Divider(color = themedMuted.copy(alpha = .2f))
                    if (savedNames.isEmpty()) Text("No saved setlists yet.", color = themedMuted, fontSize = 12.sp)
                    else LazyColumn(Modifier.heightIn(max = 260.dp)) {
                        items(savedNames) { name ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(name, color = themedText, maxLines = 1)
                                    val count = libraryPrefs.getString("saved_queue_set:$name", "").orEmpty().split(",").count { it.toLongOrNull() != null }
                                    Text("$count tracks", color = themedMuted, fontSize = 9.sp)
                                }
                                TextButton(onClick = {
                                    val ids = libraryPrefs.getString("saved_queue_set:$name", "").orEmpty()
                                        .split(",").mapNotNull { it.toLongOrNull() }
                                    val byId = tracks.associateBy { it.id }
                                    val restored = ids.mapNotNull { byId[it] }
                                    if (restored.isNotEmpty()) {
                                        player?.setMediaItems(restored.map { track ->
                                            MediaItem.Builder().setMediaId(track.id.toString()).setUri(track.uri).setMediaMetadata(
                                                MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).setAlbumTitle(track.album).setArtworkUri(track.artUri()).build()
                                            ).build()
                                        }, 0, 0L)
                                        player?.prepare()
                                        player?.play()
                                        showQueueSets = false
                                    }
                                }) { Text("LOAD", color = themedAccent, fontSize = 9.sp) }
                                TextButton(onClick = {
                                    val names = libraryPrefs.getStringSet("saved_queue_set_names", emptySet()).orEmpty().toMutableSet()
                                    names.remove(name)
                                    libraryPrefs.edit().putStringSet("saved_queue_set_names", names).remove("saved_queue_set:$name").apply()
                                    queueSetVersion++
                                }) { Text("DELETE", color = themedMuted, fontSize = 9.sp) }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showQueueSets = false }) { Text("DONE") } }
        )
    }
    if (showQueue) {
        AlertDialog(
            onDismissRequest = { showQueue = false },
            containerColor = themedBackground,
            title = { Text("PLAYBACK QUEUE", color = themedAccent, fontSize = 14.sp, letterSpacing = 1.5.sp) },
            text = {
                if (player == null || player.mediaItemCount == 0) {
                    Text("The queue is empty.", color = themedText)
                } else {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(player.mediaItemCount) { index ->
                            val item = player.getMediaItemAt(index)
                            Row(Modifier.fillMaxWidth().clickable { player.seekToDefaultPosition(index); showQueue = false }.padding(vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(if (index == player.currentMediaItemIndex) "▶" else "${index + 1}", color = themedAccent, modifier = Modifier.width(30.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(item.mediaMetadata.title?.toString() ?: "Unknown title", color = themedText, maxLines = 1)
                                    Text(item.mediaMetadata.artist?.toString() ?: "Unknown artist", color = themedMuted, fontSize = 10.sp)
                                }
                                IconButton(onClick = { if (index > 0) player.moveMediaItem(index, index - 1) }, enabled = index > 0, modifier = Modifier.size(34.dp)) {
                                    Icon(Icons.Default.KeyboardArrowUp, "Move track up", tint = if (index > 0) themedAccent else themedMuted.copy(alpha = .25f))
                                }
                                IconButton(onClick = { if (index < player.mediaItemCount - 1) player.moveMediaItem(index, index + 1) }, enabled = index < player.mediaItemCount - 1, modifier = Modifier.size(34.dp)) {
                                    Icon(Icons.Default.KeyboardArrowDown, "Move track down", tint = if (index < player.mediaItemCount - 1) themedAccent else themedMuted.copy(alpha = .25f))
                                }
                                IconButton(onClick = { player.removeMediaItem(index) }, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.Close, "Remove from queue", tint = themedMuted) }
                            }
                        }
                    }
                }
            },

            confirmButton = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { player?.clearMediaItems() }) { Text("CLEAR QUEUE", color = themedMuted) }
                    TextButton(onClick = { showQueue = false }) { Text("DONE") }
                }
            }
        )
    }
}

@Composable
private fun PlayerJournalPanel(trackId: Long?, track: Track?, accent: Color, panel: Color, textColor: Color, muted: Color) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("analog_library", Context.MODE_PRIVATE) }
    var rating by remember(trackId) { mutableIntStateOf(trackId?.let { prefs.getInt("rating:${it}", 0) } ?: 0) }
    var note by remember(trackId) { mutableStateOf(trackId?.let { prefs.getString("journal:${it}", "") } ?: "") }
    var mood by remember(trackId) { mutableStateOf(trackId?.let { prefs.getString("mood:${it}", "Unsorted") } ?: "Unsorted") }
    var expanded by remember(trackId) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 14.dp).background(panel, RoundedCornerShape(16.dp)).border(1.dp, accent.copy(alpha = .22f), RoundedCornerShape(16.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("LISTENING JOURNAL", color = accent, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.8.sp)
                Text("Personal ratings, moods and track notes", color = muted, fontSize = 9.sp)
            }
            TextButton(onClick = { expanded = !expanded }, enabled = trackId != null) { Text(if (expanded) "COLLAPSE" else "OPEN", color = accent, fontSize = 9.sp) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("YOUR RATING", color = muted, fontSize = 9.sp, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
            (1..5).forEach { star ->
                IconButton(onClick = {
                    rating = if (rating == star) 0 else star
                    trackId?.let { prefs.edit().putInt("rating:${it}", rating).apply() }
                }, enabled = trackId != null, modifier = Modifier.size(30.dp)) {
                    Icon(if (star <= rating) Icons.Default.Star else Icons.Default.StarBorder, null, tint = if (star <= rating) accent else muted.copy(alpha = .5f), modifier = Modifier.size(19.dp))
                }
            }
        }
        Text("LISTENING MOOD", color = muted, fontSize = 9.sp, letterSpacing = 1.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            listOf("Focus", "Calm", "Energy", "Nostalgia").forEach { label ->
                FilterChip(selected = mood == label, onClick = {
                    mood = label
                    trackId?.let { prefs.edit().putString("mood:${it}", label).apply() }
                }, enabled = trackId != null, label = { Text(label, fontSize = 9.sp) })
            }
        }
        if (expanded) {
            OutlinedTextField(value = note, onValueChange = { note = it }, enabled = trackId != null,
                modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5,
                label = { Text("Listening notes") }, placeholder = { Text("What stands out in this track?") })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(track?.let { "${it.album} • ${it.folder}" } ?: "No track selected", color = muted, fontSize = 9.sp, modifier = Modifier.weight(1f), maxLines = 1)
                Button(onClick = { trackId?.let { prefs.edit().putString("journal:${it}", note).putString("mood:${it}", mood).putInt("rating:${it}", rating).apply() } }, enabled = trackId != null) { Text("SAVE NOTES", fontSize = 9.sp) }
            }
        }
    }
}

@Composable
private fun SyncedLyricsPanel(
    lines: List<LyricLine>,
    activeIndex: Int,
    accent: Color,
    panel: Color,
    textColor: Color,
    muted: Color,
    onImport: () -> Unit,
    enabled: Boolean
) {
    Column(
        Modifier.fillMaxWidth().background(panel, RoundedCornerShape(16.dp))
            .border(1.dp, accent.copy(alpha = .22f), RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("SYNCHRONIZED LYRICS", color = accent, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.6.sp)
                Text(if (lines.isEmpty()) "Import a timestamped .lrc file for karaoke-style tracking" else "${lines.size} TIMED LINES • LRC", color = muted, fontSize = 9.sp)
            }
            TextButton(onClick = onImport, enabled = enabled) { Text(if (lines.isEmpty()) "IMPORT LRC" else "REPLACE", color = accent, fontSize = 9.sp) }
        }
        if (lines.isEmpty()) {
            Text("No synchronized lyrics attached to this track.", color = muted, fontSize = 12.sp)
        } else {
            val first = if (activeIndex < 0) 0 else (activeIndex - 1).coerceAtLeast(0)
            val last = (if (activeIndex < 0) 2 else activeIndex + 2).coerceAtMost(lines.lastIndex)
            Column(verticalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth()) {
                for (index in first..last) {
                    Text(
                        lines[index].text.ifBlank { "♪" },
                        color = if (index == activeIndex) accent else muted.copy(alpha = if (index < activeIndex) .55f else .82f),
                        fontSize = if (index == activeIndex) 17.sp else 13.sp,
                        fontWeight = if (index == activeIndex) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 2
                    )
                }
            }
            if (activeIndex < 0) Text("Lyrics will follow the playback position.", color = muted, fontSize = 9.sp)
        }
    }
}

private fun playerContextBookmark(context: Context, trackId: Long): Long =
    context.getSharedPreferences("analog_library", Context.MODE_PRIVATE).getLong("bookmark:$trackId", -1L)

private fun timeText(ms: Long): String { val sec = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(sec / 60, sec % 60) }
