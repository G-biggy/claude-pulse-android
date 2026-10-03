package com.ghayyath.claudepulse

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
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

        /** Layout slots for limit rows: container, label, bar, pct, reset. Extra limits beyond this are dropped. */
        private data class RowIds(val row: Int, val label: Int, val bar: Int, val pct: Int, val reset: Int)
        private val ROWS = listOf(
            RowIds(R.id.row1, R.id.row1_label, R.id.row1_bar, R.id.row1_pct, R.id.row1_reset),
            RowIds(R.id.row2, R.id.row2_label, R.id.row2_bar, R.id.row2_pct, R.id.row2_reset),
            RowIds(R.id.row3, R.id.row3_label, R.id.row3_bar, R.id.row3_pct, R.id.row3_reset),
            RowIds(R.id.row4, R.id.row4_label, R.id.row4_bar, R.id.row4_pct, R.id.row4_reset)
        )
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
        val prefs = context.getSharedPreferences("pulse_cache", Context.MODE_PRIVATE)
        val hasAuthError = prefs.getBoolean("auth_error", false)
        val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
        val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
        val isCompact = minWidth < 200

        val views = if (isCompact) {
            buildCompactViews(context, data)
        } else {
            buildFullViews(context, data, hasAuthError)
        }

        if (!isCompact) {
            if (hasAuthError) {
                // Tap widget body -> open SetupActivity for token recovery
                val setupIntent = Intent(context, SetupActivity::class.java)
                val setupPi = PendingIntent.getActivity(context, 0, setupIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(R.id.widget_root, setupPi)
            } else {
                // No body tap target — removed per spec (footer has Usage Page link)
            }

            // Tap "Refresh Now" -> trigger widget update
            val refreshIntent = Intent(context, PulseWidget::class.java).apply {
                action = ACTION_REFRESH
            }
            val refreshPi = PendingIntent.getBroadcast(context, 1, refreshIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.refresh_button, refreshPi)

            // Tap "Usage Page" -> open usage page in browser
            val usageIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://claude.ai/settings/usage"))
            val usagePi = PendingIntent.getActivity(context, 2, usageIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.usage_page_button, usagePi)
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

    /** Tint a ProgressBar's fill color to match the percentage tier (API 31+, green fallback on older) */
    private fun setBarTint(views: RemoteViews, barId: Int, pct: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setColorStateList(barId, "setProgressTintList", ColorStateList.valueOf(getColor(pct)))
        }
    }

    private fun buildFullViews(context: Context, data: UsageData, hasAuthError: Boolean = false): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_layout)

        // Header
        if (data.planLabel.isNotEmpty()) {
            views.setTextViewText(R.id.plan_label, "\u00b7 ${data.planLabel}")
        } else {
            views.setTextViewText(R.id.plan_label, "")
        }
        if (hasAuthError) {
            views.setTextViewText(R.id.updated_ago, "Token expired \u00b7 Tap to fix")
            views.setTextColor(R.id.updated_ago, COLOR_RED)
        } else {
            views.setTextViewText(R.id.updated_ago, formatTimeSince(data.cachedAt))
            views.setTextColor(R.id.updated_ago, 0x80FFFFFF.toInt())
        }

        ROWS.forEachIndexed { i, ids ->
            val limit = data.limits.getOrNull(i)
            if (limit == null) {
                views.setViewVisibility(ids.row, View.GONE)
                return@forEachIndexed
            }
            views.setViewVisibility(ids.row, View.VISIBLE)
            views.setTextViewText(ids.label, limit.label)

            if (hasAuthError) {
                // Error state: dashes instead of stale numbers
                views.setProgressBar(ids.bar, 100, 0, false)
                views.setTextViewText(ids.pct, "\u2014")
                views.setTextViewText(ids.reset, "")
                views.setTextColor(ids.pct, COLOR_RED)
            } else {
                val pct = limit.percent.coerceIn(0, 100)
                views.setProgressBar(ids.bar, 100, pct, false)
                views.setTextViewText(ids.pct, "${pct}%")
                views.setTextViewText(ids.reset, formatResetTime(limit.resetsAt))
                views.setTextColor(ids.pct, getColor(pct))
                setBarTint(views, ids.bar, pct)
            }
        }

        return views
    }

    private fun buildCompactViews(context: Context, data: UsageData): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_layout_small)

        ROWS.forEachIndexed { i, ids ->
            val limit = data.limits.getOrNull(i)
            if (limit == null) {
                views.setViewVisibility(ids.row, View.GONE)
                return@forEachIndexed
            }
            val pct = limit.percent.coerceIn(0, 100)
            views.setViewVisibility(ids.row, View.VISIBLE)
            views.setProgressBar(ids.bar, 100, pct, false)
            views.setTextViewText(ids.pct, "${pct}%")
            views.setTextColor(ids.pct, getColor(pct))
            setBarTint(views, ids.bar, pct)
        }

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

    private fun cacheData(context: Context, data: UsageData) = ApiClient.cacheUsage(context, data)

    private fun loadCachedData(context: Context): UsageData? {
        val prefs = context.getSharedPreferences("pulse_cache", Context.MODE_PRIVATE)
        val raw = prefs.getString("limits_json", null) ?: return null
        val limits = try { UsageData.limitsFromJson(raw) } catch (_: Exception) { return null }
        return UsageData(
            limits = limits,
            planLabel = prefs.getString("plan_label", "") ?: "",
            cachedAt = prefs.getString("cached_at", null)
        )
    }
}
