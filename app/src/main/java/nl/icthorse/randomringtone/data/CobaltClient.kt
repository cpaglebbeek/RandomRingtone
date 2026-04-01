package nl.icthorse.randomringtone.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/**
 * API client voor Cobalt (cobalt.tools) — YouTube/YouTube Music → MP3.
 *
 * Flow:
 * 1. POST / met YouTube URL → tunnel/redirect URL + bestandsnaam
 * 2. Download MP3 van die URL
 */
class CobaltClient {

    companion object {
        const val DEFAULT_INSTANCE = "https://cobalt.meowing.de/"
    }

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    data class CobaltResult(
        val success: Boolean,
        val file: File? = null,
        val filename: String? = null,
        val title: String? = null,
        val error: String? = null,
        val fileExists: Boolean = false
    )

    /**
     * Download audio als MP3 van een YouTube/YouTube Music URL.
     * @param youtubeUrl bijv. "https://www.youtube.com/watch?v=..." of "https://music.youtube.com/watch?v=..."
     * @param destDir map om het MP3 bestand op te slaan
     * @param instanceUrl cobalt instance URL
     * @param onProgress callback voor voortgang
     * @param forceOverwrite overschrijf bestaand bestand
     */
    suspend fun downloadAudio(
        youtubeUrl: String,
        destDir: File,
        instanceUrl: String = DEFAULT_INSTANCE,
        onProgress: (phase: String, progress: Float) -> Unit,
        forceOverwrite: Boolean = false
    ): CobaltResult = withContext(Dispatchers.IO) {
        try {
            // Stap 1: Cobalt API aanroepen
            onProgress("Verbinden met Cobalt...", 0.1f)

            val requestBody = JsonObject(mapOf(
                "url" to JsonPrimitive(youtubeUrl),
                "audioFormat" to JsonPrimitive("mp3"),
                "downloadMode" to JsonPrimitive("audio"),
                "filenameStyle" to JsonPrimitive("pretty")
            )).toString().toRequestBody("application/json".toMediaType())

            val baseUrl = instanceUrl.trimEnd('/')
            val request = Request.Builder()
                .url(baseUrl)
                .post(requestBody)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("User-Agent", UA)
                .build()

            onProgress("MP3 converteren...", 0.3f)

            val response = client.newCall(request).execute()
            val body = response.body?.string()
                ?: return@withContext CobaltResult(false, error = "Lege response van Cobalt")

            if (!response.isSuccessful) {
                val errorMsg = try {
                    val obj = json.parseToJsonElement(body).jsonObject
                    obj["error"]?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull
                        ?: "HTTP ${response.code}"
                } catch (_: Exception) { "HTTP ${response.code}" }
                return@withContext CobaltResult(false, error = "Cobalt fout: $errorMsg")
            }

            val obj = json.parseToJsonElement(body).jsonObject
            val status = obj["status"]?.jsonPrimitive?.contentOrNull

            when (status) {
                "tunnel", "redirect" -> {
                    val downloadUrl = obj["url"]?.jsonPrimitive?.contentOrNull
                        ?: return@withContext CobaltResult(false, error = "Geen download URL ontvangen")
                    val filename = obj["filename"]?.jsonPrimitive?.contentOrNull
                        ?: "youtube_${System.currentTimeMillis()}.mp3"

                    // Sanitize bestandsnaam
                    val sanitizedName = sanitizeFilename(filename)
                    val destFile = File(destDir, sanitizedName)

                    // Check of bestand al bestaat
                    if (destFile.exists() && !forceOverwrite) {
                        return@withContext CobaltResult(
                            success = false, file = destFile, filename = sanitizedName,
                            title = filename.removeSuffix(".mp3"), fileExists = true
                        )
                    }

                    // Stap 2: MP3 downloaden
                    onProgress("MP3 downloaden...", 0.6f)
                    downloadFile(downloadUrl, destFile)

                    onProgress("Klaar!", 1.0f)
                    CobaltResult(
                        success = true, file = destFile, filename = sanitizedName,
                        title = filename.removeSuffix(".mp3")
                    )
                }
                "error" -> {
                    val errorCode = obj["error"]?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull
                        ?: "onbekende fout"
                    CobaltResult(false, error = "Cobalt: $errorCode")
                }
                "picker" -> {
                    CobaltResult(false, error = "Meerdere items gevonden — selecteer een specifiek nummer")
                }
                else -> {
                    CobaltResult(false, error = "Onverwacht antwoord: $status")
                }
            }

        } catch (e: Exception) {
            CobaltResult(false, error = "Cobalt fout: ${e.message}")
        }
    }

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

    private fun sanitizeFilename(name: String): String {
        // Zorg dat het een .mp3 extensie heeft en veilige tekens gebruikt
        val base = name.removeSuffix(".mp3")
            .replace(Regex("[^a-zA-Z0-9_\\-\\s]"), "_")
            .replace(Regex("[\\s_]+"), "_")
            .trim('_')
        return "youtube_mp3_${base}.mp3"
    }
}

private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
