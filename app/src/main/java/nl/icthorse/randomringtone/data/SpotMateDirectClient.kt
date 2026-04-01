package nl.icthorse.randomringtone.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/**
 * Directe API client voor SpotMate (spotmate.online).
 *
 * Flow:
 * 1. GET /en1 → CSRF token + session cookies
 * 2. POST /getTrackData → track metadata (titel, artiest)
 * 3. POST /convert → MP3 download URL (of task_id → polling)
 * 4. Download MP3 → lokaal bestand
 */
class SpotMateDirectClient {

    companion object {
        private const val BASE_URL = "https://spotmate.online"
        private const val MAX_POLL_ATTEMPTS = 40
        private const val POLL_INTERVAL_MS = 4500L
    }

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private var csrfToken: String? = null
    private var sessionCookies: String? = null

    data class TrackInfo(
        val id: String,
        val name: String,
        val artist: String,
        val albumArt: String? = null
    )

    data class DownloadResult(
        val success: Boolean,
        val file: File? = null,
        val trackInfo: TrackInfo? = null,
        val error: String? = null,
        val fileExists: Boolean = false
    )

    /**
     * Volledige flow: haal CSRF, metadata, convert, download.
     * @param spotifyUrl bijv. "https://open.spotify.com/track/52LJ3hyknOijCrE5gCD0rE"
     * @param destDir map om het MP3 bestand op te slaan
     * @param onProgress callback voor voortgang
     */
    suspend fun downloadTrack(
        spotifyUrl: String,
        destDir: File,
        onProgress: (phase: String, progress: Float) -> Unit,
        forceOverwrite: Boolean = false
    ): DownloadResult = withContext(Dispatchers.IO) {
        try {
            // Stap 1: CSRF token ophalen
            onProgress("Verbinden met SpotMate...", 0.1f)
            fetchCsrfToken()

            // Stap 2: Track metadata ophalen
            onProgress("Track info ophalen...", 0.2f)
            val trackInfo = getTrackData(spotifyUrl)
                ?: return@withContext DownloadResult(false, error = "Track niet gevonden")

            // Stap 3: Check of bestand al bestaat
            val sanitizedName = "${trackInfo.name}-${trackInfo.artist}"
                .replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
                .replace(Regex("_+"), "_")
                .trim('_')
            val destFile = File(destDir, "spotify_mp3_${sanitizedName}.mp3")

            if (destFile.exists() && !forceOverwrite) {
                return@withContext DownloadResult(
                    success = false, file = destFile, trackInfo = trackInfo, fileExists = true
                )
            }

            // Stap 4: Converteren naar MP3
            onProgress("Converteren naar MP3...", 0.4f)
            val downloadUrl = convertTrack(spotifyUrl, onProgress)
                ?: return@withContext DownloadResult(false, trackInfo = trackInfo, error = "Conversie mislukt")

            // Stap 5: MP3 downloaden
            onProgress("MP3 downloaden...", 0.8f)
            downloadFile(downloadUrl, destFile)

            onProgress("Klaar!", 1.0f)
            DownloadResult(success = true, file = destFile, trackInfo = trackInfo)

        } catch (e: Exception) {
            DownloadResult(false, error = "SpotMate fout: ${e.message}")
        }
    }

    /**
     * Stap 1: Haal CSRF token en session cookies op via GET /en1
     */
    private fun fetchCsrfToken() {
        val request = Request.Builder()
            .url("$BASE_URL/en1")
            .header("User-Agent", UA)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("SpotMate niet bereikbaar (${response.code})")

            // Session cookies opslaan
            sessionCookies = response.headers("Set-Cookie")
                .mapNotNull { it.split(";").firstOrNull() }
                .joinToString("; ")

            // CSRF token uit HTML meta tag parsen
            val html = response.body?.string() ?: ""
            val csrfMatch = Regex("""<meta\s+name="csrf-token"\s+content="([^"]+)"""").find(html)
            csrfToken = csrfMatch?.groupValues?.get(1)
                ?: throw Exception("CSRF token niet gevonden")
        }
    }

