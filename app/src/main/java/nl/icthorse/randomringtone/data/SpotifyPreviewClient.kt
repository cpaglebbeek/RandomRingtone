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
        val previewUrl: String? = null
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
        private val TRACK_ID = Regex("(?:open\\.spotify\\.com/(?:intl-[a-z]{2}/)?track/|spotify:track:)([A-Za-z0-9]{22})")

        fun trackId(url: String): String? = TRACK_ID.find(url)?.groupValues?.get(1)
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
        forceOverwrite: Boolean = false
    ): DownloadResult = withContext(Dispatchers.IO) {
        try {
            onProgress("Track info ophalen...", 0.2f)
            val info = fetchTrackInfo(spotifyUrl)
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

            onProgress("Preview downloaden (30 s)...", 0.6f)
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
}
