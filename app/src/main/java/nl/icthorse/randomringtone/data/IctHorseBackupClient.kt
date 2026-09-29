package nl.icthorse.randomringtone.data

import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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

    private val licenseManager by lazy { LicenseManager(context) }

    // v2.0.0: toestel-token (backup_api v3). De gedeelde sleutel werkt server-side alleen nog voor toestellen zonder token.
    private fun baseRequest(): Request.Builder =
        Request.Builder()
            .addHeader("X-Api-Key", API_KEY)
            .addHeader("X-Device-Id", deviceId)
            .apply { licenseManager.deviceToken?.let { addHeader("X-Device-Token", it) } }

    private fun enc(v: String): String = java.net.URLEncoder.encode(v, "UTF-8")

    /** Totale grootte van een slot (eigen of van [sourceDeviceId]) volgens de serverlijst; null = onbekend. */
    suspend fun slotSizeBytes(slot: Int, sourceDeviceId: String? = null): Long? = withContext(Dispatchers.IO) {
        val src = sourceDeviceId?.takeIf { it.isNotBlank() && it != deviceId }?.let { "&source=${enc(it)}" } ?: ""
        runCatching {
            get("$BASE_URL?action=list&slot=$slot$src").use { r ->
                if (!r.isSuccessful) return@use null
                val files = json.parseToJsonElement(r.body?.string() ?: "{}").jsonObject["files"]?.jsonArray ?: return@use null
                files.sumOf { it.jsonObject["size"]?.jsonPrimitive?.longOrNull ?: 0L }
            }
        }.getOrNull()
    }

    // ── Aanbod: backups van andere toestellen van hetzelfde account ─────
    suspend fun getOffers(): List<BackupOffer> = withContext(Dispatchers.IO) {
        if (licenseManager.deviceToken == null) return@withContext emptyList()
        try {
            val response = get("$BASE_URL?action=offers")
            val body = response.body?.string() ?: "{}"
            response.close()
            if (!response.isSuccessful) return@withContext emptyList()
            val root = json.parseToJsonElement(body).jsonObject
            val offers = root["offers"]?.jsonArray ?: return@withContext emptyList()
            offers.map { o ->
                val obj = o.jsonObject
                BackupOffer(
                    deviceId = obj["deviceId"]?.jsonPrimitive?.content ?: "",
                    name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
                    model = obj["model"]?.jsonPrimitive?.contentOrNull ?: "",
                    slots = (obj["slots"]?.jsonArray ?: JsonArray(emptyList())).map { sl ->
                        val so = sl.jsonObject
                        SlotInfo(slot = so["slot"]?.jsonPrimitive?.intOrNull ?: 0, exists = true,
                            meta = so["meta"]?.let { json.decodeFromJsonElement(BackupMeta.serializer(), it) })
                    }
                )
            }.filter { it.deviceId.isNotBlank() && it.slots.isNotEmpty() }
        } catch (e: Exception) {
            RemoteLogger.e("IctHorseBackup", "Aanbod ophalen mislukt", mapOf("error" to (e.message ?: "unknown")))
            emptyList()
        }
    }

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

            // v2.2.0: alle velden mee (markerType/subdir bepalen de restore-map; playedTrackIds de QUASI-cyclus)
            val trackBackups = tracks.map {
                TrackBackup(it.deezerTrackId, it.title, it.artist, it.previewUrl, it.localPath, it.playlistName,
                    it.id3Title, it.id3Artist, it.albumArtPath, it.markerType,
                    BackupManager.inferSubdir(null, it.markerType, it.localPath))
            }
            val playlistBackups = playlists.map {
                PlaylistBackup(it.id, it.name, it.channel.name, it.mode.name, it.schedule.name,
                    it.contactUri, it.contactName, it.isActive, it.lastPlayedTrackId, it.playedTrackIds)
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
            File(tempDir, "settings.json").writeText(jsonEncoder.encodeToString(SettingsBackupData.serializer(), SettingsBackupData(
                downloadPath = storage.getDownloadDir().absolutePath, ringtonePath = storage.getRingtoneDir().absolutePath,
                spotifyConverter = storage.getSpotifyConverter(), backupUri = storage.getBackupUri())))

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
            val jsonFileNames = listOf("backup_meta.json", "saved_tracks.json", "playlists.json", "playlist_tracks.json")
            val allUploads = downloadFiles.map { "downloads/${it.name}" to it } +
                    ringtoneFiles.map { "ringtones/${it.name}" to it }
            // Eén meter over alles wat over de lijn gaat (JSON + audio + meta opnieuw) ⇒ ETA per byte, live
            val meter = TransferMeter(jsonFileNames.sumOf { File(tempDir, it).length() } + allUploads.sumOf { it.second.length() } +
                File(tempDir, "backup_meta.json").length())
            var phaseText = "Database uploaden..."
            var phaseNo = 3
            val onBytes: (Long) -> Unit = { n -> if (meter.add(n)) onProgress(meter.progress(phaseText, phaseNo, 7)) }
            onProgress(meter.progress(phaseText, phaseNo, 7))
            for (file in jsonFileNames) {
                uploadFile(slot, File(tempDir, file), file, onBytes)
            }
            // settings.json is optioneel: een oudere backup_api die hem weigert mag de backup niet laten mislukken
            try {
                uploadFile(slot, File(tempDir, "settings.json"), "settings.json")
            } catch (e: Exception) {
                RemoteLogger.w("IctHorseBackup", "settings.json niet geüpload", mapOf("error" to (e.message ?: "?")))
            }

            // Phase 4+5: audio uploaden — voortgang per 64 KB via de tellende RequestBody
            var uploadedFiles = 0
            allUploads.forEachIndexed { index, (remotePath, file) ->
                val isDownload = remotePath.startsWith("downloads/")
                phaseNo = if (isDownload) 4 else 5
                phaseText = "${if (isDownload) "Downloads" else "Ringtones"} uploaden (${index + 1}/${allUploads.size})..."
                onProgress(meter.progress(phaseText, phaseNo, 7))
                uploadFile(slot, file, remotePath, onBytes)
                uploadedFiles++
            }

            // Phase 6: Re-upload meta met finale counts
            phaseText = "Metadata bijwerken..."; phaseNo = 6
            onProgress(meter.progress(phaseText, phaseNo, 7))
            uploadFile(slot, File(tempDir, "backup_meta.json"), "backup_meta.json", onBytes)

            // Phase 7: Complete
            onProgress(meter.progress("Afronden...", 7, 7))
            val completeResponse = post("$BASE_URL?action=complete&slot=$slot")
            val completeBody = completeResponse.body?.string() ?: "{}"
            completeResponse.close()

            tempDir.deleteRecursively()
            // backup_api v3 telt de audiobestanden na; onvolledig = mislukt (was stil "geslaagd", zie backup 22-05)
            val completeJson = try { json.parseToJsonElement(completeBody).jsonObject } catch (_: Exception) { null }
            if (completeJson?.get("complete")?.jsonPrimitive?.booleanOrNull == false) {
                val missing = completeJson["missing"]?.jsonPrimitive?.intOrNull ?: 0
                RemoteLogger.e("IctHorseBackup", "Backup onvolledig", mapOf("slot" to slot.toString(), "missing" to missing.toString()))
                return@withContext BackupResult(false, "Backup slot $slot ONVOLLEDIG: $missing audiobestanden ontbreken op de server — probeer opnieuw")
            }

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

    private fun listSlot(slot: Int, src: String): List<FileEntry> {
        val listResponse = get("$BASE_URL?action=list&slot=$slot$src")
        val listBody = listResponse.body?.string() ?: "{}"
        if (!listResponse.isSuccessful) throw Exception("Lijst ophalen mislukt: HTTP ${listResponse.code}")
        val dec = Json { ignoreUnknownKeys = true }
        return dec.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(listBody)["files"]
            ?.let { dec.decodeFromString<List<FileEntry>>(it.toString()) } ?: emptyList()
    }

    private fun srcParam(sourceDeviceId: String?) =
        sourceDeviceId?.takeIf { it.isNotBlank() && it != deviceId }?.let { "&source=${enc(it)}" } ?: ""

    /**
     * v2.2.1: lees vóór de restore uit de BACKUP welke rechten (contacten, telefoon) en doelmappen nodig zijn,
     * zodat SetupCheck die vooraf kan vragen/testen. null = niet te bepalen (restore gaat dan zoals voorheen).
     */
    suspend fun restorePlan(slot: Int, sourceDeviceId: String? = null): RestoreTarget? = withContext(Dispatchers.IO) {
        val src = srcParam(sourceDeviceId)
        try {
            val listed = listSlot(slot, src)
            val dir = File(context.cacheDir, "ict_restore_plan").apply { deleteRecursively(); mkdirs() }
            val dec = Json { ignoreUnknownKeys = true }
            downloadFile(slot, "playlists.json", File(dir, "playlists.json"), src)
            val playlists = dec.decodeFromString<List<PlaylistBackup>>(File(dir, "playlists.json").readText())
            val settings = if (listed.any { it.path == "settings.json" }) runCatching {
                downloadFile(slot, "settings.json", File(dir, "settings.json"), src)
                dec.decodeFromString(SettingsBackupData.serializer(), File(dir, "settings.json").readText())
            }.getOrNull() else null
            dir.deleteRecursively()
            RestoreSupport.planFrom(playlists, settings).also {
                RemoteLogger.i("Restore", "Plan", mapOf("contacts" to it.needsContacts.toString(), "phone" to it.needsPhone.toString(),
                    "download" to (it.downloadPath ?: "-"), "ringtone" to (it.ringtonePath ?: "-")))
            }
        } catch (e: Exception) {
            RemoteLogger.w("Restore", "Plan niet te bepalen", mapOf("error" to (e.message ?: "?")))
            null
        }
    }

    // ── Restore: list + download files ──────────────────────────────

    suspend fun restore(
        slot: Int,
        db: RingtoneDatabase,
        storage: StorageManager,
        onProgress: (BackupProgress) -> Unit,
        sourceDeviceId: String? = null
    ): BackupResult = withContext(Dispatchers.IO) {
        // Backup van een ander toestel van hetzelfde account (alleen met token; server controleert het account)
        val src = srcParam(sourceDeviceId)
        val result = try {
            // Phase 1: Get file list
            onProgress(BackupProgress("Bestandslijst ophalen (slot $slot)...", 1, 5))
            val listed = try { listSlot(slot, src) } catch (e: Exception) {
                return@withContext BackupResult(false, e.message ?: "Lijst ophalen mislukt").also { RestoreSupport.logResult("cloud", false, it.message) }
            }
            val jsonFiles = listOf("saved_tracks.json", "playlists.json", "playlist_tracks.json")
            val audioEntries = listed.filter { e -> audioExtensions.any { e.path.endsWith(".$it") } &&
                (e.path.startsWith("downloads/") || e.path.startsWith("ringtones/")) }
            // Totaal uit de serverlijst (grootte per bestand) ⇒ echte balk + live ETA
            val meter = TransferMeter(listed.filter { it.path in jsonFiles }.sumOf { it.size } + audioEntries.sumOf { it.size })
            var phaseText = "Database downloaden..."
            var phaseNo = 2
            val onBytes: (Long) -> Unit = { n -> if (meter.add(n)) onProgress(meter.progress(phaseText, phaseNo, 5)) }

            // Phase 2: Download JSON files to temp
            onProgress(meter.progress(phaseText, phaseNo, 5))
            val tempDir = File(context.cacheDir, "ict_restore_temp").apply {
                deleteRecursively()
                mkdirs()
            }
            for (file in jsonFiles) {
                downloadFile(slot, file, File(tempDir, file), src, onBytes)
            }
            val jsonDecoder = Json { ignoreUnknownKeys = true }
            val trackBackups = jsonDecoder.decodeFromString<List<TrackBackup>>(File(tempDir, "saved_tracks.json").readText())
            val rawPlaylists = jsonDecoder.decodeFromString<List<PlaylistBackup>>(File(tempDir, "playlists.json").readText())
            val ptBackups = jsonDecoder.decodeFromString<List<PlaylistTrackBackup>>(File(tempDir, "playlist_tracks.json").readText())

            // v2.2.0: instellingen eerst — de mappen daaruit bepalen waar tracks en bestanden landen
            if (listed.any { it.path == "settings.json" }) {
                try {
                    downloadFile(slot, "settings.json", File(tempDir, "settings.json"), src)
                    RestoreSupport.applySettings(
                        jsonDecoder.decodeFromString(SettingsBackupData.serializer(), File(tempDir, "settings.json").readText()),
                        storage
                    )
                } catch (e: Exception) {
                    RemoteLogger.w("IctHorseBackup", "settings.json niet toegepast", mapOf("error" to (e.message ?: "?")))
                }
            }
            val downloadDir = storage.getDownloadDir()
            val ringtoneDir = storage.getRingtoneDir()

            // Phase 3 (v2.2.1: eerst de bestanden, dan pas de database — een fout laat de bibliotheek heel).
            // Per bestand: zelfde grootte ⇒ overslaan; anders weghalen + schrijven; lukt dat niet ⇒ noteren, doorgaan.
            var restoredFiles = 0
            var skippedSame = 0
            val fileFailures = mutableListOf<String>()
            val pending = mutableListOf<PendingOverwrite>()
            ForeignFileWriter.pendingDir(context).deleteRecursively()
            var firstError: String? = null
            audioEntries.forEachIndexed { index, entry ->
                phaseNo = 3
                phaseText = "Bestanden downloaden (${index + 1}/${audioEntries.size})..."
                onProgress(meter.progress(phaseText, phaseNo, 5))
                val dir = if (entry.path.startsWith("downloads/")) downloadDir else ringtoneDir
                val dest = File(dir, File(entry.path).name)
                try {
                    when (RestoreSupport.prepareTarget(dest, entry.size)) {
                        TargetAction.SKIP_SAME -> { skippedSame++; restoredFiles++; meter.add(entry.size) }
                        TargetAction.WRITE -> { downloadFile(slot, entry.path, dest, src, onBytes); restoredFiles++ }
                    }
                } catch (e: Exception) {
                    // v2.2.3: bestand van een eerdere installatie ⇒ nieuwe inhoud klaarzetten; de UI vraagt daarna
                    // via MediaStore één keer toestemming om ze te vervangen (ForeignFileWriter)
                    val temp = File(ForeignFileWriter.pendingDir(context), "${index}_${dest.name}")
                    val parked = dest.exists() && runCatching { downloadFile(slot, entry.path, temp, src, onBytes) }.isSuccess
                    if (parked) {
                        pending.add(PendingOverwrite(dest.absolutePath, temp.absolutePath))
                        restoredFiles++
                    } else {
                        fileFailures.add(dest.name)
                        if (firstError == null) firstError = e.message
                    }
                    RemoteLogger.w("Restore", if (parked) "Bestand wacht op toestemming" else "Bestand niet teruggezet",
                        mapOf("file" to dest.absolutePath, "error" to (e.message ?: "?")))
                }
            }
            if (audioEntries.isNotEmpty() && restoredFiles == 0) {
                tempDir.deleteRecursively()
                return@withContext BackupResult(false,
                    "Herstel afgebroken — geen enkel bestand kon worden weggeschreven. Je bibliotheek is niet gewijzigd.\n${firstError ?: ""}")
                    .also { RestoreSupport.logResult("cloud", false, it.message, fileFailures) }
            }

            // Phase 4: Restore database
            phaseText = "Database herstellen..."; phaseNo = 4
            onProgress(meter.progress(phaseText, phaseNo, 5))
            val resolved = RestoreSupport.resolveContacts(context, rawPlaylists)
            val playlistBackups = resolved.playlists

            db.clearAllTables()

            // Was: alles naar ringtoneDir ⇒ downloads wezen na restore naar een niet-bestaand pad
            val tracks = trackBackups.map { tb ->
                val newLocalPath = tb.localPath?.let { lp ->
                    val subdir = BackupManager.inferSubdir(tb.subdir, tb.markerType, lp)
                    File(if (subdir == "ringtones") ringtoneDir else downloadDir, File(lp).name).absolutePath
                }
                SavedTrack(tb.deezerTrackId, tb.title, tb.artist, tb.previewUrl, newLocalPath, tb.playlistName,
                    id3Title = tb.id3Title, id3Artist = tb.id3Artist, albumArtPath = null, markerType = tb.markerType)
            }
            db.savedTrackDao().insertAll(tracks)

            for (pb in playlistBackups) {
                db.playlistDao().insert(
                    Playlist(pb.id, pb.name, Channel.valueOf(pb.channel), Mode.valueOf(pb.mode),
                        Schedule.valueOf(pb.schedule), pb.contactUri, pb.contactName, pb.isActive, pb.lastPlayedTrackId,
                        pb.playedTrackIds)
                )
            }

            val trackIds = tracks.map { it.deezerTrackId }.toSet()
            val playlistIds = playlistBackups.map { it.id }.toSet()
            for (pt in ptBackups) {
                if (pt.trackId !in trackIds || pt.playlistId !in playlistIds) continue
                db.playlistTrackDao().insert(PlaylistTrack(pt.playlistId, pt.trackId, pt.sortOrder))
            }

            // Bestanden staan er nu ⇒ ringtones van actieve belplaylists meteen zetten
            val applyFailures = RestoreSupport.applyActiveCallPlaylists(context, db)

            // v2.2.3: album art + ID3 meteen lezen (teruggezette tracks hebben nog geen art-cache)
            phaseText = "Album art lezen..."
            onProgress(meter.progress(phaseText, 5, 5))
            Mp3TagReader.enrichAll(context, db)

            onProgress(meter.progress("Klaar!", 5, 5).copy(percentage = 1f, etaSeconds = 0))
            tempDir.deleteRecursively()

            BackupResult(
                success = true,
                message = "Herstel van slot $slot geslaagd: ${trackBackups.size} tracks, ${playlistBackups.size} playlists, $restoredFiles bestanden" +
                    (if (skippedSame > 0) " ($skippedSame stonden er al)" else "") +
                    RestoreSupport.summary(resolved.unresolved, applyFailures, fileFailures) +
                    (if (pending.isNotEmpty()) "\n${pending.size} bestand(en) van een eerdere installatie wachten op je toestemming" else ""),
                trackCount = trackBackups.size,
                playlistCount = playlistBackups.size,
                fileCount = restoredFiles,
                pendingOverwrites = pending
            ).also { RestoreSupport.logResult("cloud", true, it.message, fileFailures) }
        } catch (e: Exception) {
            BackupResult(false, "iCt Horse herstel mislukt: ${e.message}").also { RestoreSupport.logResult("cloud", false, it.message) }
        }
        result
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

    private fun uploadFile(slot: Int, file: File, remotePath: String, onBytes: (Long) -> Unit = {}) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("path", remotePath)
            .addFormDataPart("file", file.name, CountingFileBody(file, onBytes))
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

    private fun downloadFile(slot: Int, remotePath: String, destFile: File, src: String = "", onBytes: (Long) -> Unit = {}) {
        val request = baseRequest()
            .url("$BASE_URL?action=download&slot=$slot&file=${enc(remotePath)}$src")
            .get()
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw Exception("Download mislukt voor $remotePath: HTTP ${response.code}")
        }

        destFile.parentFile?.mkdirs()
        response.use { r ->
            r.body?.byteStream()?.use { input ->
                RestoreSupport.writeTarget(destFile) { output -> input.copyCounting(output, onBytes) }
            }
        }
    }
}

/** Multipart-bestandsdeel dat per 64 KB meldt hoeveel er de lijn op is gegaan (live upload-voortgang). */
private class CountingFileBody(private val file: File, private val onBytes: (Long) -> Unit) : RequestBody() {
    override fun contentType() = "application/octet-stream".toMediaType()
    override fun contentLength() = file.length()
    override fun writeTo(sink: okio.BufferedSink) {
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                sink.write(buf, 0, n)
                onBytes(n.toLong())
            }
        }
    }
}

data class BackupOffer(
    val deviceId: String,
    val name: String,
    val model: String,
    val slots: List<SlotInfo>
)

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
