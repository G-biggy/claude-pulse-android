package com.ghayyath.claudepulse

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object ApiClient {

    fun fetchUsage(): UsageData {
        val url = URL(BuildConfig.PULSE_API_URL)
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.setRequestProperty("X-Pulse-Token", BuildConfig.PULSE_API_TOKEN)
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000

            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                UsageData.fromJson(JSONObject(body))
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
