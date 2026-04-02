package nl.icthorse.randomringtone.data

import android.content.ContentValues
import android.content.Context
import android.content.ContentUris
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
 *
 * Architectuur: Vaste bestandsnamen in MediaStore.
 * - Globale ringtone: "RandomRingtone_Global" → eenmalig URI instellen, daarna alleen content swappen
 * - Per-contact: "<contactnaam>-RandomRing" → eenmalig ContactsContract, daarna alleen content swappen
 *
 * Android leest de ringtone URI pas af bij afspelen, dus als we de file content
 * swappen maar de URI hetzelfde houden, speelt Android automatisch de nieuwe track.
 */
class AppRingtoneManager(private val context: Context) {

    private val client = OkHttpClient()
    val storage = StorageManager(context)

    companion object {
        const val GLOBAL_DISPLAY_NAME = "RandomRingtone_Global"
        const val CONTACT_SUFFIX = "-RandomRing"
    }

    // ── Download ─────────────────────────────────────────────────

    suspend fun downloadPreview(track: DeezerTrack): File = withContext(Dispatchers.IO) {
        val file = storage.getDownloadFile(track.id)

        if (file.exists()) {
            RemoteLogger.d("RingtoneManager", "Preview al gedownload, skip", mapOf("trackId" to track.id.toString(), "file" to file.name))
            return@withContext file
        }

        RemoteLogger.input("RingtoneManager", "Download preview", mapOf("trackId" to track.id.toString(), "title" to track.title, "url" to track.preview))
        val request = Request.Builder().url(track.preview).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                RemoteLogger.e("RingtoneManager", "Download FAILED", mapOf("code" to response.code.toString()))
                throw Exception("Download failed: ${response.code}")
            }
            response.body?.byteStream()?.use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        RemoteLogger.output("RingtoneManager", "Preview gedownload", mapOf("file" to file.name, "size" to "${file.length()/1024}KB"))
        file
    }

    suspend fun saveToRingtoneDir(sourceFile: File, trackId: Long, playlistName: String? = null): File =
        withContext(Dispatchers.IO) {
            val destFile = storage.getRingtoneFile(trackId, playlistName)
            sourceFile.copyTo(destFile, overwrite = true)
            destFile
        }

    // ── Vaste ringtone: Install (eerste keer) ───────────────────

    /**
     * Installeer een track als globale ringtone via vaste bestandsnaam.
     * Maakt of hergebruikt MediaStore entry "RandomRingtone_Global".
     * Stelt de systeem ringtone URI in (eenmalig of bij her-registratie).
     */
    suspend fun installGlobalRingtone(sourceFile: File): Boolean = withContext(Dispatchers.IO) {
        RemoteLogger.input("RingtoneManager", "installGlobalRingtone", mapOf(
            "source" to sourceFile.name, "size" to "${sourceFile.length()/1024}KB"
        ))

        if (!Settings.System.canWrite(context)) {
            RemoteLogger.e("RingtoneManager", "WRITE_SETTINGS niet toegestaan")
            return@withContext false
        }

        val uri = ensureMediaStoreEntry(GLOBAL_DISPLAY_NAME, sourceFile, isRingtone = true)
        if (uri == null) {
            RemoteLogger.e("RingtoneManager", "MediaStore registratie MISLUKT voor $GLOBAL_DISPLAY_NAME")
            return@withContext false
        }

        AndroidRingtoneManager.setActualDefaultRingtoneUri(
            context, AndroidRingtoneManager.TYPE_RINGTONE, uri
        )

        RemoteLogger.output("RingtoneManager", "GLOBALE RINGTONE GEINSTALLEERD", mapOf(
            "displayName" to GLOBAL_DISPLAY_NAME,
            "uri" to uri.toString()
        ))
        true
    }

    /**
     * Installeer een track als per-contact ringtone via vaste bestandsnaam.
     * Maakt of hergebruikt MediaStore entry "<contactnaam>-RandomRing".
     * Stelt ContactsContract CUSTOM_RINGTONE in.
     */
    suspend fun installContactRingtone(sourceFile: File, contactName: String, contactUri: String): Boolean = withContext(Dispatchers.IO) {
        val displayName = sanitizeContactDisplayName(contactName)
        RemoteLogger.input("RingtoneManager", "installContactRingtone", mapOf(
            "contact" to contactName, "displayName" to displayName, "source" to sourceFile.name
        ))

        if (!Settings.System.canWrite(context)) {
            RemoteLogger.e("RingtoneManager", "WRITE_SETTINGS niet toegestaan")
            return@withContext false
        }

        val uri = ensureMediaStoreEntry(displayName, sourceFile, isRingtone = true)
        if (uri == null) {
            RemoteLogger.e("RingtoneManager", "MediaStore registratie MISLUKT voor $displayName")
            return@withContext false
        }

        val contactsRepo = ContactsRepository(context)
        val set = contactsRepo.setContactRingtone(contactUri, uri)

        if (set) {
            RemoteLogger.output("RingtoneManager", "CONTACT RINGTONE GEINSTALLEERD", mapOf(
                "contact" to contactName, "displayName" to displayName, "uri" to uri.toString()
            ))
        } else {
            RemoteLogger.e("RingtoneManager", "ContactsContract update MISLUKT", mapOf("contact" to contactName))
        }
        set
    }

    // ── Vaste ringtone: Swap (delete+insert voor verse URI) ────

    /**
     * Swap de globale ringtone.
     * Delete+insert de MediaStore entry zodat de URI (content:// ID) verandert.
     * Android cachet ringtones per URI — zelfde URI = zelfde audio uit cache.
     * Verse URI forceert Android om de nieuwe content te lezen.
     * Bestandsnaam blijft altijd "RandomRingtone_Global".
     */
    suspend fun swapGlobalRingtone(sourceFile: File): Boolean = withContext(Dispatchers.IO) {
        RemoteLogger.input("RingtoneManager", "swapGlobalRingtone", mapOf(
            "source" to sourceFile.name,
            "sourceExists" to sourceFile.exists().toString(),
            "sourceSize" to "${sourceFile.length()}",
            "sourceCanRead" to sourceFile.canRead().toString()
        ))

        if (!Settings.System.canWrite(context)) {
            RemoteLogger.e("RingtoneManager", "WRITE_SETTINGS niet toegestaan — canWrite()=false")
            return@withContext false
        }
        RemoteLogger.d("RingtoneManager", "WRITE_SETTINGS OK")

        // Log huidige ringtone VOOR swap
        val currentBefore = AndroidRingtoneManager.getActualDefaultRingtoneUri(context, AndroidRingtoneManager.TYPE_RINGTONE)
        RemoteLogger.d("RingtoneManager", "Huidige systeem ringtone VOOR swap", mapOf("uri" to (currentBefore?.toString() ?: "null")))

        // Delete bestaande entry → verse URI bij insert
        deleteMediaStoreEntry(GLOBAL_DISPLAY_NAME)
        val uri = createMediaStoreEntry(GLOBAL_DISPLAY_NAME, sourceFile, isRingtone = true)
        if (uri == null) {
            RemoteLogger.e("RingtoneManager", "MediaStore INSERT mislukt voor $GLOBAL_DISPLAY_NAME")
            return@withContext false
        }

        // Verifieer dat content geschreven is
        val writtenSize = try {
            context.contentResolver.openInputStream(uri)?.use { it.available().toLong() } ?: -1L
        } catch (e: Exception) { -2L }
        RemoteLogger.d("RingtoneManager", "MediaStore content verificatie", mapOf(
            "uri" to uri.toString(),
            "sourceSize" to sourceFile.length().toString(),
            "writtenSize" to writtenSize.toString(),
            "match" to (writtenSize == sourceFile.length()).toString()
        ))

        AndroidRingtoneManager.setActualDefaultRingtoneUri(
            context, AndroidRingtoneManager.TYPE_RINGTONE, uri
        )

        // Log huidige ringtone NA swap — klopt het?
        val currentAfter = AndroidRingtoneManager.getActualDefaultRingtoneUri(context, AndroidRingtoneManager.TYPE_RINGTONE)
        val uriMatch = currentAfter?.toString() == uri.toString()
        RemoteLogger.output("RingtoneManager", "GLOBALE RINGTONE GESWAPPED", mapOf(
            "newUri" to uri.toString(),
            "systemUri" to (currentAfter?.toString() ?: "null"),
            "uriMatch" to uriMatch.toString(),
            "writtenSize" to writtenSize.toString()
        ))

        if (!uriMatch) {
            RemoteLogger.e("RingtoneManager", "SYSTEEM RINGTONE NIET GEWIJZIGD — setActualDefaultRingtoneUri GENEGEERD", mapOf(
                "expected" to uri.toString(),
                "actual" to (currentAfter?.toString() ?: "null")
            ))
        }

        true
    }

    /**
     * Swap een per-contact ringtone.
     * Zelfde strategie: delete+insert voor verse URI + ContactsContract update.
     * Bestandsnaam blijft altijd "<contactnaam>-RandomRing".
     */
    suspend fun swapContactRingtone(sourceFile: File, contactName: String, contactUri: String): Boolean = withContext(Dispatchers.IO) {
        val displayName = sanitizeContactDisplayName(contactName)
        RemoteLogger.input("RingtoneManager", "swapContactRingtone", mapOf(
            "contact" to contactName,
            "source" to sourceFile.name,
            "sourceExists" to sourceFile.exists().toString(),
            "sourceSize" to "${sourceFile.length()}"
        ))

        deleteMediaStoreEntry(displayName)
        val uri = createMediaStoreEntry(displayName, sourceFile, isRingtone = true)
        if (uri == null) {
            RemoteLogger.e("RingtoneManager", "MediaStore INSERT mislukt voor $displayName")
            return@withContext false
        }

        // Verifieer content
        val writtenSize = try {
            context.contentResolver.openInputStream(uri)?.use { it.available().toLong() } ?: -1L
        } catch (_: Exception) { -2L }
        RemoteLogger.d("RingtoneManager", "Contact MediaStore content verificatie", mapOf(
            "contact" to contactName, "uri" to uri.toString(),
            "sourceSize" to sourceFile.length().toString(),
            "writtenSize" to writtenSize.toString()
        ))

        val contactsRepo = ContactsRepository(context)
        val set = contactsRepo.setContactRingtone(contactUri, uri)
        RemoteLogger.output("RingtoneManager", "CONTACT RINGTONE GESWAPPED", mapOf(
            "contact" to contactName,
            "uri" to uri.toString(),
            "contactsContractUpdated" to set.toString(),
            "writtenSize" to writtenSize.toString()
        ))
        set
    }

    // ── MediaStore helpers ───────────────────────────────────────

    /**
     * Zoek een bestaande MediaStore entry op DISPLAY_NAME.
     * @return content:// URI of null als niet gevonden.
     */
    private fun findMediaStoreEntry(displayName: String): Uri? {
        val collection = mediaStoreCollection()
        try {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.Audio.Media._ID),
                "${MediaStore.Audio.Media.DISPLAY_NAME} = ? OR ${MediaStore.Audio.Media.DISPLAY_NAME} = ?",
                arrayOf(displayName, "$displayName.mp3"),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
                    return ContentUris.withAppendedId(collection, id)
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * Maak een nieuwe MediaStore entry of hergebruik bestaande.
     * Schrijft sourceFile content naar de entry.
     */
    private fun ensureMediaStoreEntry(displayName: String, sourceFile: File, isRingtone: Boolean): Uri? {
        val existing = findMediaStoreEntry(displayName)
        if (existing != null) {
            RemoteLogger.d("RingtoneManager", "MediaStore entry bestaat al, content overschrijven", mapOf("displayName" to displayName))
            overwriteMediaStoreContent(existing, sourceFile)
            return existing
        }

        RemoteLogger.d("RingtoneManager", "Nieuwe MediaStore entry aanmaken", mapOf("displayName" to displayName))
        val collection = mediaStoreCollection()

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.IS_RINGTONE, isRingtone)
            put(MediaStore.Audio.Media.IS_NOTIFICATION, false)
            put(MediaStore.Audio.Media.IS_ALARM, false)
            put(MediaStore.Audio.Media.IS_MUSIC, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_RINGTONES}/RandomRingtone")
            }
        }

        val uri = context.contentResolver.insert(collection, values) ?: return null
        overwriteMediaStoreContent(uri, sourceFile)
        return uri
    }

    /**
     * Overschrijf de content van een bestaande MediaStore entry.
     * Gebruikt door ensureMediaStoreEntry (install flow).
     */
    private fun overwriteMediaStoreContent(uri: Uri, sourceFile: File) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
            sourceFile.inputStream().use { input ->
                input.copyTo(output)
            }
        }
    }

    /**
     * Delete een MediaStore entry op display name.
     * Gebruikt door swap flow om verse URI te forceren.
     */
    private fun deleteMediaStoreEntry(displayName: String) {
        val collection = mediaStoreCollection()
        val deleted = context.contentResolver.delete(
            collection,
            "${MediaStore.Audio.Media.DISPLAY_NAME} = ? OR ${MediaStore.Audio.Media.DISPLAY_NAME} = ?",
            arrayOf(displayName, "$displayName.mp3")
        )
        RemoteLogger.d("RingtoneManager", "MediaStore DELETE", mapOf("displayName" to displayName, "deleted" to deleted.toString()))
    }

    /**
     * Maak een nieuwe MediaStore entry en schrijf sourceFile content.
     * @return Verse content:// URI met nieuw ID.
     */
    private fun createMediaStoreEntry(displayName: String, sourceFile: File, isRingtone: Boolean): Uri? {
        val collection = mediaStoreCollection()
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.IS_RINGTONE, isRingtone)
            put(MediaStore.Audio.Media.IS_NOTIFICATION, false)
            put(MediaStore.Audio.Media.IS_ALARM, false)
            put(MediaStore.Audio.Media.IS_MUSIC, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_RINGTONES}/RandomRingtone")
            }
        }
        val uri = context.contentResolver.insert(collection, values) ?: return null
        context.contentResolver.openOutputStream(uri)?.use { output ->
            sourceFile.inputStream().use { input -> input.copyTo(output) }
        }
        RemoteLogger.d("RingtoneManager", "MediaStore INSERT", mapOf("displayName" to displayName, "uri" to uri.toString()))
        return uri
    }

    private fun mediaStoreCollection(): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
    }

    private fun sanitizeContactDisplayName(contactName: String): String {
        val safe = contactName.replace(Regex("[^a-zA-Z0-9_\\- ]"), "_").trim()
        return "$safe$CONTACT_SUFFIX"
    }

    // ── Legacy methoden (nog gebruikt door EditorScreen, SettingsScreen) ──

    /**
     * Stel een MP3 in als system ringtone via legacy flow (nieuwe MediaStore entry per keer).
     * Nog gebruikt door EditorScreen "instellen als hoofdringtone" checkbox.
     */
    suspend fun setAsRingtone(track: DeezerTrack, file: File): Boolean = withContext(Dispatchers.IO) {
        RemoteLogger.input("RingtoneManager", "setAsRingtone (legacy)", mapOf(
            "track" to "${track.artist.name} - ${track.titleShort}"
        ))

        if (!Settings.System.canWrite(context)) {
            RemoteLogger.e("RingtoneManager", "WRITE_SETTINGS niet toegestaan")
            return@withContext false
        }

        val uri = addToMediaStoreLegacy(track, file) ?: return@withContext false
        AndroidRingtoneManager.setActualDefaultRingtoneUri(
            context, AndroidRingtoneManager.TYPE_RINGTONE, uri
        )
        RemoteLogger.output("RingtoneManager", "SYSTEEM RINGTONE INGESTELD (legacy)", mapOf(
            "track" to "${track.artist.name} - ${track.titleShort}", "uri" to uri.toString()
        ))
        true
    }

    suspend fun setAsNotification(track: DeezerTrack, file: File): Boolean = withContext(Dispatchers.IO) {
        RemoteLogger.input("RingtoneManager", "setAsNotification", mapOf("track" to "${track.artist.name} - ${track.titleShort}"))

        if (!Settings.System.canWrite(context)) {
            RemoteLogger.e("RingtoneManager", "WRITE_SETTINGS niet toegestaan")
            return@withContext false
        }

        val uri = addToMediaStoreLegacy(track, file, isNotification = true) ?: return@withContext false
        AndroidRingtoneManager.setActualDefaultRingtoneUri(
            context, AndroidRingtoneManager.TYPE_NOTIFICATION, uri
        )
        RemoteLogger.output("RingtoneManager", "SYSTEEM NOTIFICATIE INGESTELD", mapOf(
            "track" to "${track.artist.name} - ${track.titleShort}", "uri" to uri.toString()
        ))
        true
    }

    fun addToMediaStorePublic(track: DeezerTrack, file: File): Uri? {
        return addToMediaStoreLegacy(track, file)
    }

    private fun addToMediaStoreLegacy(track: DeezerTrack, file: File, isNotification: Boolean = false): Uri? {
        val prefix = if (isNotification) "notif_" else ""
        val displayName = "$prefix${track.artist.name} - ${track.titleShort}"
        val collection = mediaStoreCollection()

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.IS_RINGTONE, !isNotification)
            put(MediaStore.Audio.Media.IS_NOTIFICATION, isNotification)
            put(MediaStore.Audio.Media.IS_ALARM, false)
            put(MediaStore.Audio.Media.IS_MUSIC, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val dir = if (isNotification) Environment.DIRECTORY_NOTIFICATIONS else Environment.DIRECTORY_RINGTONES
                put(MediaStore.Audio.Media.RELATIVE_PATH, "$dir/RandomRingtone")
            }
        }

        context.contentResolver.delete(collection, "${MediaStore.Audio.Media.DISPLAY_NAME} = ?", arrayOf(displayName))
        val uri = context.contentResolver.insert(collection, values) ?: return null
        context.contentResolver.openOutputStream(uri)?.use { output ->
            file.inputStream().use { input -> input.copyTo(output) }
        }
        return uri
    }

    fun canWriteSettings(): Boolean = Settings.System.canWrite(context)

    suspend fun clearDownloads() { storage.clearDownloads() }
    suspend fun clearRingtones() { storage.clearRingtones() }

    fun clearCache() {
        File(context.filesDir, "ringtones").listFiles()?.forEach { it.delete() }
    }
}