    /**
     * Stap 2: Haal track metadata op via POST /getTrackData
     */
    private fun getTrackData(spotifyUrl: String): TrackInfo? {
        val body = JsonObject(mapOf("spotify_url" to JsonPrimitive(spotifyUrl)))
            .toString()
            .toRequestBody("application/json".toMediaType())

        val request = apiRequest("$BASE_URL/getTrackData")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: return null
            if (!response.isSuccessful) return null

            val obj = json.parseToJsonElement(responseBody).jsonObject
            val type = obj["type"]?.jsonPrimitive?.contentOrNull

            if (type != "track") return null

            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "Onbekend"
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: ""
            val artists = obj["artists"]?.jsonArray
            val artist = artists?.firstOrNull()?.jsonObject?.get("name")
                ?.jsonPrimitive?.contentOrNull ?: "Onbekend"
            val albumArt = obj["album"]?.jsonObject?.get("images")
                ?.jsonArray?.firstOrNull()?.jsonObject?.get("url")
                ?.jsonPrimitive?.contentOrNull

            return TrackInfo(id = id, name = name, artist = artist, albumArt = albumArt)
        }
    }

    /**
     * Stap 3: Converteer track naar MP3 via POST /convert
     * Retourneert de download URL, of null bij fout.
     */
    private suspend fun convertTrack(
        spotifyUrl: String,
        onProgress: (phase: String, progress: Float) -> Unit
    ): String? {
        val body = JsonObject(mapOf("urls" to JsonPrimitive(spotifyUrl)))
            .toString()
            .toRequestBody("application/json".toMediaType())

        val request = apiRequest("$BASE_URL/convert")
            .post(body)
            .build()

        val responseBody = withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                response.body?.string()
            }
        } ?: return null

        val obj = json.parseToJsonElement(responseBody).jsonObject

        // Direct URL beschikbaar
        val directUrl = obj["url"]?.jsonPrimitive?.contentOrNull
        if (directUrl != null && obj["error"]?.jsonPrimitive?.booleanOrNull != true) {
            return directUrl
        }

        // Task-based: polling nodig
        val taskId = obj["task_id"]?.jsonPrimitive?.contentOrNull ?: return null
        return pollTask(taskId, onProgress)
    }

    /**
     * Poll task status totdat conversie klaar is.
     */
    private suspend fun pollTask(
        taskId: String,
        onProgress: (phase: String, progress: Float) -> Unit
    ): String? {
        for (attempt in 0 until MAX_POLL_ATTEMPTS) {
            delay(POLL_INTERVAL_MS)

            val progress = 0.4f + (attempt.toFloat() / MAX_POLL_ATTEMPTS) * 0.35f
            onProgress("Conversie bezig... (${attempt + 1}/$MAX_POLL_ATTEMPTS)", progress)

            val request = apiRequest("$BASE_URL/tasks/$taskId")
                .get()
                .build()

            val responseBody = withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    response.body?.string()
                }
            } ?: continue

            val obj = json.parseToJsonElement(responseBody).jsonObject
            val data = obj["data"]?.jsonObject ?: continue
            val status = data["status"]?.jsonPrimitive?.contentOrNull

            when (status) {
                "finished" -> {
                    return data["url"]?.jsonPrimitive?.contentOrNull
                        ?: data["download_url"]?.jsonPrimitive?.contentOrNull
                }
                "failed" -> return null
            }
        }
        return null // Timeout
    }

    /**
     * Download MP3 bestand naar lokaal pad.
     */
    private fun downloadFile(url: String, destFile: File) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Download mislukt (${response.code})")
            destFile.parentFile?.mkdirs()
            response.body?.byteStream()?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw Exception("Lege response body")
        }
    }

    private fun apiRequest(url: String): Request.Builder {
        return Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .header("X-CSRF-TOKEN", csrfToken ?: "")
            .header("Referer", "$BASE_URL/en1")
            .apply {
                if (sessionCookies != null) {
                    header("Cookie", sessionCookies!!)
                }
            }
    }
}

private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
