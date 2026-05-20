package nl.icthorse.randomringtone.data

import java.io.File

/**
 * Canonical trackId-strategie voor saved_tracks.
 *
 * Drie bronnen die voorheen elk een eigen strategie hadden:
 *  - Deezer: echte track.id (Long, positief, real)
 *  - YouTube/Spotify/scan: hash van filename — soms signed (negatief), soms positief gemaakt
 *  - Editor trim: hash van absolutePath, positief — bewust uniek per directory (#70-fix)
 *
 * Nieuwe regel:
 *  - Deezer-id behouden (filename `download_<id>.mp3` waarbij <id> == deezerTrackId)
 *  - Editor trim: blijft absolutePath-hash (uitzondering, gedocumenteerd)
 *  - Alle overige: canonicalTrackIdForName(filename) — abs(name.hashCode())
 */
object TrackIdResolver {

    /**
     * Canonical hash van een bestandsnaam → altijd positief Long.
     * Onafhankelijk van pad — symlink-safe (zie bug #66).
     */
    fun canonicalTrackIdForName(name: String): Long {
        val h = name.hashCode().toLong()
        return if (h < 0) -h else h
    }

    /**
     * Canonical hash voor trimmed bestanden — wel pad-gebaseerd om uniciteit te
     * garanderen tussen download- en ringtone-dirs (#70-fix).
     */
    fun canonicalTrimmedTrackIdForPath(absolutePath: String): Long {
        val h = absolutePath.hashCode().toLong()
        return if (h < 0) -h else h
    }

    /**
     * Bepaal of een SavedTrack een echte Deezer-id heeft die behouden moet blijven
     * bij migratie. Criterium: filename `download_<digits>.mp3` waarbij <digits>
     * exact matcht met deezerTrackId.
     */
    fun shouldKeepDeezerId(track: SavedTrack): Boolean {
        val lp = track.localPath ?: return false
        val name = File(lp).nameWithoutExtension
        val match = Regex("^download_(\\d+)$").matchEntire(name) ?: return false
        val idFromName = match.groupValues[1].toLongOrNull() ?: return false
        return idFromName == track.deezerTrackId
    }

    /**
     * Bereken de nieuwe canonical id voor een bestaande SavedTrack.
     * - Deezer-style → behoud bestaand id
     * - markerType == "trimmed" → canonicalTrimmedTrackIdForPath
     * - rest → canonicalTrackIdForName
     */
    fun computeCanonicalId(track: SavedTrack): Long {
        if (shouldKeepDeezerId(track)) return track.deezerTrackId
        val lp = track.localPath ?: return track.deezerTrackId
        val fileName = File(lp).name
        return if (track.markerType == "trimmed") {
            canonicalTrimmedTrackIdForPath(lp)
        } else {
            canonicalTrackIdForName(fileName)
        }
    }
}
