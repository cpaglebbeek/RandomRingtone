package nl.icthorse.randomringtone.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * YouTube zoeken + audio downloaden OP HET TOESTEL (v2.3.0) via NewPipeExtractor (GPL-3.0).
 *
 * Waarom: YouTube blokkeert datacenter-IP's ("Sign in to confirm you're not a bot") — Y2Mate, Piped, Invidious en
 * yt-dlp op de server gaven 29-09 allemaal 403/bot-controle. Vanaf het eigen (thuis/mobiele) IP van de telefoon werkt
 * extractie wél, zoals in de NewPipe-app. Audio = M4A/AAC (itag 140, ~128 kbps), gedownload in blokken van 1 MB
 * (YouTube knijpt ongedeelde downloads af). Bestandsnaam houdt het bestaande `youtube_mp3_`/`spotify_mp3_`-voorvoegsel
 * (scan, restore en backup herkennen de soort daaraan), extensie `.m4a`.
 */
object YouTubeOnDevice {

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    private const val CHUNK = 1L shl 20

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private object OkDownloader : Downloader() {
        override fun execute(request: Request): Response {
            val b = okhttp3.Request.Builder().url(request.url()).header("User-Agent", UA)
            request.headers().forEach { (k, vs) -> b.removeHeader(k); vs.forEach { v -> b.addHeader(k, v) } }
            val body = request.dataToSend()?.toRequestBody()
            b.method(request.httpMethod(), body ?: if (request.httpMethod() == "POST") ByteArray(0).toRequestBody() else null)
            http.newCall(b.build()).execute().use { r ->
                if (r.code == 429) throw ReCaptchaException("reCaptcha / 429", request.url())
                return Response(r.code, r.message, r.headers.toMultimap(), r.body?.string(), r.request.url.toString())
            }
        }
    }

    @Volatile private var initialized = false

