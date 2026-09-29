package nl.icthorse.randomringtone.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Rechten- en opslagcontrole (v2.1.0): bij opstarten, vóór restore en vóór het openen van de Bibliotheek.
 * [SetupRules] is zuivere logica (testbaar); [SetupCheck] verzamelt de feiten op het toestel.
 */
enum class Severity { BLOCKER, WARNING, INFO }

enum class CheckScope { STARTUP, RESTORE, LIBRARY }

enum class FixAction { WRITE_SETTINGS, REQUEST_PERMISSIONS, INSTALL_SOURCES, OPEN_STORAGE_SETTINGS, PICK_BACKUP_DIR, NONE }

data class SetupIssue(
    val id: String,
    val severity: Severity,
    val title: String,
    val detail: String,
    val fix: FixAction,
    val permissions: List<String> = emptyList()
)

data class DirFact(
    val label: String,
    val path: String,
    val exists: Boolean,
    val isDirectory: Boolean,
    val writable: Boolean,
    val freeBytes: Long
)

data class BackupDirFact(val configured: Boolean, val permissionHeld: Boolean, val reachable: Boolean)

data class SetupFacts(
    val sdkInt: Int,
    val canWriteSettings: Boolean,
    val granted: Set<String>,
    val canInstallPackages: Boolean,
    val needsPhone: Boolean,
    val needsContacts: Boolean,
    val dirs: List<DirFact>,
    val backupDir: BackupDirFact,
    val tracksWithFile: Int,
    val tracksMissingFile: Int,
    val restoreBytesNeeded: Long? = null,
    val restoreUsesLocalFolder: Boolean = false
)

object SetupRules {
    const val LOW_SPACE_BYTES = 200L * 1024 * 1024
    const val RESTORE_MARGIN_BYTES = 50L * 1024 * 1024

    private const val READ_MEDIA_AUDIO = "android.permission.READ_MEDIA_AUDIO"
    private const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    val PHONE_PERMISSIONS = listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG)
    val CONTACT_PERMISSIONS = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)

    fun evaluate(f: SetupFacts, scope: CheckScope): List<SetupIssue> {
        val out = mutableListOf<SetupIssue>()

        // ── App-rechten ──
        if (!f.canWriteSettings) out += SetupIssue("write_settings", Severity.BLOCKER, "Systeeminstellingen wijzigen",
            "Zonder dit recht kan RandomRingtone je ringtone niet wisselen.", FixAction.WRITE_SETTINGS)
        if (f.sdkInt >= 33 && READ_MEDIA_AUDIO !in f.granted) out += SetupIssue("read_media_audio", Severity.WARNING,
            "Audiobestanden lezen", "Nodig om muziek op je telefoon in de bibliotheek te gebruiken.",
            FixAction.REQUEST_PERMISSIONS, listOf(READ_MEDIA_AUDIO))
        if (f.sdkInt >= 33 && POST_NOTIFICATIONS !in f.granted) out += SetupIssue("notifications", Severity.WARNING,
            "Meldingen", "Je krijgt geen melding als je ringtone wisselt of een backup klaar is.",
            FixAction.REQUEST_PERMISSIONS, listOf(POST_NOTIFICATIONS))
        val phoneMissing = PHONE_PERMISSIONS.filter { it !in f.granted }
        if (f.needsPhone && phoneMissing.isNotEmpty()) out += SetupIssue("phone", Severity.BLOCKER, "Telefoonstatus en gesprekslog",
            "Een actieve playlist wisselt na elk gesprek; daarvoor moet de app gesprekken kunnen zien.",
            FixAction.REQUEST_PERMISSIONS, phoneMissing)
        val contactsMissing = CONTACT_PERMISSIONS.filter { it !in f.granted }
        if (f.needsContacts && contactsMissing.isNotEmpty()) out += SetupIssue("contacts", Severity.BLOCKER, "Contacten",
            "Een actieve playlist hoort bij een contact; daarvoor moet de app contacten kunnen lezen en wijzigen.",
            FixAction.REQUEST_PERMISSIONS, contactsMissing)
        if (!f.canInstallPackages) out += SetupIssue("install", Severity.INFO, "Updates installeren",
            "Nodig om updates vanuit de app te installeren.", FixAction.INSTALL_SOURCES)

        // ── Mappen ──
        for (d in f.dirs) {
            when {
                !d.exists || !d.isDirectory -> out += SetupIssue("dir_missing_${d.label}", Severity.BLOCKER,
                    "${d.label} bestaat niet", "${d.path} is niet gevonden (verwijderd, of een SD-kaart die er niet in zit).",
                    FixAction.OPEN_STORAGE_SETTINGS)
                !d.writable -> out += SetupIssue("dir_readonly_${d.label}", Severity.BLOCKER,
                    "${d.label} is niet schrijfbaar", "${d.path} — kies een andere map.", FixAction.OPEN_STORAGE_SETTINGS)
                d.freeBytes in 0 until LOW_SPACE_BYTES -> out += SetupIssue("dir_space_${d.label}", Severity.WARNING,
                    "Weinig ruimte bij ${d.label.lowercase()}", "Nog ${fmtMb(d.freeBytes)} vrij.", FixAction.NONE)
            }
        }

        // ── Backupmap (lokaal/SAF) ──
        val b = f.backupDir
        val needLocalBackup = scope == CheckScope.RESTORE && f.restoreUsesLocalFolder
        if (needLocalBackup && !b.configured) out += SetupIssue("backup_unset", Severity.BLOCKER, "Geen backupmap gekozen",
            "Kies eerst de map waarin je lokale backup staat.", FixAction.PICK_BACKUP_DIR)
        else if (b.configured && (!b.permissionHeld || !b.reachable)) out += SetupIssue("backup_lost",
            if (needLocalBackup) Severity.BLOCKER else Severity.WARNING, "Backupmap niet meer bereikbaar",
            if (!b.permissionHeld) "De app heeft geen toegang meer tot de gekozen backupmap. Kies hem opnieuw."
            else "De gekozen backupmap bestaat niet meer (verwijderd of op een verwijderd opslagmedium).",
            FixAction.PICK_BACKUP_DIR)

        // ── Bibliotheek ──
        if (f.tracksMissingFile > 0) out += SetupIssue("missing_files",
            if (scope == CheckScope.LIBRARY) Severity.WARNING else Severity.INFO,
            "${f.tracksMissingFile} ${if (f.tracksMissingFile == 1) "track mist zijn" else "tracks missen hun"} bestand",
            "Van ${f.tracksWithFile + f.tracksMissingFile} tracks met een bestand is dit deel niet meer te vinden; die kunnen niet als ringtone worden gebruikt.",
            FixAction.NONE)

        // ── Restore: ruimte ──
        val need = f.restoreBytesNeeded
        if (scope == CheckScope.RESTORE && need != null && need > 0 && f.dirs.isNotEmpty()) {
            val free = f.dirs.filter { it.exists && it.writable }.minOfOrNull { it.freeBytes } ?: 0L
            if (free < need + RESTORE_MARGIN_BYTES) out += SetupIssue("restore_space", Severity.BLOCKER,
                "Te weinig ruimte voor deze restore", "Nodig: ${fmtMb(need)} (+ marge), vrij: ${fmtMb(free)}.", FixAction.NONE)
        }
        return out.sortedBy { it.severity.ordinal }
    }

    fun blocks(issues: List<SetupIssue>, scope: CheckScope): Boolean =
        scope == CheckScope.RESTORE && issues.any { it.severity == Severity.BLOCKER }

    private fun fmtMb(b: Long) = "%.0f MB".format(b / 1048576.0)
}

