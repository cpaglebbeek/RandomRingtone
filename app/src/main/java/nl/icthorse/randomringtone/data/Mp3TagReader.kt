package nl.icthorse.randomringtone.data

import android.content.Context
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class Mp3TagInfo(
    val title: String? = null,
    val artist: String? = null,
    val albumArtPath: String? = null
)

object Mp3TagReader {

    /**
     * Lees ID3 tags uit een MP3 bestand.
     * Extraheert titel, artiest en embedded album art.
     */
    fun read(context: Context, file: File): Mp3TagInfo {
        if (!file.exists() || !file.canRead()) return Mp3TagInfo()

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)

            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() }
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() }

            val artBytes = retriever.embeddedPicture
            val artPath = if (artBytes != null) {
                saveAlbumArt(context, file.nameWithoutExtension, artBytes)
            } else null

            Mp3TagInfo(title = title, artist = artist, albumArtPath = artPath)
        } catch (_: Exception) {
            Mp3TagInfo()
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /**
     * Moet deze track (opnieuw) gelezen worden? v2.2.3: niet alleen bij ontbrekende ID3-titel — ook zonder
     * albumArtPath (null = nooit gecontroleerd; bv. na restore) of als het art-bestand uit de cache verdwenen is.
     * "" = gecontroleerd, bestand heeft geen cover (voorkomt elke keer opnieuw lezen).
     */
    fun needsEnrich(t: SavedTrack): Boolean {
        val art = t.albumArtPath
        return t.id3Title == null || art == null || (art.isNotEmpty() && !File(art).exists())
    }

    /**
     * Enrich tracks in de DB: marker injecteren + ID3 (titel, artiest, album art) lezen voor tracks waarvoor
     * [needsEnrich] geldt, plus altijd voor [forcePaths] (bestanden die net vervangen zijn). Idempotent.
     */
    suspend fun enrichAll(context: Context, db: RingtoneDatabase, forcePaths: Set<String> = emptySet()): Int = withContext(Dispatchers.IO) {
        var n = 0
        db.savedTrackDao().getAll()
            .filter { !it.localPath.isNullOrBlank() && (needsEnrich(it) || it.localPath in forcePaths) }
            .forEach { track ->
                val file = File(track.localPath!!)
                if (!file.exists()) return@forEach

                // Injecteer marker als die ontbreekt
                Mp3Marker.injectIfMissing(file, track.title, track.artist)

                val info = read(context, file)
                db.savedTrackDao().insert(track.copy(
                    id3Title = info.title ?: track.id3Title ?: "",
                    id3Artist = info.artist ?: track.id3Artist ?: "",
                    albumArtPath = info.albumArtPath ?: ""
                ))
                n++
            }
        n
    }

    private fun saveAlbumArt(context: Context, trackName: String, artBytes: ByteArray): String? {
        return try {
            val artDir = File(context.cacheDir, "album_art").apply { mkdirs() }
            val artFile = File(artDir, "${trackName.hashCode()}.jpg")
            // v2.2.3: altijd verversen — een vervangen bestand kan een nieuwe cover hebben
            FileOutputStream(artFile).use { it.write(artBytes) }
            artFile.absolutePath
        } catch (_: Exception) {
            null
        }
    }
}
