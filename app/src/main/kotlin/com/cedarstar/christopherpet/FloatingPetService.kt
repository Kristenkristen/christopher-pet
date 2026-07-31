package com.cedarstar.christopherpet

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import pl.droidsonroids.gif.GifDrawable
import pl.droidsonroids.gif.GifImageView

class FloatingPetService : Service() {

    companion object {
        var isRunning = false
        private const val NOTIF_CHANNEL_ID = "christopher_pet"
        private const val NOTIF_ID = 1
        private const val PET_SIZE_DP = 140
        private const val BUBBLE_HEIGHT_DP = 80

        // Fatigue thresholds
        private const val FATIGUE_YAWN  = 0.50f
        private const val FATIGUE_DOZE  = 0.65f
        private const val FATIGUE_SLEEP = 0.80f

        // Fling detection
        private const val FLING_MIN_VELOCITY = 1200f
        private const val FLING_COOLDOWN_MS  = 600_000L  // 10 min
        private const val FLING_SIGNAL_COUNT = 3
        private const val FLING_SIGNAL_WINDOW_MS = 60_000L
    }

    private lateinit var windowManager: WindowManager
    private lateinit var floatView: View
    private lateinit var gifView: GifImageView
    private lateinit var bubbleView: TextView
    private lateinit var statePoller: StatePoller
    private lateinit var appMonitor: AppStateMonitor

    private val mainHandler = Handler(Looper.getMainLooper())
    private var params: WindowManager.LayoutParams? = null

    // ── State layers (null = not active) ────────────────────────────────────
    private var gestureState: PetState? = null       // Layer 0: temporary gesture
    private var activityState: PetState? = null      // Layer 1: Christopher's activity
    private var fatigueState: PetState? = null       // Layer 2: fatigue
    private var musicState: PetState? = null         // Layer 3: 网易云 headphones (persistent)
    private var phoneState: PetState? = null         // Layer 4: shopping/charging/battery
    private var serverState: PetState = PetState.IDLE// Layer 5: server poll
    private var currentFatigue = 0f
    private var lastTopDrive = "boredom"

    // ── GIF cache (avoid redundant reloads) ──────────────────────────────────
    private var currentGifState: PetState? = null

    // ── Touch state ──────────────────────────────────────────────────────────
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var downLocalX = 0f    // view-local X at ACTION_DOWN, for hit-area check
    private var downLocalY = 0f    // view-local Y at ACTION_DOWN, for hit-area check
    private var velocityTracker: VelocityTracker? = null
    private var lastTapTime = 0L
    private var tapCount = 0

    // ── Walk / fling state ───────────────────────────────────────────────────
    private var isWalking = false
    private var flingCooldownUntil = 0L
    private val flingTimestamps = mutableListOf<Long>()

    // ── Runnables ────────────────────────────────────────────────────────────
    private val bubbleHideRunnable = Runnable { hideBubble() }
    private val gestureResetRunnable = Runnable { clearGesture() }

    // Single tap-resolution runnable — cancelled and rescheduled on each tap
    // so only the LAST tap in a quick sequence triggers resolveTap
    private val tapResolutionRunnable = Runnable {
        resolveTap(tapCount)
        tapCount = 0
    }

    private val idleAnimRunnable = object : Runnable {
        override fun run() {
            triggerIdleRandom()
            mainHandler.postDelayed(this, (20_000L..60_000L).random())
        }
    }
    private val walkRunnable = object : Runnable {
        override fun run() {
            if (!isWalking) startWalk()
        }
    }
    private val yawnRunnable = Runnable {
        if (!isWalking && fatigueState == null && currentFatigue >= FATIGUE_YAWN) {
            setGesture(PetState.IDLE_YAWN, 2500)
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        setupFloatingView()
        setupStatePoller()
        setupAppMonitor()
        scheduleNextWalk()
        mainHandler.postDelayed(idleAnimRunnable, 30_000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        statePoller.stop()
        appMonitor.stop()
        mainHandler.removeCallbacksAndMessages(null)
        if (::floatView.isInitialized) {
            try { windowManager.removeView(floatView) } catch (_: Exception) {}
        }
    }

    // ── Layout ───────────────────────────────────────────────────────────────

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
            petPx, totalH, type,
            // FLAG_LAYOUT_NO_LIMITS lets the pet fly off-screen during flings
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 30; y = 100
        }

        windowManager.addView(floatView, params)
        loadGif(PetState.IDLE)
        setupTouchListener()
    }

