package com.mrjdm2007.analog

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

private val Ink = Color(0xFF10110F)
private val Panel = Color(0xFF1A1C18)
private val Cream = Color(0xFFE8E1CF)
private val Amber = Color(0xFFFFB45B)
private val Green = Color(0xFFB5D69B)

data class Track(val id: Long, val title: String, val artist: String, val album: String, val uri: Uri, val duration: Long, val folder: String)
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

            LaunchedEffect(libraryAllowed) { if (libraryAllowed) tracks = scanLibrary() }
            MaterialTheme(colorScheme = darkColorScheme(primary = Amber, onPrimary = Ink, background = Ink, surface = Panel, onBackground = Cream, onSurface = Cream, secondary = Green)) {
                when {
                    !libraryAllowed -> PermissionPage { askLibrary.launch(readPermission) }
                    visualizer -> RetroVisualizerScreen(onBack = { visualizer = false }, audioPermissionGranted = audioAllowed)
                    effects -> AudioEffectsScreen(onBack = { effects = false })
                    nowPlaying -> PlayerPage(controller, tracks, favorites, onBack = { nowPlaying = false },
                        onVisualizer = { nowPlaying = false; visualizer = true; if (!audioAllowed) askAudio.launch(Manifest.permission.RECORD_AUDIO) },
                        onFavorite = { id -> favorites = favorites.toMutableSet().apply { if (contains(id)) remove(id) else add(id) }; storeIds("favorites", favorites) })
                    else -> {
                        val currentTracks = when (tab) {
                            "Favorites" -> tracks.filter { it.id in favorites }
                            "Playlists" -> activePlaylist?.let { name -> tracks.filter { it.id in (playlists[name] ?: emptySet()) } } ?: emptyList()
                            else -> tracks
                        }.filter { search.isBlank() || it.title.contains(search, true) || it.artist.contains(search, true) || it.album.contains(search, true) }
                        Column(Modifier.fillMaxSize().background(Ink)) {
                            TopHeader(onVisualizer = {
                                visualizer = true
                                if (!audioAllowed) askAudio.launch(Manifest.permission.RECORD_AUDIO)
                            }, onEffects = { effects = true })
                            SearchBox(search) { search = it }
                            val tabs = listOf("Songs", "Albums", "Artists", "Folders", "Favorites", "Playlists")
                            ScrollableTabRow(selectedTabIndex = tabs.indexOf(tab).coerceAtLeast(0), containerColor = Ink, contentColor = Amber, edgePadding = 12.dp) {
                                tabs.forEach { name -> Tab(selected = tab == name, onClick = { tab = name; activePlaylist = null }, text = { Text(name, fontSize = 11.sp) }) }
                            }
                            if (tab in listOf("Albums", "Artists", "Folders")) {
                                val groups = when (tab) {
                                    "Albums" -> tracks.groupBy { it.album }
                                    "Artists" -> tracks.groupBy { it.artist }
                                    else -> tracks.groupBy { it.folder }
                                }.map { (name, list) -> Group(name, "${list.size} tracks • ${list.firstOrNull()?.artist ?: "Local files"}", list) }
                                    .filter { search.isBlank() || it.title.contains(search, true) }
                                    .sortedBy { it.title.lowercase() }
                                LazyColumn(Modifier.weight(1f)) {
                                    items(groups) { group -> GroupRow(group) { play(group.tracks, 0) } }
                                }
                            } else if (tab == "Playlists" && activePlaylist == null) {
                                Column(Modifier.weight(1f)) {
                                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("YOUR PLAYLISTS", color = Green, fontSize = 10.sp, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
                                        TextButton(onClick = { pendingTrack = null; playlistDialog = true }) { Icon(Icons.Default.Add, null); Text("NEW") }
                                    }
                                    LazyColumn {
                                        items(playlists.keys.sorted()) { name ->
                                            GroupRow(Group(name, "${playlists[name]?.size ?: 0} saved tracks", emptyList())) { activePlaylist = name }
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
                                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp)) {
                                        itemsIndexed(currentTracks, key = { _, t -> t.id }) { i, track ->
                                            SongRow(track, i + 1, track.id in favorites,
                                                onPlay = { play(currentTracks, i) },
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
        c.setMediaItems(list.map { track ->
            MediaItem.Builder().setUri(track.uri).setMediaMetadata(MediaMetadata.Builder()
                .setTitle(track.title).setArtist(track.artist).setAlbumTitle(track.album).build()).build()
        }, index, 0L)
        c.prepare(); c.play()
    }

    private fun scanLibrary(): List<Track> {
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val columns = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATA)
        val found = mutableListOf<Track>()
        contentResolver.query(uri, columns, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val title = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val album = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val duration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val data = c.getColumnIndex(MediaStore.Audio.Media.DATA)
            while (c.moveToNext()) {
                val path = if (data >= 0) c.getString(data).orEmpty() else ""
                found += Track(c.getLong(id), c.getString(title) ?: "Unknown title", c.getString(artist) ?: "Unknown artist",
                    c.getString(album) ?: "Unknown album", Uri.withAppendedPath(uri, c.getLong(id).toString()),
                    c.getLong(duration), path.substringBeforeLast("/", "Music").substringAfterLast("/").ifBlank { "Music" })
            }
        }
        return found
    }

    private fun prefs() = getSharedPreferences("analog_library", Context.MODE_PRIVATE)
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
@Composable private fun TopHeader(onVisualizer: () -> Unit, onEffects: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 22.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ANALOG", color = Amber, fontSize = 25.sp, fontWeight = FontWeight.Black, letterSpacing = 5.sp)
            Spacer(Modifier.weight(1f)); IconButton(onClick = onEffects) { Icon(Icons.Default.Tune, "Audio effects", tint = Cream) }; IconButton(onClick = onVisualizer) { Icon(Icons.Default.GraphicEq, "Visualizer", tint = Green) }
        }
        Text("LOCAL MUSIC SYSTEM", color = Cream.copy(alpha = .48f), fontSize = 9.sp, letterSpacing = 2.sp)
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
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).background(Panel, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Album, null, tint = Amber) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) { Text(group.title, color = Cream, fontWeight = FontWeight.SemiBold, maxLines = 1); Text(group.subtitle, color = Cream.copy(alpha = .5f), fontSize = 11.sp) }
        Icon(Icons.Default.ChevronRight, null, tint = Green)
    }
}
@Composable private fun SongRow(track: Track, number: Int, favorite: Boolean, onPlay: () -> Unit, onFavorite: () -> Unit, onMore: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(number.toString().padStart(2, '0'), color = Amber.copy(alpha = .7f), fontSize = 11.sp, modifier = Modifier.width(32.dp))
        Column(Modifier.weight(1f)) { Text(track.title, color = Cream, fontWeight = FontWeight.SemiBold, maxLines = 1); Text("${track.artist} • ${track.album}", color = Cream.copy(alpha = .48f), fontSize = 11.sp, maxLines = 1) }
        Text(timeText(track.duration), color = Cream.copy(alpha = .45f), fontSize = 10.sp)
        IconButton(onClick = onFavorite) { Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (favorite) Amber else Cream.copy(alpha = .5f)) }
        IconButton(onClick = onMore) { Icon(Icons.Default.MoreVert, null, tint = Cream.copy(alpha = .6f)) }
    }
}
@Composable private fun MiniPlayer(player: MediaController?, onOpen: () -> Unit) {
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
            IconButton(onClick = { player?.seekToPrevious() }) { Icon(Icons.Default.SkipPrevious, null, tint = Cream) }
            IconButton(onClick = { if (playing) player?.pause() else player?.play() }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Amber) }
            IconButton(onClick = { player?.seekToNext() }) { Icon(Icons.Default.SkipNext, null, tint = Cream) }
        }
    }
}
@Composable private fun PlayerPage(player: MediaController?, tracks: List<Track>, favorites: Set<Long>, onBack: () -> Unit, onVisualizer: () -> Unit, onFavorite: (Long) -> Unit) {
    var title by remember { mutableStateOf("Nothing playing") }
    var artist by remember { mutableStateOf("Choose a song from your library") }
    var playing by remember { mutableStateOf(false) }
    var duration by remember { mutableFloatStateOf(1f) }
    var position by remember { mutableFloatStateOf(0f) }
    var trackId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(player) { while (player != null) {
        title = player.mediaMetadata.title?.toString() ?: "Nothing playing"
        artist = player.mediaMetadata.artist?.toString() ?: "Unknown artist"
        playing = player.isPlaying
        duration = player.duration.coerceAtLeast(1).toFloat()
        position = player.currentPosition.toFloat().coerceIn(0f, duration)
        trackId = tracks.firstOrNull { it.title == title && it.artist == artist }?.id
        delay(250)
    } }
    Column(Modifier.fillMaxSize().background(Ink).padding(horizontal = 22.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = Cream) }
            Text("NOW PLAYING", color = Amber, fontSize = 12.sp, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = onVisualizer) { Icon(Icons.Default.GraphicEq, null, tint = Green) }
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Panel, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Album, null, tint = Amber, modifier = Modifier.size(104.dp))
                Text("A N A L O G", color = Green, fontSize = 10.sp, letterSpacing = 3.sp)
            }
        }
        Spacer(Modifier.height(26.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(title, color = Cream, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2); Text(artist, color = Cream.copy(alpha = .55f), fontSize = 13.sp) }
            IconButton(onClick = { trackId?.let(onFavorite) }) { Icon(if (trackId in favorites) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = Amber) }
        }
        Slider(value = position, onValueChange = { position = it }, onValueChangeFinished = { player?.seekTo(position.toLong()) }, valueRange = 0f..duration, colors = SliderDefaults.colors(thumbColor = Amber, activeTrackColor = Amber))
        Row { Text(timeText(position.toLong()), color = Cream.copy(alpha = .5f), fontSize = 10.sp); Spacer(Modifier.weight(1f)); Text(timeText(duration.toLong()), color = Cream.copy(alpha = .5f), fontSize = 10.sp) }
        Row(Modifier.fillMaxWidth().padding(vertical = 22.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { player?.shuffleModeEnabled = !(player?.shuffleModeEnabled ?: false) }) { Icon(Icons.Default.Shuffle, null, tint = Green) }
            IconButton(onClick = { player?.seekToPrevious() }, modifier = Modifier.size(54.dp)) { Icon(Icons.Default.SkipPrevious, null, tint = Cream, modifier = Modifier.size(36.dp)) }
            FilledIconButton(onClick = { if (playing) player?.pause() else player?.play() }, modifier = Modifier.size(68.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Amber)) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Ink, modifier = Modifier.size(38.dp))
            }
            IconButton(onClick = { player?.seekToNext() }, modifier = Modifier.size(54.dp)) { Icon(Icons.Default.SkipNext, null, tint = Cream, modifier = Modifier.size(36.dp)) }
            IconButton(onClick = { player?.repeatMode = ((player?.repeatMode ?: 0) + 1) % 3 }) { Icon(Icons.Default.Repeat, null, tint = Green) }
        }
        Spacer(Modifier.weight(1f))
    }
}
private fun timeText(ms: Long): String { val sec = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(sec / 60, sec % 60) }
