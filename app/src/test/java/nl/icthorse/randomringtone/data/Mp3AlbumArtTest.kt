package nl.icthorse.randomringtone.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Mp3AlbumArtTest {
    private fun header(version: Int, flags: Int, vararg size: Int) =
        byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), version.toByte(), 0, flags.toByte()) +
            size.map { it.toByte() }.toByteArray()

    @Test fun `syncsafe grootte plus kop`() {
        assertEquals(10 + 257, Mp3AlbumArt.id3v2TotalSize(header(3, 0, 0, 0, 2, 1)))   // 2*128+1
        assertEquals(10 + 0x0FFFFFFF, Mp3AlbumArt.id3v2TotalSize(header(4, 0, 0x7F, 0x7F, 0x7F, 0x7F)))
    }

    @Test fun `v2_4 footer telt mee`() {
        assertEquals(10 + 100 + 10, Mp3AlbumArt.id3v2TotalSize(header(4, 0x10, 0, 0, 0, 100)))
    }

    @Test fun `geen of ongeldige tag`() {
        assertNull(Mp3AlbumArt.id3v2TotalSize("TAG1234567".toByteArray()))
        assertNull(Mp3AlbumArt.id3v2TotalSize(header(3, 0, 0, 0, 0x80, 0)))          // geen syncsafe
        assertNull(Mp3AlbumArt.id3v2TotalSize(header(9, 0, 0, 0, 0, 1)))             // onbekende versie
        assertNull(Mp3AlbumArt.id3v2TotalSize(ByteArray(5)))
    }
}
