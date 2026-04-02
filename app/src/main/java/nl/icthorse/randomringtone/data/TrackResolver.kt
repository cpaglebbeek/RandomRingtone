package nl.icthorse.randomringtone.data

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * Gedeelde track → file resolver.
 * Centraliseert de logica voor het ophalen van tracks uit playlists
 * en het beschikbaar maken van het audiobestand (lokaal of on-the-fly download).
 *
 * Gebruikt door: NotificationService (SMS/WA), RingtoneWorker (schema), CallStateReceiver (EVERY_CALL).
 */
class TrackResolver(
    private val db: RingtoneDatabase,
    private val ringtoneManager: AppRingtoneManager,
    private val context: Context? = null
) {
    companion object {
        private const val TAG = "TrackResolver"
    }

    /**
     * Resolve de volgende track voor een playlist.
     * - FIXED: eerste track in de playlist
     * - RANDOM: willekeurige track, filtert lastPlayedTrackId uit (als >1 track)
     *
     * @return Pair(bestand, track) of null als geen tracks beschikbaar
     */
    suspend fun resolveForPlaylist(playlist: Playlist): Pair<File, SavedTrack>? {
        RemoteLogger.i("TrackResolver", "resolveForPlaylist", mapOf(
            "playlist" to playlist.name,
            "channel" to playlist.channel.name,
            "mode" to playlist.mode.name,
            "contact" to (playlist.contactName ?: "globaal"),
            "lastPlayedId" to (playlist.lastPlayedTrackId?.toString() ?: "null")
        ))

        val tracks = db.playlistTrackDao().getTracksForPlaylist(playlist.id)
        if (tracks.isEmpty()) {
            RemoteLogger.w("TrackResolver", "Playlist is leeg — geen tracks", mapOf("playlist" to playlist.name))
            return null
        }

        RemoteLogger.d("TrackResolver", "Tracks in playlist", mapOf("count" to tracks.size.toString(), "tracks" to tracks.joinToString { "${it.artist}-${it.title}" }))

        val track = when (playlist.mode) {
            Mode.FIXED -> {
                RemoteLogger.d("TrackResolver", "FIXED mode → eerste track")
                tracks.first()
            }
            Mode.RANDOM -> {
                val candidates = if (tracks.size > 1 && playlist.lastPlayedTrackId != null) {
                    tracks.filter { it.deezerTrackId != playlist.lastPlayedTrackId }
                } else {
                    tracks
                }
                val picked = candidates.random()
                RemoteLogger.d("TrackResolver", "RANDOM mode → track gekozen", mapOf(
                    "picked" to "${picked.artist} - ${picked.title}",
                    "candidates" to candidates.size.toString(),
                    "excluded" to (playlist.lastPlayedTrackId?.toString() ?: "none")
                ))
                picked
            }
        }

        val file = resolveTrackFile(track)
        if (file == null) {
            RemoteLogger.e("TrackResolver", "Track bestand NIET gevonden", mapOf("track" to "${track.artist} - ${track.title}", "localPath" to (track.localPath ?: "null")))
            return null
        }

        RemoteLogger.output("TrackResolver", "Track resolved", mapOf(
            "track" to "${track.artist} - ${track.title}",
            "file" to file.name,
            "size" to "${file.length()/1024}KB"
        ))
        return Pair(file, track)
    }

    /**
     * Zorg dat het audiobestand voor een SavedTrack beschikbaar is.
     * 1. Check localPath via File API
     * 2. Fallback: zoek via MediaStore (scoped storage fix)
     * 3. Fallback: on-the-fly download (Deezer tracks)
     */
    suspend fun resolveTrackFile(track: SavedTrack): File? {
        RemoteLogger.d("TrackResolver", "resolveTrackFile start", mapOf("track" to track.title, "localPath" to (track.localPath ?: "null")))

        // 1. Direct via File API
        val localPath = track.localPath
        if (localPath != null && File(localPath).exists()) {
            RemoteLogger.d("TrackResolver", "Stap 1: File.exists() OK", mapOf("path" to localPath))
            return File(localPath)
        }
        RemoteLogger.d("TrackResolver", "Stap 1: File.exists() MISS", mapOf("path" to (localPath ?: "null")))

        // 1b. Zoek op bestandsnaam in bekende app-directories (pad-migratie fix)
        if (localPath != null && context != null) {
            val fileName = File(localPath).name
            val searchDirs = listOfNotNull(
                ringtoneManager.storage.getRingtoneDir(),
                ringtoneManager.storage.getDownloadDir(),
                context.filesDir?.let { File(it, "downloads") },
                context.filesDir?.let { File(it, "resolved") }
            )
            for (dir in searchDirs) {
                val candidate = File(dir, fileName)
                if (candidate.exists() && candidate.length() > 0) {
                    RemoteLogger.i("TrackResolver", "Stap 1b: Bestand gevonden in andere directory", mapOf(
                        "fileName" to fileName, "found" to candidate.absolutePath, "original" to localPath
                    ))
                    db.savedTrackDao().insert(track.copy(localPath = candidate.absolutePath))
                    return candidate
                }
            }
            RemoteLogger.d("TrackResolver", "Stap 1b: Niet gevonden in app-directories", mapOf("fileName" to fileName))
        }

        // 2. MediaStore fallback — scoped storage kan File.exists() blokkeren
        if (localPath != null && context != null) {
            RemoteLogger.d("TrackResolver", "Stap 2: MediaStore fallback proberen")
            val msFile = resolveViaMediaStore(localPath, track.title)
            if (msFile != null) {
                RemoteLogger.i("TrackResolver", "Stap 2: MediaStore HIT — bestand gevonden + gekopieerd", mapOf("resolved" to msFile.absolutePath))
                db.savedTrackDao().insert(track.copy(localPath = msFile.absolutePath))
                return msFile
            }
            RemoteLogger.d("TrackResolver", "Stap 2: MediaStore MISS")
        }

        // 3. On-the-fly download (alleen Deezer tracks met preview URL)
        if (track.previewUrl.isBlank()) {
            RemoteLogger.w("TrackResolver", "Stap 3: Geen previewUrl — track niet beschikbaar")
            return null
        }

        RemoteLogger.i("TrackResolver", "Stap 3: On-the-fly Deezer download", mapOf("previewUrl" to track.previewUrl))
        return try {
            val deezerTrack = track.toDeezerTrack()
            val file = ringtoneManager.downloadPreview(deezerTrack)
            RemoteLogger.output("TrackResolver", "Stap 3: Download OK", mapOf("file" to file.name))
            file
        } catch (e: Exception) {
            RemoteLogger.e("TrackResolver", "Stap 3: Download FAILED", mapOf("error" to (e.message ?: "unknown")))
            null
        }
    }

    /**
     * Zoek een bestand via MediaStore op bestandsnaam.
     * Kopieert het bestand naar app-interne opslag als het gevonden wordt.
     */
    private fun resolveViaMediaStore(localPath: String, title: String): File? {
        val ctx = context ?: return null
        val fileName = File(localPath).name

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        try {
            ctx.contentResolver.query(
                collection,
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME),
                "${MediaStore.Audio.Media.DISPLAY_NAME} = ?",
                arrayOf(fileName),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
                    val contentUri = android.content.ContentUris.withAppendedId(collection, id)

                    // Kopieer naar app-interne opslag (altijd toegankelijk)
                    val destDir = File(ctx.filesDir, "resolved")
                    destDir.mkdirs()
                    val destFile = File(destDir, fileName)

                    ctx.contentResolver.openInputStream(contentUri)?.use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    if (destFile.exists() && destFile.length() > 0) {
                        Log.i(TAG, "MediaStore resolved: $fileName → ${destFile.absolutePath}")
                        return destFile
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore resolve failed for $fileName: ${e.message}")
        }
        return null
    }

    /**
     * Na succesvolle track-wissel: update lastPlayedTrackId in de playlist.
     */
    suspend fun updateLastPlayed(playlist: Playlist, trackId: Long) {
        RemoteLogger.d("TrackResolver", "updateLastPlayed", mapOf("playlist" to playlist.name, "trackId" to trackId.toString()))
        db.playlistDao().update(playlist.copy(lastPlayedTrackId = trackId))
    }

    data class ApplyResult(val success: Boolean, val error: String? = null)

    /**
     * Pas de ringtone direct toe voor een CALL playlist.
     * Per-contact: ContactsContract.CUSTOM_RINGTONE
     * Globaal: system default ringtone
     * Retourneert ApplyResult met foutmelding bij falen.
     */
    suspend fun applyCallPlaylist(playlist: Playlist): ApplyResult {
        RemoteLogger.trigger("TrackResolver", "applyCallPlaylist", mapOf(
            "playlist" to playlist.name, "contact" to (playlist.contactName ?: "globaal"), "mode" to playlist.mode.name
        ))
        if (playlist.channel != Channel.CALL) return ApplyResult(false, "Geen CALL playlist")

        val tracks = db.playlistTrackDao().getTracksForPlaylist(playlist.id)
        if (tracks.isEmpty()) return ApplyResult(false, "Playlist heeft geen tracks (voeg eerst nummers toe)")

        val result = resolveForPlaylist(playlist)
        if (result == null) return ApplyResult(false, "Geen track beschikbaar (bestand niet gevonden op schijf of in MediaStore)")

        val (file, track) = result
        if (!file.exists()) return ApplyResult(false, "Bestand bestaat niet: ${file.absolutePath}")

        val deezerTrack = track.toDeezerTrack()

        return if (playlist.contactUri != null) {
            if (context == null) return ApplyResult(false, "Geen context beschikbaar")
            val hasWriteContacts = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.WRITE_CONTACTS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!hasWriteContacts) return ApplyResult(false, "WRITE_CONTACTS permissie niet toegekend — ga naar app-instellingen")

            val contactName = playlist.contactName ?: "contact"
            RemoteLogger.i("TrackResolver", "installContactRingtone via vaste bestandsnaam", mapOf(
                "contact" to contactName, "track" to "${track.artist} - ${track.title}"
            ))
            val set = ringtoneManager.installContactRingtone(file, contactName, playlist.contactUri)
            if (set) ApplyResult(true)
            else ApplyResult(false, "Contact ringtone instellen mislukt voor $contactName")
        } else {
            RemoteLogger.i("TrackResolver", "installGlobalRingtone via vaste bestandsnaam", mapOf(
                "track" to "${track.artist} - ${track.title}"
            ))
            val set = ringtoneManager.installGlobalRingtone(file)
            if (set) ApplyResult(true)
            else ApplyResult(false, "Globale ringtone instellen mislukt (WRITE_SETTINGS permissie?)")
        }
    }
}

/**
 * Helper: SavedTrack → DeezerTrack conversie voor download.
 */
fun SavedTrack.toDeezerTrack() = DeezerTrack(
    id = deezerTrackId,
    title = title,
    artist = DeezerArtist(name = artist),
    preview = previewUrl
)
