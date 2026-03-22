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
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class PulseWidget : AppWidgetProvider() {

    companion object {
        private val executor = Executors.newSingleThreadExecutor()
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle?) {
        updateWidget(context, appWidgetManager, appWidgetId)
    }

    private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        // Show loading state immediately
        val loadingViews = RemoteViews(context.packageName, R.layout.widget_layout)
        loadingViews.setTextViewText(R.id.updated_ago, "Updating...")
        appWidgetManager.updateAppWidget(appWidgetId, loadingViews)

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        executor.execute {
            try {
                val data = try {
                    ApiClient.fetchUsage(appContext)
                } catch (e: Exception) {
                    loadCachedData(appContext) ?: UsageData.placeholder()
                }

                // Cache successful data
                if (data.error == null) {
                    cacheData(appContext, data)
                }

                val displayData = if (data.error != null) {
                    loadCachedData(appContext) ?: data
                } else {
                    data
                }

                val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
                val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
                val isCompact = minWidth < 200

                val views = if (isCompact) {
                    buildCompactViews(appContext, displayData, data.error != null)
                } else {
                    buildFullViews(appContext, displayData, data.error != null)
                }

                // Tap action: open setup on auth error, otherwise open Claude usage page
                val isAuthError = data.error == "auth_error"
                val tapIntent = if (isAuthError || !TokenManager.hasCredentials(appContext)) {
                    Intent(appContext, SetupActivity::class.java).apply {
                        putExtra("re_setup", true)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                } else {
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://claude.ai/settings/usage"))
                }
                val pendingIntent = PendingIntent.getActivity(appContext, 0, tapIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

                appWidgetManager.updateAppWidget(appWidgetId, views)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun buildFullViews(context: Context, data: UsageData, isOffline: Boolean): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_layout)

        val fiveHourPct = data.fiveHourUtilization.toInt().coerceIn(0, 100)
        val weeklyPct = data.sevenDayUtilization.toInt().coerceIn(0, 100)

        // Title and update time
        val statusText = when {
            data.error == "auth_error" -> "Tap to re-setup"
            isOffline -> "Offline"
            else -> formatTimeSince(data.cachedAt)
        }
        views.setTextViewText(R.id.updated_ago, statusText)

        // 5-hour bar
        views.setProgressBar(R.id.five_hour_bar, 100, fiveHourPct, false)
        views.setTextViewText(R.id.five_hour_pct, "${fiveHourPct}%")
        views.setTextViewText(R.id.five_hour_reset, formatResetTime(data.fiveHourResetsAt))
        views.setInt(R.id.five_hour_bar, "setProgressTintList", 0) // handled by drawable

        // Weekly bar
        views.setProgressBar(R.id.weekly_bar, 100, weeklyPct, false)
        views.setTextViewText(R.id.weekly_pct, "${weeklyPct}%")
        views.setTextViewText(R.id.weekly_reset, formatResetTime(data.sevenDayResetsAt))

        // Color the percentage text based on threshold
        views.setTextColor(R.id.five_hour_pct, getThresholdColor(fiveHourPct))
        views.setTextColor(R.id.weekly_pct, getThresholdColor(weeklyPct))

        return views
    }

    private fun buildCompactViews(context: Context, data: UsageData, isOffline: Boolean): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_layout_small)

        val fiveHourPct = data.fiveHourUtilization.toInt().coerceIn(0, 100)
        val weeklyPct = data.sevenDayUtilization.toInt().coerceIn(0, 100)

        views.setProgressBar(R.id.five_hour_bar, 100, fiveHourPct, false)
        views.setTextViewText(R.id.five_hour_pct, "${fiveHourPct}%")
        views.setProgressBar(R.id.weekly_bar, 100, weeklyPct, false)
        views.setTextViewText(R.id.weekly_pct, "${weeklyPct}%")

        views.setTextColor(R.id.five_hour_pct, getThresholdColor(fiveHourPct))
        views.setTextColor(R.id.weekly_pct, getThresholdColor(weeklyPct))

        return views
    }

    private fun getThresholdColor(pct: Int): Int = when {
        pct >= 90 -> 0xFFF44336.toInt() // Red
        pct >= 75 -> 0xFFFF5722.toInt() // Orange
        pct >= 50 -> 0xFFFF9800.toInt() // Yellow
        else -> 0xFF4CAF50.toInt()      // Green
    }

    private fun formatResetTime(isoTime: String?): String {
        if (isoTime.isNullOrEmpty() || isoTime == "null") return ""
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            val resetDate = sdf.parse(isoTime) ?: return ""
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
            cachedAt = prefs.getString("cached_at", null)
        )
    }
}
