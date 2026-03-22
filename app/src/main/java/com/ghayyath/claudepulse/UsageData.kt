package com.ghayyath.claudepulse

import org.json.JSONObject

data class UsageData(
    val fiveHourUtilization: Double,
    val fiveHourResetsAt: String?,
    val sevenDayUtilization: Double,
    val sevenDayResetsAt: String?,
    val cachedAt: String?,
    val error: String? = null
) {
    companion object {
        fun fromJson(json: JSONObject): UsageData {
            val fiveHour = json.optJSONObject("five_hour")
            val sevenDay = json.optJSONObject("seven_day")
            return UsageData(
                fiveHourUtilization = fiveHour?.optDouble("utilization", 0.0) ?: 0.0,
                fiveHourResetsAt = fiveHour?.optString("resets_at", null),
                sevenDayUtilization = sevenDay?.optDouble("utilization", 0.0) ?: 0.0,
                sevenDayResetsAt = sevenDay?.optString("resets_at", null),
                cachedAt = json.optString("cached_at", null),
                error = json.optString("error", null).takeIf { it != "null" && !it.isNullOrEmpty() }
            )
        }

        fun placeholder(): UsageData = UsageData(
            fiveHourUtilization = 0.0,
            fiveHourResetsAt = null,
            sevenDayUtilization = 0.0,
            sevenDayResetsAt = null,
            cachedAt = null,
            error = "No data yet"
        )
    }
}
