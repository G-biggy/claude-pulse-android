package com.ghayyath.claudepulse

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** One usage row: Session, Weekly, or a per-model scoped limit (e.g. Fable). */
data class Limit(
    val label: String,
    val percent: Int,
    val resetsAt: String?
)

data class UsageData(
    val limits: List<Limit>,
    val planLabel: String,
    val cachedAt: String?,
    val error: String? = null
) {
    companion object {
        fun fromJson(json: JSONObject): UsageData {
            val extra = json.optJSONObject("extra_usage")
            val limits = parseLimits(json)

            val plan = when {
                extra != null && extra.optBoolean("is_enabled", false) -> {
                    when {
                        extra.optInt("monthly_limit", 0) >= 20000 -> "Max 20x"
                        else -> "Max 5x"
                    }
                }
                limits.isEmpty() -> "Free"
                else -> "Pro"
            }

            return UsageData(
                limits = limits,
                planLabel = plan,
                cachedAt = json.optNullableString("cached_at"),
                error = json.optNullableString("error")
            )
        }

        /** Prefer the generic `limits` array; fall back to legacy five_hour/seven_day fields. */
        private fun parseLimits(json: JSONObject): List<Limit> {
            val arr = json.optJSONArray("limits")
            if (arr != null && arr.length() > 0) {
                return (0 until arr.length()).mapNotNull { i ->
                    val entry = arr.optJSONObject(i) ?: return@mapNotNull null
                    val kind = entry.optString("kind")
                    val scope = entry.optJSONObject("scope")
                    val label = when (kind) {
                        "session" -> "Session"
                        "weekly_all" -> "Weekly"
                        else -> scope?.optJSONObject("model")?.optNullableString("display_name")
                            ?: scope?.optJSONObject("surface")?.optNullableString("display_name")
                            ?: kind
                    }
                    Limit(label, entry.optDouble("percent", 0.0).roundToInt(), entry.optNullableString("resets_at"))
                }
            }

            return listOf(
                "Session" to "five_hour",
                "Weekly" to "seven_day",
                "Sonnet" to "seven_day_sonnet"
            ).mapNotNull { (label, key) ->
                val obj = json.optJSONObject(key) ?: return@mapNotNull null
                Limit(label, obj.optDouble("utilization", 0.0).roundToInt(), obj.optNullableString("resets_at"))
            }
        }

        fun limitsToJson(limits: List<Limit>): String = JSONArray().apply {
            limits.forEach {
                put(JSONObject().put("label", it.label).put("percent", it.percent).put("resets_at", it.resetsAt))
            }
        }.toString()

        fun limitsFromJson(raw: String): List<Limit> {
            val arr = JSONArray(raw)
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Limit(o.optString("label"), o.optInt("percent", 0), o.optNullableString("resets_at"))
            }
        }

        fun placeholder(): UsageData = UsageData(
            limits = listOf(Limit("Session", 0, null), Limit("Weekly", 0, null)),
            planLabel = "",
            cachedAt = null,
            error = "No data yet"
        )
    }
}

/** optString returns "null" for JSON null — normalize that (and empty) to Kotlin null. */
private fun JSONObject.optNullableString(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() && it != "null" }
