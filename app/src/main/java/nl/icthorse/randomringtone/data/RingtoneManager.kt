package nl.icthorse.randomringtone.data

import android.content.ContentValues
import android.content.Context
import android.media.RingtoneManager as AndroidRingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Beheert het downloaden van MP3 previews en het instellen als system ringtone.
 */
class AppRingtoneManager(private val context: Context) {

    private val client = OkHttpClient()
    private val ringtoneDir = File(context.filesDir, "ringtones").apply { mkdirs() }

    /**
     * Download een MP3 preview en sla lokaal op.
     * @return File pad naar het opgeslagen bestand
     */
    suspend fun downloadPreview(track: DeezerTrack): File = withContext(Dispatchers.IO) {
        val fileName = "ringtone_${track.id}.mp3"
        val file = File(ringtoneDir, fileName)

        if (file.exists()) return@withContext file

        val request = Request.Builder().url(track.preview).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Download failed: ${response.code}")
            response.body?.byteStream()?.use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        file
    }

    /**
     * Stel een gedownload MP3 bestand in als system ringtone.
     * Vereist WRITE_SETTINGS permissie.
     */
    suspend fun setAsRingtone(track: DeezerTrack, file: File): Boolean = withContext(Dispatchers.IO) {
        if (!Settings.System.canWrite(context)) return@withContext false

        val uri = addToMediaStore(track, file) ?: return@withContext false
        AndroidRingtoneManager.setActualDefaultRingtoneUri(
            context,
            AndroidRingtoneManager.TYPE_RINGTONE,
            uri
        )
        true
    }

    /**
     * Voeg MP3 toe aan MediaStore zodat het systeem het kan vinden.
     */
    private fun addToMediaStore(track: DeezerTrack, file: File): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "${track.artist.name} - ${track.titleShort}")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.IS_RINGTONE, true)
            put(MediaStore.Audio.Media.IS_NOTIFICATION, false)
            put(MediaStore.Audio.Media.IS_ALARM, false)
            put(MediaStore.Audio.Media.IS_MUSIC, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_RINGTONES}/RandomRingtone")
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        // Verwijder eventueel bestaand item met dezelfde naam
        context.contentResolver.delete(
            collection,
            "${MediaStore.Audio.Media.DISPLAY_NAME} = ?",
            arrayOf("${track.artist.name} - ${track.titleShort}")
        )

        val uri = context.contentResolver.insert(collection, values) ?: return null

        context.contentResolver.openOutputStream(uri)?.use { output ->
            file.inputStream().use { input ->
                input.copyTo(output)
            }
        }

        return uri
    }

    /**
     * Check of de app WRITE_SETTINGS permissie heeft.
     */
    fun canWriteSettings(): Boolean = Settings.System.canWrite(context)

    /**
     * Verwijder alle opgeslagen ringtone bestanden.
     */
    fun clearCache() {
        ringtoneDir.listFiles()?.forEach { it.delete() }
    }
}
