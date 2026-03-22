package com.ghayyath.claudepulse

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

class SetupActivity : AppCompatActivity() {

    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        // If already set up, skip straight to done (unless re-setup requested)
        if (TokenManager.hasCredentials(this) && intent?.getBooleanExtra("re_setup", false) != true) {
            finish()
            return
        }

        val tokenInput = findViewById<EditText>(R.id.token_input)
        val connectButton = findViewById<Button>(R.id.connect_button)
        val statusText = findViewById<TextView>(R.id.status_text)

        connectButton.setOnClickListener {
            val token = tokenInput.text.toString().trim()
            if (token.isEmpty()) {
                statusText.text = "Please paste your refresh token"
                statusText.setTextColor(0xFFF44336.toInt())
                statusText.visibility = View.VISIBLE
                return@setOnClickListener
            }

            connectButton.isEnabled = false
            statusText.text = "Connecting..."
            statusText.setTextColor(0xFFAAAAAAAA.toInt())
            statusText.visibility = View.VISIBLE

            // Save token and test it
            val appContext = applicationContext
            TokenManager.saveRefreshToken(appContext, token)

            executor.execute {
                val data = ApiClient.fetchUsage(appContext)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread

                    if (data.error == null) {
                        statusText.text = "Connected! You can close this app."
                        statusText.setTextColor(0xFF4CAF50.toInt())

                        // Trigger widget update
                        triggerWidgetUpdate()

                        // Auto-close after brief delay
                        statusText.postDelayed({ finish() }, 1500)
                    } else if (data.error == "auth_error") {
                        statusText.text = "Invalid token. Check and try again."
                        statusText.setTextColor(0xFFF44336.toInt())
                        connectButton.isEnabled = true
                        TokenManager.clearCredentials(appContext)
                    } else {
                        statusText.text = "Network error: ${data.error}. Token saved — widget will retry."
                        statusText.setTextColor(0xFFFF9800.toInt())
                        connectButton.isEnabled = true

                        // Trigger widget update anyway — it'll retry on its schedule
                        triggerWidgetUpdate()
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
