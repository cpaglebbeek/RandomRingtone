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

    // ---- v2.5.0: playlist/album-import ----

    private fun embedPage(kind: String) = """<html><script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"state":{
      "data":{"entity":{"type":"$kind","name":"Testlijst","title":"Testlijst","subtitle":"Tester\u00a0Een",
        "coverArt":{"sources":[{"url":"https://example.org/hoes.jpg"}]},
        "trackList":[
          {"uri":"spotify:track:1111111111111111111111","title":"Eerste Lied (Radio Edit)","subtitle":"Band A,\u00a0Zanger B","duration":201500,"isPlayable":true,"entityType":"track"},
          {"uri":"spotify:track:2222222222222222222222","title":"Tweede","subtitle":"Band C","duration":95000,"isPlayable":false,"entityType":"track"},
          {"uri":"spotify:episode:3333333333333333333333","title":"Podcast","subtitle":"X","duration":1,"entityType":"episode"}]}},
      "settings":{"session":{"accessToken":"tok-abc","isAnonymous":true}}}}}}</script></html>"""

    @Test fun spotifyLinkKinds() {
        assertEquals(SpotifyCollection.Kind.PLAYLIST, SpotifyCollection.parseLink("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=x")!!.kind)
        assertEquals("4aawyAB9vmqN3uQ7FjRGTy", SpotifyCollection.parseLink("https://open.spotify.com/intl-nl/album/4aawyAB9vmqN3uQ7FjRGTy")!!.id)
        assertEquals(SpotifyCollection.Kind.ALBUM, SpotifyCollection.parseLink("spotify:album:4aawyAB9vmqN3uQ7FjRGTy")!!.kind)
        assertNull(SpotifyCollection.parseLink("https://open.spotify.com/track/2374M0fQpWi3dLnB54qaLX"))
        assertNull(SpotifyCollection.parseLink("https://open.spotify.com/artist/0TnOYISbd1XYRBk9myaseg"))
    }

    @Test fun spotifyCollectionParsesEmbed() {
        val ref = SpotifyCollection.Ref(SpotifyCollection.Kind.PLAYLIST, "37i9dQZF1DXcBWIGoYBM5M")
        val c = SpotifyCollection.parseEmbed(embedPage("playlist"), ref)!!
        assertEquals("Testlijst", c.name)
        assertEquals("Tester Een", c.owner)
        assertEquals("https://example.org/hoes.jpg", c.coverUrl)
        assertEquals("tok-abc", c.token)
        assertEquals(2, c.items.size)                       // de podcast-aflevering telt niet mee
        assertEquals("Band A, Zanger B", c.items[0].artist) // harde spaties uit de embed zijn weg
        assertEquals(201L, c.items[0].durationSec)
        assertFalse(c.items[1].playable)
        assertTrue(c.complete)
        assertNull(SpotifyCollection.parseEmbed("<html>niets</html>", ref))
    }

    @Test fun spotifyPlaylistOrderFromService() {
        val (ids, length) = SpotifyCollection.parsePlaylistOrder(
            """{"length":3,"contents":{"items":[{"uri":"spotify:track:2222222222222222222222"},{"uri":"spotify:local:x"},{"uri":"spotify:track:1111111111111111111111"}]}}""")
        assertEquals(listOf("2222222222222222222222", "1111111111111111111111"), ids)
        assertEquals(3, length)
    }

    private fun hit(title: String, uploader: String, dur: Long) = YouTubeOnDevice.Hit("abcdefghijk", title, uploader, dur, "u")

    @Test fun youtubeMatchRequiresTitleArtistAndDuration() {
        val hits = listOf(
            hit("Iets Heel Anders", "Band A", 200),                         // titel klopt niet
            hit("Eerste Lied (karaoke)", "Karaoke Kanaal", 200),            // artiest ontbreekt
            hit("Band A - Eerste Lied (Live in Parijs)", "Band A", 320),    // duur te ver weg
            hit("Band A - Eerste Lied (Official Video)", "BandAVEVO", 214),
            hit("Eerste Lied", "Band A - Topic", 202))
        val best = YouTubeMatch.pick(hits, "Band A, Zanger B", "Eerste Lied (Radio Edit)", 201)!!
        assertEquals("Band A - Topic", best.uploader)
        assertNull(YouTubeMatch.pick(hits.take(3), "Band A, Zanger B", "Eerste Lied (Radio Edit)", 201))
        assertEquals("Eerste Lied", YouTubeMatch.coreTitle("Eerste Lied - Remastered 2011"))
        assertEquals(listOf("band a", "zanger b", "c"), YouTubeMatch.artistTokens("Band A & Zanger B feat. C").take(3).let { it.take(2) + "c" })
        assertTrue(YouTubeMatch.titleMatches("Beyoncé – Halo (Official)", "Halo"))
        assertFalse(YouTubeMatch.titleMatches("Shalom aleichem", "Go"))
    }

    @Test fun m4aAlbumAndTrackNumber() {
        val sample = System.getenv("RR_M4A_SAMPLE") ?: return
        val src = java.io.File(sample); if (!src.exists()) return
        val f = java.io.File.createTempFile("rrtest", ".m4a"); src.copyTo(f, overwrite = true)
        M4aMetadata.write(f, "Titel", "Artiest", null, "track", album = "Het Album", trackNo = 3, trackTotal = 12)
        val raw = String(f.readBytes(), Charsets.ISO_8859_1)
        assertTrue("\u00A9alb" in raw && "Het Album" in raw)
        assertTrue("trkn" in raw)
        System.getenv("RR_M4A_OUT")?.let { f.copyTo(java.io.File(it), overwrite = true) }
        f.delete()
    }

    /** Alleen met RR_EMBED_DIR (map met playlist.html en album.html, vers opgehaald van open.spotify.com/embed/…). */
    @Test fun spotifyCollectionOnRealPages() {
        val dir = System.getenv("RR_EMBED_DIR")?.let { java.io.File(it) } ?: return
        val pl = SpotifyCollection.parseEmbed(java.io.File(dir, "playlist.html").readText(),
            SpotifyCollection.Ref(SpotifyCollection.Kind.PLAYLIST, "37i9dQZF1DXcBWIGoYBM5M"))!!
        assertTrue(pl.items.size >= 30 && pl.name.isNotBlank() && pl.token != null && pl.coverUrl != null)
        assertTrue(pl.items.all { it.title.isNotBlank() && it.artist.isNotBlank() && (it.durationSec ?: 0) > 0 })
        val al = SpotifyCollection.parseEmbed(java.io.File(dir, "album.html").readText(),
            SpotifyCollection.Ref(SpotifyCollection.Kind.ALBUM, "4aawyAB9vmqN3uQ7FjRGTy"))!!
        assertTrue(al.items.size >= 10 && al.coverUrl != null && al.owner.isNotBlank())
        println("ECHT playlist=${pl.name} n=${pl.items.size} | album=${al.name} n=${al.items.size} owner=${al.owner}")
    }
}
