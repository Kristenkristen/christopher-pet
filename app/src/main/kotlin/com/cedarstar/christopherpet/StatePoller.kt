package com.cedarstar.christopherpet

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class ServerResponse(
    val state: PetState,
    val activityState: PetState?,   // Christopher's current activity (Layer 1)
    val fatigue: Float,             // 0.0–1.0
    val topDrive: String,           // "attachment"/"curiosity"/"social"/"libido"/"boredom"
    val bubble: String,
)

class StatePoller(
    private val context: Context,
    private val onResponse: (ServerResponse) -> Unit,
    private val onBubble: ((String) -> Unit)? = null
) {
    companion object {
        private const val BASE_URL = "https://christopherkristen.xyz/api"
        const val POLL_INTERVAL_MS = 8000L
        private val PET_TOKEN: String by lazy {
            val md5 = MessageDigest.getInstance("MD5").digest("christopherkristen_thinking".toByteArray())
            "ck_pet_" + md5.joinToString("") { "%02x".format(it) }
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val handler = Handler(Looper.getMainLooper())
    private var lastBubble = ""
    private var running = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            fetchState()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    fun start() { running = true; handler.post(pollRunnable) }
    fun stop()  { running = false; handler.removeCallbacks(pollRunnable) }

    // Sends [nudge:她在想你] to Christopher
    fun sendThinkingOfYou(onResult: (Boolean) -> Unit) {
        Thread {
            try {
                val body = "token=$PET_TOKEN".toRequestBody("application/x-www-form-urlencoded".toMediaType())
                val req = Request.Builder().url("$BASE_URL/thinking.php").post(body).build()
                val ok = client.newCall(req).execute().isSuccessful
                handler.post { onResult(ok) }
            } catch (_: Exception) {
                handler.post { onResult(false) }
            }
        }.start()
    }

    fun sendFlingSignal() {
        Thread {
            try {
                val body = "token=$PET_TOKEN&type=fling".toRequestBody("application/x-www-form-urlencoded".toMediaType())
                val req = Request.Builder().url("$BASE_URL/thinking.php").post(body).build()
                client.newCall(req).execute()
            } catch (_: Exception) {}
        }.start()
    }

    private fun fetchState() {
        // Music/headphones detection is now handled by AppStateMonitor (Layer 3)
        // so we always fetch from server here without early-returning on isMusicActive
        Thread {
            try {
                val req = Request.Builder().url("$BASE_URL/pet_state.php").build()
                val bodyStr = client.newCall(req).execute().body?.string() ?: return@Thread
                val json = JSONObject(bodyStr)
                if (!json.optBoolean("ok", true)) return@Thread

                val state = json.getString("state").toPetState()
                val actStr = json.optString("activity_state", "")
                val activity = if (actStr.isNotBlank()) actStr.toPetState() else null
                val fatigue = json.optDouble("fatigue", 0.0).toFloat()
                val topDrive = json.optString("top_drive", "boredom")
                val bubble = json.optString("bubble", "")

                val resp = ServerResponse(
                    state = state,
                    activityState = activity,
                    fatigue = fatigue,
                    topDrive = topDrive,
                    bubble = bubble,
                )

                handler.post {
                    onResponse(resp)
                    if (bubble.isNotBlank() && bubble != lastBubble) {
                        lastBubble = bubble
                        onBubble?.invoke(bubble)
                    }
                }
            } catch (_: Exception) {
                val fallback = ServerResponse(PetState.IDLE, null, 0f, "boredom", "")
                handler.post { onResponse(fallback) }
            }
        }.start()
    }
}
