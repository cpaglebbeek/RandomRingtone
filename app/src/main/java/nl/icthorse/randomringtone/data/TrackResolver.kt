package nl.icthorse.randomringtone.data

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
    private val ringtoneManager: AppRingtoneManager
) {
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
     * Checkt localPath eerst. Als previewUrl beschikbaar is (Deezer),
     * downloadt on-the-fly. Spotify tracks hebben geen previewUrl
     * en zijn alleen beschikbaar als lokaal bestand.
     */
    suspend fun resolveTrackFile(track: SavedTrack): File? {
        val localPath = track.localPath
        if (localPath != null && File(localPath).exists()) {
            return File(localPath)
        }

        // Alleen on-the-fly download als er een preview URL is (Deezer tracks)
        if (track.previewUrl.isBlank()) return null

        return try {
            val deezerTrack = track.toDeezerTrack()
            ringtoneManager.downloadPreview(deezerTrack)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Na succesvolle track-wissel: update lastPlayedTrackId in de playlist.
     */
    suspend fun updateLastPlayed(playlist: Playlist, trackId: Long) {
        db.playlistDao().update(playlist.copy(lastPlayedTrackId = trackId))
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
