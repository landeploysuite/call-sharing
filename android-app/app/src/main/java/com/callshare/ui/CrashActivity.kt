package com.callshare.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * CrashActivity
 *
 * Standalone crash reporter activity built 100% programmatically in Kotlin without XML layout
 * or resource dependencies. Runs in isolated process ':crash_reporter' so it can display
 * fatal startup crashes even if the main process or theme inflation completely failed.
 */
class CrashActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_STACK_TRACE = "EXTRA_STACK_TRACE"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val stackTrace = intent.getStringExtra(EXTRA_STACK_TRACE) ?: "No error stack trace available."

        // Root container with dark background
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0D14"))
            setPadding(32, 48, 32, 48)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Title Header
        val tvTitle = TextView(this).apply {
            text = "CallShare Startup / Runtime Crash"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#EF4444"))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 16, 0, 8)
        }
        rootLayout.addView(tvTitle)

        // Subtitle explanation
        val tvSubtitle = TextView(this).apply {
            text = "A critical exception interrupted app execution. Below is the full diagnostic trace:"
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 24)
        }
        rootLayout.addView(tvSubtitle)

        // Action Buttons Row
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }

        val btnCopy = Button(this).apply {
            text = "Copy Error Log"
            setBackgroundColor(Color.parseColor("#10B981"))
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("CallShare Crash Trace", stackTrace)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this@CrashActivity, "Error trace copied to clipboard!", Toast.LENGTH_SHORT).show()
            }
        }
        val btnParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ).apply {
            marginEnd = 16
        }
        btnRow.addView(btnCopy, btnParams)

        val btnRestart = Button(this).apply {
            text = "Restart App"
            setBackgroundColor(Color.parseColor("#06B6D4"))
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener {
                val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    startActivity(launchIntent)
                }
                finish()
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
        val restartParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        )
        btnRow.addView(btnRestart, restartParams)
        rootLayout.addView(btnRow)

        // Monospace scrollable stack trace box
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#000000"))
            setPadding(24, 24, 24, 24)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val tvTrace = TextView(this).apply {
            text = stackTrace
            textSize = 11f
            setTextColor(Color.parseColor("#F1F5F9"))
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        scrollView.addView(tvTrace)
        rootLayout.addView(scrollView)

        setContentView(rootLayout)
    }
}
