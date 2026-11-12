package com.mrjdm2007.analog

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SmartPlaylistsScreen(
    tracks: List<Track>,
    favorites: Set<Long>,
    onBack: () -> Unit,
    onPlay: (List<Track>) -> Unit
) {
    val context = LocalContext.current
    val monthPrefix = remember { SimpleDateFormat("yyyy-MM", Locale.US).format(Date()) }
    val playCounts = tracks.associate { track ->
        track.id to context.getSharedPreferences("analog_monthly", Context.MODE_PRIVATE)
            .getInt("$monthPrefix:${track.id}:count", 0)
    }
    data class Mix(val title: String, val subtitle: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val songs: List<Track>)
    val mixes = listOf(
        Mix("Most played this month", "Your current month's favourites, ranked by plays", Icons.Default.History,
            tracks.filter { (playCounts[it.id] ?: 0) > 0 }.sortedByDescending { playCounts[it.id] ?: 0 }.take(30)),
        Mix("Your favourites", "Everything you've saved with a heart", Icons.Default.Favorite,
            tracks.filter { it.id in favorites }),
        Mix("Quick listen", "Short tracks under three minutes", Icons.Default.FlashOn,
            tracks.filter { it.duration in 1..179_999L }.shuffled().take(30)),
        Mix("Long listening", "Longer tracks for an uninterrupted session", Icons.Default.MusicNote,
            tracks.filter { it.duration >= 240_000L }.shuffled().take(30)),
        Mix("Surprise me", "A fresh random mix from your whole library", Icons.Default.Shuffle,
            tracks.shuffled().take(40))
    )
    Column(Modifier.fillMaxSize().background(Color(0xFF10110F)).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = Color(0xFFE8E1CF)) }
            Column(Modifier.weight(1f)) {
                Text("ANALOG / SMART MIXES", color = Color(0xFFFFB45B), fontWeight = FontWeight.Black, letterSpacing = 1.8.sp)
                Text("AUTOMATIC PLAYLISTS", color = Color(0xFFB5D69B), fontSize = 9.sp, letterSpacing = 1.5.sp)
            }
            Icon(Icons.Default.AutoAwesome, null, tint = Color(0xFFFFB45B))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 28.dp)) {
            items(mixes.size) { index ->
                val mix = mixes[index]
                Row(
                    Modifier.fillMaxWidth().background(Color(0xFF1A1C18), RoundedCornerShape(16.dp))
                        .clickable(enabled = mix.songs.isNotEmpty()) { onPlay(mix.songs) }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(48.dp).background(Color(0xFF272B23), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                        Icon(mix.icon, null, tint = if (index == 0) Color(0xFFFFB45B) else Color(0xFFB5D69B), modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(mix.title, color = Color(0xFFE8E1CF), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(mix.subtitle, color = Color(0xFFE8E1CF).copy(alpha = .55f), fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                        Text("${mix.songs.size} tracks", color = Color(0xFFB5D69B), fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                    Icon(Icons.Default.PlayArrow, null, tint = Color(0xFFFFB45B))
                }
            }
        }
    }
}
