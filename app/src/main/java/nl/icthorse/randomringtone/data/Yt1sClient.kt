package nl.icthorse.randomringtone.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/**
 * API client voor yt1s.biz — YouTube naar MP4 downloader.
 *
 * Flow (2 stappen, geen auth/polling nodig):
 * 1. POST /api/ajaxSearch/index → format tokens per kwaliteit
 * 2. POST /api/ajaxConvert/convert → directe download URL
 * 3. GET download URL → MP4 bestand
 */
class Yt1sClient {

    companion object {
        private const val BASE_URL = "https://v2.yt1s.biz"
        private const val SEARCH_URL = "$BASE_URL/api/ajaxSearch/index"
        private const val CONVERT_URL = "$BASE_URL/api/ajaxConvert/convert"
        private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }

    data class DownloadResult(
        val success: Boolean,
        val file: File? = null,
        val title: String? = null,
        val error: String? = null,
        val videoId: String? = null,
        val durationMs: Long = 0
    )

    data class QualityOption(
        val quality: String,  // "1080", "720", "480", "360"
        val key: String,
        val size: String = ""
    )

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Haal beschikbare kwaliteitsopties op voor een YouTube video.
     */
    suspend fun fetchQualities(youtubeUrl: String): Pair<String?, List<QualityOption>> = withContext(Dispatchers.IO) {
        try {
            RemoteLogger.d("Yt1s", "Fetching qualities", mapOf("url" to youtubeUrl))

            val formBody = FormBody.Builder()
                .add("q", youtubeUrl)
                .add("vt", "home")
                .build()
            val request = Request.Builder()
                .url(SEARCH_URL)
                .post(formBody)
                .header("User-Agent", UA)
                .header("Referer", "$BASE_URL/en18/youtube-to-mp4/")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    RemoteLogger.w("Yt1s", "Search failed", mapOf("code" to response.code.toString()))
                    return@withContext Pair(null, emptyList())
                }
                val body = response.body?.string() ?: return@withContext Pair(null, emptyList())
                val obj = json.parseToJsonElement(body).jsonObject

                val status = obj["status"]?.jsonPrimitive?.contentOrNull
                if (status != "ok") {
                    RemoteLogger.w("Yt1s", "Search status not ok", mapOf("status" to (status ?: "null")))
                    return@withContext Pair(null, emptyList())
                }

                val title = obj["title"]?.jsonPrimitive?.contentOrNull

                val mp4Links = obj["links"]?.jsonObject?.get("mp4")?.jsonObject
                    ?: return@withContext Pair(title, emptyList())

                val options = mp4Links.entries.mapNotNull { (quality, value) ->
                    val linkObj = value.jsonObject
                    val key = linkObj["k"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val size = linkObj["size"]?.jsonPrimitive?.contentOrNull ?: ""
                    QualityOption(quality = quality, key = key, size = size)
                }.sortedByDescending { it.quality.toIntOrNull() ?: 0 }

                RemoteLogger.i("Yt1s", "Qualities found", mapOf(
                    "title" to (title ?: "?"),
                    "count" to options.size.toString(),
                    "qualities" to options.joinToString { "${it.quality}p (${it.size})" }
                ))
                Pair(title, options)
            }
        } catch (e: Exception) {
            RemoteLogger.e("Yt1s", "Search error", mapOf("error" to (e.message ?: "unknown")))
            Pair(null, emptyList())
        }
    }

    /**
     * Download een MP4 video in de opgegeven kwaliteit.
     */
    suspend fun downloadVideo(
        videoId: String,
        qualityKey: String,
        title: String?,
        destDir: File,
        onProgress: (phase: String, progress: Float) -> Unit
    ): DownloadResult = withContext(Dispatchers.IO) {
        try {
            RemoteLogger.input("Yt1s", "Download gestart", mapOf("videoId" to videoId))

            // Stap 1: Convert → download URL
            onProgress("Video voorbereiden...", 0.1f)
            val formBody = FormBody.Builder()
                .add("vid", videoId)
                .add("k", qualityKey)
                .build()
            val request = Request.Builder()
                .url(CONVERT_URL)
                .post(formBody)
                .header("User-Agent", UA)
                .header("Referer", "$BASE_URL/en18/youtube-to-mp4/")
                .build()

            val downloadUrl: String
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext DownloadResult(false, error = "Conversie mislukt (HTTP ${response.code})")
                }
                val body = response.body?.string() ?: return@withContext DownloadResult(false, error = "Lege response")
                val obj = json.parseToJsonElement(body).jsonObject
                val status = obj["status"]?.jsonPrimitive?.contentOrNull
                if (status != "ok") {
                    return@withContext DownloadResult(false, error = "Conversie status: $status")
                }
                downloadUrl = obj["dlink"]?.jsonPrimitive?.contentOrNull
                    ?: return@withContext DownloadResult(false, error = "Geen download URL")
            }

            // Stap 2: Download MP4
            onProgress("Video downloaden...", 0.3f)
            val safeName = sanitizeFileName(title ?: videoId)
            val destFile = File(destDir, "videoring_${safeName}.mp4")
            destDir.mkdirs()

            val dlRequest = Request.Builder()
                .url(downloadUrl)
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
                                val dlProgress = bytesRead.toFloat() / totalBytes
                                onProgress("Downloaden... ${bytesRead / 1024}KB", 0.3f + dlProgress * 0.65f)
                            }
                        }
                    }
                }
            }

            // Duur ophalen via MediaMetadataRetriever
            var durationMs = 0L
            try {
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(destFile.absolutePath)
                durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                retriever.release()
            } catch (_: Exception) {}

            onProgress("Klaar!", 1.0f)
            RemoteLogger.output("Yt1s", "Download KLAAR", mapOf(
                "file" to destFile.name,
                "size" to "${destFile.length() / 1024}KB",
                "duration" to "${durationMs}ms"
            ))
            DownloadResult(success = true, file = destFile, title = title, videoId = videoId, durationMs = durationMs)

        } catch (e: Exception) {
            RemoteLogger.e("Yt1s", "Download FAILED", mapOf(
                "error" to (e.message ?: "unknown"), "videoId" to videoId
            ))
            DownloadResult(false, error = "Yt1s fout: ${e.message}")
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9_\\- ]"), "_")
            .replace(Regex("\\s+"), "_")
            .take(80)
    }
}
