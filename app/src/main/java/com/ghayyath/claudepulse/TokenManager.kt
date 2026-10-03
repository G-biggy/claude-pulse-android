package com.ghayyath.claudepulse

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.net.HttpURLConnection
import java.net.URL

object TokenManager {

    private const val PREFS_NAME = "pulse_credentials"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_EXPIRES_AT = "expires_at"

    private const val REFRESH_URL = "https://platform.claude.com/v1/oauth/token"
    private const val CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"

    // Claude Code's manual ("paste the code") login flow — gives the phone its own refresh token
    private const val AUTHORIZE_URL = "https://claude.com/cai/oauth/authorize"
    private const val MANUAL_REDIRECT_URL = "https://platform.claude.com/oauth/code/callback"
    private const val SCOPES = "user:profile user:inference"
    private const val KEY_PENDING_VERIFIER = "pending_verifier"
    private const val KEY_PENDING_STATE = "pending_state"

    /** Why the last refresh failed: "auth_error", "rate_limited", "Offline", or "HTTP nnn". */
    @Volatile var lastRefreshError: String? = null
        private set

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

    /**
     * Save an access token directly — no refresh token rotation. No local expiry:
     * long-lived tokens (`claude setup-token`) last months; a 401 sends the user back to setup.
     */
    fun saveAccessToken(context: Context, accessToken: String) {
        getPrefs(context).edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putLong(KEY_EXPIRES_AT, Long.MAX_VALUE)
            .remove(KEY_REFRESH_TOKEN)
            .commit()
    }

    fun clearCredentials(context: Context) {
        getPrefs(context).edit().clear().commit()
    }

    /** Start a PKCE login: stores verifier/state (survives the trip to the browser) and returns the URL to open. */
    fun buildLoginUrl(context: Context): String {
        val verifier = randomUrlSafe(32)
        val state = randomUrlSafe(32)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        getPrefs(context).edit()
            .putString(KEY_PENDING_VERIFIER, verifier)
            .putString(KEY_PENDING_STATE, state)
            .commit()
        return Uri.parse(AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("code", "true")
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", MANUAL_REDIRECT_URL)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .build().toString()
    }

    fun hasPendingLogin(context: Context): Boolean =
        getPrefs(context).getString(KEY_PENDING_VERIFIER, null) != null

    /**
     * Exchange the code shown on the callback page ("code#state") for tokens.
     * Returns null on success, or an error string ("auth_error", "rate_limited", "Offline", "HTTP nnn").
     */
    fun completeLogin(context: Context, pasted: String): String? {
        val prefs = getPrefs(context)
        val verifier = prefs.getString(KEY_PENDING_VERIFIER, null) ?: return "auth_error"
        val expectedState = prefs.getString(KEY_PENDING_STATE, null)
        val code = pasted.substringBefore("#")
        val state = pasted.substringAfter("#", expectedState ?: "")
        if (expectedState != null && state != expectedState) return "state_mismatch"

        val conn = URL(REFRESH_URL).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            val body = JSONObject().apply {
                put("grant_type", "authorization_code")
                put("code", code)
                put("redirect_uri", MANUAL_REDIRECT_URL)
                put("client_id", CLIENT_ID)
                put("code_verifier", verifier)
                put("state", state)
            }
            conn.outputStream.bufferedWriter().use { it.write(body.toString()) }

            if (conn.responseCode == 200) {
                val response = JSONObject(conn.inputStream.bufferedReader().readText())
                val expiresIn = response.optLong("expires_in", 28800)
                prefs.edit()
                    .putString(KEY_ACCESS_TOKEN, response.getString("access_token"))
                    .putString(KEY_REFRESH_TOKEN, response.optString("refresh_token").takeIf { it.isNotEmpty() })
                    .putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + expiresIn * 1000)
                    .remove(KEY_PENDING_VERIFIER)
                    .remove(KEY_PENDING_STATE)
                    .commit()
                null
            } else {
                val errorBody = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                when {
                    conn.responseCode == 429 || errorBody?.contains("rate_limit_error") == true -> "rate_limited"
                    conn.responseCode == 400 || conn.responseCode == 401 -> "auth_error"
                    else -> "HTTP ${conn.responseCode}"
                }
            }
        } catch (e: Exception) {
            "Offline"
        } finally {
            conn.disconnect()
        }
    }

    private fun randomUrlSafe(bytes: Int): String =
        base64Url(ByteArray(bytes).also { SecureRandom().nextBytes(it) })

    private fun base64Url(data: ByteArray): String =
        Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

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

        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null)
        if (refreshToken == null) {
            lastRefreshError = "auth_error"
            return null
        }

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

                lastRefreshError = null
                newAccessToken
            } else {
                val errorBody = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                lastRefreshError = when {
                    conn.responseCode == 429 || errorBody?.contains("rate_limit_error") == true -> "rate_limited"
                    conn.responseCode == 400 || conn.responseCode == 401 -> "auth_error"  // invalid_grant
                    else -> "HTTP ${conn.responseCode}"
                }
                null
            }
        } catch (e: Exception) {
            lastRefreshError = "Offline"
            null
        } finally {
            conn.disconnect()
        }
    }
}
