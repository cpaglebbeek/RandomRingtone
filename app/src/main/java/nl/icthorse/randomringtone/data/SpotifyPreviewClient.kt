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
 * Spotify-bron (v2.6.0): alleen nog het volledige nummer.
 *
 * Flow: Spotify-link → openbare embed-pagina (titel, artiest, duur, hoes) → zoeken op YouTube met controle op
 * titel, artiest en duur → audio (M4A) via [YouTubeDownload]. De previews (Deezer, Spotify, backend met ISRC) en de
 * converter-websites zijn in v2.6.0 verwijderd.
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
        val durationSec: Long? = null,
        /** Waar de audio vandaan komt: "youtube:<videoId>" na een download. */
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
        /** Zuiver (zonder netwerk): metadata + hoes uit de HTML van open.spotify.com/embed/track/{id}. */
        fun parseEmbedStatic(html: String): TrackInfo? {
            val m = Regex("<script id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
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
            durationSec = e.optLong("duration", 0).takeIf { it > 0 }?.let { it / 1000 }
        )
        }

        private val TRACK_ID = Regex("(?:open\\.spotify\\.com/(?:intl-[a-z]{2}/)?track/|spotify:track:)([A-Za-z0-9]{22})")

        fun trackId(url: String): String? = TRACK_ID.find(url)?.groupValues?.get(1)
    }

    /** Alleen de metadata uit de embed-pagina, zonder foutmelding; null bij een fout. */
    suspend fun fetchEmbedMeta(trackId: String): TrackInfo? = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url("https://open.spotify.com/embed/track/$trackId")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131 Mobile Safari/537.36")
                .build()).execute().use { r ->
                if (!r.isSuccessful) null else parseEmbedStatic(r.body?.string().orEmpty())?.copy(id = trackId)
            }
        } catch (_: Exception) { null }
    }

    /**
     * Metadata uit de openbare Spotify-embed (`open.spotify.com/embed/track/{id}`): titel, artiesten, duur en hoes.
     * Zet [lastError] bij een fout. v2.6.0: geen preview en geen Deezer meer.
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
            val info = meta.copy(id = id)
            RemoteLogger.output("SpotifyEmbed", "Track info", mapOf("track" to "${info.artist} - ${info.name}",
                "duration" to "${info.durationSec}"))
            info
        } catch (e: Exception) {
            lastError = "Spotify niet bereikbaar (${e.message ?: "?"})"
            null
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
        downloadFullInfo(info, destDir, onProgress, forceOverwrite, strict = false)
    }

    /**
     * Volledig nummer bij bekende metadata (v2.5.0; ook voor import van playlist/album).
     * [strict] = alleen een treffer waarbij titel, artiest en duur kloppen ([YouTubeOnDevice.strictMatch]); anders eerst
     * strikt en daarna de ruimere keuze op duur. [album]/[trackNo]/[trackTotal] gaan mee in de tags.
     */
    suspend fun downloadFullInfo(
        info: TrackInfo,
        destDir: File,
        onProgress: (phase: String, progress: Float) -> Unit,
        forceOverwrite: Boolean = false,
        strict: Boolean = true,
        album: String? = null,
        trackNo: Int? = null,
        trackTotal: Int? = null,
        coverJpeg: ByteArray? = null
    ): DownloadResult = withContext(Dispatchers.IO) {
        onProgress("Zoeken op YouTube...", 0.1f)
        val hit = try {
            YouTubeOnDevice.strictMatch(info.artist, info.name, info.durationSec)
                ?: if (strict) null else YouTubeOnDevice.bestMatch(info.artist.substringBefore(","), info.name, info.durationSec)
        } catch (e: Exception) {
            return@withContext DownloadResult(false, trackInfo = info, error = "YouTube-zoeken mislukt: ${e.message}")
        } ?: return@withContext DownloadResult(false, trackInfo = info,
            error = if (strict) "Geen passende video op YouTube (titel, artiest en duur moeten kloppen)" else "Niet gevonden op YouTube")
        val durDiff = if (info.durationSec != null && hit.durationSec > 0) kotlin.math.abs(hit.durationSec - info.durationSec) else 0
        RemoteLogger.i("SpotifyFull", "YouTube-treffer", mapOf("track" to "${info.artist} - ${info.name}",
            "video" to "${hit.title} (${hit.uploader})", "durDiff" to "$durDiff", "strict" to "$strict"))
        val sanitized = "${info.name}-${info.artist.substringBefore(",")}"
            .replace(Regex("[^a-zA-Z0-9_\\-]"), "_").replace(Regex("_+"), "_").trim('_')
        val cover = coverJpeg ?: info.albumArt?.let { fetchBytes(it) }
        val r = YouTubeDownload.audio(licenseManager, StorageManager(context).getYouTubeMethod(), hit.videoId, destDir,
            prefix = "spotify_mp3_", baseName = sanitized,
            displayTitle = info.name, displayArtist = info.artist, coverJpeg = cover, forceOverwrite = forceOverwrite,
            onProgress = { ph, p -> onProgress(ph, 0.1f + 0.9f * p) },
            album = album, trackNo = trackNo, trackTotal = trackTotal)
        DownloadResult(r.success, file = r.file, trackInfo = info.copy(previewSource = "youtube:${hit.videoId}"),
            error = r.error, fileExists = r.fileExists)
    }


    /** Download van [url] (bv. hoes) als bytes, best-effort. */
    fun fetchBytes(url: String): ByteArray? = try {
        client.newCall(Request.Builder().url(url).build()).execute().use { r -> r.body?.bytes()?.takeIf { r.isSuccessful } }
    } catch (_: Exception) { null }

}
