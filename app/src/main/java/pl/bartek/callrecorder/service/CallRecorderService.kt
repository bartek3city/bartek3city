package pl.bartek.callrecorder.service

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.service.quicksettings.TileService
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import android.content.ComponentName
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pl.bartek.callrecorder.App
import pl.bartek.callrecorder.R
import pl.bartek.callrecorder.analysis.AnalysisWorker
import pl.bartek.callrecorder.data.Direction
import pl.bartek.callrecorder.data.Recording
import pl.bartek.callrecorder.data.Settings
import pl.bartek.callrecorder.recorder.CallRecorder
import pl.bartek.callrecorder.ui.MainActivity
import java.io.File

/**
 * Serce aplikacji. Usługa dostępności (Accessibility Service) jest jedynym sposobem, w jaki
 * zwykła aplikacja (bez roota) może na Androidzie 10+ korzystać z mikrofonu równolegle z rozmową
 * telefoniczną i rysować przycisk nad ekranem dzwonienia bez dodatkowych uprawnień.
 */
class CallRecorderService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var settings: Settings
    private lateinit var recorder: CallRecorder

    private var telephonyCallback: TelephonyCallback? = null
    @Suppress("DEPRECATION")
    private var phoneStateListener: PhoneStateListener? = null

    private var lastState = TelephonyManager.CALL_STATE_IDLE
    private var wasRinging = false
    private var callActive = false
    private var callStartedAt = 0L

    private var overlay: TextView? = null
    private var speakerForced = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        settings = Settings(this)
        recorder = CallRecorder(this)
        instance = this
        registerCallListener()
        Log.i(TAG, "Usługa połączona")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* nie czytamy treści ekranu */ }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (recorder.isRecording) stopRecording()
        unregisterCallListener()
        hideOverlay()
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- stan połączenia

    @SuppressLint("MissingPermission")
    private fun registerCallListener() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Brak READ_PHONE_STATE – automatyczne wykrywanie rozmów wyłączone")
            return
        }
        val tm = getSystemService(TelephonyManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = onCallState(state)
            }
            tm.registerTelephonyCallback(ContextCompat.getMainExecutor(this), cb)
            telephonyCallback = cb
        } else {
            @Suppress("DEPRECATION")
            val l = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) = onCallState(state)
            }
            @Suppress("DEPRECATION")
            tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
            phoneStateListener = l
        }
    }

    /** Wywoływane z ekranu aplikacji po nadaniu uprawnień. */
    fun refreshListener() {
        unregisterCallListener()
        registerCallListener()
    }

    private fun unregisterCallListener() {
        val tm = getSystemService(TelephonyManager::class.java)
        telephonyCallback?.let { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) tm.unregisterTelephonyCallback(it) }
        @Suppress("DEPRECATION")
        phoneStateListener?.let { tm.listen(it, PhoneStateListener.LISTEN_NONE) }
        telephonyCallback = null
        phoneStateListener = null
    }

    private fun onCallState(state: Int) {
        Log.d(TAG, "Stan połączenia: $lastState -> $state")
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> if (!callActive) wasRinging = true
            TelephonyManager.CALL_STATE_OFFHOOK -> if (!callActive) {
                callActive = true
                callStartedAt = System.currentTimeMillis()
                if (settings.autoSpeaker) enableSpeaker()
                if (settings.autoRecord) startRecording()
                if (settings.showOverlay) showOverlay()
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (callActive) {
                    val incoming = wasRinging
                    callActive = false
                    hideOverlay()
                    if (recorder.isRecording) stopRecording(if (incoming) Direction.INCOMING else Direction.OUTGOING)
                    restoreSpeaker()
                }
                wasRinging = false
            }
        }
        lastState = state
    }

    // ---------------------------------------------------------------- nagrywanie

    val isRecording: Boolean get() = this::recorder.isInitialized && recorder.isRecording

    fun toggleRecording() {
        if (isRecording) stopRecording(if (callActive) currentDirection() else Direction.UNKNOWN)
        else startRecording()
    }

    private fun currentDirection() = if (wasRinging) Direction.INCOMING else Direction.OUTGOING

    fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Brak uprawnienia RECORD_AUDIO")
            return
        }
        if (!recorder.start(settings.audioSource)) {
            Log.e(TAG, "Nie udało się uruchomić nagrywania z żadnego źródła")
            return
        }
        showRecordingNotification()
        updateOverlay()
        requestTileUpdate()
    }

    fun stopRecording(direction: Direction = Direction.UNKNOWN) {
        val started = recorder.startedAt
        val source = recorder.usedSource
        val file = recorder.stop()
        getSystemService(NotificationManager::class.java).cancel(NOTIF_RECORDING)
        updateOverlay()
        requestTileUpdate()
        if (file != null) saveRecording(file, started, source, direction)
    }

    private fun saveRecording(file: File, startedAt: Long, source: String?, direction: Direction) {
        val duration = System.currentTimeMillis() - startedAt
        scope.launch(Dispatchers.IO) {
            // Rejestr połączeń zapisuje się z opóźnieniem – dajmy mu chwilę.
            delay(2000)
            val info = lookupCallLog(startedAt)
            val rec = Recording(
                filePath = file.absolutePath,
                startedAt = startedAt,
                durationMs = duration,
                direction = info?.direction ?: direction,
                phoneNumber = info?.number,
                contactName = info?.name,
                audioSource = source,
            )
            val id = App.instance.db.recordings().insert(rec)
            if (settings.autoAnalyze) AnalysisWorker.enqueue(this@CallRecorderService, id)
        }
    }

    private data class CallInfo(val number: String?, val name: String?, val direction: Direction)

    private fun lookupCallLog(startedAt: Long): CallInfo? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        return runCatching {
            contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE),
                "${CallLog.Calls.DATE} >= ?",
                arrayOf((startedAt - 5 * 60_000).toString()),
                "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                if (!c.moveToFirst()) return@use null
                val type = c.getInt(2)
                CallInfo(
                    number = c.getString(0),
                    name = c.getString(1),
                    direction = when (type) {
                        CallLog.Calls.INCOMING_TYPE, CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> Direction.INCOMING
                        CallLog.Calls.OUTGOING_TYPE -> Direction.OUTGOING
                        else -> Direction.UNKNOWN
                    },
                )
            }
        }.getOrNull()
    }

    // ---------------------------------------------------------------- głośnik (opcjonalnie)

    @Suppress("DEPRECATION")
    private fun enableSpeaker() {
        val am = getSystemService(AudioManager::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { speakerForced = am.setCommunicationDevice(it) }
            }
            if (!speakerForced) {
                am.isSpeakerphoneOn = true
                speakerForced = true
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun restoreSpeaker() {
        if (!speakerForced) return
        val am = getSystemService(AudioManager::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.clearCommunicationDevice()
            am.isSpeakerphoneOn = false
        }
        speakerForced = false
    }

    // ---------------------------------------------------------------- przycisk nad ekranem dzwonienia

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay() {
        if (overlay != null) return
        val wm = getSystemService(WindowManager::class.java)
        val size = dp(64)
        val tv = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            elevation = dp(6).toFloat()
            contentDescription = "Nagrywanie rozmowy"
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(160)
        }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        tv.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > dp(6) || kotlin.math.abs(dy) > dp(6)) moved = true
                    if (moved) {
                        lp.x = startX - dx // gravity END → oś X odwrócona
                        lp.y = startY + dy
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) toggleRecording()
                    true
                }
                else -> false
            }
        }
        runCatching { wm.addView(tv, lp) }.onSuccess {
            overlay = tv
            updateOverlay()
            tickOverlay()
        }.onFailure { Log.e(TAG, "Nie można pokazać przycisku", it) }
    }

    private fun tickOverlay() {
        handler.postDelayed({
            if (overlay != null) { updateOverlay(); tickOverlay() }
        }, 1000)
    }

    private fun updateOverlay() {
        val tv = overlay ?: return
        val rec = isRecording
        tv.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (rec) 0xEEDC2626.toInt() else 0xEE374151.toInt())
            setStroke(dp(2), 0xFFFFFFFF.toInt())
        }
        tv.text = if (rec) {
            val s = (System.currentTimeMillis() - recorder.startedAt) / 1000
            "■ STOP\n%d:%02d".format(s / 60, s % 60)
        } else "● REC"
    }

    private fun hideOverlay() {
        val tv = overlay ?: return
        overlay = null
        runCatching { getSystemService(WindowManager::class.java).removeView(tv) }
    }

    // ---------------------------------------------------------------- powiadomienia / kafelek

    private fun showRecordingNotification() {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getBroadcast(
            this, 1,
            Intent(this, NotificationActionReceiver::class.java).setAction(NotificationActionReceiver.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, App.CH_RECORDING)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Nagrywanie rozmowy")
            .setContentText("Źródło: ${recorder.usedSource ?: "?"}")
            .setOngoing(true)
            .setUsesChronometer(true)
            .setWhen(recorder.startedAt)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Zatrzymaj", stop).build())
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_RECORDING, n) }
    }

    private fun requestTileUpdate() {
        runCatching { TileService.requestListeningState(this, ComponentName(this, RecordTileService::class.java)) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "CallRecorderService"
        private const val NOTIF_RECORDING = 1001

        @Volatile
        var instance: CallRecorderService? = null
            private set

        fun isEnabled(ctx: Context): Boolean {
            val enabled = android.provider.Settings.Secure.getString(
                ctx.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val me = ComponentName(ctx, CallRecorderService::class.java).flattenToString()
            return enabled.split(':').any { it.equals(me, ignoreCase = true) }
        }
    }
}