    // ── State resolution ─────────────────────────────────────────────────────

    private fun resolveDisplayState(): PetState =
        gestureState ?: activityState ?: fatigueState ?: musicState ?: phoneState ?: serverState

    private fun applyDisplayState() {
        val state = resolveDisplayState()
        loadGif(state)
    }

    private fun clearGesture() {
        gestureState = null
        applyDisplayState()
    }

    private fun setGesture(state: PetState, durationMs: Long) {
        mainHandler.removeCallbacks(gestureResetRunnable)
        gestureState = state
        applyDisplayState()
        mainHandler.postDelayed(gestureResetRunnable, durationMs)
    }

    private fun updateFatigueState() {
        val oldFatigueState = fatigueState
        fatigueState = when {
            currentFatigue >= FATIGUE_SLEEP -> PetState.COLLAPSE_SLEEP
            currentFatigue >= FATIGUE_DOZE  -> PetState.IDLE_DOZE
            else -> null
        }
        if (fatigueState != oldFatigueState) applyDisplayState()

        mainHandler.removeCallbacks(yawnRunnable)
        if (currentFatigue >= FATIGUE_YAWN && fatigueState == null) {
            mainHandler.postDelayed(yawnRunnable, (30_000L..90_000L).random())
        }
    }

    // ── Speech Bubble ─────────────────────────────────────────────────────────

    fun showBubble(text: String) {
        mainHandler.removeCallbacks(bubbleHideRunnable)
        bubbleView.text = text
        bubbleView.visibility = View.VISIBLE
        mainHandler.postDelayed(bubbleHideRunnable, 6000)
    }

    private fun hideBubble() { bubbleView.visibility = View.GONE }

    // ── Idle Random Animations ───────────────────────────────────────────────

    private fun triggerIdleRandom() {
        if (gestureState != null || isWalking) return
        val currentDisplay = resolveDisplayState()
        if (currentDisplay != PetState.IDLE && currentDisplay != PetState.IDLE_READING) return

        val pick = listOf(PetState.IDLE_LOOK, PetState.IDLE_BUBBLE, PetState.THINKING).random()
        setGesture(pick, (3000L..5000L).random())
    }

    // ── Self-walking ──────────────────────────────────────────────────────────

    private fun scheduleNextWalk() {
        if (System.currentTimeMillis() < flingCooldownUntil) return
        val delay = (45_000L..300_000L).random()
        mainHandler.postDelayed(walkRunnable, delay)
    }

