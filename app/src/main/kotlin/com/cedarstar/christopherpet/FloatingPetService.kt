package com.cedarstar.christopherpet

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import pl.droidsonroids.gif.GifDrawable
import pl.droidsonroids.gif.GifImageView
import java.io.File

class FloatingPetService : Service() {

    companion object {
        var isRunning = false
        private const val NOTIF_CHANNEL_ID = "christopher_pet"
        private const val NOTIF_ID = 1
        private const val PET_SIZE_DP = 140
        private const val BUBBLE_HEIGHT_DP = 80  // extra space above pet for bubble
    }

    private lateinit var windowManager: WindowManager
    private lateinit var floatView: View
    private lateinit var gifView: GifImageView
    private lateinit var bubbleView: TextView
    private lateinit var statePoller: StatePoller

    private val mainHandler = Handler(Looper.getMainLooper())
    private var params: WindowManager.LayoutParams? = null
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var lastTapTime = 0L
    private var tapCount = 0
    private var isWalking = false
    private var screenshotObserver: FileObserver? = null

    private val bubbleHideRunnable = Runnable { hideBubble() }
    private val walkRunnable = object : Runnable {
        override fun run() {
            if (!isWalking) startWalk()
            scheduleNextWalk()
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        setupFloatingView()
        setupStatePoller()
        startScreenshotObserver()
        scheduleNextWalk()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        statePoller.stop()
        mainHandler.removeCallbacksAndMessages(null)
        screenshotObserver?.stopWatching()
        if (::floatView.isInitialized) {
            try { windowManager.removeView(floatView) } catch (_: Exception) {}
        }
    }

    // ── Layout ──────────────────────────────────────────────────────────────

    private fun setupFloatingView() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        floatView = LayoutInflater.from(this).inflate(R.layout.floating_pet, null)
        gifView = floatView.findViewById(R.id.gifView)
        bubbleView = floatView.findViewById(R.id.bubbleText)

        val density = resources.displayMetrics.density
        val petPx = (PET_SIZE_DP * density).toInt()
        val totalH = ((PET_SIZE_DP + BUBBLE_HEIGHT_DP) * density).toInt()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            petPx, totalH,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 30
            y = 100
        }

