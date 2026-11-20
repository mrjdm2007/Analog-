package com.mrjdm2007.analog

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

class AnalogQuickSettingsTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val playing = getSharedPreferences("analog_library", MODE_PRIVATE)
            .getBoolean("quick_tile_playing", false)
        qsTile?.apply {
            label = if (playing) "Analog • Playing" else "Play Analog"
            state = if (playing) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        // A direct user tap is the explicit request to begin background playback.
        ContextCompat.startForegroundService(
            this,
            Intent(this, PlaybackService::class.java).setAction(PlaybackService.ACTION_TILE_PLAY)
        )
        qsTile?.apply {
            label = "Starting Analog…"
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }
}
