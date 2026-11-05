package pl.bartek.callrecorder.ui

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.bartek.callrecorder.App
import pl.bartek.callrecorder.analysis.AnalysisWorker
import pl.bartek.callrecorder.data.Direction
import pl.bartek.callrecorder.data.Recording
import pl.bartek.callrecorder.data.Settings
import java.io.File

class MainActivity : ComponentActivity() {

    /** Id nagrania do otwarcia (np. z powiadomienia „Analiza gotowa”). */
    val openRecording = mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent { AppRoot(this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val id = intent.getLongExtra(EXTRA_RECORDING_ID, -1)
        if (id > 0) openRecording.value = id
        if (intent.action == Intent.ACTION_SEND) {
            val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            uri?.let { importAudio(it) }
        }
    }

    /** Import pliku audio (np. z wbudowanego rejestratora rozmów) i uruchomienie analizy. */
    fun importAudio(uri: Uri) {
        lifecycleScope.launch {
            val id = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(filesDir, "recordings").apply { mkdirs() }
                    val ext = contentResolver.getType(uri)?.substringAfter('/')?.let {
                        when (it) { "mpeg" -> "mp3"; "mp4", "x-m4a", "m4a" -> "m4a"; "ogg" -> "ogg"; "wav", "x-wav" -> "wav"; "amr" -> "amr"; else -> "audio" }
                    } ?: "audio"
                    val out = File(dir, "import_${System.currentTimeMillis()}.$ext")
                    contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it) } }
                    val duration = runCatching {
                        MediaMetadataRetriever().run {
                            setDataSource(out.absolutePath)
                            val d = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
                            release(); d
                        }
                    }.getOrDefault(0L)
                    App.instance.db.recordings().insert(
                        Recording(
                            filePath = out.absolutePath,
                            startedAt = System.currentTimeMillis(),
                            durationMs = duration,
                            direction = Direction.IMPORTED,
                            audioSource = "IMPORT",
                        )
                    )
                }.getOrNull()
            }
            if (id == null) {
                Toast.makeText(this@MainActivity, "Nie udało się zaimportować pliku", Toast.LENGTH_LONG).show()
            } else {
                if (Settings(this@MainActivity).autoAnalyze) AnalysisWorker.enqueue(this@MainActivity, id)
                openRecording.value = id
            }
        }
    }

    companion object {
        const val EXTRA_RECORDING_ID = "recording_id"
    }
}
