package nl.icthorse.randomringtone.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import java.io.File

/**
 * API client voor Y2Mate (y2mate.sc → etacloud.org).
 *
 * Flow:
 * 1. GET y2mate.sc → parse auth key uit inline JSON
 * 2. GET /api/v1/init → convertURL
 * 3. GET convertURL?v={videoId}&f=mp3 → progressURL + downloadURL (met redirect)
 * 4. GET progressURL → poll tot progress == 3
 * 5. GET downloadURL → MP3 bestand
 *
 * Geen CAPTCHA/Turnstile vereist.
 */
class Y2MateClient {

    companion object {
        private const val SITE_URL = "https://y2mate.sc"
        private const val MAX_POLL_ATTEMPTS = 60
        private const val POLL_INTERVAL_MS = 3000L
        private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    data class DownloadResult(
        val success: Boolean,
        val file: File? = null,
        val title: String? = null,
        val error: String? = null,
        val fileExists: Boolean = false
    )

    /**
     * Volledige flow: auth → init → convert → progress → download.
     * @param videoId YouTube video ID (bijv. "dQw4w9WgXcQ")
     * @param destDir map om het MP3 bestand op te slaan
     * @param videoTitle optionele titel voor bestandsnaam (uit WebView)
     * @param onProgress callback voor voortgang
     * @param forceOverwrite overschrijf bestaand bestand
     */
    suspend fun downloadTrack(
        videoId: String,
        destDir: File,
        videoTitle: String? = null,
        onProgress: (phase: String, progress: Float) -> Unit,
        forceOverwrite: Boolean = false
    ): DownloadResult = withContext(Dispatchers.IO) {
        try {
            RemoteLogger.input("Y2Mate", "downloadTrack gestart", mapOf("videoId" to videoId))

            // Stap 1: Auth key ophalen
            onProgress("Verbinden met Y2Mate...", 0.05f)
            val authKey = fetchAuthKey()
                ?: return@withContext DownloadResult(false, error = "Auth key niet gevonden")
            RemoteLogger.d("Y2Mate", "Auth key opgehaald: ${authKey.take(8)}...")

            // Stap 2: Init → convertURL
            onProgress("Conversie starten...", 0.1f)
            val convertUrl = fetchConvertUrl(authKey)
                ?: return@withContext DownloadResult(false, error = "Init mislukt")

            // Stap 3: Convert → progressURL + downloadURL
            onProgress("Video verwerken...", 0.2f)
            val convertResult = convert(convertUrl, videoId, "mp3")
                ?: return@withContext DownloadResult(false, error = "Conversie mislukt")

            // Titel: API title heeft voorrang (juiste naam van YouTube)
            val title = convertResult.title

            // Bestandsnaam: gebruik API title, fallback naar WebView title, dan videoId
            val rawName = title?.takeIf { it.isNotBlank() } ?: videoTitle ?: videoId
            val sanitizedName = sanitizeFileName(rawName)
            val destFile = File(destDir, "youtube_mp3_${sanitizedName}.mp3")

            if (destFile.exists() && !forceOverwrite) {
                return@withContext DownloadResult(
                    success = false, file = destFile, title = title, fileExists = true
                )
            }

            // Stap 4: Poll progress
            if (convertResult.progressUrl.isNotBlank()) {
                onProgress("Converteren...", 0.3f)
                val completed = pollProgress(convertResult.progressUrl, onProgress)
                if (!completed) {
                    return@withContext DownloadResult(false, title = title, error = "Conversie timeout")
                }
            }

            // Stap 5: Download MP3
            onProgress("MP3 downloaden...", 0.85f)
            val cdFilename = downloadFile(convertResult.downloadUrl, videoId, destFile)

            // Als Content-Disposition een betere naam geeft, hernoem het bestand
            val finalTitle = cdFilename?.takeIf { it.isNotBlank() } ?: title
            var finalFile = destFile
            if (cdFilename != null && cdFilename.isNotBlank()) {
                val cdSanitized = sanitizeFileName(cdFilename)
                val betterFile = File(destDir, "youtube_mp3_${cdSanitized}.mp3")
                if (betterFile.absolutePath != destFile.absolutePath && !betterFile.exists()) {
                    if (destFile.renameTo(betterFile)) {
                        finalFile = betterFile
                    }
                }
            }

            // Stap 6: Injecteer YouTube marker
            Mp3Marker.writeYouTubeMarker(finalFile, finalTitle, null)

            onProgress("Klaar!", 1.0f)
            RemoteLogger.output("Y2Mate", "Download KLAAR", mapOf(
                "file" to finalFile.name,
                "size" to "${finalFile.length() / 1024}KB",
                "title" to (finalTitle ?: "?")
            ))
            DownloadResult(success = true, file = finalFile, title = finalTitle)

        } catch (e: Exception) {
            RemoteLogger.e("Y2Mate", "Download FAILED", mapOf(
                "error" to (e.message ?: "unknown"), "videoId" to videoId
            ))
            DownloadResult(false, error = "Y2Mate fout: ${e.message}")
        }
    }

    /**
     * Stap 1: Haal auth key op door y2mate.sc HTML te parsen.
     * De pagina bevat: var json = JSON.parse('[[codes],reverse,[offsets],...]');
     * Auth = decodeer codes met offsets.
     */
    private fun fetchAuthKey(): String? {
        val request = Request.Builder()
            .url("$SITE_URL/")
            .header("User-Agent", UA)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val html = response.body?.string() ?: return null

            // Parse: var json = JSON.parse('...');
            val jsonMatch = Regex("""JSON\.parse\('(\[\[.+?\])'\)""").find(html) ?: return null
            val jsonStr = jsonMatch.groupValues[1]
            val arr = json.parseToJsonElement(jsonStr).jsonArray

            val codes = arr[0].jsonArray.map { it.jsonPrimitive.int }
            val reverse = arr[1].jsonPrimitive.int
            val offsets = arr[2].jsonArray.map { it.jsonPrimitive.int }

            val auth = buildString {
                for (t in codes.indices) {
                    append((codes[t] - offsets[offsets.size - (t + 1)]).toChar())
                }
            }

            return if (reverse == 1) auth.reversed() else auth
        }
    }

