package com.ghayyath.claudepulse

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import androidx.work.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PulseWidget : AppWidgetProvider() {

    companion object {
        private val executor = Executors.newSingleThreadExecutor()
        private const val COLOR_GREEN = 0xFF6ee7b7.toInt()  // 0-49%
        private const val COLOR_YELLOW = 0xFFFF9800.toInt() // 50-74%
        private const val COLOR_ORANGE = 0xFFFF5722.toInt() // 75-89%
        private const val COLOR_RED = 0xFFF44336.toInt()    // 90-100%
        private const val ACTION_REFRESH = "com.ghayyath.claudepulse.ACTION_REFRESH"
        private const val WORK_NAME = "pulse_periodic_refresh"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val widgetComponent = ComponentName(context, PulseWidget::class.java)
            val widgetIds = appWidgetManager.getAppWidgetIds(widgetComponent)
            onUpdate(context, appWidgetManager, widgetIds)
            return
        }
        super.onReceive(context, intent)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        enqueuePeriodicRefresh(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // Ensure WorkManager is scheduled (survives reboots/force-stops)
        enqueuePeriodicRefresh(context)
        for (appWidgetId in appWidgetIds) {
            renderWidget(context, appWidgetManager, appWidgetId)
            scheduleRefresh(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle?) {
        renderWidget(context, appWidgetManager, appWidgetId)
    }

    private fun enqueuePeriodicRefresh(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val work = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            work
        )
    }

    private fun renderWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val data = loadCachedData(context) ?: UsageData.placeholder()
        val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
        val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
        val isCompact = minWidth < 200

        val views = if (isCompact) {
            buildCompactViews(context, data)
        } else {
            buildFullViews(context, data)
        }

        // Tap widget body -> open usage page
        val tapIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://claude.ai/settings/usage"))
        val tapPi = PendingIntent.getActivity(context, 0, tapIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.widget_root, tapPi)

        // Tap "Refresh Now" -> trigger widget update
        if (!isCompact) {
            val refreshIntent = Intent(context, PulseWidget::class.java).apply {
                action = ACTION_REFRESH
            }
            val refreshPi = PendingIntent.getBroadcast(context, 1, refreshIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.refresh_button, refreshPi)
        }

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    private fun scheduleRefresh(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        try {
            executor.execute {
                try {
                    val freshData = ApiClient.fetchUsage(context)
                    if (freshData.error == null) {
                        cacheData(context, freshData)
                        renderWidget(context, appWidgetManager, appWidgetId)
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private fun buildFullViews(context: Context, data: UsageData): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_layout)

        val sessionPct = data.fiveHourUtilization.toInt().coerceIn(0, 100)
        val weeklyPct = data.sevenDayUtilization.toInt().coerceIn(0, 100)
        val sonnetPct = data.sonnetUtilization.toInt().coerceIn(0, 100)

        // Header
        if (data.planLabel.isNotEmpty()) {
            views.setTextViewText(R.id.plan_label, "\u00b7 ${data.planLabel}")
        } else {
            views.setTextViewText(R.id.plan_label, "")
        }
        views.setTextViewText(R.id.updated_ago, formatTimeSince(data.cachedAt))

        // Session
        views.setProgressBar(R.id.five_hour_bar, 100, sessionPct, false)
        views.setTextViewText(R.id.five_hour_pct, "${sessionPct}%")
        views.setTextViewText(R.id.five_hour_reset, formatResetTime(data.fiveHourResetsAt))
        views.setTextColor(R.id.five_hour_pct, getColor(sessionPct))

        // Weekly
        views.setProgressBar(R.id.weekly_bar, 100, weeklyPct, false)
        views.setTextViewText(R.id.weekly_pct, "${weeklyPct}%")
        views.setTextViewText(R.id.weekly_reset, formatResetTime(data.sevenDayResetsAt))
        views.setTextColor(R.id.weekly_pct, getColor(weeklyPct))

        // Sonnet
        views.setProgressBar(R.id.sonnet_bar, 100, sonnetPct, false)
        views.setTextViewText(R.id.sonnet_pct, "${sonnetPct}%")
        val sonnetResetText = if (data.sonnetResetsAt.isNullOrEmpty() || data.sonnetResetsAt == "null") {
            "No active limit"
        } else {
            formatResetTime(data.sonnetResetsAt)
        }
        views.setTextViewText(R.id.sonnet_reset, sonnetResetText)
        views.setTextColor(R.id.sonnet_pct, getColor(sonnetPct))

        return views
    }

    private fun buildCompactViews(context: Context, data: UsageData): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_layout_small)

        val sessionPct = data.fiveHourUtilization.toInt().coerceIn(0, 100)
        val weeklyPct = data.sevenDayUtilization.toInt().coerceIn(0, 100)
        val sonnetPct = data.sonnetUtilization.toInt().coerceIn(0, 100)

        views.setProgressBar(R.id.five_hour_bar, 100, sessionPct, false)
        views.setTextViewText(R.id.five_hour_pct, "${sessionPct}%")
        views.setTextColor(R.id.five_hour_pct, getColor(sessionPct))

        views.setProgressBar(R.id.weekly_bar, 100, weeklyPct, false)
        views.setTextViewText(R.id.weekly_pct, "${weeklyPct}%")
        views.setTextColor(R.id.weekly_pct, getColor(weeklyPct))

        views.setProgressBar(R.id.sonnet_bar, 100, sonnetPct, false)
        views.setTextViewText(R.id.sonnet_pct, "${sonnetPct}%")
        views.setTextColor(R.id.sonnet_pct, getColor(sonnetPct))

        return views
    }

    private fun getColor(pct: Int): Int = when {
        pct >= 90 -> COLOR_RED
        pct >= 75 -> COLOR_ORANGE
        pct >= 50 -> COLOR_YELLOW
        else -> COLOR_GREEN
    }

    private fun formatResetTime(isoTime: String?): String {
        if (isoTime.isNullOrEmpty() || isoTime == "null") return ""
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            val cleaned = isoTime.replace(Regex("[+-]\\d{2}:\\d{2}$"), "").replace("Z", "")
            val resetDate = sdf.parse(cleaned) ?: return ""
            val diffMs = resetDate.time - System.currentTimeMillis()
            if (diffMs <= 0) return "Resetting..."
            val hours = (diffMs / 3_600_000).toInt()
            val minutes = ((diffMs % 3_600_000) / 60_000).toInt()
            if (hours >= 24) {
                val days = hours / 24
                val remHours = hours % 24
                "Resets in ${days}d ${remHours}h"
            } else {
                "Resets in ${hours}h ${minutes}m"
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun formatTimeSince(isoTime: String?): String {
        if (isoTime.isNullOrEmpty() || isoTime == "null") return "Updated just now"
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            val cachedDate = sdf.parse(isoTime) ?: return "Updated just now"
            val diffMs = System.currentTimeMillis() - cachedDate.time
            val minutes = (diffMs / 60_000).toInt()
            when {
                minutes < 1 -> "Updated just now"
                minutes < 60 -> "Updated ${minutes}m ago"
                else -> "Updated ${minutes / 60}h ago"
            }
        } catch (e: Exception) {
            "Updated just now"
        }
    }

    private fun cacheData(context: Context, data: UsageData) {
        val prefs = context.getSharedPreferences("pulse_cache", Context.MODE_PRIVATE)
        prefs.edit()
            .putFloat("five_hour", data.fiveHourUtilization.toFloat())
            .putString("five_hour_reset", data.fiveHourResetsAt)
            .putFloat("seven_day", data.sevenDayUtilization.toFloat())
            .putString("seven_day_reset", data.sevenDayResetsAt)
            .putFloat("sonnet", data.sonnetUtilization.toFloat())
            .putString("sonnet_reset", data.sonnetResetsAt)
            .putString("plan_label", data.planLabel)
            .putString("cached_at", data.cachedAt)
            .apply()
    }

    private fun loadCachedData(context: Context): UsageData? {
        val prefs = context.getSharedPreferences("pulse_cache", Context.MODE_PRIVATE)
        if (!prefs.contains("five_hour")) return null
        return UsageData(
            fiveHourUtilization = prefs.getFloat("five_hour", 0f).toDouble(),
            fiveHourResetsAt = prefs.getString("five_hour_reset", null),
            sevenDayUtilization = prefs.getFloat("seven_day", 0f).toDouble(),
            sevenDayResetsAt = prefs.getString("seven_day_reset", null),
            sonnetUtilization = prefs.getFloat("sonnet", 0f).toDouble(),
            sonnetResetsAt = prefs.getString("sonnet_reset", null),
            planLabel = prefs.getString("plan_label", "") ?: "",
            cachedAt = prefs.getString("cached_at", null)
        )
    }
}
