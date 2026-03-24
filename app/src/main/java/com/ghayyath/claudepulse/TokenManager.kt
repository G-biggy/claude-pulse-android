package com.ghayyath.claudepulse

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object TokenManager {

    private const val PREFS_NAME = "pulse_credentials"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_EXPIRES_AT = "expires_at"

    private const val REFRESH_URL = "https://console.anthropic.com/v1/oauth/token"
    private const val CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun hasCredentials(context: Context): Boolean {
        val prefs = getPrefs(context)
        return prefs.getString(KEY_REFRESH_TOKEN, null) != null ||
               prefs.getString(KEY_ACCESS_TOKEN, null) != null
    }

    fun saveRefreshToken(context: Context, refreshToken: String) {
        getPrefs(context).edit()
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .remove(KEY_ACCESS_TOKEN)
            .putLong(KEY_EXPIRES_AT, 0)
            .commit()
    }

    /** Save an access token directly — no refresh token rotation */
    fun saveAccessToken(context: Context, accessToken: String) {
        // Set expiry to ~8 hours from now (access tokens last ~8h)
        val expiresAt = System.currentTimeMillis() + (8 * 3600 * 1000)
        getPrefs(context).edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putLong(KEY_EXPIRES_AT, expiresAt)
            .remove(KEY_REFRESH_TOKEN)
            .commit()
    }

    fun clearCredentials(context: Context) {
        getPrefs(context).edit().clear().commit()
    }

    fun getAccessToken(context: Context): String? {
        val prefs = getPrefs(context)
        val accessToken = prefs.getString(KEY_ACCESS_TOKEN, null)
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0)

        if (accessToken != null && System.currentTimeMillis() < expiresAt - 300_000) {
            return accessToken
        }

        return refreshAccessToken(context)
    }

    fun getMaskedToken(context: Context): String? {
        val prefs = getPrefs(context)
        val token = prefs.getString(KEY_ACCESS_TOKEN, null)
            ?: prefs.getString(KEY_REFRESH_TOKEN, null)
            ?: return null
        if (token.length < 8) return "****"
        return "${token.take(4)}...${token.takeLast(4)}"
    }

    @Synchronized
    fun refreshAccessToken(context: Context): String? {
        val prefs = getPrefs(context)

        // Double-check: another thread may have already refreshed
        val currentToken = prefs.getString(KEY_ACCESS_TOKEN, null)
        val currentExpiry = prefs.getLong(KEY_EXPIRES_AT, 0)
        if (currentToken != null && System.currentTimeMillis() < currentExpiry - 300_000) {
            return currentToken
        }

        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null) ?: return null

        val url = URL(REFRESH_URL)
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000

            val body = JSONObject().apply {
                put("grant_type", "refresh_token")
                put("refresh_token", refreshToken)
                put("client_id", CLIENT_ID)
            }
            conn.outputStream.bufferedWriter().use { it.write(body.toString()) }

            if (conn.responseCode == 200) {
                val response = JSONObject(conn.inputStream.bufferedReader().readText())
                val newAccessToken = response.getString("access_token")
                val newRefreshToken = response.optString("refresh_token", refreshToken)
                val expiresIn = response.optLong("expires_in", 28800)

                val expiresAt = System.currentTimeMillis() + (expiresIn * 1000)

                prefs.edit()
                    .putString(KEY_ACCESS_TOKEN, newAccessToken)
                    .putString(KEY_REFRESH_TOKEN, newRefreshToken)
                    .putLong(KEY_EXPIRES_AT, expiresAt)
                    .commit()  // sync write

                newAccessToken
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
