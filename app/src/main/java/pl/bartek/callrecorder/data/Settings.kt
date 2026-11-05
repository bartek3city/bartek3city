package pl.bartek.callrecorder.data

import android.content.Context
import android.content.SharedPreferences

/** Proste ustawienia aplikacji (klucze API trzymane lokalnie, w prywatnych danych aplikacji). */
class Settings(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var autoRecord: Boolean
        get() = p.getBoolean("auto_record", true)
        set(v) = p.edit().putBoolean("auto_record", v).apply()

    var showOverlay: Boolean
        get() = p.getBoolean("show_overlay", true)
        set(v) = p.edit().putBoolean("show_overlay", v).apply()

    var autoSpeaker: Boolean
        get() = p.getBoolean("auto_speaker", false)
        set(v) = p.edit().putBoolean("auto_speaker", v).apply()

    var autoAnalyze: Boolean
        get() = p.getBoolean("auto_analyze", true)
        set(v) = p.edit().putBoolean("auto_analyze", v).apply()

    /** Preferowane źródło audio: AUTO próbuje kolejno VOICE_CALL → VOICE_RECOGNITION → VOICE_COMMUNICATION → MIC */
    var audioSource: String
        get() = p.getString("audio_source", "AUTO") ?: "AUTO"
        set(v) = p.edit().putString("audio_source", v).apply()

    var language: String
        get() = p.getString("language", "pl") ?: "pl"
        set(v) = p.edit().putString("language", v).apply()

    var myName: String
        get() = p.getString("my_name", "Ja") ?: "Ja"
        set(v) = p.edit().putString("my_name", v).apply()

    var assemblyAiKey: String
        get() = p.getString("assemblyai_key", "") ?: ""
        set(v) = p.edit().putString("assemblyai_key", v.trim()).apply()

    var anthropicKey: String
        get() = p.getString("anthropic_key", "") ?: ""
        set(v) = p.edit().putString("anthropic_key", v.trim()).apply()

    var claudeModel: String
        get() = p.getString("claude_model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = p.edit().putString("claude_model", v.trim().ifEmpty { DEFAULT_MODEL }).apply()

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
    }
}
