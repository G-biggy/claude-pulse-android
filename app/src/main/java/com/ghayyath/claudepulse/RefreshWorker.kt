package com.ghayyath.claudepulse

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class RefreshWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val data = ApiClient.fetchUsage(context)

        // Cache auth error state so widget and SetupActivity can read it
        val prefs = context.getSharedPreferences("pulse_cache", Context.MODE_PRIVATE)
        if (data.error == "auth_error") {
            prefs.edit().putBoolean("auth_error", true).putLong("auth_error_at", System.currentTimeMillis()).apply()
        } else if (data.error == null) {
            prefs.edit().putBoolean("auth_error", false).apply()
            ApiClient.cacheUsage(context, data)
        }

        // Always trigger widget repaint — even on error, so "Updated Xm ago" stays current
        val manager = AppWidgetManager.getInstance(context)
        val component = ComponentName(context, PulseWidget::class.java)
        val ids = manager.getAppWidgetIds(component)
        if (ids.isNotEmpty()) {
            val intent = Intent(context, PulseWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(intent)
        }

        return if (data.error == null) Result.success() else Result.retry()
    }
}
