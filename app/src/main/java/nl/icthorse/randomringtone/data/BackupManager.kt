package nl.icthorse.randomringtone.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// --- Backup data classes ---

@Serializable
data class BackupMeta(
    val appVersion: String,
    val backupDate: String,
    val trackCount: Int,
    val playlistCount: Int,
    val playlistTrackCount: Int,
    val downloadFileCount: Int,
    val ringtoneFileCount: Int
)

@Serializable
data class TrackBackup(
    val deezerTrackId: Long,
    val title: String,
    val artist: String,
    val previewUrl: String,
    val localPath: String?,
    val playlistName: String
)

@Serializable
data class PlaylistBackup(
    val id: Long,
    val name: String,
    val channel: String,
    val mode: String,
    val schedule: String,
    val contactUri: String?,
    val contactName: String?,
    val isActive: Boolean,
    val lastPlayedTrackId: Long?
)

@Serializable
data class PlaylistTrackBackup(
    val playlistId: Long,
    val trackId: Long,
    val sortOrder: Int
)

data class BackupProgress(
    val phase: String,
    val current: Int,
    val total: Int
)

data class BackupResult(
    val success: Boolean,
    val message: String,
    val trackCount: Int = 0,
    val playlistCount: Int = 0,
    val fileCount: Int = 0
)

