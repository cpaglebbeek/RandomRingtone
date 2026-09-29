package nl.icthorse.randomringtone.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.serialization.Serializable

/** settings.json in backups (gedeeld door SAF-, lokale en cloud-restore). */
@Serializable
data class SettingsBackupData(
    val downloadPath: String? = null,
    val ringtonePath: String? = null,
    val spotifyConverter: String = StorageManager.DEFAULT_SPOTIFY_CONVERTER,
    val backupUri: String? = null
)

/**
 * Gedeelde stappen na/tijdens elke restore (v2.2.0):
 * - instellingen toepassen VÓÓR de doelmappen bepaald worden
 * - contactplaylists zonder URI op naam koppelen (anders uitzetten, nooit globaal)
 * - ringtones van actieve CALL-playlists direct zetten
 */
object RestoreSupport {

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

    fun summary(unresolved: List<String>, applyFailures: List<String>): String = buildString {
        if (unresolved.isNotEmpty()) append("\nContact niet gevonden (playlist uitgezet): ${unresolved.joinToString()}")
        if (applyFailures.isNotEmpty()) append("\nRingtone niet gezet: ${applyFailures.joinToString()}")
    }
}
