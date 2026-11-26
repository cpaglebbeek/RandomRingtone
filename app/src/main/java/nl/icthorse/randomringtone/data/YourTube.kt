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
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * v2.7.0: de enige bron van de app — de eigen server YourTube (wachtrij + workers met yt-dlp achter een NL-uitgang).
 *
 * Zoeken: `GET {backend}/youtube/zoek?q=…` → lijst met video's (altijd anoniem).
 * Downloaden: `POST {backend}/youtube/<id>` start de taak → `GET` geeft de stand → bij `done` een ondertekende
 * medialink (M4A) die de app zelf ophaalt. Hoes (thumbnail) en marker gaan in het bestand.
 * Alles met X-Device-Id + X-Device-Token: alleen voor gelicenseerde toestellen. Geen terugval op het toestel.
 */
object YourTube {

    private const val POLL_MS = 1500L
    private const val MAX_WAIT_MS = 6 * 60 * 1000L
    const val PREFIX = "youtube_mp3_"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    data class Hit(val videoId: String, val title: String, val channel: String, val duration: String,
                   val durationSec: Long, val thumb: String)

    data class Result(
        val success: Boolean,
        val file: File? = null,
        val title: String? = null,
        val artist: String? = null,
        val error: String? = null,
        val fileExists: Boolean = false,
        val videoId: String? = null,
        val cover: ByteArray? = null
    )

    data class State(val state: String, val step: String, val progress: Float, val error: String, val url: String)

    private val ID = Regex("(?:v=|youtu\\.be/|/shorts/|/embed/)([A-Za-z0-9_-]{11})")

    /** Video-id uit een YouTube-link (watch, youtu.be, shorts, embed); null als het geen link is. */
    fun videoId(url: String): String? = ID.find(url)?.groupValues?.get(1)

    internal fun sanitize(s: String) = s.replace(Regex("[^\\p{L}\\p{N}_\\-!&'(),.\\[\\]]"), "_")
        .replace(Regex("_+"), "_").trim('_').take(120)

