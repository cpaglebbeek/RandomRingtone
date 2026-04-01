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
        val tracks = db.playlistTrackDao().getTracksForPlaylist(playlist.id)
        if (tracks.isEmpty()) return null

        val track = when (playlist.mode) {
            Mode.FIXED -> tracks.first()
            Mode.RANDOM -> {
                val candidates = if (tracks.size > 1 && playlist.lastPlayedTrackId != null) {
                    tracks.filter { it.deezerTrackId != playlist.lastPlayedTrackId }
                } else {
                    tracks
                }
                candidates.random()
            }
        }

        val file = resolveTrackFile(track) ?: return null
        return Pair(file, track)
    }

    /**
     * Zorg dat het audiobestand voor een SavedTrack beschikbaar is.
     * 1. Check localPath via File API
     * 2. Fallback: zoek via MediaStore (scoped storage fix)
     * 3. Fallback: on-the-fly download (Deezer tracks)
     */
    suspend fun resolveTrackFile(track: SavedTrack): File? {
        // 1. Direct via File API
        val localPath = track.localPath
        if (localPath != null && File(localPath).exists()) {
            return File(localPath)
        }

        // 2. MediaStore fallback — scoped storage kan File.exists() blokkeren
        if (localPath != null && context != null) {
            val msFile = resolveViaMediaStore(localPath, track.title)
            if (msFile != null) {
                // Update localPath in DB naar werkend pad
                db.savedTrackDao().insert(track.copy(localPath = msFile.absolutePath))
                return msFile
            }
        }

        // 3. On-the-fly download (alleen Deezer tracks met preview URL)
        if (track.previewUrl.isBlank()) return null

        return try {
            val deezerTrack = track.toDeezerTrack()
            ringtoneManager.downloadPreview(deezerTrack)
        } catch (_: Exception) {
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
        db.playlistDao().update(playlist.copy(lastPlayedTrackId = trackId))
    }

    /**
     * Pas de ringtone direct toe voor een CALL playlist.
     * Per-contact: ContactsContract.CUSTOM_RINGTONE
     * Globaal: system default ringtone
     */
    suspend fun applyCallPlaylist(playlist: Playlist): Boolean {
        if (playlist.channel != Channel.CALL) return false
        val result = resolveForPlaylist(playlist) ?: return false
        val (file, track) = result
        val deezerTrack = track.toDeezerTrack()

        return if (playlist.contactUri != null) {
            val uri = ringtoneManager.addToMediaStorePublic(deezerTrack, file)
            if (uri != null && context != null) {
                ContactsRepository(context).setContactRingtone(playlist.contactUri, uri)
            } else false
        } else {
            ringtoneManager.setAsRingtone(deezerTrack, file)
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
