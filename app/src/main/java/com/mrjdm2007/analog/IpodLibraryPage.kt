package com.mrjdm2007.analog

import androidx.compose.animation.animateContentSize
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import android.view.HapticFeedbackConstants
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.media3.session.MediaController
import kotlin.math.atan2
import kotlin.math.PI
import kotlin.random.Random
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private val IpodBlack = Color(0xFF080A0C)
private val IpodScreen = Color(0xFFDCE4DC)
private val IpodInk = Color(0xFF17221B)
private val IpodMuted = Color(0xFF617066)
private val IpodGreen = Color(0xFF647B69)

@Composable
fun IpodLibraryPage(
    tracks: List<Track>,
    favorites: Set<Long>,
    playlists: Map<String, Set<Long>>,
    player: MediaController?,
    onPlay: (List<Track>, Int) -> Unit,
    onPlayer: () -> Unit,
    onSettings: () -> Unit,
    onVisualizer: () -> Unit,
    onRecap: () -> Unit,
    onFavorite: (Long) -> Unit,
    onMore: (Long) -> Unit
) {
    val view = LocalView.current
    var page by remember { mutableStateOf("Main Menu") }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableIntStateOf(0) }
    var wheelAngle by remember { mutableFloatStateOf(Float.NaN) }
    var wheelDelta by remember { mutableFloatStateOf(0f) }
    var currentTitle by remember { mutableStateOf("Nothing playing") }
    var currentArtist by remember { mutableStateOf("Choose music from your library") }
    var isPlaying by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var dayKey by remember { mutableStateOf(SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())) }

    LaunchedEffect(Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            val nextDay = java.util.Calendar.getInstance().apply {
                timeInMillis = now
                add(java.util.Calendar.DAY_OF_YEAR, 1)
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
            delay((nextDay - now).coerceAtLeast(1000L))
            dayKey = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        }
    }
    val dailyTracks = remember(tracks, dayKey) { tracks.shuffled(Random(dayKey.hashCode())) }

    LaunchedEffect(player) {
        while (player != null) {
            currentTitle = player.mediaMetadata.title?.toString() ?: "Nothing playing"
            currentArtist = player.mediaMetadata.artist?.toString() ?: "Choose music from your library"
            isPlaying = player.isPlaying
            position = player.currentPosition
            duration = player.duration.coerceAtLeast(0L)
            delay(350)
        }
    }

    val filtered = remember(dailyTracks, query) {
        dailyTracks.filter { query.isBlank() || it.title.contains(query, true) || it.artist.contains(query, true) }
    }
    val menuItems = when (page) {
        "Main Menu" -> listOf("Now Playing", "Songs", "Favorites", "Search", "Playlists", "Listening Recap", "Equalizer", "Visualizer", "Settings")
        "Playlists" -> playlists.keys.sorted().ifEmpty { listOf("No playlists yet") }
        else -> emptyList()
    }
    val entries = when (page) {
        "Songs" -> filtered.map { it.title to it.artist }
        "Favorites" -> filtered.filter { it.id in favorites }.map { it.title to it.artist }
        "Search" -> filtered.map { it.title to it.artist }
        "Playlists" -> emptyList()
        else -> emptyList()
    }
    val rows = if (page in listOf("Main Menu", "Playlists")) menuItems else entries.map { it.first }
    val safeSelected = selected.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
    LaunchedEffect(page, rows.size) { selected = 0 }

    fun back() {
        page = when (page) {
            "Main Menu" -> "Main Menu"
            "Playlists" -> "Main Menu"
            else -> "Main Menu"
        }
        selected = 0
    }

    fun activate(index: Int) {
        val item = rows.getOrNull(index) ?: return
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        when (page) {
            "Main Menu" -> when (item) {
                "Now Playing" -> onPlayer()
                "Playlists" -> { page = item; selected = 0 }
                "Listening Recap" -> onRecap()
                "Equalizer" -> onSettings()
                "Visualizer" -> onVisualizer()
                "Settings" -> onSettings()
                else -> { page = item; selected = 0 }
            }
            "Playlists" -> if (item != "No playlists yet") {
                val ids = playlists[item].orEmpty()
                val list = tracks.filter { it.id in ids }
                if (list.isNotEmpty()) onPlay(list, 0)
            }
            else -> {
                val target = when (page) {
                    "Songs", "Search" -> filtered
                    "Favorites" -> filtered.filter { it.id in favorites }
                    else -> filtered
                }
                target.getOrNull(index) ?: return
                onPlay(target, index)
            }
        }
    }

    BackHandler(page != "Main Menu") { back() }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color(0xFF020304)).padding(horizontal = 7.dp, vertical = 5.dp)
    ) {
        val wheelSize = minOf(maxWidth * .67f, maxHeight * .31f, 246.dp)
        Column(
            Modifier.fillMaxSize().background(IpodBlack, RoundedCornerShape(29.dp))
                .border(1.5.dp, Color(0xFF4B5054), RoundedCornerShape(29.dp))
                .padding(horizontal = 13.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth().height(29.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("◉", color = Color(0xFFBFC6C1), fontSize = 13.sp)
                Text(" ANALOG", color = Color(0xFFE7EBE8), fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                Spacer(Modifier.weight(1f))
                Text(if (isPlaying) "♫" else "Ⅱ", color = Color(0xFFD8E0D9), fontSize = 13.sp)
                Spacer(Modifier.width(7.dp))
                Icon(Icons.Default.BatteryFull, null, tint = Color(0xFFD8E0D9), modifier = Modifier.size(17.dp))
            }
            Card(
                Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp, bottom = 10.dp)
                    .border(2.dp, Color(0xFF60676A), RoundedCornerShape(9.dp)),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = IpodScreen)
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().background(IpodGreen).padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (page != "Main Menu") {
                            Text("‹", color = Color.White, fontSize = 18.sp, modifier = Modifier.clickable { back() }.padding(end = 7.dp))
                        }
                        Text(page.uppercase(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp, modifier = Modifier.weight(1f))
                        Text("${tracks.size} TRACKS", color = Color.White.copy(alpha = .82f), fontSize = 8.sp)
                    }
                    if (page == "Now Playing") {
                        val queueIndex = player?.currentMediaItemIndex ?: -1
                        val previousTitle = if (player != null && queueIndex > 0) tracks.firstOrNull { it.id == player.getMediaItemAt(queueIndex - 1).mediaId.toLongOrNull() }?.title else null
                        val nextTitle = if (player != null && queueIndex >= 0 && queueIndex < player.mediaItemCount - 1) tracks.firstOrNull { it.id == player.getMediaItemAt(queueIndex + 1).mediaId.toLongOrNull() }?.title else null
                        NowPlayingInfo(player, tracks, currentTitle, currentArtist, previousTitle, nextTitle, isPlaying, position, duration, onPlayer)
                    } else if (page == "Search") {
                        Row(Modifier.fillMaxWidth().padding(8.dp).background(Color.White, RoundedCornerShape(4.dp)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Search, null, tint = IpodMuted, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            BasicTextField(query, { query = it; selected = 0 }, modifier = Modifier.fillMaxWidth(),
                                textStyle = androidx.compose.ui.text.TextStyle(color = IpodInk, fontSize = 13.sp),
                                singleLine = true, decorationBox = { inner -> if (query.isEmpty()) Text("Search your music", color = IpodMuted, fontSize = 13.sp); inner() })
                        }
                        IpodRows(rows, entries, safeSelected, onActivate = ::activate, modifier = Modifier.weight(1f))
                    } else if (page == "Main Menu" || page == "Playlists") {
                        Column(Modifier.weight(1f)) {
                            if (page == "Main Menu") {
                                Row(Modifier.fillMaxWidth().padding(9.dp).background(Color(0xFFCBD6CC), RoundedCornerShape(5.dp)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(46.dp).background(Color(0xFF9DADA1), RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.LibraryMusic, null, tint = Color(0xFF42564A), modifier = Modifier.size(27.dp))
                                    }
                                    Spacer(Modifier.width(9.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("YOUR MUSIC", color = IpodInk, fontWeight = FontWeight.Black, fontSize = 12.sp)
                                        Text("${tracks.size} songs • Offline library", color = IpodMuted, fontSize = 9.sp)
                                    }
                                }
                            }
                            IpodRows(menuItems, emptyList(), safeSelected, onActivate = ::activate, modifier = Modifier.weight(1f))
                        }
                    } else {
                        if (entries.isEmpty()) {
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.LibraryMusic, null, tint = IpodMuted, modifier = Modifier.size(34.dp))
                                    Spacer(Modifier.height(7.dp))
                                    Text(if (tracks.isEmpty()) "No music found" else "Nothing here yet", color = IpodMuted, fontSize = 12.sp)
                                }
                            }
                        } else IpodRows(rows, entries, safeSelected, onActivate = ::activate, modifier = Modifier.weight(1f))
                    }
                    if (page != "Now Playing") {
                        Row(Modifier.fillMaxWidth().background(Color(0xFFCBD6CC)).padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (page == "Main Menu") "SELECT  •  MENU  •  PLAY" else "${rows.size} ITEMS", color = IpodMuted, fontSize = 8.sp, letterSpacing = .7.sp)
                            Spacer(Modifier.weight(1f))
                            Text("OFFLINE", color = IpodGreen, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(currentTitle, color = Color(0xFFE3E7E5), fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(currentArtist, color = Color(0xFF9DA6A1), fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { if (isPlaying) player?.pause() else player?.play() }, modifier = Modifier.size(34.dp)) {
                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color(0xFFE7EBE8))
                }
                IconButton(onClick = { onPlayer() }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Default.OpenInFull, null, tint = Color(0xFFB8C1BB))
                }
            }
            Box(
                Modifier.padding(top = 5.dp, bottom = 3.dp).size(wheelSize)
                    .background(Color(0xFF191C1F), CircleShape).border(2.dp, Color(0xFF555B60), CircleShape)
                    .pointerInput(rows.size) {
                        detectDragGestures(
                            onDragStart = { p -> wheelAngle = atan2(p.y - size.height / 2f, p.x - size.width / 2f); wheelDelta = 0f },
                            onDrag = { change, _ ->
                                val a = atan2(change.position.y - size.height / 2f, change.position.x - size.width / 2f)
                                if (!wheelAngle.isNaN() && rows.isNotEmpty()) {
                                    var d = a - wheelAngle
                                    while (d > PI.toFloat()) d -= 2f * PI.toFloat()
                                    while (d < -PI.toFloat()) d += 2f * PI.toFloat()
                                    wheelDelta += d
                                    val tick = (8f * PI.toFloat() / 180f)
                                    if (kotlin.math.abs(wheelDelta) >= tick) {
                                        val steps = (kotlin.math.abs(wheelDelta) / tick).toInt()
                                        val direction = if (wheelDelta > 0f) 1 else -1
                                        val oldSelected = selected
                                        selected = (selected + direction * steps).coerceIn(0, rows.lastIndex)
                                        wheelDelta -= direction * steps * tick
                                        if (selected != oldSelected) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    }
                                }
                                wheelAngle = a
                                change.consume()
                            },
                            onDragEnd = { wheelAngle = Float.NaN; wheelDelta = 0f },
                            onDragCancel = { wheelAngle = Float.NaN; wheelDelta = 0f }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(wheelSize * .39f).background(Color(0xFF303438), CircleShape).border(1.dp, Color(0xFF697075), CircleShape))
                Text("MENU", color = Color(0xFFE6E8EA), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopCenter).padding(top = wheelSize * .105f).clickable { back(); view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) })
                IconButton(onClick = { selected = (selected - 1).coerceAtLeast(0); view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }, modifier = Modifier.align(Alignment.CenterStart).size(wheelSize * .22f)) { Icon(Icons.Default.ChevronLeft, null, tint = Color(0xFFE6E8EA)) }
                IconButton(onClick = { selected = (selected + 1).coerceAtMost((rows.size - 1).coerceAtLeast(0)); view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }, modifier = Modifier.align(Alignment.CenterEnd).size(wheelSize * .22f)) { Icon(Icons.Default.ChevronRight, null, tint = Color(0xFFE6E8EA)) }
                IconButton(onClick = { if (page == "Now Playing") player?.seekToPrevious() else selected = (selected - 1).coerceAtLeast(0); view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }, modifier = Modifier.align(Alignment.TopCenter).size(wheelSize * .18f)) { Icon(Icons.Default.SkipPrevious, null, tint = Color(0xFFE6E8EA), modifier = Modifier.size(wheelSize * .14f)) }
                IconButton(onClick = { if (page == "Now Playing") player?.seekToNext() else selected = (selected + 1).coerceAtMost((rows.size - 1).coerceAtLeast(0)); view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }, modifier = Modifier.align(Alignment.BottomCenter).size(wheelSize * .18f)) { Icon(Icons.Default.SkipNext, null, tint = Color(0xFFE6E8EA), modifier = Modifier.size(wheelSize * .14f)) }
                FilledIconButton(onClick = { if (page == "Now Playing") { if (isPlaying) player?.pause() else player?.play() } else activate(safeSelected); view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }, modifier = Modifier.size(wheelSize * .29f), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF444A4F))) {
                    Icon(if (page == "Now Playing" && isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(wheelSize * .13f))
                }
            }
        }
    }
}