        windowManager.addView(floatView, params)
        loadGif(PetState.IDLE)
        setupTouchListener()
    }

    // ── Speech Bubble ────────────────────────────────────────────────────────

    fun showBubble(text: String) {
        mainHandler.removeCallbacks(bubbleHideRunnable)
        bubbleView.text = text
        bubbleView.visibility = View.VISIBLE
        mainHandler.postDelayed(bubbleHideRunnable, 6000)
    }

    private fun hideBubble() {
        bubbleView.visibility = View.GONE
    }

    // ── Screenshot Detection ─────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun startScreenshotObserver() {
        val screenshotsDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "Screenshots"
        )
        if (!screenshotsDir.exists()) screenshotsDir.mkdirs()

        screenshotObserver = object : FileObserver(screenshotsDir.absolutePath, CREATE) {
            override fun onEvent(event: Int, path: String?) {
                if (event == CREATE && path != null && !isWalking) {
                    mainHandler.post {
                        loadGif(PetState.NOTIFICATION)
                        statePoller.resetToIdle()
                        mainHandler.postDelayed({ resumePolling() }, 3000)
                    }
                }
            }
        }
        try { screenshotObserver?.startWatching() } catch (_: Exception) {}
    }

    // ── Self-walking ─────────────────────────────────────────────────────────

    private fun scheduleNextWalk() {
        val delay = (45_000L..120_000L).random()
        mainHandler.postDelayed(walkRunnable, delay)
    }

    private fun startWalk() {
        val dm = resources.displayMetrics
        val petPx = (PET_SIZE_DP * dm.density).toInt()
        val margin = (16 * dm.density).toInt()
        val maxX = dm.widthPixels - petPx - margin

        val targetX = (margin..maxX).random()
        isWalking = true
        loadGif(PetState.CRABWALK)
        statePoller.stop()

        val anim = ValueAnimator.ofInt(params!!.x, targetX)
        anim.duration = (1200L..2200L).random()
        anim.addUpdateListener {
            params!!.x = it.animatedValue as Int
            try { windowManager.updateViewLayout(floatView, params) } catch (_: Exception) {}
        }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                isWalking = false
                statePoller.resetToIdle()
                resumePolling()
            }
        })
        anim.start()
    }

    // ── Touch ────────────────────────────────────────────────────────────────

    private fun setupTouchListener() {
        floatView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    mainHandler.removeCallbacks(walkRunnable)
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
                        try { windowManager.updateViewLayout(floatView, params) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = Math.abs(event.rawX - initialTouchX)
                    val dy = Math.abs(event.rawY - initialTouchY)
                    if (dx < 10 && dy < 10) {
                        handleTap()
                    } else {
                        checkFlingOffScreen()
                    }
                    scheduleNextWalk()
                    true
                }
                else -> false
            }
        }
    }

    private fun checkFlingOffScreen() {
        val dm = resources.displayMetrics
        val petPx = (PET_SIZE_DP * dm.density).toInt()
        val curX = params!!.x
        val curY = params!!.y
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels

        val nearEdge = curX < -petPx / 2 || curX > screenW - petPx / 2
                || curY < -petPx / 2 || curY > screenH - petPx / 2

        if (nearEdge) {
            // Slide off, then bounce back to safe position
            loadGif(PetState.REACT_ANNOYED)
            val targetX = 30
            val targetY = 200
            mainHandler.postDelayed({
                val anim = ValueAnimator.ofFloat(0f, 1f)
                anim.duration = 600
                val startX = params!!.x
                val startY = params!!.y
                anim.addUpdateListener { va ->
                    val t = va.animatedFraction
                    params!!.x = (startX + (targetX - startX) * t).toInt()
                    params!!.y = (startY + (targetY - startY) * t).toInt()
                    try { windowManager.updateViewLayout(floatView, params) } catch (_: Exception) {}
                }
                anim.addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        statePoller.resetToIdle()
                        resumePolling()
                    }
                })
                anim.start()
            }, 1200)
        }
    }

    private fun handleTap() {
        if (isWalking) return
        val now = System.currentTimeMillis()
        if (now - lastTapTime < 500) tapCount++ else tapCount = 1
        lastTapTime = now

        when (tapCount) {
            2 -> {
                loadGif(PetState.REACT_JUMP)
                mainHandler.postDelayed({ statePoller.resetToIdle(); resumePolling() }, 2000)
            }
            3 -> {
                tapCount = 0
                loadGif(PetState.BUBBLE)
                statePoller.sendThinkingOfYou { success ->
                    mainHandler.postDelayed({
                        if (success) loadGif(PetState.HAPPY)
                        mainHandler.postDelayed({ statePoller.resetToIdle(); resumePolling() }, 2500)
                    }, 1500)
                }
            }
            5 -> {
                tapCount = 0
                loadGif(PetState.REACT_ANNOYED)
                mainHandler.postDelayed({ statePoller.resetToIdle(); resumePolling() }, 2000)
            }
        }
    }

    private fun resumePolling() {
        statePoller.stop()
        statePoller.start()
    }

    // ── GIF Loading ──────────────────────────────────────────────────────────

    fun loadGif(state: PetState) {
        try {
            val gifDrawable = GifDrawable(assets, state.gifAssetPath())
            gifView.setImageDrawable(gifDrawable)
        } catch (_: Exception) {
            try {
                gifView.setImageDrawable(GifDrawable(assets, PetState.IDLE.gifAssetPath()))
            } catch (_: Exception) {}
        }
    }

    private fun setupStatePoller() {
        statePoller = StatePoller(this,
            onStateChanged = { newState -> loadGif(newState) },
            onBubble = { text -> showBubble(text) }
        )
        statePoller.start()
    }

    // ── Notification / Foreground Service ────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIF_CHANNEL_ID, "Christopher Pet", NotificationManager.IMPORTANCE_MIN
        ).apply { description = "Chris 住在这里"; setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("Chris 在线")
            .setContentText("点击打开设置")
            .setSmallIcon(R.drawable.ic_crab)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }
}