    /**
     * Stap 2: Init request → retourneert convertURL.
     */
    private fun fetchConvertUrl(authKey: String): String? {
        val timestamp = System.currentTimeMillis() / 1000
        val request = Request.Builder()
            .url("https://eta.etacloud.org/api/v1/init?r=$authKey&t=$timestamp")
            .header("User-Agent", UA)
            .header("Referer", "$SITE_URL/")
            .header("Origin", SITE_URL)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val obj = json.parseToJsonElement(body).jsonObject
            if (obj["error"]?.jsonPrimitive?.contentOrNull != "0") return null
            return obj["convertURL"]?.jsonPrimitive?.contentOrNull
        }
    }

    private data class ConvertResult(
        val progressUrl: String,
        val downloadUrl: String,
        val title: String?
    )

    /**
     * Stap 3: Convert request. Kan een redirect bevatten (redirect:1).
     */
    private fun convert(convertUrl: String, videoId: String, format: String): ConvertResult? {
        val timestamp = System.currentTimeMillis() / 1000
        var url = "${convertUrl}&v=$videoId&f=$format&t=$timestamp"

        // Max 3 redirect levels
        for (i in 0..2) {
            val result = doConvertRequest(url)
            when {
                result == null -> return null
                result.isRedirect -> url = result.redirectUrl ?: return null
                else -> return ConvertResult(result.progressUrl ?: "", result.downloadUrl ?: return null, result.title)
            }
        }
        return null
    }

    private data class ConvertResponse(
        val isRedirect: Boolean = false,
        val redirectUrl: String? = null,
        val progressUrl: String? = null,
        val downloadUrl: String? = null,
        val title: String? = null
    )

    private fun doConvertRequest(url: String): ConvertResponse? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", "$SITE_URL/")
            .header("Origin", SITE_URL)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val obj = json.parseToJsonElement(body).jsonObject

            val error = obj["error"]?.jsonPrimitive?.intOrNull ?: 0
            if (error > 0) return null

            val redirect = obj["redirect"]?.jsonPrimitive?.intOrNull ?: 0
            if (redirect == 1) {
                val redirectUrl = obj["redirectURL"]?.jsonPrimitive?.contentOrNull ?: return null
                return ConvertResponse(isRedirect = true, redirectUrl = redirectUrl)
            }

            return ConvertResponse(
                progressUrl = obj["progressURL"]?.jsonPrimitive?.contentOrNull ?: "",
                downloadUrl = obj["downloadURL"]?.jsonPrimitive?.contentOrNull,
                title = obj["title"]?.jsonPrimitive?.contentOrNull
            )
        }
    }

    /**
     * Stap 4: Poll progress tot progress == 3 (completed).
     * progress: 0 = verifying, 1 = extracting, 2 = converting, 3 = completed
     */
    private suspend fun pollProgress(
        progressUrl: String,
        onProgress: (phase: String, progress: Float) -> Unit
    ): Boolean {
        val phases = arrayOf("Video verifiëren...", "Audio extraheren...", "Converteren naar MP3...")

        for (attempt in 0 until MAX_POLL_ATTEMPTS) {
            delay(POLL_INTERVAL_MS)

            val uiProgress = 0.3f + (attempt.toFloat() / MAX_POLL_ATTEMPTS) * 0.5f
            val timestamp = System.currentTimeMillis() / 1000

            val request = Request.Builder()
                .url("$progressUrl&t=$timestamp")
                .header("User-Agent", UA)
                .header("Referer", "$SITE_URL/")
                .header("Origin", SITE_URL)
                .get()
                .build()

            try {
                val responseBody = withContext(Dispatchers.IO) {
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use null
                        response.body?.string()
                    }
                } ?: continue

                val obj = json.parseToJsonElement(responseBody).jsonObject
                val error = obj["error"]?.jsonPrimitive?.intOrNull ?: 0
                if (error > 0) return false

                val progress = obj["progress"]?.jsonPrimitive?.intOrNull ?: 0
                val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: ""

                val phase = if (progress in phases.indices) phases[progress] else "Bezig..."
                onProgress("$phase ${if (title.isNotBlank()) "\n$title" else ""}", uiProgress)

                if (progress >= 3) return true
            } catch (_: Exception) {
                continue
            }
        }
        return false
    }

    /**
     * Stap 5: Download MP3 bestand.
     * Retourneert de filename uit Content-Disposition header (zonder extensie), of null.
     */
    private fun downloadFile(downloadUrl: String, videoId: String, destFile: File): String? {
        val url = if (downloadUrl.contains("&s=")) downloadUrl
            else "$downloadUrl&s=3&v=$videoId&f=mp3"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", "$SITE_URL/")
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

            // Extract filename uit Content-Disposition header
            val disposition = response.header("Content-Disposition") ?: return null
            val match = Regex("""filename="?(.+?)(?:\.mp3)?"?$""").find(disposition)
            return match?.groupValues?.get(1)
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.trim()
            .replace(Regex("[/\\\\:*?\"<>|]"), "-")  // Illegale bestandstekens → -
            .replace(Regex("\\s+"), "_")               // Spaties → _
            .replace(Regex("[_-]{2,}"), "_")            // Dubbele separators opruimen
            .trim('_', '-')
            .take(120)
    }
}
