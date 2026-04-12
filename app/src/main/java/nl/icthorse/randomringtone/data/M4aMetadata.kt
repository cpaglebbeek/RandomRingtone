package nl.icthorse.randomringtone.data

import java.io.File

/**
 * Schrijft en leest metadata in M4A/MP4 bestanden via iTunes-style atoms.
 *
 * Structuur: moov → udta → meta → ilst
 * Ondersteunt: ©nam (titel), ©ART (artiest), ©cmt (comment/marker), covr (albumcover JPEG)
 */
object M4aMetadata {

    /**
     * Schrijf metadata in een M4A bestand.
     * Roep aan NADAT MediaMuxer het bestand heeft geschreven.
     */
    fun write(
        file: File,
        title: String? = null,
        artist: String? = null,
        albumArtJpeg: ByteArray? = null,
        comment: String? = null
    ) {
        if (!file.exists() || !file.canWrite()) return
        if (file.extension.lowercase() !in listOf("m4a", "mp4", "aac")) return

        try {
            val bytes = file.readBytes()
            val moovPos = findTopLevelBox(bytes, "moov") ?: return
            val moovSize = readInt32(bytes, moovPos)

            // Al metadata aanwezig → niet overschrijven
            if (findChildBox(bytes, moovPos, "udta") != null) return

            // Bouw ilst items
            val items = mutableListOf<ByteArray>()
            if (!title.isNullOrBlank()) items.add(buildTextItem("\u00A9nam", title))
            if (!artist.isNullOrBlank()) items.add(buildTextItem("\u00A9ART", artist))
            if (!comment.isNullOrBlank()) items.add(buildTextItem("\u00A9cmt", comment))
            if (albumArtJpeg != null && albumArtJpeg.size > 100) {
                items.add(buildImageItem("covr", albumArtJpeg))
            }
            if (items.isEmpty()) return

            val ilst = wrapBox("ilst", concatArrays(items))
            val hdlr = buildMetaHandler()
            // meta is een full box: 4 extra bytes version+flags vóór children
            val meta = wrapBox("meta", ByteArray(4) + hdlr + ilst)
            val udta = wrapBox("udta", meta)

            // Voeg udta toe aan einde van moov content en update moov size
            val moovEnd = moovPos + moovSize
            val newFile = ByteArray(bytes.size + udta.size)
            System.arraycopy(bytes, 0, newFile, 0, moovEnd)
            System.arraycopy(udta, 0, newFile, moovEnd, udta.size)
            if (moovEnd < bytes.size) {
                System.arraycopy(bytes, moovEnd, newFile, moovEnd + udta.size, bytes.size - moovEnd)
            }
            writeInt32(newFile, moovPos, moovSize + udta.size)

            file.writeBytes(newFile)
        } catch (_: Exception) {
            // Metadata schrijven mislukt — bestand is nog steeds geldig audio
        }
    }

    /**
     * Lees de comment atom (©cmt) uit een M4A bestand.
     * @return comment string of null
     */
    fun readComment(file: File): String? {
        if (!file.exists() || file.length() < 100) return null
        if (file.extension.lowercase() !in listOf("m4a", "mp4", "aac")) return null
        return try {
            val bytes = file.readBytes()
            val moovPos = findTopLevelBox(bytes, "moov") ?: return null
            val udtaPos = findChildBox(bytes, moovPos, "udta") ?: return null
            val metaPos = findChildBox(bytes, udtaPos, "meta") ?: return null
            // meta is full box → skip 4 extra bytes voor child lookup
            val ilstPos = findChildBoxFullBox(bytes, metaPos, "ilst") ?: return null
            val cmtPos = findChildBox(bytes, ilstPos, "\u00A9cmt") ?: return null
            readTextData(bytes, cmtPos)
        } catch (_: Exception) {
            null
        }
    }

    // --- Box building ---

    private fun wrapBox(type: String, payload: ByteArray): ByteArray {
        val size = 8 + payload.size
        val box = ByteArray(size)
        writeInt32(box, 0, size)
        type.toByteArray(Charsets.ISO_8859_1).copyInto(box, 4)
        payload.copyInto(box, 8)
        return box
    }

    private fun buildTextItem(type: String, text: String): ByteArray {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val dataPayload = ByteArray(8 + textBytes.size)
        writeInt32(dataPayload, 0, 1) // type indicator: 1 = UTF-8
        // bytes 4-7: locale = 0 (already zero-initialized)
        textBytes.copyInto(dataPayload, 8)
        return wrapBox(type, wrapBox("data", dataPayload))
    }

    private fun buildImageItem(type: String, jpeg: ByteArray): ByteArray {
        val dataPayload = ByteArray(8 + jpeg.size)
        writeInt32(dataPayload, 0, 13) // type indicator: 13 = JPEG
        jpeg.copyInto(dataPayload, 8)
        return wrapBox(type, wrapBox("data", dataPayload))
    }

    private fun buildMetaHandler(): ByteArray {
        // hdlr full box: version+flags(4) + pre_defined(4) + handler_type(4) + reserved(12) + name(1)
        val payload = ByteArray(25)
        "mdir".toByteArray(Charsets.ISO_8859_1).copyInto(payload, 8)
        return wrapBox("hdlr", payload)
    }

    // --- Box finding ---

    private fun findTopLevelBox(bytes: ByteArray, type: String): Int? {
        var pos = 0
        while (pos + 8 <= bytes.size) {
            val size = readInt32(bytes, pos)
            if (size < 8) return null
            if (readBoxType(bytes, pos) == type) return pos
            pos += size
        }
        return null
    }

    private fun findChildBox(bytes: ByteArray, parentPos: Int, type: String): Int? {
        val parentSize = readInt32(bytes, parentPos)
        var pos = parentPos + 8
        val end = parentPos + parentSize
        while (pos + 8 <= end) {
            val size = readInt32(bytes, pos)
            if (size < 8) return null
            if (readBoxType(bytes, pos) == type) return pos
            pos += size
        }
        return null
    }

    /** Zoek child box in een full box (skip extra 4 bytes version+flags) */
    private fun findChildBoxFullBox(bytes: ByteArray, parentPos: Int, type: String): Int? {
        val parentSize = readInt32(bytes, parentPos)
        var pos = parentPos + 12 // header(8) + version+flags(4)
        val end = parentPos + parentSize
        while (pos + 8 <= end) {
            val size = readInt32(bytes, pos)
            if (size < 8) return null
            if (readBoxType(bytes, pos) == type) return pos
            pos += size
        }
        return null
    }

    private fun readTextData(bytes: ByteArray, itemPos: Int): String? {
        val dataPos = findChildBox(bytes, itemPos, "data") ?: return null
        val dataSize = readInt32(bytes, dataPos)
        if (dataSize < 16) return null
        val textStart = dataPos + 16 // header(8) + type(4) + locale(4)
        val textLen = dataSize - 16
        if (textStart + textLen > bytes.size || textLen <= 0) return null
        return String(bytes, textStart, textLen, Charsets.UTF_8)
    }

    // --- Byte helpers ---

    private fun readBoxType(bytes: ByteArray, pos: Int): String =
        String(bytes, pos + 4, 4, Charsets.ISO_8859_1)

    private fun readInt32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)

    private fun writeInt32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = ((value shr 24) and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 3] = (value and 0xFF).toByte()
    }

    private fun concatArrays(arrays: List<ByteArray>): ByteArray {
        val total = arrays.sumOf { it.size }
        val result = ByteArray(total)
        var pos = 0
        for (arr in arrays) {
            arr.copyInto(result, pos)
            pos += arr.size
        }
        return result
    }
}
