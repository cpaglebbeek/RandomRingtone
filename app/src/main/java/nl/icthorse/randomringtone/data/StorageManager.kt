package nl.icthorse.randomringtone.data

import android.content.Context
import android.os.Environment
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
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
        private val KEY_BACKUP_URI = stringPreferencesKey("backup_uri")
        private val KEY_DIRECT_API = booleanPreferencesKey("use_direct_api")

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
            context.getExternalFilesDir(Environment.DIRECTORY_MUSIC),
            "RandomRingtone/Ringtones"
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

    // --- Backup URI ---

    suspend fun getBackupUri(): String? {
        return context.settingsStore.data.map { prefs ->
            prefs[KEY_BACKUP_URI]
        }.first()
    }

    suspend fun setBackupUri(uri: String) {
        context.settingsStore.edit { prefs ->
            prefs[KEY_BACKUP_URI] = uri
        }
    }

    // --- Directe API download ---

    suspend fun isDirectApiEnabled(): Boolean {
        return context.settingsStore.data.map { prefs ->
            prefs[KEY_DIRECT_API] ?: false
        }.first()
    }

    suspend fun setDirectApiEnabled(enabled: Boolean) {
        context.settingsStore.edit { prefs ->
            prefs[KEY_DIRECT_API] = enabled
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
     * Kopieer alle bestanden van oldDir naar newDir.
     * @return aantal gekopieerde bestanden
     */
    suspend fun copyFilesToNewDir(oldDir: File, newDir: File): Int = withContext(Dispatchers.IO) {
        newDir.mkdirs()
        var count = 0
        oldDir.listFiles()?.filter { it.isFile }?.forEach { file ->
            file.copyTo(File(newDir, file.name), overwrite = true)
            count++
        }
        count
    }

    /**
     * Verplaats alle bestanden van oldDir naar newDir.
     * @return aantal verplaatste bestanden
     */
    suspend fun moveFilesToNewDir(oldDir: File, newDir: File): Int = withContext(Dispatchers.IO) {
        newDir.mkdirs()
        var count = 0
        oldDir.listFiles()?.filter { it.isFile }?.forEach { file ->
            val dest = File(newDir, file.name)
            if (file.renameTo(dest)) {
                count++
            } else {
                // Fallback: copy + delete (cross-filesystem move)
                file.copyTo(dest, overwrite = true)
                file.delete()
                count++
            }
        }
        count
    }

    /**
     * Scan bestaande MP3 bestanden in download- en ringtone-mappen.
     * Herkent: download_<id>.mp3, ringtone_<id>.mp3, ringtone_<id>_<playlist>.mp3,
     * spotify_mp3_<track>-<artiest>.mp3
     * @return lijst van SavedTrack objecten
     */
    data class ScanResult(
        val files: List<ScannedFile>,
        val ringtoneDir: String,
        val downloadDir: String,
        val ringtoneDirExists: Boolean,
        val downloadDirExists: Boolean,
        val ringtoneDirCount: Int,
        val downloadDirCount: Int
    )

    suspend fun scanExistingFiles(): ScanResult = withContext(Dispatchers.IO) {
        val results = mutableListOf<ScannedFile>()
        val seen = mutableSetOf<Long>()

        val audioExtensions = setOf("mp3", "m4a")

        val rtDir = getRingtoneDir()
        val dlDir = getDownloadDir()
        val rtExists = rtDir.exists() && rtDir.isDirectory
        val dlExists = dlDir.exists() && dlDir.isDirectory

        var rtCount = 0
        var dlCount = 0

        // Scan ringtones map
        if (rtExists) {
            val rtFiles = rtDir.listFiles()
            rtCount = rtFiles?.size ?: -1 // -1 = listFiles() returned null
            rtFiles?.filter { it.isFile && it.extension.lowercase() in audioExtensions }?.forEach { file ->
                val parsed = parseFileName(file)
                if (parsed != null && parsed.trackId !in seen) {
                    seen.add(parsed.trackId)
                    results.add(parsed.copy(localPath = file.absolutePath, source = "ringtone"))
                }
            }
        }

        // Scan downloads map
        if (dlExists) {
            val dlFiles = dlDir.listFiles()
            dlCount = dlFiles?.size ?: -1
            dlFiles?.filter { it.isFile && it.extension.lowercase() in audioExtensions }?.forEach { file ->
                val parsed = parseFileName(file)
                if (parsed != null && parsed.trackId !in seen) {
                    seen.add(parsed.trackId)
                    results.add(parsed.copy(localPath = file.absolutePath, source = "download"))
                }
            }
        }

        ScanResult(
            files = results,
            ringtoneDir = rtDir.absolutePath,
            downloadDir = dlDir.absolutePath,
            ringtoneDirExists = rtExists,
            downloadDirExists = dlExists,
            ringtoneDirCount = rtCount,
            downloadDirCount = dlCount
        )
    }

    private fun parseFileName(file: File): ScannedFile? {
        val name = file.nameWithoutExtension

        // download_<id>
        val downloadMatch = Regex("^download_(\\d+)$").matchEntire(name)
        if (downloadMatch != null) {
            val id = downloadMatch.groupValues[1].toLongOrNull() ?: return null
            return ScannedFile(trackId = id, title = "Track $id", artist = "Onbekend", localPath = "")
        }

        // ringtone_<id> of ringtone_<id>_<playlist>
        val ringtoneMatch = Regex("^ringtone_(\\d+)(?:_(.+))?$").matchEntire(name)
        if (ringtoneMatch != null) {
            val id = ringtoneMatch.groupValues[1].toLongOrNull() ?: return null
            val playlist = ringtoneMatch.groupValues[2].ifBlank { null }
            return ScannedFile(trackId = id, title = "Track $id", artist = "Onbekend",
                localPath = "", playlistName = playlist)
        }

        // spotify_mp3_<track>-<artiest>
        val spotifyMatch = Regex("^spotify_mp3_(.+)-(.+)$").matchEntire(name)
        if (spotifyMatch != null) {
            val track = spotifyMatch.groupValues[1].replace("_", " ").trim()
            val artist = spotifyMatch.groupValues[2].replace("_", " ").trim()
            val id = name.hashCode().toLong().let { if (it < 0) -it else it }
            return ScannedFile(trackId = id, title = track, artist = artist, localPath = "")
        }

        // Onbekend formaat — gebruik bestandsnaam als titel
        val id = name.hashCode().toLong().let { if (it < 0) -it else it }
        return ScannedFile(trackId = id, title = name.replace("_", " "), artist = "Onbekend", localPath = "")
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

data class ScannedFile(
    val trackId: Long,
    val title: String,
    val artist: String,
    val localPath: String,
    val playlistName: String? = null,
    val source: String = ""
)

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
            SpotifyConverter("spotmate", "SpotMate", "Online", "https://spotmate.online/en1"),
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
