package nl.icthorse.randomringtone.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.icthorse.randomringtone.AppBusyState
import nl.icthorse.randomringtone.data.AppRingtoneManager
import nl.icthorse.randomringtone.data.BackupOffer
import nl.icthorse.randomringtone.data.IctHorseBackupClient
import nl.icthorse.randomringtone.data.LicenseManager
import nl.icthorse.randomringtone.data.RemoteLogger
import nl.icthorse.randomringtone.data.RingtoneDatabase

/**
 * Na een geldige licentie: zorg voor een toestel-token en bied — als dit toestel nog leeg is — een backup aan van een
 * ander toestel met hetzelfde account (e-mailadres). Eén keer per app-start.
 */
@Composable
fun RestoreOfferHost(
    licenseManager: LicenseManager,
    db: RingtoneDatabase,
    ringtoneManager: AppRingtoneManager,
    snackbarHostState: SnackbarHostState,
    licensed: Boolean,
    setupGate: SetupGate
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { IctHorseBackupClient(context) }
    val restoreFinisher = rememberRestoreFinisher(db)
    var offers by remember { mutableStateOf<List<BackupOffer>>(emptyList()) }
    var dismissed by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<nl.icthorse.randomringtone.data.BackupProgress?>(null) }

    LaunchedEffect(licensed) {
        if (!licensed) return@LaunchedEffect
        licenseManager.pollActivation()  // levert het token op als een aanvraag net is toegekend
        val token = licenseManager.ensureDeviceToken()
        RemoteLogger.i("RestoreOffer", "Toestel-token", mapOf("state" to token.name))
        if (token == LicenseManager.TokenState.NEEDS_ACTIVATION) {
            snackbarHostState.showSnackbar("Dit toestel moet opnieuw worden geactiveerd voor cloud-backup (Instellingen → Licentie)")
            return@LaunchedEffect
        }
        if (token != LicenseManager.TokenState.OK) return@LaunchedEffect
        val empty = try { db.savedTrackDao().getAll().isEmpty() } catch (_: Exception) { false }
        if (!empty) return@LaunchedEffect
        offers = client.getOffers()
        RemoteLogger.i("RestoreOffer", "Aanbod", mapOf("count" to offers.size.toString()))
    }

    if (offers.isEmpty() || dismissed) return

    AlertDialog(
        onDismissRequest = { if (!restoring) dismissed = true },
        title = { Text("Backup terugzetten?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (restoring) {
                    TransferProgress(progress)
                } else {
                    Text("Er staan backups van een ander toestel met hetzelfde account:")
                    offers.forEach { o ->
                        o.slots.forEach { s ->
                            val m = s.meta
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(listOf(o.name, o.model).filter { it.isNotBlank() }.joinToString(" · "),
                                        style = MaterialTheme.typography.titleSmall)
                                    Text("${m?.backupDate ?: "?"} · ${m?.trackCount ?: 0} tracks · app ${m?.appVersion ?: "?"}",
                                        style = MaterialTheme.typography.bodySmall)
                                    if (m?.complete == false) {
                                        Text("Let op: deze backup is onvolledig", style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error)
                                    }
                                    TextButton(onClick = {
                                        scope.launch {
                                            val need = client.slotSizeBytes(s.slot, o.deviceId)
                                            val plan = client.restorePlan(s.slot, o.deviceId)
                                            setupGate.check(nl.icthorse.randomringtone.data.CheckScope.RESTORE, need, restoreTarget = plan) { scope.launch {
                                            restoring = true
                                            AppBusyState.isBusy = true
                                            val result = client.restore(s.slot, db, ringtoneManager.storage,
                                                onProgress = { p -> progress = p }, sourceDeviceId = o.deviceId)
                                            AppBusyState.isBusy = false
                                            restoring = false
                                            // pas sluiten na de toestemmingsvraag (anders gaat het resultaat verloren)
                                            restoreFinisher.finish(result) { msg ->
                                                dismissed = true
                                                scope.launch { snackbarHostState.showSnackbar(msg) }
                                            }
                                            } }
                                        }
                                    }) { Text("Deze terugzetten") }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { if (!restoring) TextButton(onClick = { dismissed = true }) { Text("Niet nu") } }
    )
}
