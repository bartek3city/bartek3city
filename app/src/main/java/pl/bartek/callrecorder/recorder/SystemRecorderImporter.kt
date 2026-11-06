package pl.bartek.callrecorder.recorder

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.delay
import java.io.File

/**
 * Pobiera nagranie z wbudowanego rejestratora rozmów (Samsung: Wewnętrzna pamięć/Recordings/Call,
 * starsze One UI: /Call; Xiaomi: MIUI/sound_recorder/call_rec). Te rejestratory działają jako aplikacje
 * systemowe i nagrywają obie strony bezpośrednio z linii – bez roota.
 */
object SystemRecorderImporter {

    private const val TAG = "SystemRecImport"
    private val PATH_HINTS = listOf("Recordings/Call", "Call/", "call_rec", "CallRecord", "Call Recordings")

    data class Found(val file: File, val durationMs: Long, val displayName: String)

    /** Czeka (do ~40 s) aż systemowy rejestrator zapisze plik po rozmowie rozpoczętej o [callStartedAt]. */
    suspend fun awaitRecording(ctx: Context, callStartedAt: Long): Found? {
        repeat(14) {
            delay(3000)
            find(ctx, callStartedAt)?.let { return it }
        }
        return null
    }

    fun find(ctx: Context, callStartedAt: Long): Found? = runCatching {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DURATION,
        )
        val since = (callStartedAt / 1000) - 10
        ctx.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection,
            "${MediaStore.Audio.Media.DATE_ADDED} >= ?", arrayOf(since.toString()),
            "${MediaStore.Audio.Media.DATE_ADDED} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val path = c.getString(2).orEmpty()
                if (PATH_HINTS.none { path.contains(it, ignoreCase = true) }) continue
                val id = c.getLong(0)
                val name = c.getString(1) ?: "call"
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                val dir = File(ctx.filesDir, "recordings").apply { mkdirs() }
                val out = File(dir, "sys_${System.currentTimeMillis()}_${name.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
                ctx.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
                if (out.length() < 1024) { out.delete(); continue }
                Log.i(TAG, "Zaimportowano $path$name")
                return@use Found(out, c.getLong(4), name)
            }
            null
        }
    }.onFailure { Log.w(TAG, "Import nieudany", it) }.getOrNull()
}
