package com.pylikv.tachowatch

import android.app.NotificationManager
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class LimitAlertActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ALERT_KEY = "limit_alert_key"
        const val EXTRA_ALERT_TEXT = "limit_alert_text"
    }

    private var currentKey: String = ""
    private lateinit var messageView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
            setBackgroundColor(Color.rgb(20, 20, 20))
        }

        root.addView(TextView(this).apply {
            text = "ВНИМАНИЕ"
            textSize = 34f
            setTextColor(Color.rgb(255, 80, 80))
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        messageView = TextView(this).apply {
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(32), 0, dp(36))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        root.addView(
            messageView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(TextView(this).apply {
            text = "Подтвердите, что предупреждение замечено."
            textSize = 17f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(24))
        })

        val confirm = Button(this).apply {
            text = "ПОНЯЛ"
            textSize = 24f
            isAllCaps = true
            setOnClickListener { acknowledgeAndClose() }
        }
        root.addView(
            confirm,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(72)
            )
        )

        setContentView(root)
        render(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render(intent)
    }

    @Deprecated("Alert requires explicit acknowledgement")
    override fun onBackPressed() {
        // Intentionally blocked: the warning is dismissed only by PONЯЛ.
    }

    private fun render(intent: Intent) {
        currentKey = intent.getStringExtra(EXTRA_ALERT_KEY).orEmpty()
        messageView.text = intent.getStringExtra(EXTRA_ALERT_TEXT)
            ?: "Предупреждение о лимите времени"
    }

    private fun acknowledgeAndClose() {
        if (currentKey.isNotBlank()) {
            // Save acknowledgement synchronously before cancelling the notification.
            // This closes the race where a service callback could re-post the same
            // threshold while the confirmation window is being dismissed.
            getSharedPreferences(DriverLiveService.PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean("alert_ack_$currentKey", true)
                .putBoolean("alert_shown_$currentKey", true)
                .commit()
        }

        try {
            getSystemService(NotificationManager::class.java)
                .cancel(DriverLiveService.ALERT_NOTIFICATION_ID)
        } catch (_: Throwable) {
        }

        finishAndRemoveTask()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
