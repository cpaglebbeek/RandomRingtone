package nl.icthorse.randomringtone.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException

/** settings.json in backups (gedeeld door SAF-, lokale en cloud-restore). */
@Serializable
data class SettingsBackupData(
    val downloadPath: String? = null,
    val ringtonePath: String? = null,
    val spotifyConverter: String = StorageManager.DEFAULT_SPOTIFY_CONVERTER,
    val backupUri: String? = null
)

/**
 * Wat een restore nodig heeft, afgeleid uit de BACKUP (niet uit de huidige toestand van het toestel) — v2.2.1.
 * SetupCheck gebruikt dit om vóór de restore de juiste rechten te vragen en de doelmappen te testen.
 */
data class RestoreTarget(
    val needsContacts: Boolean,
    val needsPhone: Boolean,
    val downloadPath: String?,
    val ringtonePath: String?
)

/** Uitkomst van [RestoreSupport.prepareTarget]. */
enum class TargetAction { SKIP_SAME, WRITE }

/**
 * Gedeelde stappen na/tijdens elke restore (v2.2.0):
 * - instellingen toepassen VÓÓR de doelmappen bepaald worden
 * - contactplaylists zonder URI op naam koppelen (anders uitzetten, nooit globaal)
 * - ringtones van actieve CALL-playlists direct zetten
 */
object RestoreSupport {

    /** Leid uit de backup af welke rechten en mappen de restore nodig heeft. */
    fun planFrom(playlists: List<PlaylistBackup>, settings: SettingsBackupData?): RestoreTarget {
        val active = playlists.filter { it.isActive }
        return RestoreTarget(
            needsContacts = active.any { !it.contactUri.isNullOrBlank() || !it.contactName.isNullOrBlank() },
            needsPhone = active.any { it.schedule == Schedule.EVERY_CALL.name },
            downloadPath = settings?.downloadPath,
            ringtonePath = settings?.ringtonePath
        )
    }

    /**
     * Maak [dest] klaar om te schrijven. Staat er al een bestand met dezelfde grootte ⇒ [TargetAction.SKIP_SAME]
     * (niets te doen). Staat er een ander bestand ⇒ eerst weghalen: Android 11+ laat een app een bestand van een
     * EERDERE installatie (andere eigenaar) niet overschrijven, wel soms verwijderen. Lukt ook dat niet ⇒ IOException.
     */
    fun prepareTarget(dest: File, expectedSize: Long?): TargetAction {
        dest.parentFile?.mkdirs()
        if (!dest.exists()) return TargetAction.WRITE
        if (expectedSize != null && expectedSize > 0 && dest.length() == expectedSize) return TargetAction.SKIP_SAME
        if (!dest.delete() && dest.exists()) throw IOException(noRights(dest))
        return TargetAction.WRITE
    }

    /** Schrijf [dest] via [write]; bij een fout het halve bestand opruimen en een leesbare melding geven. */
    fun writeTarget(dest: File, write: (java.io.OutputStream) -> Unit) {
        try {
            dest.outputStream().use(write)
        } catch (e: Exception) {
            runCatching { dest.delete() }
            val msg = e.message ?: ""
            throw IOException(if ("EACCES" in msg || "Permission denied" in msg || e is SecurityException) noRights(dest) else "${dest.name}: $msg", e)
        }
    }

    private fun noRights(f: File) =
        "Geen schrijfrechten op ${f.name} in ${f.parent} (bestand van een eerdere installatie of andere app?)"

    fun logResult(kind: String, ok: Boolean, message: String, failed: List<String> = emptyList()) {
        val data = mapOf("ok" to ok.toString(), "message" to message.take(500), "failed" to failed.take(20).joinToString(" | "))
        if (ok) RemoteLogger.i("Restore", "Resultaat $kind", data) else RemoteLogger.e("Restore", "Resultaat $kind", data)
    }

    suspend fun applySettings(s: SettingsBackupData, storage: StorageManager) {
        if (s.downloadPath != null) storage.setDownloadDir(s.downloadPath)
        if (s.ringtonePath != null) storage.setRingtoneDir(s.ringtonePath)
        storage.setSpotifyConverter(s.spotifyConverter)
        if (s.backupUri != null) storage.setBackupUri(s.backupUri)
    }

    fun resolveContacts(context: Context, playlists: List<PlaylistBackup>): ContactMatcher.Resolved {
        if (playlists.none { ContactMatcher.needsResolve(it) }) {
            return ContactMatcher.Resolved(playlists, emptyList())
        }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        val contacts = if (granted) {
            try { ContactsRepository(context).getContacts() } catch (_: Exception) { null }
        } else null
        val resolved = ContactMatcher.resolve(playlists, contacts)
        RemoteLogger.i("Restore", "Contacten op naam gekoppeld", mapOf(
            "readContacts" to granted.toString(),
            "unresolved" to resolved.unresolved.joinToString()
        ))
        return resolved
    }

    /** Zet voor elke actieve CALL-playlist meteen een ringtone. Fouten worden gelogd, niet gegooid. */
    suspend fun applyActiveCallPlaylists(context: Context, db: RingtoneDatabase): List<String> {
        val failures = mutableListOf<String>()
        val resolver = TrackResolver(db, AppRingtoneManager(context), context)
        val active = try { db.playlistDao().getAll().filter { it.isActive && it.channel == Channel.CALL } } catch (_: Exception) { emptyList() }
        for (p in active) {
            try {
                val r = resolver.applyCallPlaylist(p)
                if (!r.success) failures.add("${p.name}: ${r.error}")
            } catch (e: Exception) {
                failures.add("${p.name}: ${e.message}")
            }
        }
        RemoteLogger.i("Restore", "Ringtones na restore toegepast", mapOf(
            "playlists" to active.size.toString(), "failures" to failures.joinToString(" | ")
        ))
        return failures
    }

    fun summary(unresolved: List<String>, applyFailures: List<String>, fileFailures: List<String> = emptyList()): String = buildString {
        if (fileFailures.isNotEmpty()) append("\n${fileFailures.size} bestand(en) niet teruggezet: ${fileFailures.take(5).joinToString()}" +
            if (fileFailures.size > 5) " …" else "")
        if (unresolved.isNotEmpty()) append("\nContact niet gevonden (playlist uitgezet): ${unresolved.joinToString()}")
        if (applyFailures.isNotEmpty()) append("\nRingtone niet gezet: ${applyFailures.joinToString()}")
    }
}
