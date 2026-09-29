package nl.icthorse.randomringtone.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class Mp3TagReaderTest {
    private fun t(id3: String?, art: String?) = SavedTrack(1, "t", "a", "", "/x/a.mp3", "Gescand", id3Title = id3, albumArtPath = art)

    @Test fun restoredTrackWithId3ButNoArtIsEnriched() = assertTrue(Mp3TagReader.needsEnrich(t("Titel", null)))
    @Test fun missingId3IsEnriched() = assertTrue(Mp3TagReader.needsEnrich(t(null, "")))
    @Test fun checkedWithoutCoverIsSkipped() = assertFalse(Mp3TagReader.needsEnrich(t("Titel", "")))
    @Test fun vanishedArtCacheIsEnriched() = assertTrue(Mp3TagReader.needsEnrich(t("Titel", "/nope/123.jpg")))
    @Test fun existingArtIsSkipped() {
        val f = File(Files.createTempDirectory("art").toFile(), "1.jpg").apply { writeBytes(ByteArray(3)) }
        assertFalse(Mp3TagReader.needsEnrich(t("Titel", f.absolutePath)))
    }
}
