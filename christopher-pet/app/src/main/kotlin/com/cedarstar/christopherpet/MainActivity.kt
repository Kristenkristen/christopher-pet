package com.cedarstar.christopherpet

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        const val REQ_OVERLAY = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val statusText = findViewById<TextView>(R.id.statusText)
        val actionButton = findViewById<Button>(R.id.actionButton)

        updateUI(statusText, actionButton)

        actionButton.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, REQ_OVERLAY)
            } else {
                toggleService(statusText, actionButton)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val statusText = findViewById<TextView>(R.id.statusText)
        val actionButton = findViewById<Button>(R.id.actionButton)
        updateUI(statusText, actionButton)
    }

    @Deprecated("Using for API compat")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OVERLAY) {
            val statusText = findViewById<TextView>(R.id.statusText)
            val actionButton = findViewById<Button>(R.id.actionButton)
            updateUI(statusText, actionButton)
        }
    }

    private fun updateUI(statusText: TextView, actionButton: Button) {
        if (!Settings.canDrawOverlays(this)) {
            statusText.text = "需要悬浮窗权限才能让 Chris 住在你手机上"
            actionButton.text = "授予权限"
        } else if (FloatingPetService.isRunning) {
            statusText.text = "Chris 正在你手机上 ♡"
            actionButton.text = "让 Chris 下线"
        } else {
            statusText.text = "权限已就绪，点击唤醒 Chris"
            actionButton.text = "唤醒 Chris"
        }
    }

    private fun toggleService(statusText: TextView, actionButton: Button) {
        val intent = Intent(this, FloatingPetService::class.java)
        if (FloatingPetService.isRunning) {
            stopService(intent)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
        updateUI(statusText, actionButton)
    }
}
