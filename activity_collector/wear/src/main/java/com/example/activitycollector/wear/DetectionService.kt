package com.example.activitycollector.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.activitycollector.FallDetector
import com.example.activitycollector.InferenceEngine
import com.example.activitycollector.SensorBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

/**
 * Runs fall detection on the watch all day, with the screen off.
 *
 * Pipeline (the same one har_pipeline/simulate_detector.py replays):
 * 50 Hz accelerometer + gyroscope, capped like the training data -> SensorBuffer
 * -> InferenceEngine with the 2-class wrist model every 400 ms -> FallDetector
 * with the "wrist" preset.
 *
 * Two-step alert: a confirmed fall vibrates and asks "Are you OK?". The wearer
 * can tap "I'm OK". Otherwise the service watches the next 60 s; if the wearer
 * stays near-still it marks the event "would auto-call" (the call itself comes
 * in Phase 5). Every event is saved as recordings/alert_<time>.csv.
 */
class DetectionService : Service(), SensorEventListener {

    data class State(
        val running: Boolean = false,
        val startedAtElapsedMs: Long = 0,
        val lastFallProbability: Float? = null,   // null until the model has run
        val alertActive: Boolean = false,
        val alertStartedAtElapsedMs: Long = 0,
        val promptsThisSession: Int = 0,
    )

    companion object {
        private const val TAG = "DetectionService"
        private const val CHANNEL_RUNNING = "detection"
        private const val CHANNEL_ALERT = "fall_alert"
        private const val NOTIFICATION_RUNNING = 51
        private const val NOTIFICATION_ALERT = 52
        const val ACTION_START = "com.example.activitycollector.wear.DETECTION_START"
        const val ACTION_STOP = "com.example.activitycollector.wear.DETECTION_STOP"
        const val ACTION_IM_OK = "com.example.activitycollector.wear.IM_OK"

        // Same caps as training (har_pipeline/train_wrist.py): WEDA's Fitbit saturates at 4 g.
        const val ACC_CAP = 4f * 9.80665f
        const val GYR_CAP = 34.906586f   // 2000 deg/s in rad/s

        // Step 2 of the alert, as in simulate_detector.py.
        const val IMMOBILE_S = 60
        const val IMMOBILE_BLOCK_STD = 0.5f
        const val IMMOBILE_FRACTION = 0.8f

        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state.asStateFlow()

        fun start(context: Context) = context.startForegroundService(
            Intent(context, DetectionService::class.java).setAction(ACTION_START))

        fun stop(context: Context) = context.startService(
            Intent(context, DetectionService::class.java).setAction(ACTION_STOP))

        fun imOk(context: Context) = context.startService(
            Intent(context, DetectionService::class.java).setAction(ACTION_IM_OK))
    }

    private lateinit var sensorManager: SensorManager
    private var sensorThread: HandlerThread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val uiHandler by lazy { Handler(mainLooper) }
    private val buffer = SensorBuffer(capacity = 200)
    private lateinit var detector: FallDetector
    private var engine: InferenceEngine? = null
    private val vibrator by lazy { getSystemService(Vibrator::class.java) }