    private fun startWalk() {
        if (gestureState != null) { scheduleNextWalk(); return }
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
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                isWalking = false
                statePoller.start()
                applyDisplayState()
                scheduleNextWalk()
            }
        })
        anim.start()
    }

    // ── Touch / Fling ─────────────────────────────────────────────────────────

    private fun setupTouchListener() {
        floatView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    mainHandler.removeCallbacks(walkRunnable)
                    initialX = params!!.x; initialY = params!!.y
                    initialTouchX = event.rawX; initialTouchY = event.rawY
                    downLocalX = event.x; downLocalY = event.y
                    velocityTracker?.clear()
                    velocityTracker = VelocityTracker.obtain()
                    velocityTracker?.addMovement(event)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(event)
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
                        // END gravity: rightward drag decreases x (distance from right edge)
                        params!!.x = initialX - dx
                        params!!.y = initialY + dy
                        try { windowManager.updateViewLayout(floatView, params) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    velocityTracker?.addMovement(event)
                    velocityTracker?.computeCurrentVelocity(1000)
                    // END gravity: negate xVelocity so positive = moving toward right edge (smaller x)
                    val vx = -(velocityTracker?.xVelocity ?: 0f)
                    val vy = velocityTracker?.yVelocity ?: 0f
                    velocityTracker?.recycle(); velocityTracker = null

                    val dx = Math.abs(event.rawX - initialTouchX)
                    val dy = Math.abs(event.rawY - initialTouchY)
                    val speed = Math.sqrt((vx * vx + vy * vy).toDouble()).toFloat()

                    if (dx < 30 && dy < 30 && isTouchInHitArea(downLocalX, downLocalY)) {
                        handleTap()
                        scheduleNextWalk()
                    } else if (speed >= FLING_MIN_VELOCITY) {
                        handleFling(vx, vy)
                    } else {
                        scheduleNextWalk()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun handleFling(vx: Float, vy: Float) {
        flingCooldownUntil = System.currentTimeMillis() + FLING_COOLDOWN_MS
        mainHandler.removeCallbacks(walkRunnable)

        // Track 3-flings-in-1-min signal
        val now = System.currentTimeMillis()
        flingTimestamps.removeAll { now - it > FLING_SIGNAL_WINDOW_MS }
        flingTimestamps.add(now)
        if (flingTimestamps.size >= FLING_SIGNAL_COUNT) {
            flingTimestamps.clear()
            statePoller.sendFlingSignal()
        }

        // Fly off screen in fling direction
        val dm = resources.displayMetrics
        val startX = params!!.x
        val startY = params!!.y
        val speed = Math.sqrt((vx * vx + vy * vy).toDouble()).toFloat()
        val normVx = vx / speed
        val normVy = vy / speed

        // Move 1.5× screen width in fling direction to guarantee off-screen
        val dist = dm.widthPixels * 1.5f
        val targetX = (startX + normVx * dist).toInt()
        val targetY = (startY + normVy * dist).toInt()

        statePoller.stop()
        val flyAnim = ValueAnimator.ofFloat(0f, 1f)
        flyAnim.duration = 300
        flyAnim.addUpdateListener { va ->
            val t = va.animatedFraction
            params!!.x = (startX + (targetX - startX) * t).toInt()
            params!!.y = (startY + (targetY - startY) * t).toInt()
            try { windowManager.updateViewLayout(floatView, params) } catch (_: Exception) {}
        }
        flyAnim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                // Brief pause off-screen, then bounce or crawl back
                mainHandler.postDelayed({ bounceOrCrawlBack() }, 300)
            }
        })
        flyAnim.start()
    }

    private fun bounceOrCrawlBack() {
        val dm = resources.displayMetrics
        val petPx = (PET_SIZE_DP * dm.density).toInt()
        val margin = (16 * dm.density).toInt()
        val maxX = dm.widthPixels - petPx - margin
        val returnX = (margin..maxX).random()
        val returnY = ((80 * dm.density).toInt()..(220 * dm.density).toInt()).random()

        // Block applyDisplayState during scripted return animation (same as walking)
        isWalking = true

        if (Math.random() < 0.3) {
            // 30% chance: fast marble bounce + dizzy
            val startX = params!!.x; val startY = params!!.y
            loadGif(PetState.DIZZY)
            val bounceAnim = ValueAnimator.ofFloat(0f, 1f)
            bounceAnim.duration = 350
            bounceAnim.addUpdateListener { va ->
                val t = va.animatedFraction
                params!!.x = (startX + (returnX - startX) * t).toInt()
                params!!.y = (startY + (returnY - startY) * t).toInt()
                try { windowManager.updateViewLayout(floatView, params) } catch (_: Exception) {}
            }
            bounceAnim.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    isWalking = false
                    setGesture(PetState.DIZZY, 2000)
                    mainHandler.postDelayed({ statePoller.start() }, 2200)
                }
            })
            bounceAnim.start()
        } else {
            // 70% chance: crawl back with crabwalk
            loadGif(PetState.CRABWALK)
            val startX = params!!.x; val startY = params!!.y
            val crawlAnim = ValueAnimator.ofFloat(0f, 1f)
            crawlAnim.duration = 1800
            crawlAnim.addUpdateListener { va ->
                val t = va.animatedFraction
                params!!.x = (startX + (returnX - startX) * t).toInt()
                params!!.y = (startY + (returnY - startY) * t).toInt()
                try { windowManager.updateViewLayout(floatView, params) } catch (_: Exception) {}
            }
            crawlAnim.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    isWalking = false
                    statePoller.start()
                    applyDisplayState()
                }
            })
            crawlAnim.start()
        }
    }

    // ── Tap Gestures ──────────────────────────────────────────────────────────

    private fun handleTap() {
        if (isWalking) return

        // Tap during sleep: brief wake
        if (fatigueState == PetState.IDLE_DOZE || fatigueState == PetState.COLLAPSE_SLEEP) {
            setGesture(PetState.WAKE, 1500)
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastTapTime < 500) tapCount++ else tapCount = 1
        lastTapTime = now

        // Cancel any pending tap resolution and reschedule — only the LAST tap fires
        mainHandler.removeCallbacks(tapResolutionRunnable)
        mainHandler.postDelayed(tapResolutionRunnable, 400)
    }

    private fun resolveTap(count: Int) {
        when (count) {
            1 -> {
                val options = listOf(
                    PetState.REACT_ANNOYED, PetState.REACT_DOUBLE_JUMP,
                    PetState.REACT_LEFT, PetState.REACT_RIGHT,
                    PetState.IDLE_LOOK, PetState.HAPPY
                )
                setGesture(options.random(), 2500)
            }
            2 -> {
                val pick = when (lastTopDrive) {
                    "attachment", "libido" ->
                        listOf(PetState.AEGYO_SHY, PetState.HAPPY, PetState.REACT_DRAG).random()
                    "curiosity", "social" ->
                        listOf(PetState.NOTIFICATION, PetState.REACT_ANNOYED, PetState.REACT_DOUBLE).random()
                    else ->
                        listOf(PetState.DIZZY, PetState.IDLE_YAWN, PetState.COFFEE_HAND).random()
                }
                setGesture(pick, 2500)
            }
            3 -> {
                // Random annoyance (swapped from old 5-tap)
                val options = listOf(
                    PetState.REACT_DOUBLE, PetState.DIZZY,
                    PetState.ERROR, PetState.REACT_ANNOYED
                )
                setGesture(options.random(), 2000)
            }
            5 -> {
                // 想你 signal — sends [nudge:她在想你] to Christopher
                val pick = if (Math.random() < 0.5) PetState.AEGYO_SHY else PetState.HAPPY
                setGesture(pick, 2000)
                statePoller.sendThinkingOfYou { _ -> }
            }
        }
    }

    // Tap hitbox: accounts for GIF centering in the taller window (bubble reserves BUBBLE_HEIGHT_DP above)
    // gifView is match_parent in a (petPx+bubbleH) tall window → GIF centered vertically → pet starts at bubbleH/2 offset
    private fun isTouchInHitArea(localX: Float, localY: Float): Boolean {
        val density = resources.displayMetrics.density
        val petPx = PET_SIZE_DP * density
        val totalH = (PET_SIZE_DP + BUBBLE_HEIGHT_DP) * density
        // When bubble is gone, gifView fills totalH → GIF centered → pet occupies middle petPx of totalH
        val petTop = (totalH - petPx) / 2f
        val inset = 20 * density  // 20dp inset around the visual pet → 100dp×100dp hit area
        return localX >= inset && localX <= petPx - inset &&
               localY >= petTop + inset && localY <= petTop + petPx - inset
    }

    // ── GIF Loading ───────────────────────────────────────────────────────────

    fun loadGif(state: PetState) {
        if (state == currentGifState) return
        try {
            val gifDrawable = GifDrawable(assets, state.gifAssetPath())
            gifView.setImageDrawable(gifDrawable)
            gifDrawable.start()
            currentGifState = state
        } catch (_: Exception) {
            if (state != PetState.IDLE) {
                try {
                    gifView.setImageDrawable(GifDrawable(assets, PetState.IDLE.gifAssetPath()))
                    currentGifState = PetState.IDLE
                } catch (_: Exception) {}
            }
        }
    }

    // ── StatePoller + AppMonitor setup ────────────────────────────────────────

    private fun setupStatePoller() {
        statePoller = StatePoller(this,
            onResponse = { resp ->
                serverState = resp.state
                activityState = resp.activityState
                currentFatigue = resp.fatigue
                lastTopDrive = resp.topDrive
                updateFatigueState()
                if (!isWalking && gestureState == null) applyDisplayState()
            },
            onBubble = { text -> showBubble(text) }
        )
        statePoller.start()
    }

    private fun setupAppMonitor() {
        appMonitor = AppStateMonitor(this,
            onMusicStateChanged = { newMusicState ->
                musicState = newMusicState
                if (gestureState == null && !isWalking) applyDisplayState()
            },
            onPhoneStateChanged = { newPhoneState ->
                phoneState = newPhoneState
                if (gestureState == null && !isWalking) applyDisplayState()
            }
        )
        appMonitor.start()
    }

    // ── Foreground notification ───────────────────────────────────────────────

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
