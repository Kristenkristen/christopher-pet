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
        private const val FATIGUE_YAWN  = 0.82f
        private const val FATIGUE_DOZE  = 0.85f
        private const val FATIGUE_SLEEP = 0.87f

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
    private var isDragging = false  // true once finger moves >12dp from down position

    // ── Walk / fling state ───────────────────────────────────────────────────
    private var isWalking = false
    private var flingCooldownUntil = 0L
    private val flingTimestamps = mutableListOf<Long>()

    // ── Shake detection ──────────────────────────────────────────────────────
    private var shakeLastX = 0f
    private var shakeLastY = 0f
    private var shakeDir = 0       // +1 or -1, tracks last movement direction
    private var shakeReversals = 0 // direction reversals = shake count
    private var shakeStartTime = 0L
    private var shakeCooldownUntil = 0L
    private val shakeTimestamps = mutableListOf<Long>()  // 3 shakes in 60s → CLAWD signal

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
            mainHandler.postDelayed(this, (45_000L..300_000L).random())
        }
    }
    private val yawnRunnable = object : Runnable {
        override fun run() {
            if (currentFatigue >= FATIGUE_YAWN && fatigueState == null && !isWalking && gestureState == null) {
                setGesture(PetState.IDLE_YAWN, 2500)
            }
            // Keep scheduling random yawns while fatigue is high
            if (currentFatigue >= FATIGUE_YAWN) {
                mainHandler.postDelayed(this, (25_000L..80_000L).random())
            }
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
        mainHandler.postDelayed(idleAnimRunnable, 45_000L)
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

        val dm = resources.displayMetrics
        val petPx = (PET_SIZE_DP * dm.density).toInt()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            petPx, petPx, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Initial position: 30dp from right edge
            x = dm.widthPixels - petPx - (30 * dm.density).toInt()
            y = (100 * dm.density).toInt()
        }

        windowManager.addView(floatView, params)
        loadGif(PetState.IDLE)
        setupTouchListener()
    }

    // ── State resolution ─────────────────────────────────────────────────────

    private fun resolveDisplayState(): PetState {
        if (isDragging) return PetState.REACT_DRAG  // highest: show drag while held
        gestureState?.let { return it }
        activityState?.let { return it }
        // Fatigue (Layer 3) blocks server typing/notification (Layer 4)
        fatigueState?.let { return it }
        // Layer 4: server state — overrides phone/music state unless it's just idle
        val srv = serverState
        if (srv != PetState.IDLE) return srv
        // Layer 5: phone hardware state (music/shopping/charging/battery)
        return musicState ?: phoneState ?: PetState.IDLE
    }

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
        currentGifState = null  // force reload even if transitioning to the same state
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

        // Start yawn cycle if newly hitting threshold; the runnable reschedules itself
        if (currentFatigue >= FATIGUE_YAWN) {
            mainHandler.removeCallbacks(yawnRunnable)
            mainHandler.postDelayed(yawnRunnable, (15_000L..45_000L).random())
        } else {
            mainHandler.removeCallbacks(yawnRunnable)
        }
    }

    // ── Speech Bubble ─────────────────────────────────────────────────────────

    fun showBubble(text: String) {
        mainHandler.removeCallbacks(bubbleHideRunnable)
        bubbleView.text = text
        bubbleView.visibility = View.VISIBLE
        mainHandler.postDelayed(bubbleHideRunnable, 10_000)
        // Spec: bubble triggers same animation as double-tap emotional state
        val bubblePick = when {
            currentFatigue >= FATIGUE_YAWN ->
                listOf(PetState.DIZZY, PetState.IDLE_YAWN, PetState.COFFEE_HAND, PetState.SWEEPING, PetState.IDLE_DOZE).random()
            lastTopDrive == "attachment" || lastTopDrive == "libido" ->
                listOf(PetState.AEGYO_SHY, PetState.HAPPY, PetState.REACT_DRAG).random()
            lastTopDrive == "curiosity" || lastTopDrive == "social" ->
                listOf(PetState.NOTIFICATION, PetState.REACT_ANNOYED, PetState.REACT_DOUBLE).random()
            else ->
                listOf(PetState.HAPPY, PetState.IDLE_LOOK, PetState.NOTIFICATION).random()
        }
        setGesture(bubblePick, 2500)
    }

    private fun hideBubble() { bubbleView.visibility = View.GONE }

    // Walk to a random on-screen position using crabwalk animation
    private fun crabwalkToRandom() {
        if (isWalking) return
        isWalking = true
        val dm = resources.displayMetrics
        val petPx = (PET_SIZE_DP * dm.density).toInt()
        val margin = (16 * dm.density).toInt()
        val maxX = (dm.widthPixels - petPx - margin).coerceAtLeast(margin)
        val targetX = (margin..maxX).random()
        val targetY = ((80 * dm.density).toInt()..(400 * dm.density).toInt()).random()
            .coerceAtMost(dm.heightPixels - petPx - margin)
        loadGif(PetState.CRABWALK)
        val startX = params!!.x; val startY = params!!.y
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = 1400
        anim.addUpdateListener { va ->
            val t = va.animatedFraction
            params!!.x = (startX + (targetX - startX) * t).toInt()
            params!!.y = (startY + (targetY - startY) * t).toInt()
            try { windowManager.updateViewLayout(floatView, params) } catch (_: Exception) {}
        }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                isWalking = false
                applyDisplayState()
            }
        })
        anim.start()
    }

    // ── Idle Random Animations ───────────────────────────────────────────────

    private fun triggerIdleRandom() {
        if (gestureState != null || isWalking) return
        val currentDisplay = resolveDisplayState()
        if (currentDisplay != PetState.IDLE && currentDisplay != PetState.IDLE_READING) return

        val pick = listOf(
            PetState.IDLE_LOOK, PetState.IDLE_BUBBLE,
            PetState.THINKING, PetState.ULTRATHINK, PetState.NOTIFICATION
        ).random()
        setGesture(pick, (3000L..6000L).random())
    }

    // ── Touch / Fling ─────────────────────────────────────────────────────────

    private fun setupTouchListener() {
        floatView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Pass through if touch is outside the crab hit zone — lets user tap
                    // through the transparent parts of the overlay to the app below.
                    if (!isTouchInHitArea(event.x, event.y)) {
                        return@setOnTouchListener false
                    }
                    initialX = params!!.x; initialY = params!!.y
                    initialTouchX = event.rawX; initialTouchY = event.rawY
                    downLocalX = event.x; downLocalY = event.y
                    isDragging = false
                    velocityTracker?.clear()
                    velocityTracker = VelocityTracker.obtain()
                    velocityTracker?.addMovement(event)
                    // Reset shake tracking
                    shakeLastX = event.rawX; shakeLastY = event.rawY
                    shakeDir = 0; shakeReversals = 0
                    shakeStartTime = System.currentTimeMillis()
                    // Immediate tactile feedback — quick scale pulse so she knows the touch landed
                    if (!isWalking) {
                        val pulse = android.animation.ValueAnimator.ofFloat(1f, 0.87f, 1f)
                        pulse.duration = 110
                        pulse.addUpdateListener { a ->
                            val s = a.animatedValue as Float
                            floatView.scaleX = s; floatView.scaleY = s
                        }
                        pulse.start()
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(event)
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    // Enter drag mode once finger moves >20px — show react-drag
                    if (!isDragging && (Math.abs(dx) > 20 || Math.abs(dy) > 20) && !isWalking) {
                        isDragging = true
                        currentGifState = null
                        applyDisplayState()
                    }
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
                        // START gravity: rightward drag increases x
                        params!!.x = initialX + dx
                        params!!.y = initialY + dy
                        try { windowManager.updateViewLayout(floatView, params) } catch (_: Exception) {}
                    }
                    // Shake detection: count direction reversals within a short window
                    val now = System.currentTimeMillis()
                    if (now - shakeStartTime < 3000) {
                        val mdx = event.rawX - shakeLastX
                        val mdy = event.rawY - shakeLastY
                        val dominant = if (Math.abs(mdx) > Math.abs(mdy)) mdx else mdy
                        if (Math.abs(dominant) > 12f) {
                            val newDir = if (dominant > 0) 1 else -1
                            if (shakeDir != 0 && newDir != shakeDir) shakeReversals++
                            shakeDir = newDir
                            shakeLastX = event.rawX; shakeLastY = event.rawY
                            if (shakeReversals >= 3 && System.currentTimeMillis() > shakeCooldownUntil) {
                                shakeCooldownUntil = System.currentTimeMillis() + 5000L
                                val pick = if (shakeReversals >= 6) PetState.REACT_ANNOYED else PetState.DIZZY
                                setGesture(pick, 2200)
                                // Track 3 shake episodes in 60s → send CLAWD signal
                                val nowMs = System.currentTimeMillis()
                                shakeTimestamps.removeAll { nowMs - it > 60_000L }
                                shakeTimestamps.add(nowMs)
                                if (shakeTimestamps.size >= 3) {
                                    shakeTimestamps.clear()
                                    statePoller.sendShakeSignal()
                                }
                                shakeReversals = 0
                            }
                        }
                    } else {
                        // Reset after 3s of continuous drag
                        shakeLastX = event.rawX; shakeLastY = event.rawY
                        shakeDir = 0; shakeReversals = 0
                        shakeStartTime = now
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    velocityTracker?.addMovement(event)
                    velocityTracker?.computeCurrentVelocity(1000)
                    // START gravity: positive vx = moving right (increasing x)
                    val vx = velocityTracker?.xVelocity ?: 0f
                    val vy = velocityTracker?.yVelocity ?: 0f
                    velocityTracker?.recycle(); velocityTracker = null

                    // End drag mode — restore proper animation
                    val wasDragging = isDragging
                    isDragging = false
                    if (wasDragging) applyDisplayState()

                    val dx = Math.abs(event.rawX - initialTouchX)
                    val dy = Math.abs(event.rawY - initialTouchY)
                    val speed = Math.sqrt((vx * vx + vy * vy).toDouble()).toFloat()
                    val tapSlopPx = (20 * resources.displayMetrics.density)

                    if (dx < tapSlopPx && dy < tapSlopPx && isTouchInHitArea(downLocalX, downLocalY)) {
                        handleTap()
                    } else if (speed >= FLING_MIN_VELOCITY && !isWalking) {
                        handleFling(vx, vy)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun handleFling(vx: Float, vy: Float) {
        isWalking = true  // lock immediately so re-entrant flings are blocked during fly-off
        flingCooldownUntil = System.currentTimeMillis() + FLING_COOLDOWN_MS

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
        // START gravity: x is distance from left edge. Keep pet on-screen.
        val maxX = dm.widthPixels - petPx - margin
        val returnX = (margin..maxX.coerceAtLeast(margin)).random()
        val returnY = ((80 * dm.density).toInt()..(300 * dm.density).toInt())
            .random().coerceAtMost(dm.heightPixels - (PET_SIZE_DP * dm.density).toInt() - margin)

        isWalking = true

        if (Math.random() < 0.5) {
            // 50%: fast teleport back + dizzy
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
                    mainHandler.postDelayed({
                        statePoller.start()
                        mainHandler.postDelayed({}, 0L)
                    }, 2200)
                }
            })
            bounceAnim.start()
        } else {
            // 50%: crabwalk back
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
                    mainHandler.postDelayed({}, 0L)
                }
            })
            crawlAnim.start()
        }
    }

    // ── Tap Gestures ──────────────────────────────────────────────────────────

    private fun handleTap() {
        if (isWalking) return

        // Tap during any sleep state → brief wake, then return to sleep automatically
        if (fatigueState != null) {
            setGesture(PetState.WAKE, 1500)
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastTapTime < 400) tapCount++ else tapCount = 1
        lastTapTime = now

        // Cancel any pending tap resolution and reschedule — only the LAST tap fires
        mainHandler.removeCallbacks(tapResolutionRunnable)
        mainHandler.postDelayed(tapResolutionRunnable, 300)
    }

    private fun resolveTap(count: Int) {
        when (count) {
            1 -> {
                // 20% chance: crabwalk to a random position
                if (Math.random() < 0.20 && !isWalking) {
                    crabwalkToRandom()
                } else {
                    val options = listOf(
                        PetState.REACT_ANNOYED, PetState.REACT_DOUBLE_JUMP,
                        PetState.REACT_LEFT, PetState.REACT_RIGHT, PetState.IDLE_LOOK
                    )
                    setGesture(options.random(), 2500)
                }
            }
            2 -> {
                // Spec: fatigue always dominates in pet (even over libido, unlike Christopher's system)
                val pick = when {
                    currentFatigue >= FATIGUE_YAWN ->
                        listOf(PetState.DIZZY, PetState.IDLE_YAWN, PetState.COFFEE_HAND, PetState.SWEEPING, PetState.IDLE_DOZE).random()
                    lastTopDrive == "attachment" || lastTopDrive == "libido" ->
                        listOf(PetState.AEGYO_SHY, PetState.HAPPY, PetState.REACT_DRAG).random()
                    lastTopDrive == "curiosity" || lastTopDrive == "social" ->
                        listOf(PetState.NOTIFICATION, PetState.REACT_ANNOYED, PetState.REACT_DOUBLE).random()
                    else ->
                        listOf(PetState.DIZZY, PetState.IDLE_YAWN, PetState.COFFEE_HAND, PetState.SWEEPING, PetState.IDLE_DOZE).random()
                }
                setGesture(pick, 2500)
            }
            3 -> {
                // 想你 signal (triple tap) — sends [nudge:她在想你] to Christopher
                val pick = if (Math.random() < 0.5) PetState.AEGYO_SHY else PetState.HAPPY
                setGesture(pick, 2000)
                statePoller.sendThinkingOfYou { _ -> }
            }
            4 -> {
                // 4 taps: treat same as double (drive-based reaction)
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
            5 -> {
                // Chaos / annoyance (quintuple tap) — must not conflate with triple
                val options = listOf(
                    PetState.REACT_DOUBLE, PetState.NOTIFICATION_RETIRED,
                    PetState.DIZZY, PetState.ERROR
                )
                setGesture(options.random(), 2000)
            }
        }
    }

    // Window is exactly 140×140dp — same as the pet. Entire window is the hit area.
    private fun isTouchInHitArea(localX: Float, localY: Float): Boolean = true

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
            },
            onScrollAlert = {
                // 抖音/小红书 cumulative 1h: send bubble trigger to server
                statePoller.sendScrollAlert()
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
