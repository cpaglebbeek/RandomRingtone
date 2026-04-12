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
     * Enrich alle tracks in de DB:
     * 1. Injecteer RandomRingtone marker in bestanden die deze nog niet hebben
     * 2. Lees ID3 tags (titel, artiest, album art) voor tracks die nog niet enriched zijn
     * Idempotent: marker-injectie en ID3 enrichment worden elk eenmalig per track uitgevoerd.
     */
    suspend fun enrichAll(context: Context, db: RingtoneDatabase) = withContext(Dispatchers.IO) {
        db.savedTrackDao().getAll()
            .filter { !it.localPath.isNullOrBlank() }
            .forEach { track ->
                val file = File(track.localPath!!)
                if (!file.exists()) return@forEach

                // Injecteer marker als die ontbreekt
                Mp3Marker.injectIfMissing(file, track.title, track.artist)

                // Enrich ID3 data als die nog niet gelezen is (eenmalig per track)
                if (track.id3Title == null) {
                    val info = read(context, file)
                    db.savedTrackDao().insert(track.copy(
                        id3Title = info.title ?: "",
                        id3Artist = info.artist ?: "",
                        albumArtPath = info.albumArtPath ?: track.albumArtPath // Bewaar bestaande art
                    ))
                }
            }
    }

    private fun saveAlbumArt(context: Context, trackName: String, artBytes: ByteArray): String? {
        return try {
            val artDir = File(context.cacheDir, "album_art").apply { mkdirs() }
            val artFile = File(artDir, "${trackName.hashCode()}.jpg")
            if (!artFile.exists()) {
                FileOutputStream(artFile).use { it.write(artBytes) }
            }
            artFile.absolutePath
        } catch (_: Exception) {
            null
        }
    }
}
