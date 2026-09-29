package nl.icthorse.randomringtone.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupRulesTest {
    private val allPerms = setOf("android.permission.READ_MEDIA_AUDIO", "android.permission.POST_NOTIFICATIONS") +
        SetupRules.PHONE_PERMISSIONS + SetupRules.CONTACT_PERMISSIONS
    private val goodDir = DirFact("Downloadmap", "/d", exists = true, isDirectory = true, writable = true, freeBytes = 5L shl 30)

    private fun facts(
        canWrite: Boolean = true, granted: Set<String> = allPerms, sdk: Int = 34, install: Boolean = true,
        phone: Boolean = false, contacts: Boolean = false, dirs: List<DirFact> = listOf(goodDir, goodDir.copy(label = "Ringtonemap")),
        backup: BackupDirFact = BackupDirFact(false, false, false), missing: Int = 0,
        restoreBytes: Long? = null, local: Boolean = false
    ) = SetupFacts(sdk, canWrite, granted, install, phone, contacts, dirs, backup, 10, missing, restoreBytes, local)

    private fun ids(f: SetupFacts, s: CheckScope = CheckScope.STARTUP) = SetupRules.evaluate(f, s).map { it.id }

    @Test fun `alles in orde - geen punten`() = assertEquals(emptyList<String>(), ids(facts()))

    @Test fun `geen WRITE_SETTINGS is rood en staat bovenaan`() {
        val r = SetupRules.evaluate(facts(canWrite = false, install = false), CheckScope.STARTUP)
        assertEquals("write_settings", r.first().id)
        assertEquals(Severity.BLOCKER, r.first().severity)
        assertEquals(Severity.INFO, r.last().severity)
    }

    @Test fun `media- en meldingsrecht alleen vanaf Android 13`() {
        assertTrue(ids(facts(granted = emptySet(), sdk = 32)).none { it == "read_media_audio" || it == "notifications" })
        assertTrue(ids(facts(granted = emptySet(), sdk = 33)).containsAll(listOf("read_media_audio", "notifications")))
    }

    @Test fun `telefoon- en contactrechten alleen als een actieve playlist ze nodig heeft`() {
        assertFalse(ids(facts(granted = emptySet())).any { it == "phone" || it == "contacts" })
        val r = SetupRules.evaluate(facts(granted = emptySet(), phone = true, contacts = true), CheckScope.STARTUP)
        assertEquals(SetupRules.PHONE_PERMISSIONS, r.first { it.id == "phone" }.permissions)
        assertEquals(Severity.BLOCKER, r.first { it.id == "contacts" }.severity)
    }

    @Test fun `map ontbreekt, niet schrijfbaar, weinig ruimte`() {
        val d = listOf(goodDir.copy(exists = false, isDirectory = false), goodDir.copy(label = "Ringtonemap", writable = false))
        assertEquals(listOf("dir_missing_Downloadmap", "dir_readonly_Ringtonemap"), ids(facts(dirs = d)))
        val low = SetupRules.evaluate(facts(dirs = listOf(goodDir.copy(freeBytes = 10L shl 20))), CheckScope.STARTUP).single()
        assertEquals(Severity.WARNING, low.severity)
    }

    @Test fun `backupmap kwijt - oranje bij start, rood bij lokale restore`() {
        val lost = BackupDirFact(configured = true, permissionHeld = false, reachable = false)
        assertEquals(Severity.WARNING, SetupRules.evaluate(facts(backup = lost), CheckScope.STARTUP).single().severity)
        val r = SetupRules.evaluate(facts(backup = lost, local = true), CheckScope.RESTORE)
        assertEquals(Severity.BLOCKER, r.single().severity)
        assertTrue(SetupRules.blocks(r, CheckScope.RESTORE))
        assertEquals("backup_unset", ids(facts(local = true), CheckScope.RESTORE).single())
    }

    @Test fun `ontbrekende bestanden - waarschuwing bij Bibliotheek, info bij start`() {
        assertEquals(Severity.WARNING, SetupRules.evaluate(facts(missing = 3), CheckScope.LIBRARY).single().severity)
        assertEquals(Severity.INFO, SetupRules.evaluate(facts(missing = 3), CheckScope.STARTUP).single().severity)
    }

    @Test fun `restore - te weinig ruimte blokkeert, genoeg niet`() {
        val small = listOf(goodDir.copy(freeBytes = 100L shl 20))
        assertTrue(SetupRules.blocks(SetupRules.evaluate(facts(dirs = small, restoreBytes = 80L shl 20), CheckScope.RESTORE), CheckScope.RESTORE))
        assertTrue(ids(facts(restoreBytes = 80L shl 20), CheckScope.RESTORE).isEmpty())
        // rood blokkeert alleen een restore, niet start of Bibliotheek
        assertFalse(SetupRules.blocks(SetupRules.evaluate(facts(canWrite = false), CheckScope.LIBRARY), CheckScope.LIBRARY))
    }
}
