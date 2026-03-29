package nl.icthorse.randomringtone.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Deezer API client — geen auth nodig, volledig open.
 * https://api.deezer.com/
 */
class DeezerApi {

    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val baseUrl = "https://api.deezer.com"

    /**
     * Zoek tracks op query (artiest, nummer, etc.)
     */
    suspend fun searchTracks(query: String, limit: Int = 25): List<DeezerTrack> =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/search?q=${query.encodeUrl()}&limit=$limit"
            val response = fetch(url)
            val result = json.decodeFromString<DeezerSearchResult>(response)
            result.data
        }

    /**
     * Haal een publieke playlist op via ID.
     */
    suspend fun getPlaylist(playlistId: Long): DeezerPlaylist =
        withContext(Dispatchers.IO) {
            val response = fetch("$baseUrl/playlist/$playlistId")
            json.decodeFromString<DeezerPlaylist>(response)
        }

    /**
     * Haal track details op (inclusief preview URL).
     */
    suspend fun getTrack(trackId: Long): DeezerTrack =
        withContext(Dispatchers.IO) {
            val response = fetch("$baseUrl/track/$trackId")
            json.decodeFromString<DeezerTrack>(response)
        }

    /**
     * Haal top tracks van een artiest op.
     */
    suspend fun getArtistTopTracks(artistId: Long, limit: Int = 25): List<DeezerTrack> =
        withContext(Dispatchers.IO) {
            val response = fetch("$baseUrl/artist/$artistId/top?limit=$limit")
            val result = json.decodeFromString<DeezerSearchResult>(response)
            result.data
        }

    private fun fetch(url: String): String {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw DeezerApiException("HTTP ${response.code}: ${response.message}")
            return response.body?.string() ?: throw DeezerApiException("Empty response body")
        }
    }

    private fun String.encodeUrl(): String =
        java.net.URLEncoder.encode(this, "UTF-8")
}

class DeezerApiException(message: String) : Exception(message)

// --- Data models ---

@Serializable
data class DeezerSearchResult(
    val data: List<DeezerTrack> = emptyList(),
    val total: Int = 0
)

@Serializable
data class DeezerTrack(
    val id: Long,
    val title: String,
    @SerialName("title_short") val titleShort: String = "",
    val duration: Int = 0,
    val preview: String = "",  // Direct MP3 URL (30 sec)
    val artist: DeezerArtist = DeezerArtist(),
    val album: DeezerAlbum = DeezerAlbum()
) {
    val hasPreview: Boolean get() = preview.isNotBlank()
}

@Serializable
data class DeezerArtist(
    val id: Long = 0,
    val name: String = "",
    val picture: String = "",
    @SerialName("picture_medium") val pictureMedium: String = ""
)

@Serializable
data class DeezerAlbum(
    val id: Long = 0,
    val title: String = "",
    val cover: String = "",
    @SerialName("cover_medium") val coverMedium: String = ""
)

@Serializable
data class DeezerPlaylist(
    val id: Long,
    val title: String,
    @SerialName("nb_tracks") val nbTracks: Int = 0,
    val picture: String = "",
    @SerialName("picture_medium") val pictureMedium: String = "",
    val tracks: DeezerSearchResult = DeezerSearchResult()
)
