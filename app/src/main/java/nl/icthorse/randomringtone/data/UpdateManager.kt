package nl.icthorse.randomringtone.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

data class RemoteVersion(
    val version: String,
    val build: Int,
    val timestamp: String,
    val apkFilename: String,
    val marker: String? = null
) {
    val isBug get() = marker?.uppercase()?.contains("BUG") == true
    val isDebug get() = marker?.uppercase()?.contains("DEBUG") == true
    val isUpgrade get() = marker?.uppercase()?.contains("UPGRADE") == true

    val displayLabel: String get() = buildString {
        append("v$version (Build $build)")
        marker?.let { append(" [$it]") }
    }
}

class UpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "UpdateManager"
        const val BASE_URL = "https://icthorse.nl/RandomRing/Apk/"
        private const val TIMESTAMP_URL = "${BASE_URL}build.timestamp"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun fetchVersions(): List<RemoteVersion> = withContext(Dispatchers.IO) {
        try {
            RemoteLogger.d(TAG, "Fetching build.timestamp...")
            val request = Request.Builder().url(TIMESTAMP_URL).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    RemoteLogger.w(TAG, "Fetch failed", mapOf("httpCode" to response.code.toString()))
                    return@withContext emptyList()
                }
                val body = response.body?.string() ?: return@withContext emptyList()
                val versions = parseTimestamp(body)
                RemoteLogger.i(TAG, "Versions fetched", mapOf("count" to versions.size.toString()))
                versions
            }
        } catch (e: Exception) {
            RemoteLogger.e(TAG, "Fetch error", mapOf("error" to (e.message ?: "unknown")))
            emptyList()
        }
    }

    private fun parseTimestamp(content: String): List<RemoteVersion> {
        return content.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split("|")
                if (parts.size < 4) return@mapNotNull null
                RemoteVersion(
                    version = parts[0].trim(),
                    build = parts[1].trim().toIntOrNull() ?: return@mapNotNull null,
                    timestamp = parts[2].trim(),
                    apkFilename = parts[3].trim(),
                    marker = parts.getOrNull(4)?.trim()?.takeIf { it.isNotBlank() }
                )
            }
    }

    /**
     * Beste update voor normale gebruikers.
     * Filtert DEBUG, BUG en UPGRADE versies.
     */
    fun getBestUpdate(versions: List<RemoteVersion>, currentBuild: Int): RemoteVersion? {
        return versions
            .filter { !it.isDebug && !it.isBug && !it.isUpgrade }
            .maxByOrNull { it.build }
            ?.takeIf { it.build > currentBuild }
    }

    fun getAllVersions(versions: List<RemoteVersion>): List<RemoteVersion> {
        return versions.sortedByDescending { it.build }
    }

    fun getAvailableVersions(versions: List<RemoteVersion>): List<RemoteVersion> {
        return versions
            .filter { !it.isDebug }
            .sortedByDescending { it.build }
    }

    suspend fun downloadApk(
        version: RemoteVersion,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        try {
            val url = BASE_URL + version.apkFilename
            RemoteLogger.i(TAG, "Downloading APK", mapOf("url" to url))
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    RemoteLogger.e(TAG, "Download failed", mapOf("httpCode" to response.code.toString()))
                    return@withContext null
                }
                val body = response.body ?: return@withContext null
                val totalBytes = body.contentLength()
                val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
                val apkFile = File(updateDir, version.apkFilename)

                body.byteStream().use { input ->
                    apkFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead = 0L
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            bytesRead += read
                            onProgress(bytesRead, totalBytes)
                        }
                    }
                }

                RemoteLogger.i(TAG, "Download complete", mapOf(
                    "file" to apkFile.name,
                    "size" to "${apkFile.length() / 1024}KB"
                ))
                apkFile
            }
        } catch (e: Exception) {
            RemoteLogger.e(TAG, "Download error", mapOf("error" to (e.message ?: "unknown")))
            null
        }
    }

    fun installApk(apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        RemoteLogger.i(TAG, "Installing APK", mapOf("file" to apkFile.name))
        context.startActivity(intent)
    }
}
