package nl.icthorse.randomringtone.data

import android.content.Context
import androidx.room.*

// --- Enums ---

enum class Channel {
    CALL, NOTIFICATION, SMS, WHATSAPP
}

enum class Mode {
    FIXED, RANDOM
}

enum class Schedule {
    MANUAL,        // Alleen handmatig wisselen
    EVERY_CALL,    // Bij elke oproep/notificatie
    EVERY_HOUR,    // Elk uur
    EVERY_DAY,     // Elke dag
    EVERY_WEEK     // Elke week
}

// --- Type Converters ---

class Converters {
    @TypeConverter fun fromChannel(value: Channel): String = value.name
    @TypeConverter fun toChannel(value: String): Channel = Channel.valueOf(value)
    @TypeConverter fun fromMode(value: Mode): String = value.name
    @TypeConverter fun toMode(value: String): Mode = Mode.valueOf(value)
    @TypeConverter fun fromSchedule(value: Schedule): String = value.name
    @TypeConverter fun toSchedule(value: String): Schedule = Schedule.valueOf(value)
}

// --- Entities ---

@Entity(tableName = "assignments")
data class RingtoneAssignment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contactUri: String? = null,       // null = globaal
    val contactName: String? = null,      // display name (null = "Globaal")
    val channel: Channel,
    val mode: Mode,
    val schedule: Schedule = Schedule.EVERY_CALL,  // wanneer wisselt random ringtone
    val fixedTrackId: Long? = null,       // Deezer track ID (bij FIXED)
    val playlistName: String? = null      // playlist naam (bij RANDOM)
)

@Entity(tableName = "saved_tracks")
data class SavedTrack(
    @PrimaryKey val deezerTrackId: Long,
    val title: String,
    val artist: String,
    val previewUrl: String,
    val localPath: String? = null,
    val playlistName: String
)

// --- DAO's ---

@Dao
interface AssignmentDao {
    @Query("SELECT * FROM assignments ORDER BY contactName ASC, channel ASC")
    suspend fun getAll(): List<RingtoneAssignment>

    @Query("SELECT * FROM assignments WHERE contactUri IS NULL")
    suspend fun getGlobal(): List<RingtoneAssignment>

    @Query("SELECT * FROM assignments WHERE contactUri = :contactUri")
    suspend fun getForContact(contactUri: String): List<RingtoneAssignment>

    @Query("SELECT * FROM assignments WHERE contactUri = :contactUri AND channel = :channel LIMIT 1")
    suspend fun getForContactAndChannel(contactUri: String, channel: Channel): RingtoneAssignment?

    @Query("SELECT * FROM assignments WHERE contactUri IS NULL AND channel = :channel LIMIT 1")
    suspend fun getGlobalForChannel(channel: Channel): RingtoneAssignment?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(assignment: RingtoneAssignment): Long

    @Update
    suspend fun update(assignment: RingtoneAssignment)

    @Delete
    suspend fun delete(assignment: RingtoneAssignment)

    @Query("DELETE FROM assignments WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface SavedTrackDao {
    @Query("SELECT * FROM saved_tracks WHERE playlistName = :playlistName")
    suspend fun getByPlaylist(playlistName: String): List<SavedTrack>

    @Query("SELECT DISTINCT playlistName FROM saved_tracks ORDER BY playlistName")
    suspend fun getPlaylistNames(): List<String>

    @Query("SELECT * FROM saved_tracks WHERE deezerTrackId = :trackId LIMIT 1")
    suspend fun getById(trackId: Long): SavedTrack?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(track: SavedTrack)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tracks: List<SavedTrack>)

    @Delete
    suspend fun delete(track: SavedTrack)

    @Query("DELETE FROM saved_tracks WHERE deezerTrackId = :trackId AND playlistName = :playlistName")
    suspend fun removeFromPlaylist(trackId: Long, playlistName: String)
}

// --- Database ---

@Database(
    entities = [RingtoneAssignment::class, SavedTrack::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class RingtoneDatabase : RoomDatabase() {
    abstract fun assignmentDao(): AssignmentDao
    abstract fun savedTrackDao(): SavedTrackDao

    companion object {
        @Volatile
        private var INSTANCE: RingtoneDatabase? = null

        fun getInstance(context: Context): RingtoneDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    RingtoneDatabase::class.java,
                    "randomringtone.db"
                ).fallbackToDestructiveMigration()
                .build().also { INSTANCE = it }
            }
        }
    }
}
