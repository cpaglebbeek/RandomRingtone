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
    EVERY_CALL,    // Bij elke inkomend/uitgaand gesprek — nooit dezelfde ringtone
    HOURLY_1,      // Elke 1 uur
    HOURLY_2,      // Elke 2 uur
    HOURLY_4,      // Elke 4 uur
    HOURLY_8,      // Elke 8 uur
    HOURLY_12,     // Elke 12 uur
    DAILY,         // Elke 24 uur
    WEEKLY         // Elke week
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

/**
 * Playlist: benoemde verzameling ringtones met trigger- en schema-configuratie.
 */
@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,                          // Playlist naam (bijv. "Rock", "Werk", "Familie")
    val channel: Channel = Channel.CALL,       // Trigger: telefoon, sms, whatsapp, notificatie
    val mode: Mode = Mode.RANDOM,              // Vast (eerste track) of Random
    val schedule: Schedule = Schedule.EVERY_CALL,
    val contactUri: String? = null,            // null = globaal, anders per-contact
    val contactName: String? = null,
    val isActive: Boolean = true,              // Playlist actief of gepauzeerd
    val lastPlayedTrackId: Long? = null        // Bijhouden welke track laatst gespeeld is (voor EVERY_CALL: niet dezelfde)
)

/**
 * Koppeltabel: welke ringtones zitten in welke playlist.
 */
@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "trackId"],
    foreignKeys = [
        ForeignKey(
            entity = Playlist::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class PlaylistTrack(
    val playlistId: Long,
    val trackId: Long,          // Verwijst naar SavedTrack.deezerTrackId
    val sortOrder: Int = 0      // Volgorde binnen playlist
)

@Entity(tableName = "saved_tracks")
data class SavedTrack(
    @PrimaryKey val deezerTrackId: Long,
    val title: String,
    val artist: String,
    val previewUrl: String,
    val localPath: String? = null,
    val playlistName: String          // Legacy veld — nieuwe tracks gebruiken playlist_tracks
)

// --- DAO's ---

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY name ASC")
    suspend fun getAll(): List<Playlist>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getById(id: Long): Playlist?

    @Query("SELECT * FROM playlists WHERE isActive = 1")
    suspend fun getActive(): List<Playlist>

    @Query("SELECT * FROM playlists WHERE isActive = 1 AND channel = :channel AND contactUri IS NULL")
    suspend fun getActiveGlobalForChannel(channel: Channel): List<Playlist>

    @Query("SELECT * FROM playlists WHERE isActive = 1 AND channel = :channel AND contactName = :contactName")
    suspend fun getActiveForContactAndChannel(contactName: String, channel: Channel): List<Playlist>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(playlist: Playlist): Long

    @Update
    suspend fun update(playlist: Playlist)

    @Delete
    suspend fun delete(playlist: Playlist)

    @Query("UPDATE playlists SET isActive = 0 WHERE channel = :channel AND contactUri IS NULL AND id != :excludeId AND isActive = 1")
    suspend fun deactivateOtherGlobal(channel: Channel, excludeId: Long)

    @Query("UPDATE playlists SET isActive = 0 WHERE channel = :channel AND contactUri = :contactUri AND id != :excludeId AND isActive = 1")
    suspend fun deactivateOtherForContact(channel: Channel, contactUri: String, excludeId: Long)
}

@Dao
interface PlaylistTrackDao {
    @Query("SELECT st.* FROM saved_tracks st INNER JOIN playlist_tracks pt ON st.deezerTrackId = pt.trackId WHERE pt.playlistId = :playlistId ORDER BY pt.sortOrder ASC")
    suspend fun getTracksForPlaylist(playlistId: Long): List<SavedTrack>

    @Query("SELECT COUNT(*) FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun getTrackCount(playlistId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(playlistTrack: PlaylistTrack)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun remove(playlistId: Long, trackId: Long)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun removeAll(playlistId: Long)

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun getNextSortOrder(playlistId: Long): Int
}

@Dao
interface SavedTrackDao {
    @Query("SELECT * FROM saved_tracks WHERE playlistName = :playlistName")
    suspend fun getByPlaylist(playlistName: String): List<SavedTrack>

    @Query("SELECT DISTINCT playlistName FROM saved_tracks ORDER BY playlistName")
    suspend fun getPlaylistNames(): List<String>

    @Query("SELECT * FROM saved_tracks WHERE deezerTrackId = :trackId LIMIT 1")
    suspend fun getById(trackId: Long): SavedTrack?

    @Query("SELECT * FROM saved_tracks ORDER BY artist ASC, title ASC")
    suspend fun getAll(): List<SavedTrack>

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
    entities = [SavedTrack::class, Playlist::class, PlaylistTrack::class],
    version = 4,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class RingtoneDatabase : RoomDatabase() {
    abstract fun savedTrackDao(): SavedTrackDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun playlistTrackDao(): PlaylistTrackDao

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
