package com.mrjdm2007.analog

import android.content.Context
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Refreshes the app's library scan bookkeeping weekly.
 * Android MediaStore itself indexes new audio files; the app re-queries it on launch.
 */
class WeeklyLibraryScanWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = try {
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        var count = 0
        applicationContext.contentResolver.query(
            uri,
            arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            null
        )?.use { cursor -> count = cursor.count }
        applicationContext.getSharedPreferences("analog_library", Context.MODE_PRIVATE)
            .edit()
            .putInt("weekly_scan_track_count", count)
            .putLong("weekly_scan_timestamp", System.currentTimeMillis())
            .apply()
        Result.success()
    } catch (_: SecurityException) {
        Result.failure()
    } catch (_: Exception) {
        Result.retry()
    }

    companion object {
        private const val WORK_NAME = "analog_weekly_library_scan"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WeeklyLibraryScanWorker>(7, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
