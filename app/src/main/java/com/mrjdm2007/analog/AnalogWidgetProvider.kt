package com.mrjdm2007.analog

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class AnalogWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id -> updateOne(context, manager, id, "Choose a track", "ANALOG • OFFLINE", false) }
    }

    companion object {
        fun update(context: Context, title: String, artist: String, isPlaying: Boolean) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, AnalogWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { updateOne(context, manager, it, title, artist, isPlaying) }
        }

        private fun updateOne(context: Context, manager: AppWidgetManager, id: Int, title: String, artist: String, isPlaying: Boolean) {
            val views = RemoteViews(context.packageName, R.layout.widget_analog)
            views.setTextViewText(R.id.widget_title, title)
            views.setTextViewText(R.id.widget_artist, artist)
            views.setImageViewResource(R.id.widget_play, if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
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
            return PendingIntent.getService(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}
