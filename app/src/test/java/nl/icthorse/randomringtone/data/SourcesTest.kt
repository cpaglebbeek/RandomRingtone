package nl.icthorse.randomringtone.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.7.0: de enige bron is YourTube (eigen server). */
class SourcesTest {

    @Test fun videoIdFromUrls() {
        assertEquals("djV11Xbc914", YourTube.videoId("https://m.youtube.com/watch?v=djV11Xbc914&pp=abc"))
        assertEquals("djV11Xbc914", YourTube.videoId("https://youtu.be/djV11Xbc914"))
        assertEquals("djV11Xbc914", YourTube.videoId("https://www.youtube.com/shorts/djV11Xbc914"))
        assertNull(YourTube.videoId("https://www.youtube.com/"))
        assertNull("een zoekopdracht is geen link", YourTube.videoId("johnny cash ring of fire"))
    }

    @Test fun searchAnswerBecomesHits() {
        val hits = YourTube.parseSearch("""{"items":[
          {"id":"1WaV2x8GXj0","title":"Ring of Fire","channel":"Johnny Cash","duration":"2:36","durationSec":156,
           "thumb":"https://i.ytimg.com/vi/1WaV2x8GXj0/mqdefault.jpg"},
          {"id":"kort","title":"ongeldig id"},
          {"id":"tObz-q9i3P4","title":"Zonder thumb","channel":"X","duration":"","durationSec":0}]}""")
        assertEquals(2, hits.size)
        assertEquals("Ring of Fire", hits[0].title)
        assertEquals("Johnny Cash", hits[0].channel)
        assertEquals(156L, hits[0].durationSec)
        assertEquals("https://i.ytimg.com/vi/tObz-q9i3P4/mqdefault.jpg", hits[1].thumb)
        assertTrue(YourTube.parseSearch("""{"items":[]}""").isEmpty())
        assertTrue(YourTube.parseSearch("""{}""").isEmpty())
    }

    @Test fun taskStateParsesBackendAnswer() {
        val q = YourTube.parseState("""{"state":"queued","step":"In de rij","progress":0,"size":0,"error":"","url":"","ext":"m4a"}""")
        assertEquals("queued", q.state)
        assertEquals(0.05f, YourTube.barProgress(q), 0.0001f)
        val w = YourTube.parseState("""{"state":"working","step":"Downloaden","progress":0.5}""")
        assertEquals(0.4f, YourTube.barProgress(w), 0.0001f)
        assertEquals("YourTube: Downloaden", YourTube.phaseText(w))
        val d = YourTube.parseState("""{"state":"done","progress":1,"url":"https://yourtube.icthorsecloud.nl/m/t/djV11Xbc914.m4a"}""")
        assertEquals(0.7f, YourTube.barProgress(d), 0.0001f)
        assertTrue(d.url.endsWith(".m4a"))
        assertEquals(1f, YourTube.parseState("""{"state":"working","progress":7}""").progress, 0.0001f)
    }

    @Test fun fileNameIsSafe() {
        assertEquals("Johnny_Cash_-_Ring_of_Fire_(Official)", YourTube.sanitize("Johnny Cash - Ring of Fire (Official)"))
        assertEquals("a_b", YourTube.sanitize("a/b"))
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
