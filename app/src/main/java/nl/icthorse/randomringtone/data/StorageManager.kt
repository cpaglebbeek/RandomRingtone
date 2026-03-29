package nl.icthorse.randomringtone.data

import android.content.Context
import android.os.Environment
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "storage_settings")

/**
 * Beheert opslaglocaties voor downloads (tijdelijk) en ringtones (permanent).
 *
 * Standaard locaties:
 * - Downloads (tijdelijk): {app-intern}/downloads/
 * - Ringtones (permanent): {extern}/Music/RandomRingtone/Ringtones/
 *
 * Beide configureerbaar via Instellingen.
 */
class StorageManager(private val context: Context) {

    companion object {
        private val KEY_DOWNLOAD_PATH = stringPreferencesKey("download_path")
        private val KEY_RINGTONE_PATH = stringPreferencesKey("ringtone_path")
        private val KEY_SPOTIFY_CONVERTER = stringPreferencesKey("spotify_converter")

        // Standaard subfolders
        private const val DEFAULT_DOWNLOAD_SUBFOLDER = "downloads"
        private const val DEFAULT_RINGTONE_SUBFOLDER = "Music/RandomRingtone/Ringtones"

        // Standaard Spotify converter
        const val DEFAULT_SPOTIFY_CONVERTER = "spotifydown"
    }

    // --- Standaard paden ---

    private val defaultDownloadDir: File
        get() = File(context.filesDir, DEFAULT_DOWNLOAD_SUBFOLDER).apply { mkdirs() }

    private val defaultRingtoneDir: File
        get() = File(
            Environment.getExternalStorageDirectory(),
            DEFAULT_RINGTONE_SUBFOLDER
        ).apply { mkdirs() }

    // --- Huidige paden ophalen ---

    /**
     * Haal het huidige download-pad op (tijdelijke MP3's).
     */
    suspend fun getDownloadDir(): File {
        val custom = context.settingsStore.data.map { prefs ->
            prefs[KEY_DOWNLOAD_PATH]
        }.first()

        return if (custom != null) {
            File(custom).apply { mkdirs() }
        } else {
            defaultDownloadDir
        }
    }

    /**
     * Haal het huidige ringtone-pad op (getrimde permanente bestanden).
     */
    suspend fun getRingtoneDir(): File {
        val custom = context.settingsStore.data.map { prefs ->
            prefs[KEY_RINGTONE_PATH]
        }.first()

        return if (custom != null) {
            File(custom).apply { mkdirs() }
        } else {
            defaultRingtoneDir
        }
    }

    // --- Paden instellen ---

    /**
     * Stel een custom download-pad in.
     */
    suspend fun setDownloadDir(path: String) {
        context.settingsStore.edit { prefs ->
            prefs[KEY_DOWNLOAD_PATH] = path
        }
        File(path).mkdirs()
    }

    /**
     * Stel een custom ringtone-pad in.
     */
    suspend fun setRingtoneDir(path: String) {
        context.settingsStore.edit { prefs ->
            prefs[KEY_RINGTONE_PATH] = path
        }
        File(path).mkdirs()
    }

    /**
     * Reset naar standaard paden.
     */
    suspend fun resetToDefaults() {
        context.settingsStore.edit { prefs ->
            prefs.remove(KEY_DOWNLOAD_PATH)
            prefs.remove(KEY_RINGTONE_PATH)
        }
    }

    // --- Spotify Converter ---

    suspend fun getSpotifyConverter(): String {
        val saved = context.settingsStore.data.map { prefs ->
            prefs[KEY_SPOTIFY_CONVERTER]
        }.first()
        return saved ?: DEFAULT_SPOTIFY_CONVERTER
    }

    suspend fun setSpotifyConverter(converterId: String) {
        context.settingsStore.edit { prefs ->
            prefs[KEY_SPOTIFY_CONVERTER] = converterId
        }
    }

    // --- Bestandsbeheer ---

    /**
     * Download-bestand pad voor een track.
     */
    suspend fun getDownloadFile(trackId: Long): File {
        return File(getDownloadDir(), "download_$trackId.mp3")
    }

    /**
     * Ringtone-bestand pad voor een getrimde track.
     */
    suspend fun getRingtoneFile(trackId: Long, playlistName: String? = null): File {
        val suffix = if (playlistName != null) "_${playlistName}" else ""
        return File(getRingtoneDir(), "ringtone_${trackId}${suffix}.mp3")
    }

    /**
     * Wis alle tijdelijke downloads.
     */
    suspend fun clearDownloads() {
        getDownloadDir().listFiles()?.forEach { it.delete() }
    }

    /**
     * Wis alle opgeslagen ringtones.
     */
    suspend fun clearRingtones() {
        getRingtoneDir().listFiles()?.forEach { it.delete() }
    }

    /**
     * Bereken schijfgebruik per map.
     */
    suspend fun getDiskUsage(): DiskUsage {
        val downloadSize = getDownloadDir().listFiles()?.sumOf { it.length() } ?: 0L
        val ringtoneSize = getRingtoneDir().listFiles()?.sumOf { it.length() } ?: 0L
        val downloadCount = getDownloadDir().listFiles()?.size ?: 0
        val ringtoneCount = getRingtoneDir().listFiles()?.size ?: 0
        return DiskUsage(
            downloadBytes = downloadSize,
            ringtoneBytes = ringtoneSize,
            downloadCount = downloadCount,
            ringtoneCount = ringtoneCount
        )
    }
}

data class SpotifyConverter(
    val id: String,
    val name: String,
    val type: String,
    val url: String
) {
    companion object {
        val ALL = listOf(
            SpotifyConverter("spotifydown", "SpotifyDown", "Online", "https://spotifydown.com"),
            SpotifyConverter("spotifymate", "SpotifyMate", "Online", "https://spotifymate.com"),
            SpotifyConverter("soundloaders", "Soundloaders", "Online", "https://soundloaders.com"),
            SpotifyConverter("spotify-downloader", "Spotify-downloader", "Online", "https://spotify-downloader.com"),
            SpotifyConverter("spotisongdownloader", "SpotiSongDownloader", "Online", "https://spotisongdownloader.com"),
            SpotifyConverter("spotifydownload", "Spotifydownload", "Online", "https://spotifydownload.org"),
            SpotifyConverter("spotidown", "Spotidown", "Online", "https://spotidown.app"),
            SpotifyConverter("keepvid", "KEEPVID", "Online", "https://keepvid.to"),
            SpotifyConverter("spotiflyer", "SpotiFlyer", "Android", "https://github.com/Shabinder/SpotiFlyer")
        )

        fun findById(id: String): SpotifyConverter =
            ALL.find { it.id == id } ?: ALL.first()
    }
}

data class DiskUsage(
    val downloadBytes: Long,
    val ringtoneBytes: Long,
    val downloadCount: Int,
    val ringtoneCount: Int
) {
    fun downloadFormatted(): String = formatBytes(downloadBytes)
    fun ringtoneFormatted(): String = formatBytes(ringtoneBytes)
    fun totalFormatted(): String = formatBytes(downloadBytes + ringtoneBytes)

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
}
