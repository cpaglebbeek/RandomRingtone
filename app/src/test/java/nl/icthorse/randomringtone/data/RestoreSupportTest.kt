package nl.icthorse.randomringtone.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RestoreSupportTest {

    private fun pb(id: Long, contactUri: String? = null, contactName: String? = null, active: Boolean = true,
                   schedule: String = "EVERY_CALL") =
        PlaylistBackup(id, "p$id", "CALL", "QUASI_RANDOM", schedule, contactUri, contactName, active, null)

    @Test fun planNeedsContactsForActiveContactPlaylistFromBackup() {
        val plan = RestoreSupport.planFrom(listOf(pb(1, "name:Joy", "Joy"), pb(2)), null)
        assertTrue(plan.needsContacts)
        assertTrue(plan.needsPhone)
    }

    @Test fun planIgnoresInactiveContactPlaylists() {
        val plan = RestoreSupport.planFrom(listOf(pb(1, "name:Joy", "Joy", active = false), pb(2, schedule = "MANUAL")), null)
        assertFalse(plan.needsContacts)
        assertFalse(plan.needsPhone)
    }

    @Test fun planTakesTargetDirsFromSettings() {
        val plan = RestoreSupport.planFrom(emptyList(), SettingsBackupData(downloadPath = "/d", ringtonePath = "/r"))
        assertEquals("/d", plan.downloadPath)
        assertEquals("/r", plan.ringtonePath)
    }

    @Test fun prepareTargetWritesWhenMissing() {
        val dir = Files.createTempDirectory("rr").toFile()
        assertEquals(TargetAction.WRITE, RestoreSupport.prepareTarget(File(dir, "sub/a.mp3"), 10))
        assertTrue(File(dir, "sub").isDirectory)
    }

    @Test fun prepareTargetSkipsSameSize() {
        val f = File(Files.createTempDirectory("rr").toFile(), "a.mp3").apply { writeBytes(ByteArray(10)) }
        assertEquals(TargetAction.SKIP_SAME, RestoreSupport.prepareTarget(f, 10))
        assertTrue(f.exists())
    }

    @Test fun prepareTargetRemovesDifferentFile() {
        val f = File(Files.createTempDirectory("rr").toFile(), "a.mp3").apply { writeBytes(ByteArray(5)) }
        assertEquals(TargetAction.WRITE, RestoreSupport.prepareTarget(f, 10))
        assertFalse(f.exists())
    }

    @Test fun writeTargetCleansUpPartialFileOnError() {
        val f = File(Files.createTempDirectory("rr").toFile(), "a.mp3")
        val ex = runCatching { RestoreSupport.writeTarget(f) { it.write(1); throw java.io.IOException("open failed: EACCES (Permission denied)") } }
            .exceptionOrNull()
        assertTrue(ex?.message!!.startsWith("Geen schrijfrechten op a.mp3"))
        assertFalse(f.exists())
    }
}
