package com.ghayyath.claudepulse

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

object ApiClient {

    private const val USAGE_URL = "https://api.anthropic.com/api/oauth/usage"
    private const val API_BETA = "oauth-2025-04-20"

    fun fetchUsage(context: Context): UsageData {
        val token = TokenManager.getAccessToken(context)
            ?: return UsageData.placeholder().copy(error = "auth_error")

        val result = callUsageApi(token)

        // On auth error, force refresh and retry once
        if (result.error == "HTTP 401" || result.error == "HTTP 403") {
            val freshToken = TokenManager.refreshAccessToken(context)
                ?: return UsageData.placeholder().copy(error = "auth_error")
            return callUsageApi(freshToken)
        }

        return result
    }

    private fun callUsageApi(accessToken: String): UsageData {
        val url = URL(USAGE_URL)
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $accessToken")
            conn.setRequestProperty("anthropic-beta", API_BETA)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000

            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(body)

                // API doesn't include cached_at — generate it locally
                val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date())

                UsageData.fromJson(json).copy(cachedAt = now)
            } else {
                UsageData.placeholder().copy(error = "HTTP ${conn.responseCode}")
            }
        } catch (e: Exception) {
            UsageData.placeholder().copy(error = "Offline")
        } finally {
            conn.disconnect()
        }
    }
}
