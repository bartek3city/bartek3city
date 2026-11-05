package pl.bartek.callrecorder.analysis

import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Wypowiedź z transkrypcji z rozpoznanym mówcą. */
data class Utterance(val speaker: String, val text: String, val startMs: Long, val endMs: Long)

class TranscriptionException(message: String, val retryable: Boolean) : Exception(message)

/**
 * Transkrypcja z rozpoznawaniem mówców (diaryzacją) przez AssemblyAI.
 * Dokumentacja: https://www.assemblyai.com/docs
 */
class AssemblyAiClient(private val apiKey: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build()

    suspend fun transcribe(audio: File, language: String, onStatus: (String) -> Unit = {}): List<Utterance> {
        onStatus("Wysyłanie nagrania…")
        val uploadUrl = upload(audio)

        val body = JSONObject().apply {
            put("audio_url", uploadUrl)
            put("speaker_labels", true)
            put("speakers_expected", 2)
            put("punctuate", true)
            put("format_text", true)
            if (language == "auto") put("language_detection", true) else put("language_code", language)
        }
        val created = call(
            Request.Builder().url("$BASE/transcript")
                .post(body.toString().toRequestBody(JSON))
        )
        val id = created.getString("id")

        onStatus("Transkrypcja…")
        // WorkManager pozwala na ok. 10 min pracy – po przekroczeniu zadanie zostanie ponowione.
        val deadline = System.currentTimeMillis() + 8 * 60_000
        while (System.currentTimeMillis() < deadline) {
            delay(4000)
            val t = call(Request.Builder().url("$BASE/transcript/$id").get())
            when (t.optString("status")) {
                "completed" -> return parse(t)
                "error" -> throw TranscriptionException("AssemblyAI: ${t.optString("error")}", retryable = false)
            }
        }
        throw TranscriptionException("Przekroczono czas oczekiwania na transkrypcję", retryable = true)
    }

    private fun upload(audio: File): String {
        val req = Request.Builder().url("$BASE/upload")
            .post(audio.asRequestBody("application/octet-stream".toMediaType()))
        return call(req).getString("upload_url")
    }

    private fun call(builder: Request.Builder): JSONObject {
        val req = builder.header("authorization", apiKey).build()
        try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val retry = resp.code == 429 || resp.code >= 500
                    val msg = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty()
                    throw TranscriptionException("AssemblyAI HTTP ${resp.code} ${msg.ifEmpty { text.take(200) }}", retry)
                }
                return JSONObject(text)
            }
        } catch (e: IOException) {
            throw TranscriptionException("Błąd sieci: ${e.message}", retryable = true)
        }
    }

    private fun parse(t: JSONObject): List<Utterance> {
        val arr = t.optJSONArray("utterances")
        if (arr == null || arr.length() == 0) {
            val text = t.optString("text")
            return if (text.isBlank()) emptyList() else listOf(Utterance("A", text, 0, t.optLong("audio_duration") * 1000))
        }
        return (0 until arr.length()).map { i ->
            val u = arr.getJSONObject(i)
            Utterance(u.optString("speaker", "?"), u.optString("text"), u.optLong("start"), u.optLong("end"))
        }
    }

    companion object {
        private const val BASE = "https://api.assemblyai.com/v2"
        private val JSON = "application/json".toMediaType()

        fun toJson(list: List<Utterance>): String = JSONArray().apply {
            list.forEach {
                put(JSONObject().put("speaker", it.speaker).put("text", it.text).put("start", it.startMs).put("end", it.endMs))
            }
        }.toString()

        fun fromJson(json: String?): List<Utterance> {
            if (json.isNullOrBlank()) return emptyList()
            val arr = JSONArray(json)
            return (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Utterance(o.optString("speaker"), o.optString("text"), o.optLong("start"), o.optLong("end"))
            }
        }
    }
}