    // Immobility check after a prompt, updated on the sensor thread.
    @Volatile private var watchingStillness = false
    private var blockN = 0
    private var blockSum = 0.0
    private var blockSumSq = 0.0
    private val stillBlocks = mutableListOf<Boolean>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_IM_OK -> { finishAlert("dismissed_by_tap"); return START_NOT_STICKY }
        }
        if (_state.value.running) return START_NOT_STICKY

        createChannels()
        ServiceCompat.startForeground(
            this, NOTIFICATION_RUNNING, runningNotification(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH else 0,
        )

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (accel == null || gyro == null) {
            Log.e(TAG, "Needs both accelerometer and gyroscope")
            stopSelf()
            return START_NOT_STICKY
        }

        detector = FallDetector(
            onFallConfirmed = { uiHandler.post { startAlert() } },
            onPhaseChanged = { phase, reason -> Log.i(TAG, "$phase: $reason") },
        ).apply { applySensitivity("wrist") }

        engine = InferenceEngine(
            context = this, buffer = buffer, windowSize = 100, inferenceIntervalMs = 400L,
            modelAsset = "wrist_model.ptl", labelsAsset = "wrist_labels.txt",
        ) { label, confidence, probs ->
            val window = buffer.copyWindow2D(100) ?: return@InferenceEngine
            detector.onInferenceResult(label, probs, window)
            if (confidence != InferenceEngine.CONFIDENCE_NOT_FROM_MODEL && probs.isNotEmpty()) {
                _state.value = _state.value.copy(lastFallProbability = probs[0])
            }
        }

        // Keep the CPU draining the sensor FIFO with the screen off (see RecordingService).
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FallGuard:Detection").apply { acquire() }
        val thread = HandlerThread("detection-sensors").apply { start() }
        sensorThread = thread
        val handler = Handler(thread.looper)
        sensorManager.registerListener(this, accel, RecordingService.SAMPLING_PERIOD_US, handler)
        sensorManager.registerListener(this, gyro, RecordingService.SAMPLING_PERIOD_US, handler)
        engine?.start()

        _state.value = State(running = true, startedAtElapsedMs = SystemClock.elapsedRealtime())
        Log.i(TAG, "Fall detection started")
        return START_NOT_STICKY
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                buffer.latestGx = v[0].coerceIn(-GYR_CAP, GYR_CAP)
                buffer.latestGy = v[1].coerceIn(-GYR_CAP, GYR_CAP)
                buffer.latestGz = v[2].coerceIn(-GYR_CAP, GYR_CAP)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val ax = v[0].coerceIn(-ACC_CAP, ACC_CAP)
                val ay = v[1].coerceIn(-ACC_CAP, ACC_CAP)
                val az = v[2].coerceIn(-ACC_CAP, ACC_CAP)
                buffer.push(ax, ay, az)
                if (watchingStillness) addToStillnessBlock(sqrt(ax * ax + ay * ay + az * az).toDouble())
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** One block = 1 s at 50 Hz; a block is "still" when |a| barely varies. */
    private fun addToStillnessBlock(mag: Double) {
        blockN++
        blockSum += mag
        blockSumSq += mag * mag
        if (blockN < 50) return
        val mean = blockSum / blockN
        val std = sqrt((blockSumSq / blockN - mean * mean).coerceAtLeast(0.0))
        stillBlocks.add(std < IMMOBILE_BLOCK_STD)
        blockN = 0; blockSum = 0.0; blockSumSq = 0.0
        if (stillBlocks.size >= IMMOBILE_S) {
            watchingStillness = false   // stop here, on the sensor thread, so the result is posted once
            val stillFraction = stillBlocks.count { it }.toFloat() / stillBlocks.size
            val outcome = if (stillFraction >= IMMOBILE_FRACTION) "would_auto_call" else "moved_no_call"
            uiHandler.post { finishAlert(outcome, stillFraction) }
        }
    }

    private fun startAlert() {
        if (_state.value.alertActive) return
        // Safe to reset here: the sensor thread only touches these while watchingStillness is true.
        stillBlocks.clear()
        blockN = 0; blockSum = 0.0; blockSumSq = 0.0
        watchingStillness = true
        _state.value = _state.value.copy(
            alertActive = true,
            alertStartedAtElapsedMs = SystemClock.elapsedRealtime(),
            promptsThisSession = _state.value.promptsThisSession + 1,
        )
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 300, 600, 300), 0))
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ALERT, alertNotification())
        Log.w(TAG, "Possible fall: impact ${detector.lastFallImpactPeak} m/s², model ${detector.lastFallConfidencePct}%")
    }

    private fun finishAlert(outcome: String, stillFraction: Float? = null) {
        if (!_state.value.alertActive) return
        watchingStillness = false
        vibrator?.cancel()
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ALERT)
        val secondsToOutcome = (SystemClock.elapsedRealtime() - _state.value.alertStartedAtElapsedMs) / 1000.0
        saveAlert(outcome, secondsToOutcome, stillFraction)
        detector.dismissFall()
        _state.value = _state.value.copy(alertActive = false)
        Log.i(TAG, "Alert finished: $outcome")
    }

    private fun saveAlert(outcome: String, secondsToOutcome: Double, stillFraction: Float?) {
        val now = Date()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now)
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(now)
        val file = File(RecordingService.recordingsDir(this), "alert_$stamp.csv")
        file.writeText(
            "time,outcome,seconds_to_outcome,still_fraction,impact_peak_ms2,model_fall_pct,activity_before\n" +
                "$iso,$outcome,${"%.1f".format(Locale.US, secondsToOutcome)}," +
                "${stillFraction?.let { "%.2f".format(Locale.US, it) } ?: ""}," +
                "${"%.1f".format(Locale.US, detector.lastFallImpactPeak)},${detector.lastFallConfidencePct}," +
                "${detector.lastFallPriorActivity ?: ""}\n"
        )
    }

    override fun onDestroy() {
        if (_state.value.alertActive) finishAlert("detection_stopped")
        engine?.destroy()
        if (::sensorManager.isInitialized) sensorManager.unregisterListener(this)
        sensorThread?.quitSafely()
        wakeLock?.let { if (it.isHeld) it.release() }
        _state.value = State()
        super.onDestroy()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_RUNNING, "Fall detection", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERT, "Possible fall", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun openApp(requestCode: Int) = PendingIntent.getActivity(
        this, requestCode,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun runningNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_RUNNING)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("FallGuard is on")
            .setContentText("Watching for falls")
            .setOngoing(true)
            .setContentIntent(openApp(10))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun alertNotification(): Notification {
        val ok = PendingIntent.getService(
            this, 11, Intent(this, DetectionService::class.java).setAction(ACTION_IM_OK), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Are you OK?")
            .setContentText("A possible fall was detected. Tap I'm OK if you're fine.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(openApp(12))
            .setFullScreenIntent(openApp(13), true)
            .addAction(android.R.drawable.checkbox_on_background, "I'm OK", ok)
            .build()
    }
}
