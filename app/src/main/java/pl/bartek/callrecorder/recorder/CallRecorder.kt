package pl.bartek.callrecorder.recorder

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Nagrywanie rozmowy przez MediaRecorder (AAC/M4A).
 *
 * Kolejność źródeł w trybie AUTO:
 *  1. VOICE_CALL – obie strony bezpośrednio z linii (działa tylko na części urządzeń / z rootem),
 *  2. VOICE_RECOGNITION – mikrofon bez agresywnej redukcji szumów; dla usługi dostępności
 *     Android 10+ pozwala nagrywać równolegle z rozmową,
 *  3. VOICE_COMMUNICATION, 4. MIC.
 * Jeśli źródło startuje, ale przez ~2,5 s daje ciszę (typowe dla zablokowanego VOICE_CALL),
 * rekorder automatycznie przełącza się na następne.
 */
class CallRecorder(private val ctx: Context) {

    private var recorder: MediaRecorder? = null
    private val handler = Handler(Looper.getMainLooper())
    private var candidates: List<Int> = emptyList()
    private var candidateIndex = 0
    private var silenceChecks = 0

    var outputFile: File? = null
        private set
    var startedAt: Long = 0
        private set
    var usedSource: String? = null
        private set
    val isRecording: Boolean get() = recorder != null

    fun start(preferred: String): Boolean {
        if (isRecording) return true
        candidates = when (preferred) {
            "VOICE_CALL" -> listOf(MediaRecorder.AudioSource.VOICE_CALL)
            "VOICE_RECOGNITION" -> listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            "VOICE_COMMUNICATION" -> listOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            "MIC" -> listOf(MediaRecorder.AudioSource.MIC)
            else -> listOf(
                MediaRecorder.AudioSource.VOICE_CALL,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.MIC,
            )
        }
        startedAt = System.currentTimeMillis()
        val dir = File(ctx.filesDir, "recordings").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startedAt))
        outputFile = File(dir, "call_$stamp.m4a")
        candidateIndex = 0
        return startFromIndex()
    }

    private fun startFromIndex(): Boolean {
        while (candidateIndex < candidates.size) {
            val src = candidates[candidateIndex]
            if (tryStart(src)) {
                usedSource = sourceName(src)
                Log.i(TAG, "Nagrywanie ze źródła $usedSource")
                if (candidateIndex < candidates.size - 1) scheduleSilenceCheck()
                return true
            }
            candidateIndex++
        }
        usedSource = null
        return false
    }

    private fun tryStart(source: Int): Boolean {
        val file = outputFile ?: return false
        file.delete()
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(ctx)
        else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            r.setAudioSource(source)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(32000)
            r.setAudioEncodingBitRate(64000)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            true
        } catch (e: Exception) {
            Log.w(TAG, "Źródło ${sourceName(source)} niedostępne: ${e.message}")
            runCatching { r.release() }
            false
        }
    }

    /** Sprawdza, czy wybrane źródło nie nagrywa samej ciszy; jeśli tak – przełącza na kolejne. */
    private fun scheduleSilenceCheck() {
        silenceChecks = 0
        var maxSeen = 0
        handler.removeCallbacksAndMessages(null)
        val check = object : Runnable {
            override fun run() {
                val r = recorder ?: return
                maxSeen = maxOf(maxSeen, runCatching { r.maxAmplitude }.getOrDefault(0))
                silenceChecks++
                if (maxSeen > 0) return // jest sygnał – zostajemy przy tym źródle
                if (silenceChecks < 5) {
                    handler.postDelayed(this, 500)
                } else {
                    Log.w(TAG, "Źródło $usedSource daje ciszę – przełączam")
                    releaseRecorder()
                    candidateIndex++
                    startFromIndex()
                }
            }
        }
        handler.postDelayed(check, 500)
    }

    /** Zatrzymuje nagrywanie i zwraca plik (lub null, gdy nic nie nagrano). */
    fun stop(): File? {
        handler.removeCallbacksAndMessages(null)
        val ok = releaseRecorder()
        val f = outputFile
        outputFile = null
        return if (ok && f != null && f.exists() && f.length() > 1024) f else {
            f?.delete(); null
        }
    }

    private fun releaseRecorder(): Boolean {
        val r = recorder ?: return false
        recorder = null
        val ok = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        return ok
    }

    companion object {
        private const val TAG = "CallRecorder"
        fun sourceName(s: Int) = when (s) {
            MediaRecorder.AudioSource.VOICE_CALL -> "VOICE_CALL"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
            MediaRecorder.AudioSource.MIC -> "MIC"
            else -> "SOURCE_$s"
        }
    }
}