class SetupCheck(private val context: Context, private val storage: StorageManager, private val db: RingtoneDatabase) {

    suspend fun run(scope: CheckScope, restoreBytesNeeded: Long? = null, restoreUsesLocalFolder: Boolean = false): List<SetupIssue> {
        val facts = gather(restoreBytesNeeded, restoreUsesLocalFolder)
        return SetupRules.evaluate(facts, scope).also {
            RemoteLogger.i("SetupCheck", "Controle $scope", mapOf("issues" to it.joinToString { i -> "${i.id}:${i.severity}" }))
        }
    }

    suspend fun gather(restoreBytesNeeded: Long?, restoreUsesLocalFolder: Boolean): SetupFacts = withContext(Dispatchers.IO) {
        val perms = listOf("android.permission.READ_MEDIA_AUDIO", "android.permission.POST_NOTIFICATIONS") +
            SetupRules.PHONE_PERMISSIONS + SetupRules.CONTACT_PERMISSIONS
        val granted = perms.filter { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }.toSet()
        val playlists = runCatching { db.playlistDao().getAll() }.getOrDefault(emptyList()).filter { it.isActive }
        val tracks = runCatching { db.savedTrackDao().getAll() }.getOrDefault(emptyList()).filter { !it.localPath.isNullOrBlank() }
        val missing = tracks.count { !File(it.localPath!!).exists() }
        SetupFacts(
            sdkInt = Build.VERSION.SDK_INT,
            canWriteSettings = Settings.System.canWrite(context),
            granted = granted,
            canInstallPackages = context.packageManager.canRequestPackageInstalls(),
            needsPhone = playlists.any { it.schedule == Schedule.EVERY_CALL },
            needsContacts = playlists.any { !it.contactUri.isNullOrBlank() },
            dirs = listOf(dirFact("Downloadmap", rawDir(isDownload = true)), dirFact("Ringtonemap", rawDir(isDownload = false))),
            backupDir = backupFact(storage.getBackupUri()),
            tracksWithFile = tracks.size - missing,
            tracksMissingFile = missing,
            restoreBytesNeeded = restoreBytesNeeded,
            restoreUsesLocalFolder = restoreUsesLocalFolder
        )
    }

    // getDownloadDir()/getRingtoneDir() doen mkdirs(): een map die weg is maar terug kan komt vanzelf terug; lukt dat
    // niet (SD-kaart weg, geen rechten) dan meldt dirFact hem als ontbrekend.
    private suspend fun rawDir(isDownload: Boolean): File = if (isDownload) storage.getDownloadDir() else storage.getRingtoneDir()

    private fun dirFact(label: String, dir: File): DirFact {
        val exists = dir.exists()
        val isDir = dir.isDirectory
        val writable = isDir && runCatching {
            val probe = File(dir, ".rr_write_test"); probe.writeText("ok"); probe.delete()
        }.getOrDefault(false)
        return DirFact(label, dir.absolutePath, exists, isDir, writable, if (isDir) dir.usableSpace else -1)
    }

    private fun backupFact(uriString: String?): BackupDirFact {
        if (uriString.isNullOrBlank()) return BackupDirFact(configured = false, permissionHeld = false, reachable = false)
        val uri = Uri.parse(uriString)
        val held = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }
        val reachable = held && runCatching { DocumentFile.fromTreeUri(context, uri)?.let { it.exists() && it.isDirectory } == true }.getOrDefault(false)
        return BackupDirFact(configured = true, permissionHeld = held, reachable = reachable)
    }
}
