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
    private val onPhoneStateChanged: (PetState?) -> Unit,   // Layer 4: shopping/charging/battery
    private val onScrollAlert: (() -> Unit)? = null         // 抖音/小红书 cumulative 1h alert
) {
    companion object {
        private const val LOW_BATTERY_THRESHOLD = 19
        private const val APP_POLL_INTERVAL_MS = 5000L
        private const val NETEASE_PACKAGE = "com.netease.cloudmusic"
        // 网易云 stays in background while playing — use 30-minute window to detect it
        private const val MUSIC_WINDOW_MS = 30 * 60 * 1000L
        // Shopping apps: only relevant if actively in foreground (60s window)
        private const val PHONE_WINDOW_MS = 60_000L
        // Scroll alert: fire once per cumulative hour of Douyin/Xiaohongshu usage today
        private const val SCROLL_ALERT_INTERVAL_MS = 60 * 60 * 1000L

        private val SHOPPING_PACKAGES = setOf(
            "com.taobao.taobao",
            "com.sankuai.meituan",
            "com.taobao.idlefish"
        )
        private val SCROLL_PACKAGES = setOf(
            "com.ss.android.ugc.aweme",   // 抖音
            "com.xingin.xhs"              // 小红书
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private var currentMusicState: PetState? = null
    private var currentPhoneState: PetState? = null
    private var isCharging = false
    private var batteryLevel = 100
    private var running = false
    private var scrollHoursAlerted = 0L  // cumulative 1-hour intervals already notified

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
            // Layer 3: music — NetEase Cloud Music only (spec requirement)
            val newMusicState: PetState? =
                if (isMusicActive() && getForegroundPackage(MUSIC_WINDOW_MS) == NETEASE_PACKAGE)
                    PetState.HEADPHONES else null

            // Layer 4: phone hardware / shopping — short window (only if actively foregrounded)
            val newPhoneState: PetState? = when {
                charging -> PetState.BUILDING_BOXES
                batt <= LOW_BATTERY_THRESHOLD -> PetState.IDLE_LOW_BATTERY
                else -> {
                    val fg = getForegroundPackage(PHONE_WINDOW_MS)
                    if (fg != null && fg in SHOPPING_PACKAGES) PetState.TYPING_BOSS else null
                }
            }

            // 抖音/小红书: fire once each time cumulative usage today crosses another 1-hour mark
            val todayScrollMs = if (onScrollAlert != null) getScrollAppTodayUsageMs() else 0L
            val completedScrollHours = todayScrollMs / SCROLL_ALERT_INTERVAL_MS

            handler.post {
                if (onScrollAlert != null && completedScrollHours > scrollHoursAlerted) {
                    scrollHoursAlerted = completedScrollHours
                    onScrollAlert.invoke()
                }
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

    // Returns total milliseconds spent in 抖音/小红书 today
    private fun getScrollAppTodayUsageMs(): Long {
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val startOfDay = now - (now % (24 * 60 * 60 * 1000L))
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startOfDay, now)
            stats?.filter { it.packageName in SCROLL_PACKAGES }
                ?.sumOf { it.totalTimeInForeground } ?: 0L
        } catch (_: Exception) { 0L }
    }

    private fun isMusicActive(): Boolean {
        return try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audio.isMusicActive
        } catch (_: Exception) { false }
    }
}
