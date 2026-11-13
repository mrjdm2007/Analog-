package com.mrjdm2007.analog

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.session.MediaController

object SleepTimer {
    private val handler = Handler(Looper.getMainLooper())
    private var stopRunnable: Runnable? = null
    private var endListener: Player.Listener? = null
    private var activePlayer: MediaController? = null

    fun cancel() {
        stopRunnable?.let(handler::removeCallbacks)
        stopRunnable = null
        endListener?.let { activePlayer?.removeListener(it) }
        endListener = null
        activePlayer = null
    }

    fun startMinutes(player: MediaController?, minutes: Int) {
        cancel()
        if (player == null || minutes <= 0) return
        activePlayer = player
        stopRunnable = Runnable { player.pause(); cancel() }
        handler.postDelayed(stopRunnable!!, minutes * 60_000L)
    }

    fun stopAtEnd(player: MediaController?) {
        cancel()
        if (player == null) return
        activePlayer = player
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    player.pause()
                    cancel()
                }
            }
        }
        endListener = listener
        player.addListener(listener)
    }
}

@Composable
fun SleepTimerPanel(player: MediaController?) {
    var selected by remember { mutableIntStateOf(30) }
    var status by remember { mutableStateOf("No timer active") }
    Column(Modifier.fillMaxWidth().background(Color(0xFF1A1C18), RoundedCornerShape(14.dp)).padding(14.dp)) {
        Text("SLEEP TIMER", color = Color(0xFFB5D69B), fontSize = 10.sp, letterSpacing = 2.sp)
        Text("Stop playback after a set time or when the current queue ends.", color = Color(0xFFE8E1CF).copy(alpha = .65f), fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp, bottom = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(10, 15, 30, 45, 60).forEach { minutes ->
                FilterChip(selected = selected == minutes, onClick = { selected = minutes }, label = { Text("${minutes}m") })
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { SleepTimer.startMinutes(player, selected); status = "Playback stops in $selected minutes" }, enabled = player != null, modifier = Modifier.weight(1f)) { Text("START TIMER") }
            OutlinedButton(onClick = { SleepTimer.stopAtEnd(player); status = "Will stop at end of track" }, enabled = player != null, modifier = Modifier.weight(1f)) { Text("END OF TRACK") }
        }
        TextButton(onClick = { SleepTimer.cancel(); status = "Timer cancelled" }) { Text("CANCEL TIMER") }
        Text(status, color = Color(0xFFE8E1CF).copy(alpha = .6f), fontSize = 10.sp)
    }
}
