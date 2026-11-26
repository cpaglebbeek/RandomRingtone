package nl.icthorse.randomringtone.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Nummerlijst van een Spotify-playlist of -album (v2.5.0), zonder sleutels:
 * 1. de openbare embed-pagina (open.spotify.com/embed/{playlist|album}/{id}) bevat de eerste ±100 nummers;
 * 2. voor langere playlists geeft Spotify's playlistdienst de volledige volgorde (met het anonieme token uit dezelfde
 *    pagina); de ontbrekende nummers worden daarna één voor één uit hun eigen embed-pagina gelezen.
 * Stap 2 is een interne dienst van Spotify en kan zonder aankondiging wijzigen: mislukt hij, dan blijft de lijst uit
 * stap 1 staan en is [Collection.complete] false.
 */
object SpotifyCollection {
    enum class Kind(val path: String, val label: String) { PLAYLIST("playlist", "Playlist"), ALBUM("album", "Album") }

    data class Ref(val kind: Kind, val id: String) {
        val url: String get() = "https://open.spotify.com/${kind.path}/$id"
    }

    data class Item(val id: String, val title: String, val artist: String, val durationSec: Long?, val playable: Boolean = true)

    data class Collection(
        val ref: Ref,
        val name: String,
        val owner: String,
        val coverUrl: String?,
        val items: List<Item>,
        val total: Int,
        val token: String? = null
    ) {
        val complete: Boolean get() = items.size >= total
    }

    /** Meer dan dit aantal nummers bovenop de embed-lijst halen we niet op (één verzoek per nummer). */
    const val MAX_EXTRA = 400
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"
    private val LINK = Regex("(?:open\\.spotify\\.com/(?:intl-[a-z]{2}/)?(playlist|album)/|spotify:(playlist|album):)([A-Za-z0-9]{22})")
    private val NEXT_DATA = Regex("<script id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
    private val TRACK_URI = Regex("^spotify:track:([A-Za-z0-9]{22})$")

    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    }

    fun parseLink(text: String): Ref? {
        val m = LINK.find(text) ?: return null
        val kind = if ((m.groupValues[1] + m.groupValues[2]) == "album") Kind.ALBUM else Kind.PLAYLIST
        return Ref(kind, m.groupValues[3])
    }

    private fun cleanArtists(s: String) = s.replace(' ', ' ').replace(Regex("\\s+"), " ").trim()

    /** Zuiver (zonder netwerk): lijst uit de HTML van de embed-pagina. */
    fun parseEmbed(html: String, ref: Ref): Collection? {
        val m = NEXT_DATA.find(html) ?: return null
        val state = try {
            JSONObject(m.groupValues[1]).optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("state")
        } catch (_: Exception) { null } ?: return null
        val e = state.optJSONObject("data")?.optJSONObject("entity") ?: return null
        val list = e.optJSONArray("trackList") ?: return null
        val items = (0 until list.length()).mapNotNull { i ->
            val t = list.optJSONObject(i) ?: return@mapNotNull null
            if (t.optString("entityType", "track") != "track") return@mapNotNull null
            val id = TRACK_URI.find(t.optString("uri"))?.groupValues?.get(1) ?: return@mapNotNull null
            Item(id, t.optString("title"), cleanArtists(t.optString("subtitle")),
                t.optLong("duration", 0).takeIf { it > 0 }?.let { it / 1000 }, t.optBoolean("isPlayable", true))
        }
        var cover: String? = e.optJSONObject("coverArt")?.optJSONArray("sources")?.optJSONObject(0)?.optString("url")?.ifBlank { null }
        if (cover == null) {
            val images = e.optJSONObject("visualIdentity")?.optJSONArray("image")
            var best = -1
            for (i in 0 until (images?.length() ?: 0)) {
                val img = images!!.getJSONObject(i); val w = img.optInt("maxWidth", 0)
                if (w > best) { cover = img.optString("url").ifBlank { null }; best = w }
            }
        }
        val token = state.optJSONObject("settings")?.optJSONObject("session")?.optString("accessToken")?.ifBlank { null }
        return Collection(ref, e.optString("name", e.optString("title")), cleanArtists(e.optString("subtitle")),
            cover, items, items.size, token)
    }