@Composable
private fun IpodRows(
    rows: List<String>,
    details: List<Pair<String, String>>,
    selected: Int,
    onActivate: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    LaunchedEffect(selected, rows.size) {
        if (rows.isNotEmpty()) listState.animateScrollToItem(selected.coerceIn(0, rows.lastIndex))
    }
    LazyColumn(state = listState, modifier = modifier) {
        items(rows.size) { index ->
            val active = index == selected
            val detail = details.getOrNull(index)?.second
            Row(
                Modifier.fillMaxWidth().background(if (active) Color(0xFF718477) else Color.Transparent)
                    .clickable { onActivate(index) }.padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(rows[index], color = if (active) Color.White else IpodInk, fontSize = 13.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (detail != null) Text(detail, color = if (active) Color.White.copy(alpha = .82f) else IpodMuted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Default.ChevronRight, null, tint = if (active) Color.White else IpodMuted, modifier = Modifier.size(17.dp))
            }
            HorizontalDivider(color = Color(0xFF9EAAA0).copy(alpha = .35f), thickness = .5.dp)
        }
    }
}

@Composable
private fun NowPlayingInfo(
    player: MediaController?,
    tracks: List<Track>,
    title: String,
    artist: String,
    previousTitle: String?,
    nextTitle: String?,
    playing: Boolean,
    position: Long,
    duration: Long,
    onOpen: () -> Unit
) {
    val id = player?.currentMediaItem?.mediaId?.toLongOrNull()
    val track = tracks.firstOrNull { it.id == id }
    Column(Modifier.fillMaxSize().clickable(onClick = onOpen).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (playing) "PLAYING" else "PAUSED", color = IpodGreen, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Default.Bluetooth, null, tint = IpodMuted, modifier = Modifier.size(13.dp))
            Text("  LOCAL", color = IpodMuted, fontSize = 8.sp)
        }
        Spacer(Modifier.height(9.dp))
        Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFFCAD4CB), RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
            if (track != null) AsyncImage(model = track.artUri(), contentDescription = track.album, modifier = Modifier.fillMaxSize())
            else Icon(Icons.Default.Album, null, tint = IpodMuted, modifier = Modifier.size(62.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(previousTitle?.let { "‹  $it" } ?: "• • •", color = IpodMuted.copy(alpha = .48f), fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(title, color = IpodInk, fontSize = 15.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(artist, color = IpodMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(nextTitle?.let { "$it  ›" } ?: "• • •", color = IpodMuted.copy(alpha = .48f), fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(7.dp))
        LinearProgressIndicator(progress = { if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth().height(4.dp), color = IpodGreen, trackColor = Color(0xFFB9C5BB))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(ipodTime(position), color = IpodMuted, fontSize = 8.sp)
            Text(ipodTime(duration), color = IpodMuted, fontSize = 8.sp)
        }
    }
}

private fun ipodTime(ms: Long): String {
    val seconds = (ms / 1000L).coerceAtLeast(0L)
    return "%d:%02d".format(seconds / 60L, seconds % 60L)
}


@Composable
fun IpodSubscreenFrame(
    title: String,
    enabled: Boolean,
    onMenu: () -> Unit,
    content: @Composable () -> Unit
) {
    if (!enabled) {
        content()
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF020304)).padding(horizontal = 7.dp, vertical = 5.dp)) {
        val wheelSize = minOf(maxWidth * .55f, maxHeight * .27f, 210.dp)
        Column(
            Modifier.fillMaxSize().background(IpodBlack, RoundedCornerShape(29.dp))
                .border(1.5.dp, Color(0xFF4B5054), RoundedCornerShape(29.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("ANALOG", color = Color(0xFFE7EBE8), fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                Spacer(Modifier.weight(1f))
                Icon(Icons.Default.BatteryFull, null, tint = Color(0xFFD8E0D9), modifier = Modifier.size(17.dp))
            }
            Card(
                Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp, bottom = 8.dp)
                    .border(2.dp, Color(0xFF60676A), RoundedCornerShape(9.dp)),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = IpodScreen)
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().background(IpodGreen).padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(title.uppercase(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
                        Spacer(Modifier.weight(1f))
                        Text("ANALOG", color = Color.White.copy(alpha = .8f), fontSize = 8.sp)
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                }
            }
            Box(
                Modifier.padding(top = 3.dp, bottom = 2.dp).size(wheelSize)
                    .background(Color(0xFF191C1F), CircleShape).border(2.dp, Color(0xFF555B60), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(wheelSize * .39f).background(Color(0xFF303438), CircleShape).border(1.dp, Color(0xFF697075), CircleShape))
                Text("MENU", color = Color(0xFFE6E8EA), fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = wheelSize * .105f).clickable { onMenu() })
                Icon(Icons.Default.SkipPrevious, null, tint = Color(0xFFE6E8EA), modifier = Modifier.align(Alignment.CenterStart).padding(start = 13.dp).size(22.dp))
                Icon(Icons.Default.SkipNext, null, tint = Color(0xFFE6E8EA), modifier = Modifier.align(Alignment.CenterEnd).padding(end = 13.dp).size(22.dp))
                Icon(Icons.Default.PlayArrow, null, tint = Color(0xFFE6E8EA), modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).size(20.dp))
            }
        }
    }
}
