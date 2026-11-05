package pl.bartek.callrecorder.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE id = :id")
    fun observe(id: Long): Flow<Recording?>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun get(id: Long): Recording?

    @Insert
    suspend fun insert(r: Recording): Long

    @Update
    suspend fun update(r: Recording)

    @Delete
    suspend fun delete(r: Recording)

    @Query("UPDATE recordings SET status = :status, error = :error WHERE id = :id")
    suspend fun setStatus(id: Long, status: AnalysisStatus, error: String? = null)
}