    /** Zuiver: track-id's in playlistvolgorde uit het antwoord van de playlistdienst, plus de totale lengte. */
    fun parsePlaylistOrder(json: String): Pair<List<String>, Int> {
        val o = JSONObject(json)
        val arr = o.optJSONObject("contents")?.optJSONArray("items")
        val ids = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
            TRACK_URI.find(arr!!.optJSONObject(i)?.optString("uri").orEmpty())?.groupValues?.get(1)
        }
        return ids to o.optInt("length", ids.size)
    }

    private fun get(url: String, token: String? = null): String {
        val b = Request.Builder().url(url).header("User-Agent", UA)
        if (token != null) b.header("Authorization", "Bearer $token").header("Accept", "application/json")
        return client.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("Spotify gaf ${r.code}")
            r.body?.string().orEmpty()
        }
    }

    suspend fun fetch(ref: Ref, onProgress: (String) -> Unit = {}): Collection = withContext(Dispatchers.IO) {
        RemoteLogger.input("SpotifyCollection", "fetch", mapOf("kind" to ref.kind.path, "id" to ref.id))
        onProgress("${ref.kind.label} ophalen...")
        val base = parseEmbed(get("https://open.spotify.com/embed/${ref.kind.path}/${ref.id}"), ref)
            ?: throw java.io.IOException("Spotify-pagina onleesbaar (formaat gewijzigd of ${ref.kind.label.lowercase()} is privé)")
        var result = base
        if (ref.kind == Kind.PLAYLIST && base.items.size >= 100 && base.token != null) {
            try {
                val (order, length) = parsePlaylistOrder(get("https://spclient.wg.spotify.com/playlist/v2/playlist/${ref.id}", base.token))
                val known = base.items.associateBy { it.id }
                val missing = order.filter { it !in known }.distinct().take(MAX_EXTRA)
                if (missing.isNotEmpty()) {
                    var done = 0
                    val gate = Semaphore(4)
                    val extra = coroutineScope {
                        missing.map { id ->
                            async {
                                gate.withPermit {
                                    val item = try {
                                        SpotifyPreviewClient.parseEmbedStatic(get("https://open.spotify.com/embed/track/$id"))
                                            ?.let { Item(id, it.name, it.artist, it.durationSec) }
                                    } catch (_: Exception) { null }
                                    synchronized(this@SpotifyCollection) { done++; onProgress("Nummers ophalen ($done/${missing.size})...") }
                                    item
                                }
                            }
                        }.awaitAll()
                    }.filterNotNull().associateBy { it.id }
                    val all = known + extra
                    result = base.copy(items = order.distinct().mapNotNull { all[it] }, total = maxOf(length, order.size))
                } else {
                    result = base.copy(total = maxOf(length, base.items.size))
                }
            } catch (e: Exception) {
                RemoteLogger.w("SpotifyCollection", "Volledige lijst niet beschikbaar", mapOf("error" to (e.message ?: "?")))
                result = base.copy(total = base.items.size + 1) // markeer als onvolledig: alleen de eerste nummers geladen
            }
        }
        RemoteLogger.output("SpotifyCollection", "Lijst", mapOf("name" to result.name, "items" to "${result.items.size}",
            "total" to "${result.total}"))
        result
    }

    suspend fun selfTest(): String = try {
        val c = fetch(Ref(Kind.ALBUM, "4aawyAB9vmqN3uQ7FjRGTy"))
        if (c.items.isNotEmpty()) "✓ ${c.name} — ${c.items.size} nummers" else "✗ lege lijst"
    } catch (e: Exception) { "✗ ${e.message ?: "mislukt"}" }
}
