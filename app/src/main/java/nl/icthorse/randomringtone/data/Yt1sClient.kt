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
 * YouTube naar MP4 downloader via hub.ytconvert.org (ytmp3.gg backend).
 *
 * Flow:
 * 1. POST /api/download → statusUrl + title + selectedQuality
 * 2. GET statusUrl (poll) → progress + downloadUrl wanneer completed
 * 3. GET downloadUrl → MP4 bestand
 *
 * Geen auth/captcha/API key nodig voor basis gebruik.
 */
class Yt1sClient {

    companion object {
        private const val API_URL = "https://hub.ytconvert.org/api/download"
        private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        private const val MAX_POLL_ATTEMPTS = 120
        private const val POLL_INTERVAL_MS = 2000L
        private val JSON_MEDIA = "application/json".toMediaType()
    }

    data class DownloadResult(
        val success: Boolean,
        val file: File? = null,
        val title: String? = null,
        val error: String? = null,
        val videoId: String? = null,
        val durationMs: Long = 0
    )

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Download een YouTube video als MP4.
     * @param videoId YouTube video ID
     * @param quality Gewenste kwaliteit: "360p", "720p", "1080p"
     * @param destDir Map om MP4 in op te slaan
     * @param onProgress Callback (fase, 0.0-1.0)
     */
    suspend fun downloadVideo(
        videoId: String,
        quality: String = "720p",
        title: String? = null,
        destDir: File,
        onProgress: (phase: String, progress: Float) -> Unit
    ): DownloadResult = withContext(Dispatchers.IO) {
        try {
            val youtubeUrl = "https://www.youtube.com/watch?v=$videoId"
            RemoteLogger.input("Yt1s", "Download gestart", mapOf("videoId" to videoId, "quality" to quality))

            // Stap 1: Create job
            onProgress("Video voorbereiden...", 0.05f)
            val jobBody = buildJsonObject {
                put("url", youtubeUrl)
                put("os", "android")
                putJsonObject("output") {
                    put("type", "video")
                    put("format", "mp4")
                    put("quality", quality)
                }
            }.toString()

            val jobRequest = Request.Builder()
                .url(API_URL)
                .post(jobBody.toRequestBody(JSON_MEDIA))
                .header("User-Agent", UA)
                .header("Accept", "application/json")
                .header("Origin", "https://media.ytmp3.gg")
                .header("Referer", "https://media.ytmp3.gg/")
                .build()

            val statusUrl: String
            var videoTitle: String? = title
            var duration = 0L

            client.newCall(jobRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    RemoteLogger.w("Yt1s", "Job failed", mapOf("code" to response.code.toString(), "body" to body.take(200)))
                    return@withContext DownloadResult(false, error = "API fout (HTTP ${response.code})")
                }
                val body = response.body?.string() ?: return@withContext DownloadResult(false, error = "Lege response")
                val obj = json.parseToJsonElement(body).jsonObject

                statusUrl = obj["statusUrl"]?.jsonPrimitive?.contentOrNull
                    ?: return@withContext DownloadResult(false, error = "Geen statusUrl: ${body.take(200)}")

                videoTitle = videoTitle ?: obj["title"]?.jsonPrimitive?.contentOrNull
                duration = (obj["duration"]?.jsonPrimitive?.longOrNull ?: 0L) * 1000L

                val selectedQ = obj["selectedQuality"]?.jsonPrimitive?.contentOrNull ?: quality
                RemoteLogger.d("Yt1s", "Job aangemaakt", mapOf(
                    "title" to (videoTitle ?: "?"),
                    "quality" to selectedQ,
                    "duration" to "${duration / 1000}s"
                ))
            }

            // Stap 2: Poll status
            onProgress("Converteren...", 0.1f)
            var downloadUrl: String? = null

            for (attempt in 0 until MAX_POLL_ATTEMPTS) {
                delay(POLL_INTERVAL_MS)

                val pollRequest = Request.Builder()
                    .url(statusUrl)
                    .header("User-Agent", UA)
                    .header("Accept", "application/json")
                    .build()

                client.newCall(pollRequest).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body?.string() ?: return@use
                    val obj = json.parseToJsonElement(body).jsonObject

                    val status = obj["status"]?.jsonPrimitive?.contentOrNull ?: ""
                    val progress = obj["progress"]?.jsonPrimitive?.intOrNull ?: 0

                    when (status) {
                        "completed" -> {
                            downloadUrl = obj["downloadUrl"]?.jsonPrimitive?.contentOrNull
                        }
                        "failed", "error" -> {
                            return@withContext DownloadResult(false, error = "Conversie mislukt op server")
                        }
                        else -> {
                            onProgress("Converteren... ${progress}%", 0.1f + (progress / 100f) * 0.4f)
                        }
                    }
                }

                if (downloadUrl != null) break
            }

            if (downloadUrl == null) {
                return@withContext DownloadResult(false, error = "Conversie timeout")
            }

            // Stap 3: Download MP4
            onProgress("Video downloaden...", 0.5f)
            val safeName = sanitizeFileName(videoTitle ?: videoId)
            destDir.mkdirs()
            val destFile = File(destDir, "videoring_${safeName}.mp4")

            val dlRequest = Request.Builder()
                .url(downloadUrl!!)
                .header("User-Agent", UA)
                .build()

            client.newCall(dlRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext DownloadResult(false, error = "Download mislukt (HTTP ${response.code})")
                }
                val responseBody = response.body ?: return@withContext DownloadResult(false, error = "Lege download")
                val totalBytes = responseBody.contentLength()

                responseBody.byteStream().use { input ->
                    destFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead = 0L
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            bytesRead += read
                            if (totalBytes > 0) {
                                onProgress("Downloaden... ${bytesRead / 1024}KB", 0.5f + (bytesRead.toFloat() / totalBytes) * 0.45f)
                            }
                        }
                    }
                }
            }

            // Duur ophalen als API het niet gaf
            if (duration == 0L) {
                try {
                    val retriever = android.media.MediaMetadataRetriever()
                    retriever.setDataSource(destFile.absolutePath)
                    duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                    retriever.release()
                } catch (_: Exception) {}
            }

            onProgress("Klaar!", 1.0f)
            RemoteLogger.output("Yt1s", "Download KLAAR", mapOf(
                "file" to destFile.name,
                "size" to "${destFile.length() / 1024}KB",
                "title" to (videoTitle ?: "?"),
                "duration" to "${duration / 1000}s"
            ))
            DownloadResult(success = true, file = destFile, title = videoTitle, videoId = videoId, durationMs = duration)

        } catch (e: Exception) {
            RemoteLogger.e("Yt1s", "Download FAILED", mapOf(
                "error" to (e.message ?: "unknown"), "videoId" to videoId
            ))
            DownloadResult(false, error = "Fout: ${e.message}")
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9_\\- ]"), "_")
            .replace(Regex("\\s+"), "_")
            .take(80)
    }
}
