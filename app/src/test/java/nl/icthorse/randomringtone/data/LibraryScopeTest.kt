package nl.icthorse.randomringtone.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryScopeTest {
    private val dl = "/storage/emulated/0/Download/RandomRing"
    private val rt = "/storage/emulated/0/Download/RandomRing/Tones"
    private val dirs = listOf(dl, rt)

    private fun track(id: Long, path: String?) =
        SavedTrack(deezerTrackId = id, title = "T$id", artist = "A", previewUrl = "", localPath = path, playlistName = "Gescand")

    @Test fun `normalize - aliassen en slashes`() {
        assertEquals("/storage/emulated/0/Download/x.mp3", LibraryScope.normalize("/sdcard/Download/x.mp3"))
        assertEquals("/storage/emulated/0/Download/x.mp3", LibraryScope.normalize("/storage/self/primary//Download/./x.mp3"))
        assertEquals("/storage/emulated/0/Download", LibraryScope.normalize("/storage/emulated/0/Download/"))
        assertEquals("/storage/emulated/0/a", LibraryScope.normalize("/storage/emulated/0/b/../a"))
        assertEquals("/data/user/0/nl.x/files", LibraryScope.normalize("/data/data/nl.x/files"))
        assertEquals("/sdcardx/a", LibraryScope.normalize("/sdcardx/a"))
    }

    @Test fun `isInDirs - alleen direct in ingestelde map`() {
        assertTrue(LibraryScope.isInDirs("$dl/spotify_mp3_Africa-TOTO.mp3", dirs))
        assertTrue(LibraryScope.isInDirs("$rt/tone.m4a", dirs))
        assertTrue(LibraryScope.isInDirs("/sdcard/Download/RandomRing/a.mp3", dirs))
        assertFalse(LibraryScope.isInDirs("/storage/emulated/0/Download/a.mp3", dirs))          // systeem-Downloads
        assertFalse(LibraryScope.isInDirs("/storage/emulated/0/Download/_RandomRingtone/a.mp3", dirs)) // oude map
        assertFalse(LibraryScope.isInDirs("$dl/sub/a.mp3", dirs))                               // dieper dan de scan
        assertFalse(LibraryScope.isInDirs("/storage/emulated/0/Download/RandomRingX/a.mp3", dirs)) // geen prefix-lek
        assertFalse(LibraryScope.isInDirs(null, dirs))
        assertFalse(LibraryScope.isInDirs("", dirs))
    }

    @Test fun `findStale - buiten map, weg of zonder pad`() {
        val present = setOf("$dl/a.mp3", "$rt/b.m4a")
        val tracks = listOf(
            track(1, "$dl/a.mp3"),
            track(2, "$rt/b.m4a"),
            track(3, "/storage/emulated/0/Download/c.mp3"),
            track(4, "$dl/weg.mp3"),
            track(5, null)
        )
        val stale = LibraryScope.findStale(tracks, dirs) { it in present }
        assertEquals(listOf(3L, 4L, 5L), stale.map { it.deezerTrackId })
    }

    @Test fun `summarize - kapt af na max`() {
        val s = LibraryScope.summarize((1L..12L).map { track(it, null) }, max = 10)
        assertTrue(s.endsWith("… en 2 meer"))
        assertEquals(11, s.lines().size)
    }
}

class ContactMatcherTest {
    private val contacts = listOf(
        ContactInfo("uri:joy", "Joy"),
        ContactInfo("uri:thomas", "Thomas Join Recr."),
        ContactInfo("uri:maryka", "Maryka Glebbeek"),
        ContactInfo("uri:marius", "Marius Glebbeek"),
        ContactInfo("uri:theo", "Theo van Scheppingen")
    )

    private fun pl(name: String, contact: String?, uri: String? = null, active: Boolean = true) =
        PlaylistBackup(1, name, "CALL", "QUASI_RANDOM", "EVERY_CALL", uri, contact, active, null)

    @Test fun `match - exact, hoofdletters, afgekapt`() {
        assertEquals("uri:joy", ContactMatcher.match("Joy", contacts)?.uri)
        assertEquals("uri:maryka", ContactMatcher.match("maryka glebbeek ", contacts)?.uri)
        assertEquals("uri:thomas", ContactMatcher.match("Thomas Join Recr", contacts)?.uri)
    }

    @Test fun `match - dubbelzinnig of onbekend geeft null`() {
        assertNull(ContactMatcher.match("Ma", contacts))       // Maryka én Marius
        assertNull(ContactMatcher.match("Onbekend", contacts))
        assertNull(ContactMatcher.match("", contacts))
        assertNull(ContactMatcher.match(null, contacts))
    }

    @Test fun `resolve - vult uri, zet onvindbare uit, laat globaal en bestaande uri met rust`() {
        val r = ContactMatcher.resolve(listOf(
            pl("Joy", "Joy"),
            pl("x", "Niemand"),
            pl("shad", null),
            pl("theo", "Theo van Scheppingen", uri = "content://bestaand")
        ), contacts)
        assertEquals("uri:joy", r.playlists[0].contactUri)
        assertTrue(r.playlists[0].isActive)
        assertEquals("name:Niemand", r.playlists[1].contactUri)
        assertFalse(r.playlists[1].isActive)
        assertNull(r.playlists[2].contactUri)
        assertTrue(r.playlists[2].isActive)
        assertEquals("content://bestaand", r.playlists[3].contactUri)
        assertEquals(listOf("x (Niemand)"), r.unresolved)
    }

    @Test fun `resolve - zonder contactenrecht nooit globaal`() {
        val r = ContactMatcher.resolve(listOf(pl("Joy", "Joy")), null)
        assertFalse(r.playlists[0].isActive)
        assertEquals("name:Joy", r.playlists[0].contactUri)
    }

    @Test fun `resolve - name-placeholder wordt gekoppeld of blijft uit`() {
        val r = ContactMatcher.resolve(listOf(
            pl("mam", "Maryka Glebbeek", uri = "name:Maryka Glebbeek"),
            pl("zonder naam", null, uri = "name:Thomas Join Recr"),
            pl("weg", null, uri = "name:Niemand")
        ), contacts)
        assertEquals("uri:maryka", r.playlists[0].contactUri)
        assertTrue(r.playlists[0].isActive)
        assertEquals("uri:thomas", r.playlists[1].contactUri)
        assertEquals("Thomas Join Recr.", r.playlists[1].contactName)
        assertEquals("name:Niemand", r.playlists[2].contactUri)
        assertEquals("Niemand", r.playlists[2].contactName)
        assertFalse(r.playlists[2].isActive)
        assertTrue(ContactMatcher.isPlaceholder("name:x"))
        assertFalse(ContactMatcher.isPlaceholder("content://com.android.contacts/contacts/lookup/x/1"))
    }
}
