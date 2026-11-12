package com.mrjdm2007.analog

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.ListenableFuture

class AnalogWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        refresh(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action
        if (action == ACTION_PLAY || action == ACTION_PREVIOUS || action == ACTION_NEXT) {
            val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
            future.addListener({
                runCatching {
                    val controller = future.get()
                    when (action) {
                        ACTION_PLAY -> if (controller.isPlaying) controller.pause() else controller.play()
                        ACTION_PREVIOUS -> controller.seekToPrevious()
                        ACTION_NEXT -> controller.seekToNext()
                    }
                    updateViews(context, controller.mediaMetadata.title?.toString(), controller.mediaMetadata.artist?.toString(), controller.isPlaying)
                    MediaController.releaseFuture(future)
                }.onFailure { runCatching { MediaController.releaseFuture(future) } }
            }, ContextCompat.getMainExecutor(context))
        } else if (action == AppWidgetManager.ACTION_APPWIDGET_UPDATE) {
            refresh(context)
        }
    }

    companion object {
        const val ACTION_PLAY = "com.mrjdm2007.analog.widget.PLAY"
        const val ACTION_PREVIOUS = "com.mrjdm2007.analog.widget.PREVIOUS"
        const val ACTION_NEXT = "com.mrjdm2007.analog.widget.NEXT"

        fun refresh(context: Context) {
            val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
            future.addListener({
                runCatching {
                    val controller = future.get()
                    updateViews(context, controller.mediaMetadata.title?.toString(), controller.mediaMetadata.artist?.toString(), controller.isPlaying)
                    MediaController.releaseFuture(future)
                }.onFailure { runCatching { MediaController.releaseFuture(future) } }
            }, ContextCompat.getMainExecutor(context))
        }

        private fun updateViews(context: Context, title: String?, artist: String?, playing: Boolean) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, AnalogWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val views = RemoteViews(context.packageName, R.layout.analog_widget)
            views.setTextViewText(R.id.widget_title, title?.takeIf { it.isNotBlank() } ?: "Choose a track")
            views.setTextViewText(R.id.widget_artist, artist?.takeIf { it.isNotBlank() } ?: "ANALOG • OFFLINE")
            views.setTextViewText(R.id.widget_play, if (playing) "Ⅱ" else "▶")
            views.setOnClickPendingIntent(R.id.widget_previous, actionIntent(context, ACTION_PREVIOUS, 1))
            views.setOnClickPendingIntent(R.id.widget_play, actionIntent(context, ACTION_PLAY, 2))
            views.setOnClickPendingIntent(R.id.widget_next, actionIntent(context, ACTION_NEXT, 3))
            val open = PendingIntent.getActivity(context, 4, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_art, open)
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        private fun actionIntent(context: Context, action: String, request: Int): PendingIntent {
            val intent = Intent(context, AnalogWidgetProvider::class.java).setAction(action)
            return PendingIntent.getBroadcast(context, request, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}
