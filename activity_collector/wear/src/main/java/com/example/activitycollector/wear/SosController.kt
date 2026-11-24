package com.example.activitycollector.wear

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import com.example.activitycollector.AlertEvent
import com.example.activitycollector.AlertHistoryManager
import com.example.activitycollector.WatchProtocol
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Sends the SOS through the phone. A Galaxy Watch4 Classic has no cellular radio,
 * so the phone app (FallGuard Companion) texts the contacts and places the call,
 * then reports back. If no phone answers, the watch sounds an alarm so people
 * nearby notice, and keeps retrying every 10 s until the wearer stops it.
 */
object SosController {

    sealed class State {
        object Idle : State()
        data class Contacting(val startedAtElapsedMs: Long) : State()
        data class Notified(
            val callPlaced: Boolean, val primaryName: String, val textsSent: Int,
            val textedNames: List<String>, val contacts: Int, val locationFound: Boolean,
        ) : State()
        data class Unreachable(val attempts: Int) : State()
    }

    private const val TAG = "SosController"
    private const val REPLY_TIMEOUT_MS = 20_000L
    private const val RETRY_MS = 10_000L

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val worker by lazy { Handler(HandlerThread("sos").apply { start() }.looper) }
    private var attempts = 0
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    fun start(context: Context, reason: String) {
        if (_state.value is State.Contacting || _state.value is State.Notified) return
        val app = context.applicationContext
        Log.w(TAG, "SOS started ($reason)")
        attempts = 0
        _state.value = State.Contacting(SystemClock.elapsedRealtime())
        worker.post { attempt(app, reason) }
    }

    private fun attempt(app: Context, reason: String) {
        if (_state.value is State.Idle || _state.value is State.Notified) return
        attempts++
        val sent = try {
            val nodes = Tasks.await(Wearable.getNodeClient(app).connectedNodes, 5, TimeUnit.SECONDS)
            Log.i(TAG, "Attempt $attempts: ${nodes.size} connected node(s)")
            nodes.count { node ->
                runCatching {
                    Tasks.await(Wearable.getMessageClient(app).sendMessage(node.id, WatchProtocol.PATH_SOS, reason.toByteArray()), 5, TimeUnit.SECONDS)
                }.isSuccess
            }
        } catch (e: Exception) {
            Log.w(TAG, "No phone reachable: ${e.message}")
            0
        }
        Log.i(TAG, "SOS sent to $sent node(s)")
        if (sent > 0) {
            // Wait for the phone's reply; WearMessageService calls onPhoneResult.
            worker.postDelayed({ if (_state.value is State.Contacting) markUnreachable(app, reason) }, REPLY_TIMEOUT_MS)
        } else {
            markUnreachable(app, reason)
        }
    }

    private fun markUnreachable(app: Context, reason: String) {
        _state.value = State.Unreachable(attempts)
        startAlarm(app)
        worker.postDelayed({ if (_state.value is State.Unreachable) attempt(app, reason) }, RETRY_MS)
    }

    /** Called with the phone's JSON reply (WatchProtocol.sosResultJson). */
    fun onPhoneResult(app: Context, json: String) {
        Log.i(TAG, "Phone replied to SOS")
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        val names = o.optJSONArray("texted_names")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
        stopAlarm()
        _state.value = State.Notified(
            callPlaced = o.optBoolean("call_placed"), primaryName = o.optString("primary_name"),
            textsSent = o.optInt("texts_sent"), textedNames = names, contacts = o.optInt("contacts"),
            locationFound = o.optBoolean("location_found"),
        )
        AlertHistoryManager(app).recordAlert(AlertEvent(
            outcome = AlertEvent.Outcome.SOS_DISPATCHED,
            summary = if (o.optBoolean("call_placed")) "Called ${o.optString("primary_name")}" else "Call not placed",
        ))
    }

    /** The wearer is OK: stop the alarm, and if contacts may have been alerted, tell them. */
    fun imOk(context: Context) {
        val app = context.applicationContext
        val wasSent = _state.value is State.Notified || _state.value is State.Contacting
        stopAlarm()
        worker.removeCallbacksAndMessages(null)
        _state.value = State.Idle
        if (wasSent) worker.post {
            runCatching {
                val nodes = Tasks.await(Wearable.getNodeClient(app).connectedNodes, 5, TimeUnit.SECONDS)
                nodes.forEach { Tasks.await(Wearable.getMessageClient(app).sendMessage(it.id, WatchProtocol.PATH_SAFE, ByteArray(0)), 5, TimeUnit.SECONDS) }
            }.onFailure { Log.w(TAG, "Couldn't send I'm OK to the phone: ${it.message}") }
        }
    }

    fun done() {
        stopAlarm()
        _state.value = State.Idle
    }

    private fun startAlarm(app: Context) {
        vibrator = app.getSystemService(Vibrator::class.java)
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 400, 800, 400), 0))
        if (!WatchPrefs.alarmSound(app) || ringtone?.isPlaying == true) return
        ringtone = RingtoneManager.getRingtone(app, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))?.apply {
            audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            isLooping = true
            play()
        }
    }

    private fun stopAlarm() {
        ringtone?.stop()
        ringtone = null
        vibrator?.cancel()
    }
}
