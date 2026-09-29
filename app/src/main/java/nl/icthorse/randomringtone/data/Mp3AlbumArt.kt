package nl.icthorse.randomringtone.data

import android.media.MediaMetadataRetriever
import java.io.File
import java.io.FileOutputStream

/**
 * Schrijft een ID3v2.3 tag aan het begin van een MP3 bestand met titel, artiest en
 * een ingebedde JPEG album cover (APIC frame, picture type 0x03 = cover front).
 *
 * Pure byte-level I/O, geen externe libraries. Analoog aan T13 M4aMetadata voor M4A.
 *
 * Skip-condities:
 *  - bestand bestaat niet / niet schrijfbaar
 *  - extensie != mp3
 *  - JPEG bytes < 1 KB (te klein voor zinvolle cover)
 *  - bestand heeft al een ingebedde cover
 *
 * v2.2.0: een bestaande ID3v2-tag ZONDER cover (Y2Mate levert die altijd) werd overgeslagen ⇒ YouTube-
 * downloads kregen nooit art. Nu wordt zo'n tag vervangen; titel/artiest worden overgenomen als ze niet
 * meegegeven zijn. De ID3v1-marker aan het eind blijft staan (alleen de v2-kop verandert).
 *
 * Verify-strategie: schrijf naar <file>.tmp, lees met MediaMetadataRetriever,
 * vervang origineel alleen bij succesvolle embeddedPicture-readback.
 */
object Mp3AlbumArt {

    private const val PIC_TYPE_COVER_FRONT: Byte = 0x03
    private const val MIN_JPEG_BYTES = 1000

    fun hasID3v2(file: File): Boolean {
        if (!file.exists() || file.length() < 3) return false
        return try {
            file.inputStream().use { stream ->
                val header = ByteArray(3)
                if (stream.read(header) != 3) return false
                header[0] == 'I'.code.toByte() &&
                    header[1] == 'D'.code.toByte() &&
                    header[2] == '3'.code.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Embed [jpegBytes] als APIC cover-front frame in [file].
     * @return true bij geslaagde write + verify, false bij skip of fail.
     */
    fun write(file: File, jpegBytes: ByteArray, title: String? = null, artist: String? = null): Boolean {
        if (!file.exists() || !file.canWrite()) return false
        if (file.extension.lowercase() != "mp3") return false
        if (jpegBytes.size < MIN_JPEG_BYTES) return false

        var skip = 0L
        var t = title
        var a = artist
        if (hasID3v2(file)) {
            if (verifyHasEmbeddedPicture(file)) return false
            val header = ByteArray(10)
            file.inputStream().use { if (it.read(header) != 10) return false }
            skip = id3v2TotalSize(header)?.toLong() ?: return false
            if (skip >= file.length()) return false
            if (t.isNullOrBlank() || a.isNullOrBlank()) {
                val (et, ea) = readTitleArtist(file)
                if (t.isNullOrBlank()) t = et
                if (a.isNullOrBlank()) a = ea
            }
        }

        val tag = buildId3v23Tag(t, a, jpegBytes)
        val tmp = File(file.parentFile, "${file.name}.art.tmp")
        return try {
            FileOutputStream(tmp).use { out ->
                out.write(tag)
                file.inputStream().use { input ->
                    var left = skip
                    while (left > 0) { val n = input.skip(left); if (n <= 0) break; left -= n }
                    input.copyTo(out)
                }
            }
            if (!verifyHasEmbeddedPicture(tmp)) {
                tmp.delete()
                return false
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            true
        } catch (_: Exception) {
            try { tmp.delete() } catch (_: Exception) {}
            false
        }
    }

    /**
     * Totale lengte van een ID3v2-tag (kop + inhoud + evt. footer) uit de eerste 10 bytes; null als geen/ongeldige tag.
     */
    fun id3v2TotalSize(header: ByteArray): Int? {
        if (header.size < 10) return null
        if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) return null
        if (header[3].toInt() !in 2..4) return null
        var size = 0
        for (i in 6..9) {
            val b = header[i].toInt() and 0xFF
            if (b and 0x80 != 0) return null
            size = (size shl 7) or b
        }
        val footer = if (header[3].toInt() == 4 && (header[5].toInt() and 0x10) != 0) 10 else 0
        return 10 + size + footer
    }

    private fun readTitleArtist(file: File): Pair<String?, String?> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) to
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
        } catch (_: Exception) {
            null to null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun verifyHasEmbeddedPicture(file: File): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val art = retriever.embeddedPicture
            art != null && art.size >= MIN_JPEG_BYTES
        } catch (_: Exception) {
            false
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun buildId3v23Tag(title: String?, artist: String?, jpegBytes: ByteArray): ByteArray {
        val frames = mutableListOf<ByteArray>()
        title?.takeIf { it.isNotBlank() }?.let { frames.add(buildTextFrame("TIT2", it)) }
        artist?.takeIf { it.isNotBlank() }?.let { frames.add(buildTextFrame("TPE1", it)) }
        frames.add(buildApicFrame(jpegBytes))

        var frameData = ByteArray(0)
        for (f in frames) frameData += f

        val header = ByteArray(10)
        header[0] = 'I'.code.toByte()
        header[1] = 'D'.code.toByte()
        header[2] = '3'.code.toByte()
        header[3] = 0x03
        header[4] = 0x00
        header[5] = 0x00
        writeSyncsafeInt(header, 6, frameData.size)
        return header + frameData
    }

    private fun buildTextFrame(frameId: String, text: String): ByteArray {
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val textBytes = text.toByteArray(Charsets.UTF_16LE)
        val terminator = byteArrayOf(0x00, 0x00)
        val body = byteArrayOf(0x01) + bom + textBytes + terminator
        return frameHeader(frameId, body.size) + body
    }

    private fun buildApicFrame(jpegBytes: ByteArray): ByteArray {
        val mime = "image/jpeg".toByteArray(Charsets.ISO_8859_1)
        val body = byteArrayOf(0x00) +
            mime + byteArrayOf(0x00) +
            byteArrayOf(PIC_TYPE_COVER_FRONT) +
            byteArrayOf(0x00) +
            jpegBytes
        return frameHeader("APIC", body.size) + body
    }

    private fun frameHeader(frameId: String, bodySize: Int): ByteArray {
        require(frameId.length == 4) { "frame id must be 4 chars" }
        val header = ByteArray(10)
        val idBytes = frameId.toByteArray(Charsets.ISO_8859_1)
        idBytes.copyInto(header, 0, 0, 4)
        header[4] = (bodySize ushr 24 and 0xFF).toByte()
        header[5] = (bodySize ushr 16 and 0xFF).toByte()
        header[6] = (bodySize ushr 8 and 0xFF).toByte()
        header[7] = (bodySize and 0xFF).toByte()
        header[8] = 0x00
        header[9] = 0x00
        return header
    }

    private fun writeSyncsafeInt(buf: ByteArray, offset: Int, value: Int) {
        buf[offset]     = ((value ushr 21) and 0x7F).toByte()
        buf[offset + 1] = ((value ushr 14) and 0x7F).toByte()
        buf[offset + 2] = ((value ushr 7) and 0x7F).toByte()
        buf[offset + 3] = (value and 0x7F).toByte()
    }
}
