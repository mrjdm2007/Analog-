package com.mrjdm2007.analog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ListeningRecapScreen(tracks: List<Track>, monthlyCounts: Map<Long, Int>, onBack: () -> Unit) {
    val ranked = tracks.mapNotNull { track -> monthlyCounts[track.id]?.takeIf { it > 0 }?.let { track to it } }.sortedByDescending { it.second }
    val total = monthlyCounts.values.sum()
    Column(Modifier.fillMaxSize().background(Color(0xFF10110F)).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = Color(0xFFE8E1CF)) }
            Column { Text("ANALOG / RECAP", color = Color(0xFFFFB45B), fontWeight = FontWeight.Black, letterSpacing = 2.sp); Text("YOUR MONTH IN MUSIC", color = Color(0xFFB5D69B), fontSize = 9.sp, letterSpacing = 1.5.sp) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1C18))) { Column(Modifier.padding(16.dp)) { Text(total.toString(), color = Color(0xFFFFB45B), fontSize = 30.sp, fontWeight = FontWeight.Black); Text("PLAYS THIS MONTH", color = Color(0xFFE8E1CF), fontSize = 9.sp) } }
            Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1C18))) { Column(Modifier.padding(16.dp)) { Text(ranked.size.toString(), color = Color(0xFFB5D69B), fontSize = 30.sp, fontWeight = FontWeight.Black); Text("TRACKS PLAYED", color = Color(0xFFE8E1CF), fontSize = 9.sp) } }
        }
        Text("MOST PLAYED", color = Color(0xFFB5D69B), fontSize = 10.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 24.dp, bottom = 10.dp))
        if (ranked.isEmpty()) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.MusicNote, null, tint = Color(0xFFFFB45B), modifier = Modifier.size(42.dp)); Text("Your monthly recap starts as you listen.", color = Color(0xFFE8E1CF)) } } }
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) { items(ranked.take(50), key = { it.first.id }) { (track, count) -> Row(Modifier.fillMaxWidth().background(Color(0xFF1A1C18)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Text("${ranked.indexOfFirst { it.first.id == track.id } + 1}", color = Color(0xFFFFB45B), modifier = Modifier.width(28.dp)); Column(Modifier.weight(1f)) { Text(track.title, color = Color(0xFFE8E1CF), fontWeight = FontWeight.SemiBold, maxLines = 1); Text(track.artist, color = Color(0xFFE8E1CF).copy(alpha = .55f), fontSize = 11.sp, maxLines = 1) }; Text("$count plays", color = Color(0xFFB5D69B), fontSize = 11.sp) } } }
    }
}