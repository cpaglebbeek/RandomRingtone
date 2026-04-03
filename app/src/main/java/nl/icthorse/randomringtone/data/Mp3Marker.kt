package nl.icthorse.randomringtone.data

import java.io.File
import java.io.RandomAccessFile

/**
 * Leest en schrijft markers in het ID3v1 comment-veld van MP3 bestanden.
 *
 * Twee marker-varianten:
 * - "RandomRingtone track"   → ongetrimde downloads
 * - "RandomRingtone trimmed" → getrimde ringtones
 *
 * De scan filtert ALLE media op het apparaat op deze markers.
 * Hierdoor worden alleen bestanden die door de app zijn gedownload geïmporteerd.
 * Het verschil tussen track/trimmed bepaalt of een bestand als Download of Ringtone
 * wordt getoond in de bibliotheek.
 */
object Mp3Marker {

    const val MARKER_TRACK = "RandomRingtone track"
    const val MARKER_TRIMMED = "RandomRingtone trimmed"
    const val MARKER_YOUTUBE = "RandomRingtone YoutubeClip"
    private const val TAG_SIZE = 128
    private const val TAG_HEADER = "TAG"
    private const val COMMENT_OFFSET = 97   // offset van comment-veld binnen ID3v1 tag
    private const val COMMENT_SIZE = 30

    /**
     * Lees het comment-veld uit de ID3v1 tag.
     * @return comment string of null als er geen ID3v1 tag is
     */
    private fun readComment(file: File): String? {
        if (!file.exists() || file.length() < TAG_SIZE || file.extension.lowercase() != "mp3") return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(file.length() - TAG_SIZE)
                val header = ByteArray(3)
                raf.readFully(header)
                if (String(header, Charsets.ISO_8859_1) != TAG_HEADER) return null

                raf.seek(file.length() - TAG_SIZE + COMMENT_OFFSET)
                val comment = ByteArray(COMMENT_SIZE)
                raf.readFully(comment)
                String(comment, Charsets.ISO_8859_1).trimEnd('\u0000')
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Check of een MP3 bestand een RandomRingtone marker bevat (track of trimmed).
     */
    fun hasMarker(file: File): Boolean {
        val comment = readComment(file) ?: return false
        return comment == MARKER_TRACK || comment == MARKER_TRIMMED || comment == MARKER_YOUTUBE
    }

    /**
     * Check of een MP3 bestand de "trimmed" marker bevat.
     */
    fun isTrimmed(file: File): Boolean {
        return readComment(file) == MARKER_TRIMMED
    }

    /**
     * Schrijf een marker in het ID3v1 comment-veld.
     * Behoudt bestaande ID3v1 velden als die er zijn,
     * of maakt een nieuwe ID3v1 tag aan met de meegegeven title/artist.
     */
    private fun writeMarker(file: File, title: String?, artist: String?, markerText: String) {
        if (!file.exists() || !file.canWrite() || file.extension.lowercase() != "mp3") return
        try {
            RandomAccessFile(file, "rw").use { raf ->
                val hasExistingTag = file.length() >= TAG_SIZE && run {
                    raf.seek(file.length() - TAG_SIZE)
                    val header = ByteArray(3)
                    raf.readFully(header)
                    String(header, Charsets.ISO_8859_1) == TAG_HEADER
                }

                if (hasExistingTag) {
                    // Update alleen het comment-veld van de bestaande tag
                    raf.seek(file.length() - TAG_SIZE + COMMENT_OFFSET)
                    raf.write(padField(markerText, COMMENT_SIZE))
                } else {
                    // Maak een nieuwe ID3v1 tag aan het einde van het bestand
                    raf.seek(file.length())
                    raf.write(TAG_HEADER.toByteArray(Charsets.ISO_8859_1))  // "TAG"
                    raf.write(padField(title ?: "", 30))                    // Title
                    raf.write(padField(artist ?: "", 30))                   // Artist
                    raf.write(padField("", 30))                             // Album
                    raf.write(padField("", 4))                              // Year
                    raf.write(padField(markerText, COMMENT_SIZE))            // Comment
                    raf.write(byteArrayOf(0xFF.toByte()))                    // Genre: unknown
                }
            }
        } catch (_: Exception) {
            // Bestand is read-only of andere IO fout — negeer
        }
    }

    /**
     * Injecteer de "track" marker (voor downloads/ongetrimde bestanden).
     */
    fun injectMarker(file: File, title: String? = null, artist: String? = null) {
        writeMarker(file, title, artist, MARKER_TRACK)
    }

    /**
     * Injecteer de "trimmed" marker (voor getrimde ringtones).
     */
    fun injectTrimmedMarker(file: File, title: String? = null, artist: String? = null) {
        writeMarker(file, title, artist, MARKER_TRIMMED)
    }

    /**
     * Injecteer "track" marker alleen als het bestand nog GEEN marker heeft.
     * Bestaande markers (inclusief "trimmed") worden niet overschreven.
     */
    fun injectIfMissing(file: File, title: String? = null, artist: String? = null) {
        if (!hasMarker(file)) {
            writeMarker(file, title, artist, MARKER_TRACK)
        }
    }

    /**
     * Check of een MP3 bestand de "YoutubeClip" marker bevat.
     */
    fun isYouTube(file: File): Boolean {
        return readComment(file) == MARKER_YOUTUBE
    }

    /**
     * Injecteer de "YoutubeClip" marker (voor YouTube downloads).
     */
    fun writeYouTubeMarker(file: File, title: String? = null, artist: String? = null) {
        writeMarker(file, title, artist, MARKER_YOUTUBE)
    }

    private fun padField(value: String, size: Int): ByteArray {
        val bytes = value.toByteArray(Charsets.ISO_8859_1)
        val result = ByteArray(size)
        bytes.copyInto(result, 0, 0, minOf(bytes.size, size))
        return result
    }
}