class BackupManager(private val context: Context) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /**
     * Maak een volledige backup: database export + MP3 bestanden → SAF locatie.
     */
    suspend fun backup(
        backupUri: Uri,
        db: RingtoneDatabase,
        storage: StorageManager,
        onProgress: (BackupProgress) -> Unit
    ): BackupResult = withContext(Dispatchers.IO) {
        try {
            val rootDoc = DocumentFile.fromTreeUri(context, backupUri)
                ?: return@withContext BackupResult(false, "Backup map niet toegankelijk")

            val backupDir = rootDoc.findFile("RandomRingtone_Backup")
                ?.takeIf { it.isDirectory }
                ?: rootDoc.createDirectory("RandomRingtone_Backup")
                ?: return@withContext BackupResult(false, "Kan backup map niet aanmaken")

            // Phase 1: Export database
            onProgress(BackupProgress("Database exporteren...", 1, 5))

            val tracks = db.savedTrackDao().getAll()
            val playlists = db.playlistDao().getAll()
            val playlistTracks = db.playlistTrackDao().getAll()

            val trackBackups = tracks.map {
                TrackBackup(it.deezerTrackId, it.title, it.artist, it.previewUrl, it.localPath, it.playlistName)
            }
            val playlistBackups = playlists.map {
                PlaylistBackup(it.id, it.name, it.channel.name, it.mode.name, it.schedule.name,
                    it.contactUri, it.contactName, it.isActive, it.lastPlayedTrackId)
            }
            val ptBackups = playlistTracks.map {
                PlaylistTrackBackup(it.playlistId, it.trackId, it.sortOrder)
            }

            // Phase 2: Write JSON files
            onProgress(BackupProgress("JSON bestanden schrijven...", 2, 5))
            writeJsonFile(backupDir, "saved_tracks.json", json.encodeToString(trackBackups))
            writeJsonFile(backupDir, "playlists.json", json.encodeToString(playlistBackups))
            writeJsonFile(backupDir, "playlist_tracks.json", json.encodeToString(ptBackups))

            // Phase 3: Copy download files
            onProgress(BackupProgress("Downloads kopiëren...", 3, 5))
            val downloadDir = storage.getDownloadDir()
            val downloadFiles = downloadDir.listFiles()?.filter { it.isFile && it.name.endsWith(".mp3") } ?: emptyList()
            val dlSafDir = getOrCreateSubDir(backupDir, "downloads")
            var copiedFiles = 0
            for (file in downloadFiles) {
                copyFileToSaf(file, dlSafDir)
                copiedFiles++
            }

            // Phase 4: Copy ringtone files
            onProgress(BackupProgress("Ringtones kopiëren...", 4, 5))
            val ringtoneDir = storage.getRingtoneDir()
            val ringtoneFiles = ringtoneDir.listFiles()?.filter { it.isFile && it.name.endsWith(".mp3") } ?: emptyList()
            val rtSafDir = getOrCreateSubDir(backupDir, "ringtones")
            for (file in ringtoneFiles) {
                copyFileToSaf(file, rtSafDir)
                copiedFiles++
            }

            // Phase 5: Write metadata
            onProgress(BackupProgress("Metadata opslaan...", 5, 5))
            val meta = BackupMeta(
                appVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?",
                backupDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
                trackCount = tracks.size,
                playlistCount = playlists.size,
                playlistTrackCount = playlistTracks.size,
                downloadFileCount = downloadFiles.size,
                ringtoneFileCount = ringtoneFiles.size
            )
            writeJsonFile(backupDir, "backup_meta.json", json.encodeToString(meta))

            BackupResult(
                success = true,
                message = "Backup geslaagd: ${tracks.size} tracks, ${playlists.size} playlists, $copiedFiles bestanden",
                trackCount = tracks.size,
                playlistCount = playlists.size,
                fileCount = copiedFiles
            )
        } catch (e: Exception) {
            BackupResult(false, "Backup mislukt: ${e.message}")
        }
    }

    /**
     * Herstel een volledige backup: database import + MP3 bestanden ← SAF locatie.
     * Vervangt ALLE huidige data.
     */
    suspend fun restore(
        backupUri: Uri,
        db: RingtoneDatabase,
        storage: StorageManager,
        onProgress: (BackupProgress) -> Unit
    ): BackupResult = withContext(Dispatchers.IO) {
        try {
            val rootDoc = DocumentFile.fromTreeUri(context, backupUri)
                ?: return@withContext BackupResult(false, "Backup map niet toegankelijk")

            val backupDir = rootDoc.findFile("RandomRingtone_Backup")
                ?: return@withContext BackupResult(false, "Geen backup gevonden in deze map")

            // Phase 1: Validate
            onProgress(BackupProgress("Backup valideren...", 1, 5))
            backupDir.findFile("backup_meta.json")
                ?: return@withContext BackupResult(false, "backup_meta.json niet gevonden")

            // Phase 2: Read JSON
            onProgress(BackupProgress("Database lezen...", 2, 5))

            val tracksJson = readSafFile(backupDir.findFile("saved_tracks.json")
                ?: return@withContext BackupResult(false, "saved_tracks.json niet gevonden"))
            val playlistsJson = readSafFile(backupDir.findFile("playlists.json")
                ?: return@withContext BackupResult(false, "playlists.json niet gevonden"))
            val ptJson = readSafFile(backupDir.findFile("playlist_tracks.json")
                ?: return@withContext BackupResult(false, "playlist_tracks.json niet gevonden"))

            val trackBackups = json.decodeFromString<List<TrackBackup>>(tracksJson)
            val playlistBackups = json.decodeFromString<List<PlaylistBackup>>(playlistsJson)
            val ptBackups = json.decodeFromString<List<PlaylistTrackBackup>>(ptJson)

            // Phase 3: Clear and restore database
            onProgress(BackupProgress("Database herstellen...", 3, 5))
            db.clearAllTables()

            // Insert tracks
            val ringtoneDir = storage.getRingtoneDir()
            val tracks = trackBackups.map { tb ->
                val newLocalPath = if (tb.localPath != null) {
                    val fileName = File(tb.localPath).name
                    File(ringtoneDir, fileName).absolutePath
                } else null
                SavedTrack(tb.deezerTrackId, tb.title, tb.artist, tb.previewUrl, newLocalPath, tb.playlistName)
            }
            db.savedTrackDao().insertAll(tracks)

            // Insert playlists (met expliciete IDs)
            for (pb in playlistBackups) {
                db.playlistDao().insert(
                    Playlist(
                        id = pb.id,
                        name = pb.name,
                        channel = Channel.valueOf(pb.channel),
                        mode = Mode.valueOf(pb.mode),
                        schedule = Schedule.valueOf(pb.schedule),
                        contactUri = pb.contactUri,
                        contactName = pb.contactName,
                        isActive = pb.isActive,
                        lastPlayedTrackId = pb.lastPlayedTrackId
                    )
                )
            }

            // Insert playlist_tracks
            for (pt in ptBackups) {
                db.playlistTrackDao().insert(PlaylistTrack(pt.playlistId, pt.trackId, pt.sortOrder))
            }

            // Phase 4: Copy files back
            onProgress(BackupProgress("Bestanden herstellen...", 4, 5))
            var restoredFiles = 0

            val dlSafDir = backupDir.findFile("downloads")
            if (dlSafDir != null && dlSafDir.isDirectory) {
                val downloadDir = storage.getDownloadDir()
                for (safFile in dlSafDir.listFiles()) {
                    if (safFile.isFile && safFile.name?.endsWith(".mp3") == true) {
                        copyFileFromSaf(safFile, File(downloadDir, safFile.name!!))
                        restoredFiles++
                    }
                }
            }

            val rtSafDir = backupDir.findFile("ringtones")
            if (rtSafDir != null && rtSafDir.isDirectory) {
                for (safFile in rtSafDir.listFiles()) {
                    if (safFile.isFile && safFile.name?.endsWith(".mp3") == true) {
                        copyFileFromSaf(safFile, File(ringtoneDir, safFile.name!!))
                        restoredFiles++
                    }
                }
            }

            onProgress(BackupProgress("Klaar!", 5, 5))

            BackupResult(
                success = true,
                message = "Herstel geslaagd: ${trackBackups.size} tracks, ${playlistBackups.size} playlists, $restoredFiles bestanden",
                trackCount = trackBackups.size,
                playlistCount = playlistBackups.size,
                fileCount = restoredFiles
            )
        } catch (e: Exception) {
            BackupResult(false, "Herstel mislukt: ${e.message}")
        }
    }

    /**
     * Lees backup metadata zonder volledig herstel.
     */
    suspend fun readBackupInfo(backupUri: Uri): BackupMeta? = withContext(Dispatchers.IO) {
        try {
            val rootDoc = DocumentFile.fromTreeUri(context, backupUri) ?: return@withContext null
            val backupDir = rootDoc.findFile("RandomRingtone_Backup") ?: return@withContext null
            val metaFile = backupDir.findFile("backup_meta.json") ?: return@withContext null
            json.decodeFromString<BackupMeta>(readSafFile(metaFile))
        } catch (_: Exception) {
            null
        }
    }

    // --- Lokale auto-backup (overleeft app updates) ---

    private fun getAutoBackupDir(): File =
        File(context.filesDir, "auto_backup").apply { mkdirs() }

    /**
     * Schrijf database + instellingen naar filesDir/auto_backup/.
     * Wordt aangeroepen bij significante wijzigingen.
     */
    suspend fun autoBackupToLocal(
        db: RingtoneDatabase,
        storage: StorageManager
    ) = withContext(Dispatchers.IO) {
        try {
            val dir = getAutoBackupDir()

            val tracks = db.savedTrackDao().getAll()
            val playlists = db.playlistDao().getAll()
            val playlistTracks = db.playlistTrackDao().getAll()

            val trackBackups = tracks.map {
                TrackBackup(it.deezerTrackId, it.title, it.artist, it.previewUrl, it.localPath, it.playlistName)
            }
            val playlistBackups = playlists.map {
                PlaylistBackup(it.id, it.name, it.channel.name, it.mode.name, it.schedule.name,
                    it.contactUri, it.contactName, it.isActive, it.lastPlayedTrackId)
            }
            val ptBackups = playlistTracks.map {
                PlaylistTrackBackup(it.playlistId, it.trackId, it.sortOrder)
            }

            File(dir, "saved_tracks.json").writeText(json.encodeToString(trackBackups))
            File(dir, "playlists.json").writeText(json.encodeToString(playlistBackups))
            File(dir, "playlist_tracks.json").writeText(json.encodeToString(ptBackups))

            // Instellingen opslaan
            @Serializable
            data class SettingsBackup(
                val downloadPath: String?,
                val ringtonePath: String?,
                val spotifyConverter: String,
                val backupUri: String?
            )
            val settings = SettingsBackup(
                downloadPath = storage.getDownloadDir().absolutePath,
                ringtonePath = storage.getRingtoneDir().absolutePath,
                spotifyConverter = storage.getSpotifyConverter(),
                backupUri = storage.getBackupUri()
            )
            File(dir, "settings.json").writeText(json.encodeToString(settings))

            val meta = BackupMeta(
                appVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?",
                backupDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
                trackCount = tracks.size,
                playlistCount = playlists.size,
                playlistTrackCount = playlistTracks.size,
                downloadFileCount = 0,
                ringtoneFileCount = 0
            )
            File(dir, "backup_meta.json").writeText(json.encodeToString(meta))
        } catch (_: Exception) {
            // Stille fout — auto-backup is best-effort
        }
    }

    /**
     * Herstel vanuit lokale auto-backup als de database leeg is.
     * @return true als er hersteld is
     */
    suspend fun autoRestoreFromLocal(
        db: RingtoneDatabase,
        storage: StorageManager
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Alleen herstellen als DB leeg is
            val existingTracks = db.savedTrackDao().getAll()
            val existingPlaylists = db.playlistDao().getAll()
            if (existingTracks.isNotEmpty() || existingPlaylists.isNotEmpty()) return@withContext false

            val dir = getAutoBackupDir()
            val tracksFile = File(dir, "saved_tracks.json")
            val playlistsFile = File(dir, "playlists.json")
            if (!tracksFile.exists() && !playlistsFile.exists()) return@withContext false

            // Herstel tracks
            if (tracksFile.exists()) {
                val trackBackups = json.decodeFromString<List<TrackBackup>>(tracksFile.readText())
                val tracks = trackBackups.map {
                    SavedTrack(it.deezerTrackId, it.title, it.artist, it.previewUrl, it.localPath, it.playlistName)
                }
                db.savedTrackDao().insertAll(tracks)
            }

            // Herstel playlists
            if (playlistsFile.exists()) {
                val playlistBackups = json.decodeFromString<List<PlaylistBackup>>(playlistsFile.readText())
                for (pb in playlistBackups) {
                    db.playlistDao().insert(
                        Playlist(
                            id = pb.id, name = pb.name,
                            channel = Channel.valueOf(pb.channel),
                            mode = Mode.valueOf(pb.mode),
                            schedule = Schedule.valueOf(pb.schedule),
                            contactUri = pb.contactUri, contactName = pb.contactName,
                            isActive = pb.isActive, lastPlayedTrackId = pb.lastPlayedTrackId
                        )
                    )
                }
            }

            // Herstel playlist_tracks
            val ptFile = File(dir, "playlist_tracks.json")
            if (ptFile.exists()) {
                val ptBackups = json.decodeFromString<List<PlaylistTrackBackup>>(ptFile.readText())
                for (pt in ptBackups) {
                    db.playlistTrackDao().insert(PlaylistTrack(pt.playlistId, pt.trackId, pt.sortOrder))
                }
            }

            // Herstel instellingen
            val settingsFile = File(dir, "settings.json")
            if (settingsFile.exists()) {
                @Serializable
                data class SettingsBackup(
                    val downloadPath: String? = null,
                    val ringtonePath: String? = null,
                    val spotifyConverter: String = StorageManager.DEFAULT_SPOTIFY_CONVERTER,
                    val backupUri: String? = null
                )
                val settings = json.decodeFromString<SettingsBackup>(settingsFile.readText())
                if (settings.downloadPath != null) storage.setDownloadDir(settings.downloadPath)
                if (settings.ringtonePath != null) storage.setRingtoneDir(settings.ringtonePath)
                storage.setSpotifyConverter(settings.spotifyConverter)
                if (settings.backupUri != null) storage.setBackupUri(settings.backupUri)
            }

            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Check of er een lokale auto-backup beschikbaar is.
     */
    fun hasLocalAutoBackup(): Boolean {
        val dir = getAutoBackupDir()
        return File(dir, "backup_meta.json").exists()
    }

    // --- SAF Helpers ---

    private fun writeJsonFile(parentDir: DocumentFile, fileName: String, content: String) {
        parentDir.findFile(fileName)?.delete()
        val file = parentDir.createFile("application/json", fileName)
            ?: throw Exception("Kan $fileName niet aanmaken")
        context.contentResolver.openOutputStream(file.uri)?.use { os ->
            os.write(content.toByteArray(Charsets.UTF_8))
        } ?: throw Exception("Kan niet schrijven naar $fileName")
    }

    private fun readSafFile(file: DocumentFile): String {
        return context.contentResolver.openInputStream(file.uri)?.use { input ->
            input.bufferedReader().readText()
        } ?: throw Exception("Kan ${file.name} niet lezen")
    }

    private fun getOrCreateSubDir(parent: DocumentFile, name: String): DocumentFile {
        parent.findFile(name)?.let { existing ->
            if (existing.isDirectory) {
                existing.listFiles().forEach { it.delete() }
                return existing
            }
            existing.delete()
        }
        return parent.createDirectory(name)
            ?: throw Exception("Kan map '$name' niet aanmaken")
    }

    private fun copyFileToSaf(sourceFile: File, destDir: DocumentFile) {
        destDir.findFile(sourceFile.name)?.delete()
        val destFile = destDir.createFile("audio/mpeg", sourceFile.name)
            ?: throw Exception("Kan ${sourceFile.name} niet aanmaken in backup")
        context.contentResolver.openOutputStream(destFile.uri)?.use { os ->
            sourceFile.inputStream().use { input -> input.copyTo(os) }
        }
    }

    private fun copyFileFromSaf(safFile: DocumentFile, destFile: File) {
        destFile.parentFile?.mkdirs()
        context.contentResolver.openInputStream(safFile.uri)?.use { input ->
            destFile.outputStream().use { os -> input.copyTo(os) }
        }
    }
}
