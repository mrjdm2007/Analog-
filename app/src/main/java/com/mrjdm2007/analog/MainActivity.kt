package com.mrjdm2007.analog

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.audiofx.Visualizer
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor
import kotlin.math.max

private val Ink = Color(0xFF10110F)
private val Panel = Color(0xFF1A1C18)
private val Cream = Color(0xFFE8E1CF)
private val Amber = Color(0xFFFFB45B)
private val Green = Color(0xFFB5D69B)

data class Track(val id: Long, val title: String, val artist: String, val album: String, val uri: Uri, val duration: Long)

class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller by mutableStateOf<MediaController?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture?.addListener({
            runCatching { controller = controllerFuture?.get() }
        }, ContextCompat.getMainExecutor(this))

        setContent {
            var granted by remember {
                mutableStateOf(ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED)
            }
            val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
            var tracks by remember { mutableStateOf(emptyList<Track>()) }
            var selectedTab by remember { mutableStateOf("Songs") }
            LaunchedEffect(granted) { if (granted) tracks = loadTracks() }
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Amber, onPrimary = Ink, background = Ink, surface = Panel,
                onBackground = Cream, onSurface = Cream, secondary = Green
            )) {
                if (!granted) {
                    Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(28.dp)) {
                            Text("ANALOG", color = Amber, fontSize = 30.sp, fontWeight = FontWeight.Black, letterSpacing = 5.sp)
                            Spacer(Modifier.height(12.dp))
                            Text("Your music. Your device. No signal required.", color = Cream)
                            Spacer(Modifier.height(22.dp))
                            Button(onClick = { permission.launch(Manifest.permission.READ_MEDIA_AUDIO) }) { Text("ALLOW MUSIC LIBRARY") }
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize().background(Ink)) {
                        Header()
                        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            listOf("Songs", "Albums", "Artists").forEach { tab ->
                                Text(tab, color = if (selectedTab == tab) Amber else Cream.copy(alpha = .55f),
                                    fontWeight = FontWeight.Bold, modifier = Modifier.clickable { selectedTab = tab }.padding(vertical = 12.dp))
                            }
                        }
                        if (tracks.isEmpty()) {
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text("No local audio found", color = Cream.copy(alpha = .6f))
                            }
                        } else {
                            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
                                itemsIndexed(tracks) { index, track ->
                                    TrackRow(track, index + 1) {
                                        val c = controller ?: return@TrackRow
                                        c.setMediaItems(tracks.map { t ->
                                            MediaItem.Builder().setUri(t.uri).setMediaMetadata(
                                                MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).setAlbumTitle(t.album).build()
                                            ).build()
                                        }, index, 0L)
                                        c.prepare()
                                        c.play()
                                    }
                                }
                            }
                        }
                        MiniPlayer(controller)
                    }
                }
            }
        }
    }

    private fun loadTracks(): List<Track> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION)
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sort = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        val result = mutableListOf<Track>()
        contentResolver.query(collection, projection, selection, null, sort)?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (cursor.moveToNext()) {
                val mediaId = cursor.getLong(id)
                result += Track(mediaId, cursor.getString(title) ?: "Unknown title", cursor.getString(artist) ?: "Unknown artist",
                    cursor.getString(album) ?: "Unknown album", Uri.withAppendedPath(collection, mediaId.toString()), cursor.getLong(duration))
            }
        }
        return result
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onDestroy()
    }
}

@Composable
private fun Header() {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ANALOG", color = Amber, fontSize = 25.sp, fontWeight = FontWeight.Black, letterSpacing = 5.sp)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Default.GraphicEq, contentDescription = "Visualizer", tint = Green)
        }
        Text("LOCAL MUSIC SYSTEM", color = Cream.copy(alpha = .48f), fontSize = 10.sp, letterSpacing = 2.5.sp)
    }
}

@Composable
private fun TrackRow(track: Track, number: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(number.toString().padStart(2, '0'), color = Amber.copy(alpha = .7f), fontSize = 12.sp, modifier = Modifier.width(34.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, color = Cream, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(track.artist, color = Cream.copy(alpha = .48f), fontSize = 12.sp, maxLines = 1)
        }
        Text(formatTime(track.duration), color = Cream.copy(alpha = .45f), fontSize = 11.sp)
    }
}

@Composable
private fun MiniPlayer(player: MediaController?) {
    var title by remember { mutableStateOf("Choose a track") }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(player) {
        while (player != null) {
            title = player.mediaMetadata.title?.toString() ?: "Choose a track"
            playing = player.isPlaying
            position = if (player.duration > 0) player.currentPosition.toFloat() / player.duration else 0f
            kotlinx.coroutines.delay(300)
        }
    }
    Column(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 18.dp, vertical = 12.dp)) {
        LinearProgressIndicator(progress = { position.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(2.dp), color = Amber, trackColor = Cream.copy(alpha = .12f))
        Row(Modifier.fillMaxWidth().padding(top = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Cream, fontWeight = FontWeight.Bold, maxLines = 1)
                Text("ANALOG • OFFLINE", color = Green.copy(alpha = .75f), fontSize = 9.sp, letterSpacing = 1.5.sp)
            }
            IconButton(onClick = { player?.seekToPrevious() }) { Icon(Icons.Default.SkipPrevious, null, tint = Cream) }
            IconButton(onClick = { if (playing) player?.pause() else player?.play() }) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Amber)
            }
            IconButton(onClick = { player?.seekToNext() }) { Icon(Icons.Default.SkipNext, null, tint = Cream) }
        }
    }
}

@Composable
private fun formatTime(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