    private fun init() {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                NewPipe.init(OkDownloader, Localization("nl", "NL"), ContentCountry("NL"))
                initialized = true
            }
        }
    }

    data class Hit(val videoId: String, val title: String, val uploader: String, val durationSec: Long, val url: String)

    data class Result(
        val success: Boolean,
        val file: File? = null,
        val title: String? = null,
        val artist: String? = null,
        val error: String? = null,
        val fileExists: Boolean = false,
        val videoId: String? = null
    )

    private val ID = Regex("(?:v=|youtu\\.be/|/shorts/|/embed/)([A-Za-z0-9_-]{11})")

    fun videoId(url: String): String? = ID.find(url)?.groupValues?.get(1)

    /** Zoek video's (alleen streams, geen kanalen/playlists). */
    suspend fun search(query: String, limit: Int = 10): List<Hit> = withContext(Dispatchers.IO) {
        init()
        val yt = ServiceList.YouTube
        val handler = yt.searchQHFactory.fromQuery(query, listOf("videos"), "")
        val info = org.schabi.newpipe.extractor.search.SearchInfo.getInfo(yt, handler)
        info.relatedItems.filterIsInstance<StreamInfoItem>().take(limit).mapNotNull { item ->
            val id = videoId(item.url) ?: return@mapNotNull null
            Hit(id, item.name, item.uploaderName ?: "", item.duration, item.url)
        }
    }

    /**
     * Beste treffer voor een nummer (bv. vanuit Spotify): zoek "artiest titel", kies de video waarvan de duur het
     * dichtst bij [durationSec] ligt (±15 s), met voorkeur voor "official audio"/"topic"-kanalen.
     */
    suspend fun bestMatch(artist: String, title: String, durationSec: Long?): Hit? {
        val hits = search("$artist $title audio", 10)
        if (hits.isEmpty()) return null
        fun score(h: Hit): Double {
            var s = 0.0
            if (durationSec != null && durationSec > 0 && h.durationSec > 0) {
                val d = abs(h.durationSec - durationSec)
                if (d > 15) s += 1000.0 + d else s += d.toDouble()
            }
            val t = h.title.lowercase()
            if (h.uploader.endsWith(" - Topic")) s -= 5
            if ("official audio" in t) s -= 3
            if ("live" in t && "live" !in title.lowercase()) s += 20
            if ("cover" in t || "karaoke" in t || "remix" in t && "remix" !in title.lowercase()) s += 30
            return s
        }
        return hits.minByOrNull(::score)
    }

    private fun sanitize(s: String) = s.replace(Regex("[^\\p{L}\\p{N}_\\-!&'(),.\\[\\]]"), "_")
        .replace(Regex("_+"), "_").trim('_').take(120)

    /**
     * Download de audio van [videoIdOrUrl] als M4A in [destDir].
     * [prefix] = "youtube_mp3_" (YouTube-tab) of "spotify_mp3_" (Spotify volledig; [baseName] = "Titel-Artiest").
     * Hoes = YouTube-thumbnail tenzij [coverJpeg] gegeven is; marker = YouTube of track.
     */
    suspend fun downloadAudio(
        videoIdOrUrl: String,
        destDir: File,
        prefix: String = "youtube_mp3_",
        baseName: String? = null,
        displayTitle: String? = null,
        displayArtist: String? = null,
        coverJpeg: ByteArray? = null,
        forceOverwrite: Boolean = false,
        onProgress: (phase: String, progress: Float) -> Unit = { _, _ -> }
    ): Result = withContext(Dispatchers.IO) {
        val id = videoId(videoIdOrUrl) ?: videoIdOrUrl.takeIf { it.length == 11 }
            ?: return@withContext Result(false, error = "Geen geldige YouTube-link")
        try {
            init()
            onProgress("Video-info ophalen (op toestel)...", 0.05f)
            RemoteLogger.input("YouTubeOnDevice", "downloadAudio", mapOf("videoId" to id, "prefix" to prefix))
            val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$id")
            val audio = info.audioStreams
                .filter { it.format == MediaFormat.M4A && !it.content.isNullOrBlank() }
                .maxByOrNull { it.averageBitrate }
                ?: return@withContext Result(false, videoId = id, error = "Geen M4A-audiostream voor deze video")
            val title = displayTitle ?: info.name
            val artist = displayArtist ?: info.uploaderName.orEmpty().removeSuffix(" - Topic")
            val name = prefix + sanitize(baseName ?: title) + ".m4a"
            val dest = File(destDir, name)
            if (dest.exists() && !forceOverwrite) {
                return@withContext Result(false, file = dest, title = title, artist = artist, fileExists = true, videoId = id)
            }
            destDir.mkdirs()
            val tmp = File(destDir, ".$name.part")
            val total = fetchChunked(audio.content, tmp) { done, all ->
                onProgress("Audio downloaden...", 0.1f + 0.8f * (if (all > 0) done.toFloat() / all else 0f))
            }
            if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }

            onProgress("Hoes en marker toevoegen...", 0.95f)
            val art = coverJpeg ?: thumbnail(id)
            val marker = if (prefix == "youtube_mp3_") Mp3Marker.MARKER_YOUTUBE else Mp3Marker.MARKER_TRACK
            M4aMetadata.write(dest, title, artist, art, marker)
            onProgress("Klaar!", 1f)
            RemoteLogger.output("YouTubeOnDevice", "Download KLAAR", mapOf(
                "file" to dest.name, "size" to "${dest.length() / 1024}KB", "bytes" to "$total",
                "bitrate" to "${audio.averageBitrate}", "art" to (art != null).toString()))
            Result(true, file = dest, title = title, artist = artist, videoId = id)
        } catch (e: Exception) {
            val msg = when {
                e is ReCaptchaException || e.javaClass.simpleName.contains("SignInConfirmNotBot") ->
                    "YouTube vraagt een robotcontrole voor dit netwerk — probeer wifi/mobiel te wisselen of Y2Mate"
                else -> e.message ?: e.javaClass.simpleName
            }
            RemoteLogger.e("YouTubeOnDevice", "Download FAILED", mapOf("videoId" to id, "error" to msg.take(300),
                "type" to e.javaClass.simpleName))
            Result(false, videoId = id, error = msg)
        }
    }

    /** Download in blokken van 1 MB via &range= (YouTube knijpt ongedeelde verzoeken af). Geeft totaal aantal bytes. */
    private fun fetchChunked(url: String, dest: File, onBytes: (Long, Long) -> Unit): Long {
        var pos = 0L
        var total = -1L
        dest.outputStream().use { out ->
            while (total < 0 || pos < total) {
                val end = pos + CHUNK - 1
                val req = okhttp3.Request.Builder().url("$url&range=$pos-$end").header("User-Agent", UA).build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw Exception("Audio-download HTTP ${r.code}")
                    if (total < 0) total = Regex("[?&]clen=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()
                        ?: r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1L
                    val bytes = r.body?.bytes() ?: ByteArray(0)
                    if (bytes.isEmpty()) { total = pos; return@use }
                    out.write(bytes)
                    pos += bytes.size
                    onBytes(pos, total)
                    if (total < 0 && bytes.size < CHUNK) total = pos
                }
            }
        }
        return pos
    }

    private fun thumbnail(id: String): ByteArray? {
        for (q in listOf("maxresdefault", "hqdefault", "mqdefault")) {
            try {
                http.newCall(okhttp3.Request.Builder().url("https://i.ytimg.com/vi/$id/$q.jpg").build()).execute().use { r ->
                    val b = r.body?.bytes()
                    if (r.isSuccessful && b != null && b.size > 2048) return b
                }
            } catch (_: Exception) { }
        }
        return null
    }

    /** Snelle zelftest voor Instellingen: zoeken + stream-URL ophalen (zonder download). */
    suspend fun selfTest(): String = withContext(Dispatchers.IO) {
        try {
            val hit = search("toto africa", 3).firstOrNull() ?: return@withContext "✗ Zoeken gaf niets"
            init()
            val info = StreamInfo.getInfo(ServiceList.YouTube, hit.url)
            val a = info.audioStreams.filter { it.format == MediaFormat.M4A }.maxByOrNull { it.averageBitrate }
                ?: return@withContext "✗ Gevonden (${hit.title}) maar geen M4A-audio"
            val ok = http.newCall(okhttp3.Request.Builder().url("${a.content}&range=0-65535").header("User-Agent", UA).build())
                .execute().use { it.isSuccessful && (it.body?.bytes()?.size ?: 0) > 1000 }
            if (ok) "✓ Werkt — ${hit.title} (${a.averageBitrate / 1000} kbps)" else "✗ Audio-URL gaf geen data"
        } catch (e: Exception) {
            "✗ ${if (e.javaClass.simpleName.contains("SignInConfirmNotBot")) "YouTube robotcontrole voor dit netwerk" else e.message ?: e.javaClass.simpleName}"
        }
    }
}
