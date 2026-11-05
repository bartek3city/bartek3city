package pl.bartek.callrecorder.analysis

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.bartek.callrecorder.App
import pl.bartek.callrecorder.R
import pl.bartek.callrecorder.data.AnalysisStatus
import pl.bartek.callrecorder.data.Direction
import pl.bartek.callrecorder.data.Settings
import pl.bartek.callrecorder.ui.MainActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Analiza w tle po zakończeniu rozmowy: transkrypcja z mówcami → podsumowanie Claude. */
class AnalysisWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getLong(KEY_ID, -1)
        val dao = App.instance.db.recordings()
        var rec = dao.get(id) ?: return@withContext Result.failure()
        val settings = Settings(applicationContext)

        if (settings.assemblyAiKey.isBlank() || settings.anthropicKey.isBlank()) {
            dao.setStatus(id, AnalysisStatus.NO_KEYS, "Uzupełnij klucze API w Ustawieniach")
            return@withContext Result.failure()
        }

        try {
            // 1. Transkrypcja (pomijana, jeśli już jest – np. przy ponownej analizie po błędzie Claude)
            var utterances = AssemblyAiClient.fromJson(rec.transcriptJson)
            if (utterances.isEmpty()) {
                dao.setStatus(id, AnalysisStatus.TRANSCRIBING)
                utterances = AssemblyAiClient(settings.assemblyAiKey)
                    .transcribe(File(rec.filePath), settings.language)
                rec = rec.copy(transcriptJson = AssemblyAiClient.toJson(utterances))
                dao.update(rec)
            }
            if (utterances.isEmpty()) {
                dao.update(rec.copy(status = AnalysisStatus.ERROR, error = "Nie rozpoznano mowy w nagraniu"))
                return@withContext Result.failure()
            }

            // 2. Podsumowanie
            dao.setStatus(id, AnalysisStatus.SUMMARIZING)
            val meta = ClaudeSummarizer.CallMeta(
                date = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale("pl")).format(Date(rec.startedAt)),
                direction = when (rec.direction) {
                    Direction.INCOMING -> "przychodząca (ktoś zadzwonił do właściciela)"
                    Direction.OUTGOING -> "wychodząca (właściciel zadzwonił)"
                    Direction.IMPORTED -> "nagranie zaimportowane"
                    Direction.UNKNOWN -> "nieznany"
                },
                contact = listOfNotNull(rec.contactName, rec.phoneNumber).joinToString(" ").ifBlank { null },
                myName = settings.myName,
                duration = "${rec.durationMs / 60000} min ${(rec.durationMs / 1000) % 60} s",
            )
            val summary = ClaudeSummarizer(settings.anthropicKey, settings.claudeModel).summarize(utterances, meta)
            rec = dao.get(id) ?: rec
            dao.update(
                rec.copy(
                    summaryJson = summary.toString(),
                    title = summary.optString("tytul").ifBlank { null },
                    status = AnalysisStatus.DONE,
                    error = null,
                )
            )
            notifyDone(id, summary.optString("tytul"), summary.optString("temat"))
            Result.success()
        } catch (e: TranscriptionException) {
            fail(id, e.message, e.retryable)
        } catch (e: SummaryException) {
            fail(id, e.message, e.retryable)
        } catch (e: Exception) {
            fail(id, e.message ?: e.javaClass.simpleName, false)
        }
    }

    private suspend fun fail(id: Long, msg: String?, retryable: Boolean): Result {
        val dao = App.instance.db.recordings()
        return if (retryable && runAttemptCount < 4) {
            dao.setStatus(id, AnalysisStatus.PENDING, "Ponawiam: $msg")
            Result.retry()
        } else {
            dao.setStatus(id, AnalysisStatus.ERROR, msg)
            Result.failure()
        }
    }

    private fun notifyDone(id: Long, title: String, topic: String) {
        val intent = Intent(applicationContext, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_RECORDING_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            applicationContext, id.toInt(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(applicationContext, App.CH_ANALYSIS)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(title.ifBlank { "Analiza rozmowy gotowa" })
            .setContentText(topic)
            .setStyle(Notification.BigTextStyle().bigText(topic))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { applicationContext.getSystemService(NotificationManager::class.java).notify(2000 + id.toInt(), n) }
    }

    companion object {
        private const val KEY_ID = "recording_id"

        fun enqueue(ctx: Context, recordingId: Long) {
            val req = OneTimeWorkRequestBuilder<AnalysisWorker>()
                .setInputData(workDataOf(KEY_ID to recordingId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork("analysis_$recordingId", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
