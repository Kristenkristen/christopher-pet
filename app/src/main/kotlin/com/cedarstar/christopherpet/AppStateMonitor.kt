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
    private val onPhoneStateChanged: (PetState?) -> Unit
) {
    companion object {
        private const val LOW_BATTERY_THRESHOLD = 19
        private const val APP_POLL_INTERVAL_MS = 5000L
        private const val NETEASE_PACKAGE = "com.netease.cloudmusic"

        private val SHOPPING_PACKAGES = setOf(
            "com.taobao.taobao",
            "com.sankuai.meituan",
            "com.taobao.idlefish"
        )
    }

    private val handler = Handler(Looper.getMainLooper())
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

    // Run the heavy foreground-app query on a background thread to avoid main-thread jank
    private fun schedulePhoneStateUpdate() {
        val charging = isCharging
        val batt = batteryLevel
        Thread {
            val newState: PetState? = when {
                charging -> PetState.BUILDING_BOXES
                batt <= LOW_BATTERY_THRESHOLD -> PetState.IDLE_LOW_BATTERY
                else -> {
                    val fg = getForegroundPackage()
                    val musicActive = isMusicActive()
                    when {
                        // Headphones: only when 网易云 is active AND music is actually playing
                        fg == NETEASE_PACKAGE && musicActive -> PetState.HEADPHONES
                        fg != null && fg in SHOPPING_PACKAGES -> PetState.TYPING_BOSS
                        else -> null
                    }
                }
            }
            handler.post {
                if (newState != currentPhoneState) {
                    currentPhoneState = newState
                    onPhoneStateChanged(newState)
                }
            }
        }.start()
    }

    private fun getForegroundPackage(): String? {
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 5000, now)
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
