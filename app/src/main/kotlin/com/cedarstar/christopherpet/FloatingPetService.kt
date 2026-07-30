package com.cedarstar.christopherpet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import pl.droidsonroids.gif.GifDrawable
import pl.droidsonroids.gif.GifImageView

class FloatingPetService : Service() {

    companion object {
        var isRunning = false
        private const val NOTIF_CHANNEL_ID = "christopher_pet"
        private const val NOTIF_ID = 1
        private const val PET_SIZE_DP = 140
    }

    private lateinit var windowManager: WindowManager
    private lateinit var floatView: View
    private lateinit var gifView: GifImageView
    private lateinit var statePoller: StatePoller

    private var params: WindowManager.LayoutParams? = null
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var lastTapTime = 0L
    private var tapCount = 0

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        setupFloatingView()
        setupStatePoller()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        statePoller.stop()
        if (::floatView.isInitialized) {
            try { windowManager.removeView(floatView) } catch (_: Exception) {}
        }
    }

    private fun setupFloatingView() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        floatView = LayoutInflater.from(this).inflate(R.layout.floating_pet, null)
        gifView = floatView.findViewById(R.id.gifView)

        val sizePx = (PET_SIZE_DP * resources.displayMetrics.density).toInt()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            sizePx, sizePx,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 30
            y = 200
        }

        windowManager.addView(floatView, params)
        loadGif(PetState.IDLE)
        setupTouchListener()
    }

    private fun setupTouchListener() {
        floatView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params!!.x
                    initialY = params!!.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
                        params!!.x = initialX - dx
                        params!!.y = initialY + dy
                        windowManager.updateViewLayout(floatView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = Math.abs(event.rawX - initialTouchX)
                    val dy = Math.abs(event.rawY - initialTouchY)
                    if (dx < 10 && dy < 10) {
                        handleTap()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun handleTap() {
        val now = System.currentTimeMillis()
        if (now - lastTapTime < 500) {
            tapCount++
        } else {
            tapCount = 1
        }
        lastTapTime = now

        when (tapCount) {
            2 -> {
                // Double tap: jump reaction
                loadGif(PetState.REACT_JUMP)
                floatView.postDelayed({
                    statePoller.resetToIdle()
                    resumePolling()
                }, 2000)
            }
            3 -> {
                // Triple tap: "thinking of you" signal ♡
                tapCount = 0
                loadGif(PetState.BUBBLE)
                statePoller.sendThinkingOfYou { success ->
                    floatView.postDelayed({
                        if (success) loadGif(PetState.HAPPY)
                        floatView.postDelayed({
                            statePoller.resetToIdle()
                            resumePolling()
                        }, 2500)
                    }, 1500)
                }
            }
            5 -> {
                // 5 taps: annoyed
                tapCount = 0
                loadGif(PetState.REACT_ANNOYED)
                floatView.postDelayed({
                    statePoller.resetToIdle()
                    resumePolling()
                }, 2000)
            }
        }
    }

    private fun resumePolling() {
        statePoller.stop()
        statePoller.start()
    }

    private fun loadGif(state: PetState) {
        try {
            val gifDrawable = GifDrawable(assets, state.gifAssetPath())
            gifView.setImageDrawable(gifDrawable)
        } catch (e: Exception) {
            // Fallback to idle if gif not found
            try {
                val fallback = GifDrawable(assets, PetState.IDLE.gifAssetPath())
                gifView.setImageDrawable(fallback)
            } catch (_: Exception) {}
        }
    }

    private fun setupStatePoller() {
        statePoller = StatePoller(this) { newState ->
            loadGif(newState)
        }
        statePoller.start()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIF_CHANNEL_ID,
            "Christopher Pet",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Chris 住在这里"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("Chris 在线")
            .setContentText("点击打开设置")
            .setSmallIcon(R.drawable.ic_crab)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }
}
