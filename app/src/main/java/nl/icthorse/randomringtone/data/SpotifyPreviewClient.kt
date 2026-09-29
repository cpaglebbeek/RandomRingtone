package nl.icthorse.randomringtone.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Spotify-bron "Web API + Deezer (ISRC)" (v2.0.0, vervangt SpotMateDirectClient — bug #81).
 *
 * Flow: Spotify-link → RandomRingtone-backend `/rrlog/spotify/track/{id}` (Spotify Web API, Client Credentials, secret
 * alleen op de server) → ISRC → Deezer-track met dezelfde ISRC → 30-seconden-preview (MP3) → lokaal bestand.
 * Spotify geeft nieuwe apps sinds 27-11-2024 geen preview_url meer; de ISRC maakt de Deezer-match exact.
 * Alleen gelicenseerde toestellen met toestel-token (X-Device-Id + X-Device-Token).
 */
class SpotifyPreviewClient(private val context: Context) {

    private val licenseManager = LicenseManager(context)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    data class TrackInfo(
        val id: String,
        val name: String,
        val artist: String,
        val albumArt: String? = null,
        val isrc: String? = null,
        val previewUrl: String? = null,
        val durationSec: Long? = null,
        /** Waar de preview vandaan komt: "deezer" (30 s, 128 kbps) of "spotify" (~30 s, 96 kbps). */
        val previewSource: String? = null
    )

    data class DownloadResult(
        val success: Boolean,
        val file: File? = null,
        val trackInfo: TrackInfo? = null,
        val error: String? = null,
        val fileExists: Boolean = false
    )

    /** Laatste foutmelding van [fetchTrackInfo], voor de snackbar. */
    var lastError: String? = null
        private set

    companion object {
        /** Zuiver (zonder netwerk): metadata + preview + hoes uit de HTML van open.spotify.com/embed/track/{id}. */
        fun parseEmbedStatic(html: String): TrackInfo? {
            val m = Regex("<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
                .find(html) ?: return null
            val e = JSONObject(m.groupValues[1]).optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("state")
                ?.optJSONObject("data")?.optJSONObject("entity") ?: return null
            val artists = e.optJSONArray("artists")
            val artist = (0 until (artists?.length() ?: 0)).joinToString(", ") { artists!!.getJSONObject(it).optString("name") }
            val images = e.optJSONObject("visualIdentity")?.optJSONArray("image")
            var cover: String? = null; var best = 0
            for (i in 0 until (images?.length() ?: 0)) {
                val img = images!!.getJSONObject(i); val w = img.optInt("maxWidth", 0)
                if (cover == null || w > best) { cover = img.optString("url"); best = w }
        }
        return TrackInfo(
            id = e.optString("id"),
            name = e.optString("name", e.optString("title")),
            artist = artist,
            albumArt = cover?.ifBlank { null },
            previewUrl = e.optJSONObject("audioPreview")?.optString("url")?.ifBlank { null },
            durationSec = e.optLong("duration", 0).takeIf { it > 0 }?.let { it / 1000 }
        )
        }

        private val TRACK_ID = Regex("(?:open\\.spotify\\.com/(?:intl-[a-z]{2}/)?track/|spotify:track:)([A-Za-z0-9]{22})")

        fun trackId(url: String): String? = TRACK_ID.find(url)?.groupValues?.get(1)
    }

    /**
     * v2.3.0 — bron zonder sleutels: de openbare Spotify-embed (`open.spotify.com/embed/track/{id}`) bevat titel,
     * artiesten, duur, hoes en een preview-URL (29-09 gemeten: 4/4 tracks). Daarna een Deezer-match op
     * artiest + titel + duur (±3 s) voor de betere 30-s-preview; geen match ⇒ Spotify's eigen preview.
     */
    suspend fun fetchTrackInfoEmbed(spotifyUrl: String): TrackInfo? = withContext(Dispatchers.IO) {
        lastError = null
        val id = trackId(spotifyUrl) ?: run { lastError = "Geen geldige Spotify-tracklink"; return@withContext null }
        try {
            RemoteLogger.input("SpotifyEmbed", "fetchTrackInfo", mapOf("id" to id))
            val html = client.newCall(Request.Builder().url("https://open.spotify.com/embed/track/$id")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131 Mobile Safari/537.36")
                .build()).execute().use { r ->
                if (!r.isSuccessful) { lastError = "Spotify-embed gaf ${r.code}"; return@withContext null }
                r.body?.string().orEmpty()
            }
            val meta = parseEmbedStatic(html) ?: run { lastError = "Spotify-embed onleesbaar (formaat gewijzigd?)"; return@withContext null }
            val dz = deezerMatch(meta.artist, meta.name, meta.durationSec)
            val info = meta.copy(id = id,
                previewUrl = dz?.preview ?: meta.previewUrl,
                previewSource = if (dz != null) "deezer" else if (meta.previewUrl != null) "spotify" else null)
            if (info.previewUrl == null) lastError = "Geen preview voor deze track (Deezer en Spotify) — kies 'Volledig nummer (via YouTube)'"
            RemoteLogger.output("SpotifyEmbed", "Track info", mapOf("track" to "${info.artist} - ${info.name}",
                "duration" to "${info.durationSec}", "preview" to (info.previewSource ?: "geen")))
            info
        } catch (e: Exception) {
            lastError = "Spotify niet bereikbaar (${e.message ?: "?"})"
            null
        }
    }

    private data class DeezerHit(val preview: String, val durationSec: Long)

    /** Deezer-zoek: eerst strikt (artist:/track:), dan vrij; alleen treffer met passende duur (±3 s) en titel. */
    private fun deezerMatch(artist: String, title: String, durationSec: Long?): DeezerHit? {
        val first = artist.substringBefore(",").trim()
        val cleanTitle = title.replace(Regex("\\s*[-(].*(remaster|version|edit|live).*$", RegexOption.IGNORE_CASE), "").trim()
        fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
        val queries = listOf("artist:\"$first\" track:\"$cleanTitle\"", "$first $cleanTitle")
        for (q in queries) {
            val url = "https://api.deezer.com/search?limit=10&q=" + java.net.URLEncoder.encode(q, "UTF-8")
            val body = try { client.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string() } } catch (_: Exception) { null }
                ?: continue
            val data = JSONObject(body).optJSONArray("data") ?: continue
            for (i in 0 until data.length()) {
                val t = data.getJSONObject(i)
                val prev = t.optString("preview"); if (prev.isBlank()) continue
                val d = t.optLong("duration", 0)
                val durOk = durationSec == null || d == 0L || kotlin.math.abs(d - durationSec) <= 3
                val titleOk = norm(t.optString("title")).contains(norm(cleanTitle)) || norm(cleanTitle).contains(norm(t.optString("title_short")))
                val artistOk = norm(t.optJSONObject("artist")?.optString("name") ?: "").let { a -> a.isNotEmpty() && (norm(first).contains(a) || a.contains(norm(first))) }
                if (durOk && titleOk && artistOk) return DeezerHit(prev, d)
            }
        }
        return null
    }

    suspend fun fetchTrackInfo(spotifyUrl: String): TrackInfo? = withContext(Dispatchers.IO) {
        lastError = null
        val id = trackId(spotifyUrl) ?: run { lastError = "Geen geldige Spotify-tracklink"; return@withContext null }
        if (licenseManager.deviceToken == null && licenseManager.ensureDeviceToken() != LicenseManager.TokenState.OK) {
            lastError = "Toestel niet gekoppeld (Instellingen → Licentie → Controleer koppeling)"
            return@withContext null
        }
        try {
            RemoteLogger.input("SpotifyPreview", "fetchTrackInfo", mapOf("id" to id))
            val req = Request.Builder().url("${LicenseManager.BACKEND_BASE}/spotify/track/$id")
                .header("X-Device-Id", licenseManager.deviceHash)
                .header("X-Device-Token", licenseManager.deviceToken ?: "")
                .get().build()
            client.newCall(req).execute().use { r ->
                val json = JSONObject(r.body?.string() ?: "{}")
                if (!r.isSuccessful) {
                    lastError = json.optString("error", "Server gaf ${r.code}")
                    RemoteLogger.w("SpotifyPreview", "Backend fout", mapOf("code" to r.code.toString(), "error" to (lastError ?: "")))
                    return@withContext null
                }
                val dz = json.optJSONObject("deezer")
                val info = TrackInfo(
                    id = id,
                    name = json.optString("title", ""),
                    artist = json.optString("artist", ""),
                    albumArt = json.optString("coverUrl", "").ifBlank { null },
                    isrc = json.optString("isrc", "").ifBlank { null },
                    previewUrl = dz?.optString("preview", "")?.ifBlank { null }
                )
                if (info.previewUrl == null) {
                    lastError = if (info.isrc == null) "Spotify geeft voor deze track geen ISRC — gebruik de WebView-converter"
                        else "Geen Deezer-preview voor deze track — gebruik de WebView-converter"
                }
                RemoteLogger.output("SpotifyPreview", "Track info", mapOf("isrc" to (info.isrc ?: "-"),
                    "preview" to (info.previewUrl != null).toString()))
                info
            }
        } catch (e: Exception) {
            lastError = "Geen verbinding met de server (${e.message ?: "?"})"
            null
        }
    }

    suspend fun downloadTrack(
        spotifyUrl: String,
        destDir: File,
        onProgress: (phase: String, progress: Float) -> Unit,
        forceOverwrite: Boolean = false,
        useBackend: Boolean = false
    ): DownloadResult = withContext(Dispatchers.IO) {
        try {
            onProgress("Track info ophalen...", 0.2f)
            val info = (if (useBackend) fetchTrackInfo(spotifyUrl) else fetchTrackInfoEmbed(spotifyUrl))
                ?: return@withContext DownloadResult(false, error = lastError ?: "Track niet gevonden")
            val preview = info.previewUrl
                ?: return@withContext DownloadResult(false, trackInfo = info, error = lastError)

            val sanitizedName = "${info.name}-${info.artist}"
                .replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
                .replace(Regex("_+"), "_")
                .trim('_')
            val destFile = File(destDir, "spotify_mp3_${sanitizedName}.mp3")
            if (destFile.exists() && !forceOverwrite) {
                return@withContext DownloadResult(success = false, file = destFile, trackInfo = info, fileExists = true)
            }

            onProgress("Preview downloaden (${if (info.previewSource == "spotify") "Spotify" else "Deezer"}, 30 s)...", 0.6f)
            val tmp = File(destDir, ".${destFile.name}.part")
            client.newCall(Request.Builder().url(preview).get().build()).execute().use { r ->
                if (!r.isSuccessful) throw Exception("Preview-download mislukt (${r.code})")
                destDir.mkdirs()
                r.body?.byteStream()?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    ?: throw Exception("Lege preview")
            }
            if (!tmp.renameTo(destFile)) { tmp.copyTo(destFile, overwrite = true); tmp.delete() }

            info.albumArt?.let { art ->
                onProgress("Albumhoes toevoegen...", 0.9f)
                try {
                    client.newCall(Request.Builder().url(art).get().build()).execute().use { r ->
                        r.body?.bytes()?.takeIf { r.isSuccessful }?.let { Mp3AlbumArt.write(destFile, it, info.name, info.artist) }
                    }
                    Mp3Marker.injectIfMissing(destFile, info.name, info.artist)
                } catch (_: Exception) { /* best-effort */ }
            }
            onProgress("Klaar!", 1.0f)
            RemoteLogger.output("SpotifyPreview", "Download KLAAR", mapOf("file" to destFile.name, "size" to "${destFile.length() / 1024}KB"))
            DownloadResult(success = true, file = destFile, trackInfo = info)
        } catch (e: Exception) {
            RemoteLogger.e("SpotifyPreview", "Download FAILED", mapOf("error" to (e.message ?: "unknown")))
            DownloadResult(false, error = "Spotify-preview mislukt: ${e.message}")
        }
    }

    /**
     * v2.3.0 — volledig nummer: Spotify-embed (titel/artiest/duur/hoes) → YouTube-zoek op het toestel, treffer met
     * dezelfde duur → audio (M4A) → `spotify_mp3_<Titel>-<Artiest>.m4a` met Spotify-hoes en "track"-marker.
     */
    suspend fun downloadFull(
        spotifyUrl: String,
        destDir: File,
        onProgress: (phase: String, progress: Float) -> Unit,
        forceOverwrite: Boolean = false
    ): DownloadResult = withContext(Dispatchers.IO) {
        onProgress("Track info ophalen (Spotify)...", 0.05f)
        val info = fetchTrackInfoEmbed(spotifyUrl)
            ?: return@withContext DownloadResult(false, error = lastError ?: "Track niet gevonden")
        onProgress("Zoeken op YouTube...", 0.1f)
        val hit = try { YouTubeOnDevice.bestMatch(info.artist.substringBefore(","), info.name, info.durationSec) } catch (e: Exception) {
            return@withContext DownloadResult(false, trackInfo = info, error = "YouTube-zoeken mislukt: ${e.message}")
        } ?: return@withContext DownloadResult(false, trackInfo = info, error = "Niet gevonden op YouTube")
        val durDiff = if (info.durationSec != null && hit.durationSec > 0) kotlin.math.abs(hit.durationSec - info.durationSec) else 0
        RemoteLogger.i("SpotifyFull", "YouTube-treffer", mapOf("track" to "${info.artist} - ${info.name}",
            "video" to "${hit.title} (${hit.uploader})", "durDiff" to "$durDiff"))
        val sanitized = "${info.name}-${info.artist.substringBefore(",")}"
            .replace(Regex("[^a-zA-Z0-9_\\-]"), "_").replace(Regex("_+"), "_").trim('_')
        val cover = info.albumArt?.let { fetchBytes(it) }
        val r = YouTubeOnDevice.downloadAudio(hit.videoId, destDir, prefix = "spotify_mp3_", baseName = sanitized,
            displayTitle = info.name, displayArtist = info.artist, coverJpeg = cover, forceOverwrite = forceOverwrite,
            onProgress = { ph, p -> onProgress(ph, 0.1f + 0.9f * p) })
        DownloadResult(r.success, file = r.file, trackInfo = info.copy(previewSource = "youtube:${hit.videoId}"),
            error = r.error, fileExists = r.fileExists)
    }

    /** Download van [url] (bv. hoes) als bytes, best-effort. */
    fun fetchBytes(url: String): ByteArray? = try {
        client.newCall(Request.Builder().url(url).build()).execute().use { r -> r.body?.bytes()?.takeIf { r.isSuccessful } }
    } catch (_: Exception) { null }

    /** Zelftest voor Instellingen. */
    suspend fun selfTestEmbed(): String {
        val i = fetchTrackInfoEmbed("https://open.spotify.com/track/2374M0fQpWi3dLnB54qaLX") ?: return "✗ ${lastError ?: "mislukt"}"
        return if (i.previewUrl != null) "✓ Werkt — ${i.artist} - ${i.name} (preview via ${i.previewSource})" else "✗ ${lastError}"
    }

    suspend fun selfTestBackend(): String {
        val i = fetchTrackInfo("https://open.spotify.com/track/2374M0fQpWi3dLnB54qaLX") ?: return "✗ ${lastError ?: "mislukt"}"
        return if (i.previewUrl != null) "✓ Werkt — ${i.artist} - ${i.name} (ISRC ${i.isrc})" else "✗ ${lastError}"
    }
}
