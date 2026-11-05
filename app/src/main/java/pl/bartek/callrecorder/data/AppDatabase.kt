package pl.bartek.callrecorder.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter fun dirToString(d: Direction) = d.name
    @TypeConverter fun stringToDir(s: String) = runCatching { Direction.valueOf(s) }.getOrDefault(Direction.UNKNOWN)
    @TypeConverter fun statusToString(s: AnalysisStatus) = s.name
    @TypeConverter fun stringToStatus(s: String) = runCatching { AnalysisStatus.valueOf(s) }.getOrDefault(AnalysisStatus.PENDING)
}

@Database(entities = [Recording::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recordings(): RecordingDao

    companion object {
        fun build(ctx: Context): AppDatabase =
            Room.databaseBuilder(ctx, AppDatabase::class.java, "recordings.db").build()
    }
}
