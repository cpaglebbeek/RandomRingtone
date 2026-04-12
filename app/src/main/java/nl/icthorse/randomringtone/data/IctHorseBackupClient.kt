package nl.icthorse.randomringtone.data

import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/**
 * HTTP client voor backup/restore via icthorse.nl/randomringtone/backup_api.php
 * Ondersteunt maximaal 2 backup-slots per device.
 */
class IctHorseBackupClient(private val context: Context) {

    companion object {
        private const val BASE_URL = "https://icthorse.nl/randomringtone/backup_api.php"
        private const val API_KEY = "16cBm1-DBBFgYSnveI2v3F8zKGfVILI_"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private val deviceId: String
        get() = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

    private fun baseRequest(): Request.Builder =
        Request.Builder()
            .addHeader("X-Api-Key", API_KEY)
            .addHeader("X-Device-Id", deviceId)

    // ── Status: ophalen van beide slots ─────────────────────────────

    suspend fun getSlotStatus(): List<SlotInfo> = withContext(Dispatchers.IO) {
        try {
            val request = baseRequest()
                .url("$BASE_URL?action=status")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: "{}"
            response.close()
            if (!response.isSuccessful) {
                return@withContext emptyList()
            }

            val map = json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(body)
            val slotsArray = map["slots"]?.toString() ?: "[]"
            val rawSlots = json.decodeFromString<List<RawSlotStatus>>(slotsArray)

            rawSlots.map { raw ->
                val meta = if (raw.exists && raw.meta != null) {
                    json.decodeFromString<BackupMeta>(raw.meta.toString())
                } else null
                SlotInfo(slot = raw.slot, exists = raw.exists, meta = meta)
            }
        } catch (e: Exception) {
            RemoteLogger.e("IctHorseBackup", "Status ophalen mislukt", mapOf("error" to (e.message ?: "unknown")))
            emptyList()
        }
    }

    // ── Backup: init + upload files + complete ──────────────────────

    private val audioExtensions = setOf("mp3", "m4a")

    suspend fun backup(
        slot: Int,
        db: RingtoneDatabase,
        storage: StorageManager,
        backupManager: BackupManager,
        onProgress: (BackupProgress) -> Unit
    ): BackupResult = withContext(Dispatchers.IO) {
        try {
            // Phase 1: Init
            onProgress(BackupProgress("Server voorbereiden (slot $slot)...", 1, 7))
            RemoteLogger.i("IctHorseBackup", "Backup gestart", mapOf("slot" to slot.toString()))
            val initResponse = post("$BASE_URL?action=init&slot=$slot")
            if (!initResponse.isSuccessful) {
                val code = initResponse.code
                initResponse.close()
                RemoteLogger.e("IctHorseBackup", "Init mislukt", mapOf("httpCode" to code.toString()))
                return@withContext BackupResult(false, "Init mislukt: HTTP $code")
            }
            initResponse.close()

            // Phase 2: Export database to temp files
            onProgress(BackupProgress("Database exporteren...", 2, 7))
            val tempDir = File(context.cacheDir, "ict_backup_temp").apply {
                deleteRecursively()
                mkdirs()
            }

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

            val downloadDir = storage.getDownloadDir()
            val ringtoneDir = storage.getRingtoneDir()
            val downloadFiles = downloadDir.listFiles()?.filter { it.isFile && it.extension.lowercase() in audioExtensions } ?: emptyList()
            val ringtoneFiles = ringtoneDir.listFiles()?.filter { it.isFile && it.extension.lowercase() in audioExtensions } ?: emptyList()

            val jsonEncoder = Json { prettyPrint = true }
            File(tempDir, "saved_tracks.json").writeText(jsonEncoder.encodeToString(kotlinx.serialization.builtins.ListSerializer(TrackBackup.serializer()), trackBackups))
            File(tempDir, "playlists.json").writeText(jsonEncoder.encodeToString(kotlinx.serialization.builtins.ListSerializer(PlaylistBackup.serializer()), playlistBackups))
            File(tempDir, "playlist_tracks.json").writeText(jsonEncoder.encodeToString(kotlinx.serialization.builtins.ListSerializer(PlaylistTrackBackup.serializer()), ptBackups))

            val meta = BackupMeta(
                appVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?",
                backupDate = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()),
                trackCount = tracks.size,
                playlistCount = playlists.size,
                playlistTrackCount = playlistTracks.size,
                downloadFileCount = downloadFiles.size,
                ringtoneFileCount = ringtoneFiles.size
            )
            File(tempDir, "backup_meta.json").writeText(jsonEncoder.encodeToString(BackupMeta.serializer(), meta))

            RemoteLogger.i("IctHorseBackup", "Database geexporteerd", mapOf(
                "tracks" to tracks.size.toString(),
                "playlists" to playlists.size.toString(),
                "downloads" to downloadFiles.size.toString(),
                "ringtones" to ringtoneFiles.size.toString()
            ))

            // Phase 3: Upload JSON files
            onProgress(BackupProgress("Database uploaden...", 3, 7))
            val jsonFileNames = listOf("backup_meta.json", "saved_tracks.json", "playlists.json", "playlist_tracks.json")
            for (file in jsonFileNames) {
                uploadFile(slot, File(tempDir, file), file)
            }

            // Phase 4: Upload download files
            onProgress(BackupProgress("Downloads uploaden (${downloadFiles.size})...", 4, 7))
            var uploadedFiles = 0
            downloadFiles.forEach { file ->
                uploadFile(slot, file, "downloads/${file.name}")
                uploadedFiles++
            }

            // Phase 5: Upload ringtone files
            onProgress(BackupProgress("Ringtones uploaden (${ringtoneFiles.size})...", 5, 7))
            ringtoneFiles.forEach { file ->
                uploadFile(slot, file, "ringtones/${file.name}")
                uploadedFiles++
            }

            // Phase 6: Re-upload meta met finale counts
            onProgress(BackupProgress("Metadata bijwerken...", 6, 7))
            uploadFile(slot, File(tempDir, "backup_meta.json"), "backup_meta.json")

            // Phase 7: Complete
            onProgress(BackupProgress("Afronden...", 7, 7))
            val completeResponse = post("$BASE_URL?action=complete&slot=$slot")
            completeResponse.close()

            tempDir.deleteRecursively()

            RemoteLogger.i("IctHorseBackup", "Backup voltooid", mapOf(
                "slot" to slot.toString(),
                "tracks" to tracks.size.toString(),
                "files" to uploadedFiles.toString()
            ))

            BackupResult(
                success = true,
                message = "Backup naar slot $slot geslaagd: ${tracks.size} tracks, ${playlists.size} playlists, $uploadedFiles bestanden",
                trackCount = tracks.size,
                playlistCount = playlists.size,
                fileCount = uploadedFiles
            )
        } catch (e: Exception) {
            RemoteLogger.e("IctHorseBackup", "Backup mislukt", mapOf("error" to (e.message ?: "unknown")))
            BackupResult(false, "iCt Horse backup mislukt: ${e.message}")
        }
    }

