package com.cedarstar.christopherpet

import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

        private val MUSIC_PACKAGES = setOf("com.netease.cloudmusic")
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
            updatePhoneState()
        }
    }

    private val appPollRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            updatePhoneState()
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

    private fun getForegroundPackage(): String? {
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 5000, now)
            stats?.maxByOrNull { it.lastTimeUsed }?.packageName
        } catch (_: Exception) { null }
    }

    private fun updatePhoneState() {
        val newState: PetState? = when {
            isCharging -> PetState.BUILDING_BOXES
            batteryLevel <= LOW_BATTERY_THRESHOLD -> PetState.IDLE_LOW_BATTERY
            else -> {
                val fg = getForegroundPackage()
                when {
                    fg != null && fg in SHOPPING_PACKAGES -> PetState.TYPING_BOSS
                    else -> null
                }
            }
        }

        if (newState != currentPhoneState) {
            currentPhoneState = newState
            handler.post { onPhoneStateChanged(newState) }
        }
    }
}
