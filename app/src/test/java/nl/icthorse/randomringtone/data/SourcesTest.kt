package nl.icthorse.randomringtone.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcesTest {

    private fun html() = javaClass.classLoader!!.getResource("spotify_embed_africa.html")!!.readText()

    @Test fun spotifyEmbedParsesMetaPreviewAndCover() {
        // Echte embed-HTML van open.spotify.com/embed/track/2374M0fQpWi3dLnB54qaLX (29-09-2026)
        val i = SpotifyPreviewClient.parseEmbedStatic(html())!!
        assertEquals("Africa", i.name)
        assertEquals("TOTO", i.artist)
        assertEquals(295L, i.durationSec)
        assertTrue(i.previewUrl!!.startsWith("https://p.scdn.co/mp3-preview/"))
        assertNotNull(i.albumArt)
    }

    @Test fun spotifyEmbedGarbageGivesNull() = assertNull(SpotifyPreviewClient.parseEmbedStatic("<html>geen data</html>"))

    @Test fun youtubeVideoIdFromUrls() {
        assertEquals("djV11Xbc914", YouTubeOnDevice.videoId("https://m.youtube.com/watch?v=djV11Xbc914&pp=abc"))
        assertEquals("djV11Xbc914", YouTubeOnDevice.videoId("https://youtu.be/djV11Xbc914"))
        assertEquals("djV11Xbc914", YouTubeOnDevice.videoId("https://www.youtube.com/shorts/djV11Xbc914"))
        assertNull(YouTubeOnDevice.videoId("https://www.youtube.com/"))
    }

    @Test fun removedHijackedConvertersAreGone() {
        val urls = SpotifyConverter.ALL.map { it.url }
        assertFalse(urls.any { "keepvid" in it || "spotifydownload.org" in it || "spotifydown.com" in it })
        assertTrue(SpotifyConverter.ALL.any { it.id == StorageManager.DEFAULT_SPOTIFY_CONVERTER })
    }

    // ── v2.4.0: eigen server (YourTube-motor) ──
    @Test fun youtubeServerStateParsesBackendAnswer() {
        val q = YouTubeServer.parseState("""{"state":"queued","step":"In de rij","progress":0,"size":0,"error":"","url":"","ext":"m4a"}""")
        assertEquals("queued", q.state)
        assertEquals(0.05f, YouTubeServer.barProgress(q), 0.0001f)
        val w = YouTubeServer.parseState("""{"state":"working","step":"Downloaden","progress":0.5}""")
        assertEquals(0.4f, YouTubeServer.barProgress(w), 0.0001f)
        assertEquals("Eigen server: Downloaden", YouTubeServer.phaseText(w))
        val d = YouTubeServer.parseState("""{"state":"done","progress":1,"url":"https://yourtube.icthorsecloud.nl/m/t/djV11Xbc914.m4a"}""")
        assertEquals(0.7f, YouTubeServer.barProgress(d), 0.0001f)
        assertTrue(d.url.endsWith(".m4a"))
        assertEquals(1f, YouTubeServer.parseState("""{"state":"working","progress":7}""").progress, 0.0001f)
    }

    @Test fun youtubeMethodDefaultsAndIds() {
        assertEquals(listOf("server", "device"), YouTubeMethod.entries.map { it.id })
        assertNull(YouTubeMethod.entries.find { it.id == "y2mate" })
    }

    /**
     * Alleen met RR_M4A_SAMPLE=<pad naar een m4a van de YourTube-worker> (moov vóór mdat, udta van ffmpeg):
     * hoes + titel + marker komen erin, en de uitvoer gaat naar RR_M4A_OUT om met ffmpeg te decoderen.
     */
    @Test fun m4aMetadataOnServerFile() {
        val sample = System.getenv("RR_M4A_SAMPLE")?.let { java.io.File(it) }?.takeIf { it.exists() } ?: return
        val f = java.io.File.createTempFile("rrtest", ".m4a").also { sample.copyTo(it, overwrite = true) }
        val before = f.length()
        val cover = ByteArray(5000) { (it % 251).toByte() }
        M4aMetadata.write(f, "Titel Test", "Artiest Test", cover, "RR-MARKER-TEST")
        assertTrue("bestand moet groeien met de hoes", f.length() > before + 4000)
        assertTrue(String(f.readBytes(), Charsets.ISO_8859_1).contains("Titel Test"))
        assertEquals("RR-MARKER-TEST", M4aMetadata.readComment(f))
        val after = f.length()
        M4aMetadata.write(f, "Andere titel", "X", cover, "ANDERS")
        assertEquals("eigen metadata wordt niet overschreven", after, f.length())
        System.getenv("RR_M4A_OUT")?.let { f.copyTo(java.io.File(it), overwrite = true) }
        f.delete()
    }
}
