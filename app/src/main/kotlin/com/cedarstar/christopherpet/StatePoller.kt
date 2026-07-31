package com.cedarstar.christopherpet

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class StatePoller(
    private val context: Context,
    private val onStateChanged: (PetState) -> Unit,
    private val onBubble: ((String) -> Unit)? = null
) {
    companion object {
        private const val BASE_URL = "https://christopherkristen.xyz/api"
        private const val POLL_INTERVAL_MS = 8000L
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
    private var currentState = PetState.IDLE
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

    fun resetToIdle() { currentState = PetState.IDLE }

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

    private fun fetchState() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (audioManager.isMusicActive) { updateState(PetState.HEADPHONES); return }
        if (currentState == PetState.HEADPHONES) currentState = PetState.IDLE

        Thread {
            try {
                val req = Request.Builder().url("$BASE_URL/pet_state.php").build()
                val body = client.newCall(req).execute().body?.string() ?: return@Thread
                val json = JSONObject(body)
                if (!json.optBoolean("ok", true)) return@Thread
                val newState = json.getString("state").toPetState()
                // Bubble text (optional field)
                val bubble = json.optString("bubble", "")
                handler.post {
                    updateState(newState)
                    if (bubble.isNotBlank() && bubble != lastBubble) {
                        lastBubble = bubble
                        onBubble?.invoke(bubble)
                    }
                }
            } catch (_: Exception) {
                handler.post { updateState(PetState.IDLE) }
            }
        }.start()
    }

    private fun updateState(newState: PetState) {
        if (newState != currentState) {
            currentState = newState
            onStateChanged(newState)
        }
    }
}
