package nl.icthorse.randomringtone.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import nl.icthorse.randomringtone.data.*

/**
 * Poort vóór opstarten / restore / Bibliotheek (v2.1.0): controleert rechten en mappen via [SetupCheck].
 * Geen problemen (of alleen info) ⇒ direct door. Anders een venster met per punt *Oplossen*; bij terugkomst uit
 * Android-instellingen wordt vanzelf opnieuw gecontroleerd. Rood blokkeert alleen een restore.
 */
class SetupGate internal constructor(private val checker: SetupCheck, private val scope: CoroutineScope) {
    var issues by mutableStateOf<List<SetupIssue>>(emptyList()); private set
    var current by mutableStateOf<CheckScope?>(null); private set
    var busy by mutableStateOf(false); private set
    private var proceed: (() -> Unit)? = null
    private var restoreBytes: Long? = null
    private var usesLocal = false
    private var target: RestoreTarget? = null

    fun check(
        s: CheckScope, restoreBytesNeeded: Long? = null, restoreUsesLocalFolder: Boolean = false,
        restoreTarget: RestoreTarget? = null, onProceed: () -> Unit = {}
    ) {
        scope.launch {
            busy = true
            val r = runCatching { checker.run(s, restoreBytesNeeded, restoreUsesLocalFolder, restoreTarget) }.getOrDefault(emptyList())
            busy = false
            if (r.none { it.severity != Severity.INFO }) { onProceed(); return@launch }
            issues = r; current = s; proceed = onProceed; restoreBytes = restoreBytesNeeded; usesLocal = restoreUsesLocalFolder
            target = restoreTarget
        }
    }

    fun recheck() {
        val s = current ?: return
        scope.launch {
            busy = true
            val r = runCatching { checker.run(s, restoreBytes, usesLocal, target) }.getOrDefault(issues)
            busy = false
            issues = r
            if (r.none { it.severity != Severity.INFO }) proceedAnyway()
        }
    }

    fun dismiss() { current = null; proceed = null }

    fun proceedAnyway() { val p = proceed; dismiss(); p?.invoke() }
}

@Composable
fun rememberSetupGate(storage: StorageManager, db: RingtoneDatabase): SetupGate {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember { SetupGate(SetupCheck(context.applicationContext, storage, db), scope) }
}

@Composable
fun SetupGateDialog(gate: SetupGate, storage: StorageManager, onOpenStorageSettings: () -> Unit) {
    val s = gate.current ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Terug uit Android-instellingen ⇒ opnieuw controleren
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) gate.recheck() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    fun openAppDetails() = runCatching {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        // Alles geweigerd = vaak "niet meer vragen" ⇒ Android toont niets meer; dan de app-instellingen openen
        if (res.isNotEmpty() && res.values.none { it }) openAppDetails() else gate.recheck()
    }
    val dirPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            scope.launch { storage.setBackupUri(uri.toString()); gate.recheck() }
        }
    }

    fun fix(i: SetupIssue) {
        when (i.fix) {
            FixAction.WRITE_SETTINGS -> runCatching {
                context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { openAppDetails() }
            FixAction.REQUEST_PERMISSIONS -> permLauncher.launch(i.permissions.toTypedArray())
            FixAction.INSTALL_SOURCES -> runCatching {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { openAppDetails() }
            FixAction.PICK_BACKUP_DIR -> runCatching { dirPicker.launch(null) }
            FixAction.OPEN_STORAGE_SETTINGS -> { gate.dismiss(); onOpenStorageSettings() }
            FixAction.NONE -> Unit
        }
    }

    val blocked = SetupRules.blocks(gate.issues, s)
    val title = when (s) {
        CheckScope.STARTUP -> "Controle bij opstarten"
        CheckScope.RESTORE -> if (blocked) "Restore kan nog niet" else "Controle vóór restore"
        CheckScope.LIBRARY -> "Controle bibliotheek"
    }
    AlertDialog(
        onDismissRequest = { gate.dismiss() },
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (blocked) "Los eerst de rode punten op:" else "Niet alles is goed ingesteld:",
                    style = MaterialTheme.typography.bodyMedium
                )
                gate.issues.forEach { i -> IssueRow(i, onFix = { fix(i) }) }
                if (gate.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            if (!blocked) TextButton(onClick = { gate.proceedAnyway() }) {
                Text(when (s) { CheckScope.STARTUP -> "Later"; CheckScope.RESTORE -> "Toch doorgaan"; CheckScope.LIBRARY -> "Toch openen" })
            }
        },
        dismissButton = {
            Row {
                if (blocked) TextButton(onClick = { gate.dismiss() }) { Text("Annuleren") }
                TextButton(enabled = !gate.busy, onClick = { gate.recheck() }) { Text("Opnieuw controleren") }
            }
        }
    )
}

@Composable
private fun IssueRow(i: SetupIssue, onFix: () -> Unit) {
    val (label, color) = when (i.severity) {
        Severity.BLOCKER -> "Nodig" to MaterialTheme.colorScheme.error
        Severity.WARNING -> "Aanbevolen" to Color(0xFFB26A00)
        Severity.INFO -> "Info" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = color)
                Spacer(Modifier.width(8.dp))
                Text(i.title, style = MaterialTheme.typography.titleSmall)
            }
            Text(i.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (i.fix != FixAction.NONE) TextButton(onClick = onFix, contentPadding = PaddingValues(0.dp)) {
                Text(when (i.fix) {
                    FixAction.PICK_BACKUP_DIR -> "Map kiezen"
                    FixAction.OPEN_STORAGE_SETTINGS -> "Naar opslaginstellingen"
                    else -> "Oplossen"
                })
            }
        }
    }
}
