package com.ghayyath.claudepulse

import org.json.JSONObject

data class UsageData(
    val fiveHourUtilization: Double,
    val fiveHourResetsAt: String?,
    val sevenDayUtilization: Double,
    val sevenDayResetsAt: String?,
    val sonnetUtilization: Double,
    val sonnetResetsAt: String?,
    val planLabel: String,
    val cachedAt: String?,
    val error: String? = null
) {
    companion object {
        fun fromJson(json: JSONObject): UsageData {
            val fiveHour = json.optJSONObject("five_hour")
            val sevenDay = json.optJSONObject("seven_day")
            val sonnet = json.optJSONObject("seven_day_sonnet")
            val extra = json.optJSONObject("extra_usage")

            val plan = when {
                extra != null && extra.optBoolean("is_enabled", false) -> {
                    when {
                        extra.optInt("monthly_limit", 0) >= 20000 -> "Max 20x"
                        else -> "Max 5x"
                    }
                }
                fiveHour == null && sevenDay == null -> "Free"
                else -> "Pro"
            }

            return UsageData(
                fiveHourUtilization = fiveHour?.optDouble("utilization", 0.0) ?: 0.0,
                fiveHourResetsAt = fiveHour?.optString("resets_at", null),
                sevenDayUtilization = sevenDay?.optDouble("utilization", 0.0) ?: 0.0,
                sevenDayResetsAt = sevenDay?.optString("resets_at", null),
                sonnetUtilization = sonnet?.optDouble("utilization", 0.0) ?: 0.0,
                sonnetResetsAt = sonnet?.optString("resets_at", null),
                planLabel = plan,
                cachedAt = json.optString("cached_at", null),
                error = json.optString("error", null).takeIf { it != "null" && !it.isNullOrEmpty() }
            )
        }

        fun placeholder(): UsageData = UsageData(
            fiveHourUtilization = 0.0,
            fiveHourResetsAt = null,
            sevenDayUtilization = 0.0,
            sevenDayResetsAt = null,
            sonnetUtilization = 0.0,
            sonnetResetsAt = null,
            planLabel = "",
            cachedAt = null,
            error = "No data yet"
        )
    }
}
