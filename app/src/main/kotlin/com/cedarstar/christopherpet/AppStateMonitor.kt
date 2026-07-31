package com.cedarstar.christopherpet

import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper

class AppStateMonitor(
    private val context: Context,
    private val onMusicStateChanged: (PetState?) -> Unit,   // Layer 3: 网易云 headphones
    private val onPhoneStateChanged: (PetState?) -> Unit    // Layer 4: shopping/charging/battery
) {
    companion object {
        private const val LOW_BATTERY_THRESHOLD = 19
        private const val APP_POLL_INTERVAL_MS = 5000L
        private const val NETEASE_PACKAGE = "com.netease.cloudmusic"
        // 网易云 lives in background for hours — use 30-minute window to detect it
        private const val MUSIC_WINDOW_MS = 30 * 60 * 1000L
        // Shopping apps: only relevant if actively in foreground (60s window)
        private const val PHONE_WINDOW_MS = 60_000L

        private val SHOPPING_PACKAGES = setOf(
            "com.taobao.taobao",
            "com.sankuai.meituan",
            "com.taobao.idlefish"
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private var currentMusicState: PetState? = null
    private var currentPhoneState: PetState? = null
    private var isCharging = false
    private var batteryLevel = 100
    private var running = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                         status == BatteryManager.BATTERY_STATUS_FULL
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) batteryLevel = (level * 100 / scale)
            schedulePhoneStateUpdate()
        }
    }

    private val appPollRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            schedulePhoneStateUpdate()
            handler.postDelayed(this, APP_POLL_INTERVAL_MS)
        }
    }

    fun start() {
        running = true
        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        handler.post(appPollRunnable)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(appPollRunnable)
        try { context.unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
    }

    private fun schedulePhoneStateUpdate() {
        val charging = isCharging
        val batt = batteryLevel
        Thread {
            // Layer 3: music — isMusicActive is the primary signal; package check uses wide window
            // since 网易云 stays in background indefinitely while playing
            val musicActive = isMusicActive()
            val newMusicState: PetState? = if (musicActive) {
                val pkg = getForegroundPackage(MUSIC_WINDOW_MS)
                if (pkg == NETEASE_PACKAGE) PetState.HEADPHONES else null
            } else null

            // Layer 4: phone hardware / shopping — short window (only if actively foregrounded)
            val newPhoneState: PetState? = when {
                charging -> PetState.BUILDING_BOXES
                batt <= LOW_BATTERY_THRESHOLD -> PetState.IDLE_LOW_BATTERY
                else -> {
                    val fg = getForegroundPackage(PHONE_WINDOW_MS)
                    if (fg != null && fg in SHOPPING_PACKAGES) PetState.TYPING_BOSS else null
                }
            }

            handler.post {
                if (newMusicState != currentMusicState) {
                    currentMusicState = newMusicState
                    onMusicStateChanged(newMusicState)
                }
                if (newPhoneState != currentPhoneState) {
                    currentPhoneState = newPhoneState
                    onPhoneStateChanged(newPhoneState)
                }
            }
        }.start()
    }

    private fun getForegroundPackage(windowMs: Long): String? {
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - windowMs, now)
            stats?.maxByOrNull { it.lastTimeUsed }?.packageName
        } catch (_: Exception) { null }
    }

    private fun isMusicActive(): Boolean {
        return try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audio.isMusicActive
        } catch (_: Exception) { false }
    }
}
