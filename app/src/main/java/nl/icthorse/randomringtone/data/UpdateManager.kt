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
    val marker: String? = null,
    val codename: String? = null,
    val releaseName: String? = null
) {
    val isBug get() = marker?.uppercase()?.contains("BUG") == true
    val isDebug get() = marker?.uppercase()?.contains("DEBUG") == true
    val isUpgrade get() = marker?.uppercase()?.contains("UPGRADE") == true

    val buildName: String? get() = when {
        codename != null && releaseName != null -> "$codename / $releaseName"
        codename != null -> codename
        releaseName != null -> releaseName
        else -> null
    }

    val displayLabel: String get() = buildString {
        append("v$version (Build $build)")
        buildName?.let { append(" \"$it\"") }
        marker?.let { append(" [$it]") }
    }
}

class UpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "UpdateManager"
        const val BASE_URL = "https://icthorse.nl/RandomRing/Apk/"
        private const val TIMESTAMP_URL = "${BASE_URL}build_info.php"

        /** v2.2.2: publieke spiegel op HC55 (tools/sync-apk-mirror.sh) — fallback als icthorse.nl niet reageert. */
        const val MIRROR_URL = "https://horsecloud55.ddns.net/rrlog/apk/"
        private const val MIRROR_TIMESTAMP_URL = "${MIRROR_URL}build.timestamp"

        /** Bronnen in volgorde: (label, versielijst-URL, APK-basis). */
        val SOURCES = listOf(
            Triple("icthorse.nl", TIMESTAMP_URL, BASE_URL),
            Triple("HC55", MIRROR_TIMESTAMP_URL, MIRROR_URL)
        )
    }

    // v2.2.2: harde plafonds (callTimeout) — readTimeout alleen begint na elk pakketje opnieuw, waardoor een
    // druppelende verbinding de knop eindeloos liet draaien.
    private val checkClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    /** Bron waarvan de laatste geslaagde versielijst kwam (null = geen). */
    var lastSource: String? = null
        private set

    private var preferredIndex = 0

    private fun orderedSources() = SOURCES.indices.sortedBy { if (it == preferredIndex) -1 else it }.map { SOURCES[it] }

    suspend fun fetchVersions(): List<RemoteVersion> = withContext(Dispatchers.IO) {
        for ((label, url, _) in orderedSources()) {
            try {
                RemoteLogger.d(TAG, "Fetching build.timestamp...", mapOf("source" to label))
                val versions = checkClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        RemoteLogger.w(TAG, "Fetch failed", mapOf("source" to label, "httpCode" to response.code.toString()))
                        null
                    } else parseTimestamp(response.body?.string().orEmpty())
                }
                if (!versions.isNullOrEmpty()) {
                    lastSource = label
                    preferredIndex = SOURCES.indexOfFirst { it.first == label }
                    RemoteLogger.i(TAG, "Versions fetched", mapOf("count" to versions.size.toString(), "source" to label))
                    return@withContext versions
                }
            } catch (e: Exception) {
                RemoteLogger.e(TAG, "Fetch error", mapOf("source" to label, "error" to (e.message ?: "unknown")))
            }
        }
        lastSource = null
        emptyList()
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
                    marker = parts.getOrNull(4)?.trim()?.takeIf { it.isNotBlank() },
                    codename = parts.getOrNull(5)?.trim()?.takeIf { it.isNotBlank() },
                    releaseName = parts.getOrNull(6)?.trim()?.takeIf { it.isNotBlank() }
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

    /**
     * Download met fallback: eerst de bron die de versielijst leverde, dan de andere. Elke poging heeft een hard
     * plafond; een onvolledige download (minder bytes dan Content-Length) telt als mislukt. [onSource] meldt de bron.
     */
    suspend fun downloadApk(
        version: RemoteVersion,
        onSource: (String) -> Unit = {},
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apkFile = File(updateDir, version.apkFilename)
        for ((label, _, base) in orderedSources()) {
            val url = base + version.apkFilename
            try {
                onSource(label)
                onProgress(0, -1)
                RemoteLogger.i(TAG, "Downloading APK", mapOf("url" to url))
                val ok = downloadClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        RemoteLogger.e(TAG, "Download failed", mapOf("url" to url, "httpCode" to response.code.toString()))
                        return@use false
                    }
                    val body = response.body ?: return@use false
                    val totalBytes = body.contentLength()
                    var bytesRead = 0L
                    body.byteStream().use { input ->
                        apkFile.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                bytesRead += read
                                onProgress(bytesRead, totalBytes)
                            }
                        }
                    }
                    if (totalBytes > 0 && bytesRead != totalBytes) {
                        RemoteLogger.e(TAG, "Download onvolledig", mapOf("url" to url, "read" to "$bytesRead", "total" to "$totalBytes"))
                        false
                    } else true
                }
                if (ok) {
                    preferredIndex = SOURCES.indexOfFirst { it.first == label }
                    RemoteLogger.i(TAG, "Download complete", mapOf(
                        "file" to apkFile.name, "size" to "${apkFile.length() / 1024}KB", "source" to label
                    ))
                    return@withContext apkFile
                }
            } catch (e: Exception) {
                RemoteLogger.e(TAG, "Download error", mapOf("url" to url, "error" to (e.message ?: "unknown")))
            }
            apkFile.delete()
        }
        null
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