    /** Antwoord van de backend op een zoekopdracht → treffers; los van het netwerk zodat het testbaar is. */
    fun parseSearch(json: String): List<Hit> {
        val items = JSONObject(json).optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val o = items.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (id.length != 11) return@mapNotNull null
            Hit(id, o.optString("title"), o.optString("channel"), o.optString("duration"),
                o.optLong("durationSec", 0), o.optString("thumb").ifBlank { "https://i.ytimg.com/vi/$id/mqdefault.jpg" })
        }
    }

    /** Antwoord van de backend op een taak → [State]. */
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
        "queued" -> "In de rij op YourTube..."
        "done" -> "Klaar op de server — ophalen..."
        else -> if (s.step.isBlank()) "YourTube is bezig..." else "YourTube: ${s.step}"
    }

    class ServerError(message: String) : Exception(message)

    private fun request(lm: LicenseManager, path: String) = Request.Builder()
        .url("${LicenseManager.BACKEND_BASE}/youtube/$path")
        .header("X-Device-Id", lm.deviceHash).header("X-Device-Token", lm.deviceToken ?: "")

    private fun execute(req: Request): String = http.newCall(req).execute().use { r ->
        val text = r.body?.string().orEmpty()
        if (!r.isSuccessful) {
            val msg = try { JSONObject(text).optString("error") } catch (_: Exception) { "" }
            throw ServerError(when (r.code) {
                401 -> "Toestel niet geautoriseerd (licentie of toestel-token ontbreekt)"
                else -> msg.ifBlank { "YourTube gaf HTTP ${r.code}" }
            })
        }
        text
    }

    private suspend fun ensureToken(lm: LicenseManager): Boolean =
        lm.deviceToken != null || lm.ensureDeviceToken() == LicenseManager.TokenState.OK

    private fun task(lm: LicenseManager, method: String, id: String, title: String?): State {
        val b = request(lm, id)
        if (method == "POST") {
            val body = JSONObject().apply { if (!title.isNullOrBlank()) put("title", title.take(120)) }
            b.post(body.toString().toRequestBody("application/json".toMediaType()))
        }
        return parseState(execute(b.build()))
    }

    /** Zoek video's op YourTube. Gooit [ServerError] met een leesbare melding. */
    suspend fun search(lm: LicenseManager, query: String): List<Hit> = withContext(Dispatchers.IO) {
        val q = query.trim().take(200)
        if (q.isEmpty()) return@withContext emptyList()
        if (!ensureToken(lm)) throw ServerError("Geen toestel-token — YourTube werkt alleen op een gelicenseerd toestel")
        RemoteLogger.input("YourTube", "search", mapOf("q" to q.take(80)))
        try {
            val hits = parseSearch(execute(request(lm, "zoek?q=" + URLEncoder.encode(q, "UTF-8")).build()))
            RemoteLogger.output("YourTube", "Zoekresultaat", mapOf("q" to q.take(80), "n" to "${hits.size}"))
            hits
        } catch (e: Exception) {
            RemoteLogger.e("YourTube", "Zoeken FAILED", mapOf("error" to (e.message ?: e.javaClass.simpleName).take(300)))
            throw if (e is ServerError) e else ServerError("YourTube is niet bereikbaar (${e.message ?: e.javaClass.simpleName})")
        }
    }

    /** Titel en kanaal via YouTube-oEmbed (best-effort; alleen bij een geplakte link zonder titel). */
    private fun oembed(id: String): Pair<String?, String?> = try {
        http.newCall(Request.Builder()
            .url("https://www.youtube.com/oembed?format=json&url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3D$id").build())
            .execute().use { r ->
                val o = JSONObject(r.body?.string().orEmpty())
                o.optString("title").ifBlank { null } to o.optString("author_name").removeSuffix(" - Topic").ifBlank { null }
            }
    } catch (_: Exception) { null to null }

    /** Bytes van [url] (thumbnail), best-effort. */
    fun fetchBytes(url: String): ByteArray? = try {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            r.body?.bytes()?.takeIf { r.isSuccessful && it.size > 1000 }
        }
    } catch (_: Exception) { null }

    internal fun thumbnail(id: String): ByteArray? =
        listOf("maxresdefault", "hqdefault", "mqdefault").firstNotNullOfOrNull { fetchBytes("https://i.ytimg.com/vi/$id/$it.jpg") }

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

    /**
     * Download de audio van [videoIdOrUrl] als M4A in [destDir]: `youtube_mp3_<titel>.m4a` met hoes en YouTube-marker.
     * [displayTitle]/[displayArtist] komen uit de treffer; ontbreken ze (geplakte link), dan via oEmbed.
     */
    suspend fun downloadAudio(
        lm: LicenseManager,
        videoIdOrUrl: String,
        destDir: File,
        displayTitle: String? = null,
        displayArtist: String? = null,
        forceOverwrite: Boolean = false,
        onProgress: (phase: String, progress: Float) -> Unit = { _, _ -> }
    ): Result = withContext(Dispatchers.IO) {
        val id = videoId(videoIdOrUrl) ?: videoIdOrUrl.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
            ?: return@withContext Result(false, error = "Geen geldige YouTube-link")
        try {
            if (!ensureToken(lm)) {
                return@withContext Result(false, videoId = id,
                    error = "Geen toestel-token — YourTube werkt alleen op een gelicenseerd toestel")
            }
            onProgress("Verbinden met YourTube...", 0.02f)
            val (oTitle, oArtist) = if (displayTitle.isNullOrBlank()) oembed(id) else null to null
            val title = displayTitle?.ifBlank { null } ?: oTitle ?: id
            val artist = displayArtist?.ifBlank { null } ?: oArtist.orEmpty()
            val name = PREFIX + sanitize(title) + ".m4a"
            val dest = File(destDir, name)
            if (dest.exists() && !forceOverwrite) {
                return@withContext Result(false, file = dest, title = title, artist = artist, fileExists = true, videoId = id)
            }
            RemoteLogger.input("YourTube", "downloadAudio", mapOf("videoId" to id))
            var s = task(lm, "POST", id, title)
            val start = System.currentTimeMillis()
            while (s.state != "done") {
                if (s.state == "failed") throw ServerError(s.error.ifBlank { "De server kon deze video niet ophalen" })
                if (System.currentTimeMillis() - start > MAX_WAIT_MS) throw ServerError("YourTube deed er te lang over")
                onProgress(phaseText(s), barProgress(s))
                delay(POLL_MS)
                s = task(lm, "GET", id, null)
            }
            if (s.url.isBlank()) throw ServerError("De server gaf geen downloadlink")
            destDir.mkdirs()
            val tmp = File(destDir, ".$name.part")
            val total = fetch(s.url, tmp) { done, all ->
                onProgress("Audio ophalen van YourTube...", 0.7f + 0.25f * (if (all > 0) done.toFloat() / all else 0f))
            }
            if (total < 10_000) { tmp.delete(); throw ServerError("Bestand van de server is te klein ($total bytes)") }
            if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }

            onProgress("Hoes en marker toevoegen...", 0.96f)
            val art = thumbnail(id)
            M4aMetadata.write(dest, title, artist, art, Mp3Marker.MARKER_YOUTUBE)
            onProgress("Klaar!", 1f)
            RemoteLogger.output("YourTube", "Download KLAAR", mapOf("file" to dest.name,
                "size" to "${dest.length() / 1024}KB", "bytes" to "$total", "art" to (art != null).toString(),
                "wachtMs" to "${System.currentTimeMillis() - start}"))
            Result(true, file = dest, title = title, artist = artist, videoId = id, cover = art)
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            RemoteLogger.e("YourTube", "Download FAILED", mapOf("videoId" to id, "error" to msg.take(300),
                "type" to e.javaClass.simpleName))
            Result(false, videoId = id, error = msg)
        }
    }

    /** Zelftest voor Instellingen: toestel-token + een echte zoekopdracht (start geen download). */
    suspend fun selfTest(lm: LicenseManager): String = try {
        val hits = search(lm, "Johnny Cash Ring of Fire")
        if (hits.isEmpty()) "✗ YourTube bereikbaar, maar de zoekopdracht gaf niets terug"
        else "✓ YourTube bereikbaar, toestel geautoriseerd (${hits.size} treffers; eerste: ${hits.first().title.take(40)})"
    } catch (e: Exception) { "✗ ${e.message ?: e.javaClass.simpleName}" }
}
