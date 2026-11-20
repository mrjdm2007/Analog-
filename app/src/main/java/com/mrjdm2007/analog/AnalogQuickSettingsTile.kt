package com.mrjdm2007.analog

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

class AnalogQuickSettingsTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            label = "Play Analog"
            state = Tile.STATE_INACTIVE
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
            label = "Analog"
            state = Tile.STATE_ACTIVE
            updateTile()
        }
    }
}
