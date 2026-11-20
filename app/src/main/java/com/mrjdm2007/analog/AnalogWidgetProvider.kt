package com.mrjdm2007.analog

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.RemoteViews

class AnalogWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val prefs = context.getSharedPreferences("analog_library", Context.MODE_PRIVATE)
        val title = prefs.getString("widget_title", "Choose a track") ?: "Choose a track"
        val artist = prefs.getString("widget_artist", "ANALOG • OFFLINE") ?: "ANALOG • OFFLINE"
        val isPlaying = prefs.getBoolean("widget_is_playing", false)
        val artwork = prefs.getString("widget_artwork_uri", null)?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val position = prefs.getLong("widget_position_ms", 0L)
        val duration = prefs.getLong("widget_duration_ms", 0L)
        ids.forEach { id -> updateOne(context, manager, id, title, artist, isPlaying, artwork, position, duration) }
    }

    companion object {
        fun update(context: Context, title: String, artist: String, isPlaying: Boolean, artworkUri: Uri?, positionMs: Long = 0L, durationMs: Long = 0L) {
            context.getSharedPreferences("analog_library", Context.MODE_PRIVATE).edit()
                .putString("widget_title", title)
                .putString("widget_artist", artist)
                .putBoolean("widget_is_playing", isPlaying)
                .putString("widget_artwork_uri", artworkUri?.toString())
                .putLong("widget_position_ms", positionMs.coerceAtLeast(0L))
                .putLong("widget_duration_ms", durationMs.coerceAtLeast(0L))
                .apply()
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, AnalogWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { updateOne(context, manager, it, title, artist, isPlaying, artworkUri, positionMs, durationMs) }
        }

        private fun updateOne(context: Context, manager: AppWidgetManager, id: Int, title: String, artist: String, isPlaying: Boolean, artworkUri: Uri?, positionMs: Long = 0L, durationMs: Long = 0L) {
            val views = RemoteViews(context.packageName, R.layout.widget_analog)
            views.setTextViewText(R.id.widget_title, title)
            views.setTextViewText(R.id.widget_artist, artist)
            if (artworkUri != null) views.setImageViewUri(R.id.widget_art, artworkUri) else views.setImageViewResource(R.id.widget_art, android.R.drawable.ic_menu_gallery)
            views.setImageViewResource(R.id.widget_play, if (isPlaying) R.drawable.widget_ic_pause else R.drawable.widget_ic_play)
            val progress = if (durationMs > 0L) ((positionMs.coerceIn(0L, durationMs) * 1000L) / durationMs).toInt() else 0
            views.setProgressBar(R.id.widget_progress, 1000, progress, false)
            views.setOnClickPendingIntent(R.id.widget_previous, serviceIntent(context, PlaybackService.ACTION_WIDGET_PREVIOUS, 1))
            views.setOnClickPendingIntent(R.id.widget_play, serviceIntent(context, PlaybackService.ACTION_WIDGET_PLAY_PAUSE, 2))
            views.setOnClickPendingIntent(R.id.widget_next, serviceIntent(context, PlaybackService.ACTION_WIDGET_NEXT, 3))
            views.setOnClickPendingIntent(R.id.widget_open, PendingIntent.getActivity(
                context, 4, context.packageManager.getLaunchIntentForPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
            manager.updateAppWidget(id, views)
        }

        private fun serviceIntent(context: Context, action: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, PlaybackService::class.java).setAction(action)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(context, requestCode, intent, flags)
            } else {
                PendingIntent.getService(context, requestCode, intent, flags)
            }
        }
    }
}
