package com.ghayyath.claudepulse

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
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
        private const val BRAND_COLOR = 0xFF6ee7b7.toInt()
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            renderWidget(context, appWidgetManager, appWidgetId)
            scheduleRefresh(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle?) {
        renderWidget(context, appWidgetManager, appWidgetId)
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

        val tapIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://claude.ai/settings/usage"))
        val pi = PendingIntent.getActivity(context, 0, tapIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.widget_root, pi)

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

        views.setTextViewText(R.id.updated_ago, formatTimeSince(data.cachedAt))

        // Session
        views.setProgressBar(R.id.five_hour_bar, 100, sessionPct, false)
        views.setTextViewText(R.id.five_hour_pct, "${sessionPct}%")
        views.setTextViewText(R.id.five_hour_reset, formatResetTime(data.fiveHourResetsAt))
        views.setTextColor(R.id.five_hour_pct, BRAND_COLOR)

        // Weekly
        views.setProgressBar(R.id.weekly_bar, 100, weeklyPct, false)
        views.setTextViewText(R.id.weekly_pct, "${weeklyPct}%")
        views.setTextViewText(R.id.weekly_reset, formatResetTime(data.sevenDayResetsAt))
        views.setTextColor(R.id.weekly_pct, BRAND_COLOR)

        // Sonnet
        views.setProgressBar(R.id.sonnet_bar, 100, sonnetPct, false)
        views.setTextViewText(R.id.sonnet_pct, "${sonnetPct}%")
        views.setTextViewText(R.id.sonnet_reset, formatResetTime(data.sonnetResetsAt))
        views.setTextColor(R.id.sonnet_pct, BRAND_COLOR)

        return views
    }

    private fun buildCompactViews(context: Context, data: UsageData): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_layout_small)

        val sessionPct = data.fiveHourUtilization.toInt().coerceIn(0, 100)
        val weeklyPct = data.sevenDayUtilization.toInt().coerceIn(0, 100)
        val sonnetPct = data.sonnetUtilization.toInt().coerceIn(0, 100)

        views.setProgressBar(R.id.five_hour_bar, 100, sessionPct, false)
        views.setTextViewText(R.id.five_hour_pct, "${sessionPct}%")
        views.setTextColor(R.id.five_hour_pct, BRAND_COLOR)

        views.setProgressBar(R.id.weekly_bar, 100, weeklyPct, false)
        views.setTextViewText(R.id.weekly_pct, "${weeklyPct}%")
        views.setTextColor(R.id.weekly_pct, BRAND_COLOR)

        views.setProgressBar(R.id.sonnet_bar, 100, sonnetPct, false)
        views.setTextViewText(R.id.sonnet_pct, "${sonnetPct}%")
        views.setTextColor(R.id.sonnet_pct, BRAND_COLOR)

        return views
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
            .putFloat("sonnet", data.sonnetUtilization.toFloat())
            .putString("sonnet_reset", data.sonnetResetsAt)
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
            cachedAt = prefs.getString("cached_at", null)
        )
    }
}
