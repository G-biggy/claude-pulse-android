package com.ghayyath.claudepulse

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
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

    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    private fun getPrefs(context: Context): SharedPreferences {
        return cachedPrefs ?: synchronized(this) {
            cachedPrefs ?: EncryptedSharedPreferences.create(
                PREFS_NAME,
                MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                context.applicationContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            ).also { cachedPrefs = it }
        }
    }

    fun hasCredentials(context: Context): Boolean {
        return getPrefs(context).getString(KEY_REFRESH_TOKEN, null) != null
    }

    fun saveRefreshToken(context: Context, refreshToken: String) {
        getPrefs(context).edit()
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putString(KEY_ACCESS_TOKEN, null)
            .putLong(KEY_EXPIRES_AT, 0)
            .apply()
    }

    fun clearCredentials(context: Context) {
        getPrefs(context).edit().clear().apply()
    }

    /**
     * Returns a valid access token. Refreshes automatically if expired.
     * Returns null if no credentials or refresh fails.
     */
    fun getAccessToken(context: Context): String? {
        val prefs = getPrefs(context)
        val accessToken = prefs.getString(KEY_ACCESS_TOKEN, null)
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0)

        // Return current token if still valid (with 5-min buffer)
        if (accessToken != null && System.currentTimeMillis() < expiresAt - 300_000) {
            return accessToken
        }

        // Need to refresh
        return refreshAccessToken(context)
    }

    /**
     * Refreshes the access token using the stored refresh token.
     * Synchronized to prevent concurrent refreshes from burning rotated tokens.
     * Returns the new access token, or null on failure.
     */
    @Synchronized
    fun refreshAccessToken(context: Context): String? {
        val prefs = getPrefs(context)

        // Double-check: another thread may have already refreshed while we waited
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
                val expiresIn = response.optLong("expires_in", 28800) // 8 hours default

                val expiresAt = System.currentTimeMillis() + (expiresIn * 1000)

                prefs.edit()
                    .putString(KEY_ACCESS_TOKEN, newAccessToken)
                    .putString(KEY_REFRESH_TOKEN, newRefreshToken)
                    .putLong(KEY_EXPIRES_AT, expiresAt)
                    .apply()

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
