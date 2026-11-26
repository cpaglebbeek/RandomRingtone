package nl.icthorse.randomringtone.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Track-model dat door de ringtone-motor, de bibliotheek en de back-ups wordt gebruikt (historische naam).
 * v2.6.0: de Deezer-API-client zelf is verwijderd — de app haalt niets meer bij Deezer op.
 */

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
