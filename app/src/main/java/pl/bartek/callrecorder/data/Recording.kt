package pl.bartek.callrecorder.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class Direction { INCOMING, OUTGOING, UNKNOWN, IMPORTED }

enum class AnalysisStatus { PENDING, TRANSCRIBING, SUMMARIZING, DONE, ERROR, NO_KEYS }

@Entity(tableName = "recordings")
data class Recording(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filePath: String,
    val startedAt: Long,
    val durationMs: Long = 0,
    val direction: Direction = Direction.UNKNOWN,
    val phoneNumber: String? = null,
    val contactName: String? = null,
    /** Źródło audio użyte do nagrania (VOICE_CALL / VOICE_RECOGNITION / MIC ...). */
    val audioSource: String? = null,
    val status: AnalysisStatus = AnalysisStatus.PENDING,
    val error: String? = null,
    /** JSON: lista wypowiedzi [{speaker, text, start, end}] */
    val transcriptJson: String? = null,
    /** JSON z podsumowaniem wygenerowanym przez Claude */
    val summaryJson: String? = null,
    /** Krótki tytuł (z podsumowania) do listy */
    val title: String? = null,
)