    // ── Restore: list + download files ──────────────────────────────

    suspend fun restore(
        slot: Int,
        db: RingtoneDatabase,
        storage: StorageManager,
        onProgress: (BackupProgress) -> Unit
    ): BackupResult = withContext(Dispatchers.IO) {
        try {
            // Phase 1: Get file list
            onProgress(BackupProgress("Bestandslijst ophalen (slot $slot)...", 1, 5))
            val listResponse = get("$BASE_URL?action=list&slot=$slot")
            val listBody = listResponse.body?.string() ?: "{}"
            if (!listResponse.isSuccessful) {
                return@withContext BackupResult(false, "Lijst ophalen mislukt: HTTP ${listResponse.code}")
            }

            // Phase 2: Download JSON files to temp
            onProgress(BackupProgress("Database downloaden...", 2, 5))
            val tempDir = File(context.cacheDir, "ict_restore_temp").apply {
                deleteRecursively()
                mkdirs()
            }

            val jsonFiles = listOf("saved_tracks.json", "playlists.json", "playlist_tracks.json")
            for (file in jsonFiles) {
                downloadFile(slot, file, File(tempDir, file))
            }

            // Phase 3: Restore database
            onProgress(BackupProgress("Database herstellen...", 3, 5))
            val jsonDecoder = Json { ignoreUnknownKeys = true }

            db.clearAllTables()

            val tracksJson = File(tempDir, "saved_tracks.json").readText()
            val trackBackups = jsonDecoder.decodeFromString<List<TrackBackup>>(tracksJson)
            val ringtoneDir = storage.getRingtoneDir()
            val tracks = trackBackups.map { tb ->
                val newLocalPath = if (tb.localPath != null) {
                    File(ringtoneDir, File(tb.localPath).name).absolutePath
                } else null
                SavedTrack(tb.deezerTrackId, tb.title, tb.artist, tb.previewUrl, newLocalPath, tb.playlistName)
            }
            db.savedTrackDao().insertAll(tracks)

            val playlistsJson = File(tempDir, "playlists.json").readText()
            val playlistBackups = jsonDecoder.decodeFromString<List<PlaylistBackup>>(playlistsJson)
            for (pb in playlistBackups) {
                db.playlistDao().insert(
                    Playlist(pb.id, pb.name, Channel.valueOf(pb.channel), Mode.valueOf(pb.mode),
                        Schedule.valueOf(pb.schedule), pb.contactUri, pb.contactName, pb.isActive, pb.lastPlayedTrackId)
                )
            }

            val ptJson = File(tempDir, "playlist_tracks.json").readText()
            val ptBackups = jsonDecoder.decodeFromString<List<PlaylistTrackBackup>>(ptJson)
            for (pt in ptBackups) {
                db.playlistTrackDao().insert(PlaylistTrack(pt.playlistId, pt.trackId, pt.sortOrder))
            }

            // Phase 4: Download MP3 files
            onProgress(BackupProgress("Bestanden downloaden...", 4, 5))
            var restoredFiles = 0

            val listMap = jsonDecoder.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(listBody)
            val filesArray = listMap["files"]?.let {
                jsonDecoder.decodeFromString<List<FileEntry>>(it.toString())
            } ?: emptyList()

            val downloadDir = storage.getDownloadDir()
            for (entry in filesArray) {
                val isAudio = audioExtensions.any { entry.path.endsWith(".$it") }
                if (entry.path.startsWith("downloads/") && isAudio) {
                    downloadFile(slot, entry.path, File(downloadDir, File(entry.path).name))
                    restoredFiles++
                } else if (entry.path.startsWith("ringtones/") && isAudio) {
                    downloadFile(slot, entry.path, File(ringtoneDir, File(entry.path).name))
                    restoredFiles++
                }
            }

            onProgress(BackupProgress("Klaar!", 5, 5))
            tempDir.deleteRecursively()

            BackupResult(
                success = true,
                message = "Herstel van slot $slot geslaagd: ${trackBackups.size} tracks, ${playlistBackups.size} playlists, $restoredFiles bestanden",
                trackCount = trackBackups.size,
                playlistCount = playlistBackups.size,
                fileCount = restoredFiles
            )
        } catch (e: Exception) {
            BackupResult(false, "iCt Horse herstel mislukt: ${e.message}")
        }
    }

