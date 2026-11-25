package nl.icthorse.randomringtone.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * v2.4.0: YouTube-audio via de eigen server — de motor achter YourTube (wachtrij + workers met yt-dlp achter een
 * NL-uitgang, failover tussen workers).
 *
 * Flow: `POST {backend}/youtube/<id>` start de taak (X-Device-Id + X-Device-Token) → `GET` geeft de stand →
 * bij `done` een ondertekende medialink (M4A) die de app zelf ophaalt. Hoes en marker zoals bij "Op toestel".
 * Alleen voor gelicenseerde toestellen met toestel-token.
 */
object YouTubeServer {

    private const val POLL_MS = 1500L
    private const val MAX_WAIT_MS = 6 * 60 * 1000L

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    data class State(val state: String, val step: String, val progress: Float, val error: String, val url: String)

    /** Antwoord van de backend → [State]; los van het netwerk zodat het testbaar is. */
    fun parseState(json: String): State {
        val o = JSONObject(json)
        return State(o.optString("state", "working"), o.optString("step", ""),
            o.optDouble("progress", 0.0).toFloat().coerceIn(0f, 1f), o.optString("error", ""), o.optString("url", ""))
    }

    /** Voortgang van de servertaak (0..1) op de balk van de app: wachten/maken = 5–70 %, ophalen = 70–95 %. */
    fun barProgress(s: State): Float = when (s.state) {
        "queued" -> 0.05f
        "done" -> 0.7f
        else -> 0.1f + 0.6f * s.progress
    }

    fun phaseText(s: State): String = when (s.state) {
        "queued" -> "In de rij op de eigen server..."
        "done" -> "Klaar op de server — ophalen..."
        else -> if (s.step.isBlank()) "Eigen server is bezig..." else "Eigen server: ${s.step}"
    }

    private class ServerError(message: String) : Exception(message)

