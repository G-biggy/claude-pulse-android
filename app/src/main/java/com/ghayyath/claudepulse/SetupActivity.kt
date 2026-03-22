package com.ghayyath.claudepulse

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import java.util.concurrent.Executors

class SetupActivity : Activity() {

    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        val tokenInput = findViewById<EditText>(R.id.token_input)
        val connectButton = findViewById<Button>(R.id.connect_button)
        val statusText = findViewById<TextView>(R.id.status_text)

        // If already connected, show status but let user update
        if (TokenManager.hasCredentials(this)) {
            statusText.text = "Connected \u2714  \u2014  Paste a new token below to update."
            statusText.setTextColor(0xFF4CAF50.toInt())
            statusText.visibility = View.VISIBLE
            connectButton.text = "Update Token"
        }

        connectButton.setOnClickListener {
            val token = tokenInput.text.toString().trim()
            if (token.isEmpty()) {
                statusText.text = "Please paste a token"
                statusText.setTextColor(0xFFF44336.toInt())
                statusText.visibility = View.VISIBLE
                return@setOnClickListener
            }

            connectButton.isEnabled = false
            statusText.text = "Connecting..."
            statusText.setTextColor(0xFFAAAAAA.toInt())
            statusText.visibility = View.VISIBLE

            val appContext = applicationContext

            executor.execute {
                // Try as access token first
                val directResult = ApiClient.fetchUsageWithAccessToken(appContext, token)

                if (directResult.error == null) {
                    TokenManager.saveAccessToken(appContext, token)
                    ApiClient.cacheUsage(appContext, directResult)

                    runOnUiThread {
                        if (isFinishing) return@runOnUiThread
                        statusText.text = "Connected \u2714  \u2014  You can close this app and use the widget."
                        statusText.setTextColor(0xFF4CAF50.toInt())
                        connectButton.text = "Update Token"
                        connectButton.isEnabled = true
                        tokenInput.text.clear()
                        triggerWidgetUpdate()
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
                        statusText.text = "Connected \u2714  \u2014  You can close this app and use the widget."
                        statusText.setTextColor(0xFF4CAF50.toInt())
                        connectButton.text = "Update Token"
                        connectButton.isEnabled = true
                        tokenInput.text.clear()
                        triggerWidgetUpdate()
                    } else {
                        statusText.text = "Invalid token. Check and try again."
                        statusText.setTextColor(0xFFF44336.toInt())
                        connectButton.isEnabled = true
                        TokenManager.clearCredentials(appContext)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
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