    // ── HTTP helpers ────────────────────────────────────────────────

    private fun post(url: String): Response {
        val request = baseRequest()
            .url(url)
            .post(ByteArray(0).toRequestBody(null))
            .build()
        return client.newCall(request).execute()
    }

    private fun get(url: String): Response {
        val request = baseRequest()
            .url(url)
            .get()
            .build()
        return client.newCall(request).execute()
    }

    private fun uploadFile(slot: Int, file: File, remotePath: String) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("path", remotePath)
            .addFormDataPart("file", file.name, file.asRequestBody("application/octet-stream".toMediaType()))
            .build()

        val request = baseRequest()
            .url("$BASE_URL?action=upload&slot=$slot")
            .post(body)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Upload mislukt voor $remotePath: HTTP ${response.code}")
        }
        response.close()
    }

    private fun downloadFile(slot: Int, remotePath: String, destFile: File) {
        val request = baseRequest()
            .url("$BASE_URL?action=download&slot=$slot&file=$remotePath")
            .get()
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw Exception("Download mislukt voor $remotePath: HTTP ${response.code}")
        }

        destFile.parentFile?.mkdirs()
        response.body?.byteStream()?.use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        response.close()
    }
}

data class SlotInfo(
    val slot: Int,
    val exists: Boolean,
    val meta: BackupMeta? = null
)

@kotlinx.serialization.Serializable
data class RawSlotStatus(
    val slot: Int,
    val exists: Boolean,
    val meta: kotlinx.serialization.json.JsonElement? = null
)

data class IctHorseStatus(
    val exists: Boolean,
    val meta: BackupMeta? = null,
    val error: String? = null
)

@kotlinx.serialization.Serializable
data class FileEntry(
    val path: String,
    val size: Long
)