    private fun call(lm: LicenseManager, method: String, id: String, title: String?): State {
        val b = Request.Builder().url("${LicenseManager.BACKEND_BASE}/youtube/$id")
            .header("X-Device-Id", lm.deviceHash).header("X-Device-Token", lm.deviceToken ?: "")
        if (method == "POST") {
            val body = JSONObject().apply { if (!title.isNullOrBlank()) put("title", title.take(120)) }
            b.post(body.toString().toRequestBody("application/json".toMediaType()))
        }
        http.newCall(b.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val msg = try { JSONObject(text).optString("error") } catch (_: Exception) { "" }
                throw ServerError(when (r.code) {
                    401 -> "Toestel niet geautoriseerd (licentie of toestel-token ontbreekt)"
                    else -> msg.ifBlank { "Eigen server gaf HTTP ${r.code}" }
                })
            }
            return parseState(text)
        }
    }

    /** Titel en kanaal via YouTube-oEmbed (best-effort; alleen als de aanroeper geen titel meegeeft). */
    private fun oembed(id: String): Pair<String?, String?> = try {
        http.newCall(Request.Builder()
            .url("https://www.youtube.com/oembed?format=json&url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3D$id").build())
            .execute().use { r ->
                val o = JSONObject(r.body?.string().orEmpty())
                o.optString("title").ifBlank { null } to o.optString("author_name").removeSuffix(" - Topic").ifBlank { null }
            }
    } catch (_: Exception) { null to null }

    private fun fetch(url: String, dest: File, onBytes: (Long, Long) -> Unit): Long {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw ServerError("Ophalen van de server mislukt (HTTP ${r.code})")
            val body = r.body ?: throw ServerError("Leeg antwoord van de server")
            val total = body.contentLength()
            var done = 0L
            dest.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onBytes(done, total)
                    }
                }
            }
            return done
        }
    }

    /** Zelfde contract als [YouTubeOnDevice.downloadAudio]. */
    suspend fun downloadAudio(
        lm: LicenseManager,
        videoIdOrUrl: String,
        destDir: File,
        prefix: String = "youtube_mp3_",
        baseName: String? = null,
        displayTitle: String? = null,
        displayArtist: String? = null,
        coverJpeg: ByteArray? = null,
        forceOverwrite: Boolean = false,
        onProgress: (phase: String, progress: Float) -> Unit = { _, _ -> }
    ): YouTubeOnDevice.Result = withContext(Dispatchers.IO) {
        val id = YouTubeOnDevice.videoId(videoIdOrUrl) ?: videoIdOrUrl.takeIf { it.length == 11 }
            ?: return@withContext YouTubeOnDevice.Result(false, error = "Geen geldige YouTube-link")
        try {
            if (lm.deviceToken == null && lm.ensureDeviceToken() != LicenseManager.TokenState.OK) {
                return@withContext YouTubeOnDevice.Result(false, videoId = id,
                    error = "Geen toestel-token — de eigen server werkt alleen op een gelicenseerd toestel")
            }
            onProgress("Verbinden met de eigen server...", 0.02f)
            val (oTitle, oArtist) = if (displayTitle == null) oembed(id) else null to null
            val title = displayTitle ?: oTitle ?: baseName ?: id
            val artist = displayArtist ?: oArtist.orEmpty()
            val name = prefix + YouTubeOnDevice.sanitize(baseName ?: title) + ".m4a"
            val dest = File(destDir, name)
            if (dest.exists() && !forceOverwrite) {
                return@withContext YouTubeOnDevice.Result(false, file = dest, title = title, artist = artist,
                    fileExists = true, videoId = id)
            }
            RemoteLogger.input("YouTubeServer", "downloadAudio", mapOf("videoId" to id, "prefix" to prefix))
            var s = call(lm, "POST", id, title)
            val start = System.currentTimeMillis()
            while (s.state != "done") {
                if (s.state == "failed") throw ServerError(s.error.ifBlank { "De server kon deze video niet ophalen" })
                if (System.currentTimeMillis() - start > MAX_WAIT_MS) throw ServerError("De eigen server deed er te lang over")
                onProgress(phaseText(s), barProgress(s))
                delay(POLL_MS)
                s = call(lm, "GET", id, null)
            }
            if (s.url.isBlank()) throw ServerError("De server gaf geen downloadlink")
            destDir.mkdirs()
            val tmp = File(destDir, ".$name.part")
            val total = fetch(s.url, tmp) { done, all ->
                onProgress("Audio ophalen van de eigen server...", 0.7f + 0.25f * (if (all > 0) done.toFloat() / all else 0f))
            }
            if (total < 10_000) { tmp.delete(); throw ServerError("Bestand van de server is te klein ($total bytes)") }
            if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }

            onProgress("Hoes en marker toevoegen...", 0.96f)
            val art = coverJpeg ?: YouTubeOnDevice.thumbnail(id)
            val marker = if (prefix == "youtube_mp3_") Mp3Marker.MARKER_YOUTUBE else Mp3Marker.MARKER_TRACK
            M4aMetadata.write(dest, title, artist, art, marker)
            onProgress("Klaar!", 1f)
            RemoteLogger.output("YouTubeServer", "Download KLAAR", mapOf("file" to dest.name,
                "size" to "${dest.length() / 1024}KB", "bytes" to "$total", "art" to (art != null).toString(),
                "wachtMs" to "${System.currentTimeMillis() - start}"))
            YouTubeOnDevice.Result(true, file = dest, title = title, artist = artist, videoId = id)
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            RemoteLogger.e("YouTubeServer", "Download FAILED", mapOf("videoId" to id, "error" to msg.take(300),
                "type" to e.javaClass.simpleName))
            YouTubeOnDevice.Result(false, videoId = id, error = msg)
        }
    }

    /** Zelftest voor Instellingen: toestel-token + bereikbaarheid van de backend-route (start geen download). */
    suspend fun selfTest(lm: LicenseManager): String = withContext(Dispatchers.IO) {
        try {
            if (lm.deviceToken == null && lm.ensureDeviceToken() != LicenseManager.TokenState.OK) {
                return@withContext "✗ Geen toestel-token (licentie nodig)"
            }
            try {
                val s = call(lm, "GET", "dQw4w9WgXcQ", null)
                "✓ Eigen server bereikbaar (testvideo: ${s.state})"
            } catch (e: ServerError) {
                // "staat niet in de rij" = route, token en YourTube werken; er loopt alleen geen taak voor de testvideo
                if (e.message.orEmpty().contains("niet in de rij")) "✓ Eigen server bereikbaar, toestel geautoriseerd"
                else "✗ ${e.message}"
            }
        } catch (e: Exception) { "✗ ${e.message ?: e.javaClass.simpleName}" }
    }
}

/** v2.4.0: één ingang voor YouTube-audio volgens de gekozen methode; "Eigen server" valt terug op "Op toestel". */
object YouTubeDownload {
    suspend fun audio(
        lm: LicenseManager,
        method: YouTubeMethod,
        videoIdOrUrl: String,
        destDir: File,
        prefix: String = "youtube_mp3_",
        baseName: String? = null,
        displayTitle: String? = null,
        displayArtist: String? = null,
        coverJpeg: ByteArray? = null,
        forceOverwrite: Boolean = false,
        onProgress: (phase: String, progress: Float) -> Unit = { _, _ -> }
    ): YouTubeOnDevice.Result {
        if (method == YouTubeMethod.SERVER) {
            val s = YouTubeServer.downloadAudio(lm, videoIdOrUrl, destDir, prefix, baseName, displayTitle, displayArtist,
                coverJpeg, forceOverwrite, onProgress)
            if (s.success || s.fileExists) return s
            RemoteLogger.w("YouTube", "Eigen server mislukt — terugval op toestel", mapOf("error" to (s.error ?: "")))
            onProgress("Eigen server mislukt — op toestel...", 0f)
            val d = YouTubeOnDevice.downloadAudio(videoIdOrUrl, destDir, prefix, baseName, displayTitle, displayArtist,
                coverJpeg, forceOverwrite, onProgress)
            return if (d.success || d.fileExists) d else d.copy(error = "Eigen server: ${s.error}\nOp toestel: ${d.error}")
        }
        return YouTubeOnDevice.downloadAudio(videoIdOrUrl, destDir, prefix, baseName, displayTitle, displayArtist,
            coverJpeg, forceOverwrite, onProgress)
    }
}
