package com.ghayyath.claudepulse

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.work.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SetupActivity : Activity() {

    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        val tokenInput = findViewById<EditText>(R.id.token_input)
        val connectButton = findViewById<Button>(R.id.connect_button)
        val statusText = findViewById<TextView>(R.id.status_text)
        val errorBanner = findViewById<LinearLayout>(R.id.error_banner)
        val signInButton = findViewById<Button>(R.id.sign_in_button)

        signInButton.setOnClickListener {
            val url = TokenManager.buildLoginUrl(this)
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            statusText.text = "After approving, copy the code and paste it below."
            statusText.setTextColor(0xFFAAAAAA.toInt())
            statusText.visibility = View.VISIBLE
        }

        // If already connected, validate the token in background
        if (TokenManager.hasCredentials(this)) {
            statusText.text = "Validating token..."
            statusText.setTextColor(0xFFAAAAAA.toInt())
            statusText.visibility = View.VISIBLE
            connectButton.text = "Update Token"

            // Show masked token as hint
            val masked = TokenManager.getMaskedToken(this)
            if (masked != null) {
                tokenInput.hint = masked
            }

            val appContext = applicationContext
            executor.execute {
                val result = ApiClient.fetchUsage(appContext)
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    when (result.error) {
                        null -> {
                            // Token is valid
                            statusText.text = "Connected \u2714"
                            statusText.setTextColor(0xFF4CAF50.toInt())
                            errorBanner.visibility = View.GONE
                        }
                        "auth_error" -> {
                            // Token is expired/invalid — show recovery banner
                            showErrorState(statusText, errorBanner, tokenInput)
                        }
                        "rate_limited" -> {
                            statusText.text = "Connected \u2714 Usage data temporarily unavailable (rate limited)"
                            statusText.setTextColor(0xFF4CAF50.toInt())
                            statusText.visibility = View.VISIBLE
                            errorBanner.visibility = View.GONE
                            val masked = TokenManager.getMaskedToken(this@SetupActivity)
                            if (masked != null) tokenInput.hint = masked
                        }
                        else -> {
                            // Network error or other — assume token is probably fine
                            statusText.text = "Connected \u2714 Could not verify (offline?)"
                            statusText.setTextColor(0xFFAAAAAA.toInt())
                            statusText.visibility = View.VISIBLE
                            errorBanner.visibility = View.GONE
                            val masked = TokenManager.getMaskedToken(this@SetupActivity)
                            if (masked != null) tokenInput.hint = masked
                        }
                    }
                }
            }
        } else {
            // Check if we have a cached auth_error (came from widget tap)
            val prefs = getSharedPreferences("pulse_cache", Context.MODE_PRIVATE)
            if (prefs.getBoolean("auth_error", false)) {
                showErrorState(statusText, errorBanner, tokenInput)
            }
        }

        connectButton.setOnClickListener {
            // Strip all whitespace: tokens copied from a terminal often wrap across lines
            val token = tokenInput.text.toString().replace(Regex("\\s+"), "")
            if (token.isEmpty()) {
                statusText.text = "Please paste a token"
                statusText.setTextColor(0xFFF44336.toInt())
                statusText.visibility = View.VISIBLE
                return@setOnClickListener
            }

            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(tokenInput.windowToken, 0)
            connectButton.isEnabled = false
            connectButton.text = "Connecting..."
            statusText.text = "Connecting..."
            statusText.setTextColor(0xFFAAAAAA.toInt())
            statusText.visibility = View.VISIBLE

            val appContext = applicationContext

            executor.execute {
                // Code from the "Sign in with Claude" flow looks like "code#state"
                if (TokenManager.hasPendingLogin(appContext) && token.contains("#")) {
                    val loginError = TokenManager.completeLogin(appContext, token)
                    val result = if (loginError == null) ApiClient.fetchUsage(appContext) else null
                    runOnUiThread {
                        if (isFinishing) return@runOnUiThread
                        if (result != null && result.error == null) {
                            ApiClient.cacheUsage(appContext, result)
                            clearAuthError(appContext)
                            showConnected(statusText, connectButton, tokenInput, errorBanner)
                        } else {
                            statusText.text = when (loginError ?: result?.error) {
                                "auth_error" -> "Code rejected or expired. Tap Sign in with Claude and try again."
                                "state_mismatch" -> "That code is from an older sign-in. Tap Sign in with Claude again."
                                "rate_limited" -> "Rate limited by Anthropic. Wait a minute and try again."
                                "Offline" -> "Can't reach Anthropic. Check your connection."
                                else -> "Couldn't connect (${loginError ?: result?.error}). Try again."
                            }
                            statusText.setTextColor(0xFFF44336.toInt())
                            connectButton.text = if (TokenManager.hasCredentials(appContext)) "Update Token" else "Connect"
                            connectButton.isEnabled = true
                        }
                    }
                    return@execute
                }

                // Try as access token first
                val directResult = ApiClient.fetchUsageWithAccessToken(appContext, token)

                if (directResult.error == null) {
                    TokenManager.saveAccessToken(appContext, token)
                    ApiClient.cacheUsage(appContext, directResult)
                    clearAuthError(appContext)

                    runOnUiThread {
                        if (isFinishing) return@runOnUiThread
                        statusText.text = "Connected \u2714"
                        statusText.setTextColor(0xFF4CAF50.toInt())
                        connectButton.text = "Update Token"
                        connectButton.isEnabled = true
                        tokenInput.text.clear()
                        errorBanner.visibility = View.GONE
                        // Show masked token as hint
                        val masked = TokenManager.getMaskedToken(appContext)
                        if (masked != null) tokenInput.hint = masked
                        triggerWidgetUpdate()
                        ensurePeriodicRefresh()
                    }
                    return@execute
                }

                // Try as refresh token
                TokenManager.saveRefreshToken(appContext, token)
                val refreshResult = ApiClient.fetchUsage(appContext)

                runOnUiThread {
                    if (isFinishing) return@runOnUiThread

                    if (refreshResult.error == null) {
                        ApiClient.cacheUsage(appContext, refreshResult)
                        clearAuthError(appContext)
                        statusText.text = "Connected \u2714"
                        statusText.setTextColor(0xFF4CAF50.toInt())
                        connectButton.text = "Update Token"
                        connectButton.isEnabled = true
                        tokenInput.text.clear()
                        errorBanner.visibility = View.GONE
                        val masked = TokenManager.getMaskedToken(appContext)
                        if (masked != null) tokenInput.hint = masked
                        triggerWidgetUpdate()
                        ensurePeriodicRefresh()
                    } else {
                        statusText.text = when (refreshResult.error) {
                            "auth_error" -> "Invalid token. Check and try again."
                            "rate_limited" -> "Rate limited by Anthropic. Wait a minute and try again."
                            "Offline" -> "Can't reach Anthropic. Check your connection."
                            else -> "Couldn't connect (${refreshResult.error}). Try again."
                        }
                        statusText.setTextColor(0xFFF44336.toInt())
                        // Only discard the token if Anthropic actually rejected it
                        if (refreshResult.error == "auth_error") TokenManager.clearCredentials(appContext)
                        connectButton.text = if (TokenManager.hasCredentials(appContext)) "Update Token" else "Connect"
                        connectButton.isEnabled = true
                    }
                }
            }
        }
    }

    private fun showConnected(statusText: TextView, connectButton: Button, tokenInput: EditText, errorBanner: LinearLayout) {
        statusText.text = "Connected \u2714"
        statusText.setTextColor(0xFF4CAF50.toInt())
        statusText.visibility = View.VISIBLE
        connectButton.text = "Update Token"
        connectButton.isEnabled = true
        tokenInput.text.clear()
        errorBanner.visibility = View.GONE
        triggerWidgetUpdate()
        ensurePeriodicRefresh()
    }

    private fun showErrorState(statusText: TextView, errorBanner: LinearLayout, tokenInput: EditText) {
        statusText.text = "Token expired \u2014 paste a new one below"
        statusText.setTextColor(0xFFF44336.toInt())
        statusText.visibility = View.VISIBLE
        errorBanner.visibility = View.VISIBLE
        val masked = TokenManager.getMaskedToken(this)
        if (masked != null) tokenInput.hint = masked
    }

    private fun clearAuthError(context: Context) {
        context.getSharedPreferences("pulse_cache", Context.MODE_PRIVATE)
            .edit().putBoolean("auth_error", false).apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    private fun ensurePeriodicRefresh() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val work = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "pulse_periodic_refresh",
            ExistingPeriodicWorkPolicy.KEEP,
            work
        )
    }

    private fun triggerWidgetUpdate() {
        val appWidgetManager = AppWidgetManager.getInstance(this)
        val widgetComponent = ComponentName(this, PulseWidget::class.java)
        val widgetIds = appWidgetManager.getAppWidgetIds(widgetComponent)
        if (widgetIds.isNotEmpty()) {
            val intent = Intent(this, PulseWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, widgetIds)
            }
            sendBroadcast(intent)
        }
    }
}
