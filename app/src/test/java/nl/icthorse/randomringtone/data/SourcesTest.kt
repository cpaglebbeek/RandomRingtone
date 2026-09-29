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
}
