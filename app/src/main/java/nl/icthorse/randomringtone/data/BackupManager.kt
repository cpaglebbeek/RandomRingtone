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
    val playlistName: String,
    val id3Title: String? = null,
    val id3Artist: String? = null,
    val albumArtPath: String? = null,
    val markerType: String? = null
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
    val lastPlayedTrackId: Long?,
    val playedTrackIds: String? = null
)

@Serializable
data class PlaylistTrackBackup(
    val playlistId: Long,
    val trackId: Long,
    val sortOrder: Int
)

@Serializable
data class PlaylistExport(
    val version: Int = 1,
    val exportDate: String,
    val playlist: PlaylistBackup,
    val tracks: List<TrackBackup>,
    val playlistTracks: List<PlaylistTrackBackup>
)

data class BackupProgress(
    val phase: String,
    val current: Int,
    val total: Int,
    val percentage: Float = 0f,     // 0.0 - 1.0 overall
    val bytesCopied: Long = 0,
    val totalBytes: Long = 0,
    val bytesPerSecond: Long = 0,   // huidige kopieersnelheid (EMA)
    val etaSeconds: Int = -1        // geschatte resterende tijd (-1 = onbekend)
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

            // Pre-scan: inventariseer bestanden + totale grootte
            val audioExts = setOf("mp3", "m4a")
            val downloadDir = storage.getDownloadDir()
            val ringtoneDir = storage.getRingtoneDir()
            val downloadFiles = downloadDir.listFiles()?.filter { it.isFile && it.extension.lowercase() in audioExts } ?: emptyList()
            val ringtoneFiles = ringtoneDir.listFiles()?.filter { it.isFile && it.extension.lowercase() in audioExts } ?: emptyList()
            val allFiles = downloadFiles + ringtoneFiles
            val totalBytes = allFiles.sumOf { it.length() }
            val totalFileCount = allFiles.size

            // Speed tracker: EMA (exponential moving average) van bytes/sec
            var bytesPerSecond = 0L
            var bytesCopied = 0L
            var copiedFiles = 0

            fun reportProgress(phase: String) {
                val pct = if (totalBytes > 0) bytesCopied.toFloat() / totalBytes else copiedFiles.toFloat() / totalFileCount.coerceAtLeast(1)
                val eta = if (bytesPerSecond > 0) ((totalBytes - bytesCopied) / bytesPerSecond).toInt() else -1
                onProgress(BackupProgress(phase, copiedFiles, totalFileCount, pct, bytesCopied, totalBytes, bytesPerSecond, eta))
            }

            // Phase 1: Export database
            reportProgress("Database exporteren...")
            val tracks = db.savedTrackDao().getAll()
            val playlists = db.playlistDao().getAll()
            val playlistTracks = db.playlistTrackDao().getAll()

            val trackBackups = tracks.map {
                TrackBackup(it.deezerTrackId, it.title, it.artist, it.previewUrl, it.localPath, it.playlistName, it.id3Title, it.id3Artist, it.albumArtPath, it.markerType)
            }
            val playlistBackups = playlists.map {
                PlaylistBackup(it.id, it.name, it.channel.name, it.mode.name, it.schedule.name,
                    it.contactUri, it.contactName, it.isActive, it.lastPlayedTrackId, it.playedTrackIds)
            }
            val ptBackups = playlistTracks.map {
                PlaylistTrackBackup(it.playlistId, it.trackId, it.sortOrder)
            }

            // Phase 2: Write JSON files
            reportProgress("JSON bestanden schrijven...")
            writeJsonFile(backupDir, "saved_tracks.json", json.encodeToString(trackBackups))
            writeJsonFile(backupDir, "playlists.json", json.encodeToString(playlistBackups))
            writeJsonFile(backupDir, "playlist_tracks.json", json.encodeToString(ptBackups))

            // Phase 3: Copy download files
            val dlSafDir = getOrCreateSubDir(backupDir, "downloads")
            for (file in downloadFiles) {
                val fileSize = file.length()
                val fileStart = System.currentTimeMillis()
                reportProgress("Downloads: ${file.name}")
                copyFileToSaf(file, dlSafDir)
                val fileTimeMs = System.currentTimeMillis() - fileStart
                if (fileTimeMs > 0) {
                    val fileBps = fileSize * 1000 / fileTimeMs
                    bytesPerSecond = if (bytesPerSecond == 0L) fileBps else (0.3 * fileBps + 0.7 * bytesPerSecond).toLong()
                }
                bytesCopied += fileSize
                copiedFiles++
            }

            // Phase 4: Copy ringtone files
            val rtSafDir = getOrCreateSubDir(backupDir, "ringtones")
            for (file in ringtoneFiles) {
                val fileSize = file.length()
                val fileStart = System.currentTimeMillis()
                reportProgress("Ringtones: ${file.name}")
                copyFileToSaf(file, rtSafDir)
                val fileTimeMs = System.currentTimeMillis() - fileStart
                if (fileTimeMs > 0) {
                    val fileBps = fileSize * 1000 / fileTimeMs
                    bytesPerSecond = if (bytesPerSecond == 0L) fileBps else (0.3 * fileBps + 0.7 * bytesPerSecond).toLong()
                }
                bytesCopied += fileSize
                copiedFiles++
            }

            // Phase 5: Write metadata
            reportProgress("Metadata opslaan...")
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

            val totalMB = "%.1f".format(totalBytes / (1024.0 * 1024.0))
            BackupResult(
                success = true,
                message = "Backup geslaagd: ${tracks.size} tracks, ${playlists.size} playlists, $copiedFiles bestanden (${totalMB}MB)",
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
                SavedTrack(tb.deezerTrackId, tb.title, tb.artist, tb.previewUrl, newLocalPath, tb.playlistName,
                    id3Title = tb.id3Title, id3Artist = tb.id3Artist, albumArtPath = tb.albumArtPath, markerType = tb.markerType)
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
                        lastPlayedTrackId = pb.lastPlayedTrackId,
                        playedTrackIds = pb.playedTrackIds
                    )
                )
            }

            // Insert playlist_tracks
            for (pt in ptBackups) {
                db.playlistTrackDao().insert(PlaylistTrack(pt.playlistId, pt.trackId, pt.sortOrder))
            }

            // Phase 4: Copy files back — met speed tracking
            val restoreAudioExts = setOf("mp3", "m4a")
            val dlSafDir = backupDir.findFile("downloads")
            val rtSafDir = backupDir.findFile("ringtones")
            val allSafFiles = mutableListOf<Pair<DocumentFile, File>>() // (bron, doel)
            val downloadDir = storage.getDownloadDir()
            if (dlSafDir != null && dlSafDir.isDirectory) {
                for (sf in dlSafDir.listFiles()) {
                    val ext = sf.name?.substringAfterLast(".", "")?.lowercase()
                    if (sf.isFile && ext in restoreAudioExts) allSafFiles.add(sf to File(downloadDir, sf.name!!))
                }
            }
            if (rtSafDir != null && rtSafDir.isDirectory) {
                for (sf in rtSafDir.listFiles()) {
                    val ext = sf.name?.substringAfterLast(".", "")?.lowercase()
                    if (sf.isFile && ext in restoreAudioExts) allSafFiles.add(sf to File(ringtoneDir, sf.name!!))
                }
            }

            val totalRestoreFiles = allSafFiles.size
            val totalRestoreBytes = allSafFiles.sumOf { it.first.length() }
            var restBps = 0L
            var restBytesCopied = 0L
            var restoredFiles = 0

            for ((safFile, destFile) in allSafFiles) {
                val fileSize = safFile.length()
                val fileStart = System.currentTimeMillis()
                val pct = if (totalRestoreBytes > 0) restBytesCopied.toFloat() / totalRestoreBytes else restoredFiles.toFloat() / totalRestoreFiles.coerceAtLeast(1)
                val eta = if (restBps > 0) ((totalRestoreBytes - restBytesCopied) / restBps).toInt() else -1
                onProgress(BackupProgress("Herstellen: ${safFile.name}", restoredFiles, totalRestoreFiles, pct, restBytesCopied, totalRestoreBytes, restBps, eta))
                copyFileFromSaf(safFile, destFile)
                val fileTimeMs = System.currentTimeMillis() - fileStart
                if (fileTimeMs > 0) {
                    val fileBps = fileSize * 1000 / fileTimeMs
                    restBps = if (restBps == 0L) fileBps else (0.3 * fileBps + 0.7 * restBps).toLong()
                }
                restBytesCopied += fileSize
                restoredFiles++
            }

            onProgress(BackupProgress("Klaar!", totalRestoreFiles, totalRestoreFiles, 1f, restBytesCopied, totalRestoreBytes, restBps, 0))

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

    // --- Individuele playlist export/import ---

    /**
     * Exporteer één playlist naar JSON string.
     */
    suspend fun exportPlaylist(playlistId: Long, db: RingtoneDatabase): String? = withContext(Dispatchers.IO) {
        try {
            val playlist = db.playlistDao().getById(playlistId) ?: return@withContext null
            val playlistTracks = db.playlistTrackDao().getAll().filter { it.playlistId == playlistId }
            val trackIds = playlistTracks.map { it.trackId }.toSet()
            val tracks = db.savedTrackDao().getAll().filter { it.deezerTrackId in trackIds }

            val export = PlaylistExport(
                exportDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
                playlist = PlaylistBackup(
                    playlist.id, playlist.name, playlist.channel.name, playlist.mode.name,
                    playlist.schedule.name, playlist.contactUri, playlist.contactName,
                    playlist.isActive, playlist.lastPlayedTrackId, playlist.playedTrackIds
                ),
                tracks = tracks.map { TrackBackup(it.deezerTrackId, it.title, it.artist, it.previewUrl, it.localPath, it.playlistName) },
                playlistTracks = playlistTracks.map { PlaylistTrackBackup(it.playlistId, it.trackId, it.sortOrder) }
            )

            json.encodeToString(export)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Schrijf playlist export JSON naar een SAF URI.
     */
    suspend fun writePlaylistExport(uri: Uri, jsonContent: String): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openOutputStream(uri)?.use { os ->
                os.write(jsonContent.toByteArray(Charsets.UTF_8))
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Importeer een playlist vanuit een SAF URI.
     * Tracks worden toegevoegd (REPLACE bij conflict), playlist krijgt unieke naam.
     */
    suspend fun importPlaylist(uri: Uri, db: RingtoneDatabase): BackupResult = withContext(Dispatchers.IO) {
        try {
            val jsonContent = context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader().readText()
            } ?: return@withContext BackupResult(false, "Kan bestand niet lezen")

            val export = json.decodeFromString<PlaylistExport>(jsonContent)

            // Insert tracks (REPLACE bij conflict)
            val tracks = export.tracks.map {
                SavedTrack(it.deezerTrackId, it.title, it.artist, it.previewUrl, it.localPath, it.playlistName,
                    id3Title = it.id3Title, id3Artist = it.id3Artist, albumArtPath = it.albumArtPath, markerType = it.markerType)
            }
            db.savedTrackDao().insertAll(tracks)

            // Unieke playlist naam genereren
            val existingNames = db.playlistDao().getAll().map { it.name }.toSet()
            var newName = export.playlist.name
            var counter = 1
            while (newName in existingNames) {
                newName = "${export.playlist.name} ($counter)"
                counter++
            }

            // Insert playlist met nieuw ID
            val newPlaylistId = db.playlistDao().insert(
                Playlist(
                    id = 0, // auto-generate
                    name = newName,
                    channel = Channel.valueOf(export.playlist.channel),
                    mode = Mode.valueOf(export.playlist.mode),
                    schedule = Schedule.valueOf(export.playlist.schedule),
                    contactUri = export.playlist.contactUri,
                    contactName = export.playlist.contactName,
                    isActive = false, // altijd inactief bij import
                    lastPlayedTrackId = null
                )
            )

            // Insert playlist_tracks met nieuw playlist ID
            for (pt in export.playlistTracks) {
                db.playlistTrackDao().insert(PlaylistTrack(newPlaylistId, pt.trackId, pt.sortOrder))
            }

            BackupResult(
                success = true,
                message = "Playlist '$newName' geimporteerd (${tracks.size} tracks)",
                trackCount = tracks.size,
                playlistCount = 1
            )
        } catch (e: Exception) {
            BackupResult(false, "Import mislukt: ${e.message}")
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
                TrackBackup(it.deezerTrackId, it.title, it.artist, it.previewUrl, it.localPath, it.playlistName, it.id3Title, it.id3Artist, it.albumArtPath, it.markerType)
            }
            val playlistBackups = playlists.map {
                PlaylistBackup(it.id, it.name, it.channel.name, it.mode.name, it.schedule.name,
                    it.contactUri, it.contactName, it.isActive, it.lastPlayedTrackId, it.playedTrackIds)
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
                    SavedTrack(it.deezerTrackId, it.title, it.artist, it.previewUrl, it.localPath, it.playlistName,
                        id3Title = it.id3Title, id3Artist = it.id3Artist, albumArtPath = it.albumArtPath, markerType = it.markerType)
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
                            isActive = pb.isActive, lastPlayedTrackId = pb.lastPlayedTrackId,
                            playedTrackIds = pb.playedTrackIds
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
