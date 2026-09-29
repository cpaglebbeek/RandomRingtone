package nl.icthorse.randomringtone.ui.screens

import android.accounts.AccountManager
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.icthorse.randomringtone.data.LicenseManager

private val EMAIL_RE = Regex("^[^\\s@]+@[^\\s@]+\\.[A-Za-z]{2,24}$")

/** Leesbare labels voor wat er met de aanvraag meegaat (volgorde = weergave). */
private val FIELD_LABELS = linkedMapOf(
    "manufacturer" to "Fabrikant", "brand" to "Merk", "model" to "Model", "device" to "Device", "product" to "Product",
    "androidRelease" to "Android-versie", "sdkInt" to "Android SDK", "appVersion" to "App-versie",
    "appVersionCode" to "App-build", "locale" to "Taal", "timezone" to "Tijdzone", "screen" to "Scherm",
    "density" to "Schermdichtheid", "installer" to "Geïnstalleerd via", "firstInstall" to "Eerste installatie",
    "accountPicked" to "E-mail gekozen via"
)

/**
 * Scherm bij geen (geldige) licentie: activatie aanvragen. De backend mailt de beheerder een magic link; toekennen
 * gebeurt daar na goedkeuring in HorseAPK. Vóór versturen ziet de gebruiker precies welke gegevens meegaan.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicenseActivationScreen(
    status: LicenseManager.LicenseStatus,
    licenseManager: LicenseManager,
    onRecheck: suspend () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf(licenseManager.pendingActivation()) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var accountPicked by remember { mutableStateOf(false) }
    var showPreview by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val accountPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            res.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)?.let { email = it; accountPicked = true }
        }
    }

    suspend fun refresh() {
        busy = true
        val st = licenseManager.pollActivation()
        info = when (st) {
            "pending" -> "Aanvraag staat nog open — je krijgt toegang zodra iCt Horse hem heeft goedgekeurd."
            "rejected" -> "Je aanvraag is afgewezen. Neem contact op met iCt Horse."
            "expired" -> "Je aanvraag is verlopen. Vraag opnieuw aan."
            "superseded" -> "Er is een nieuwere aanvraag verstuurd."
            else -> null
        }
        pending = licenseManager.pendingActivation()
        onRecheck()
        busy = false
    }

    // Openstaande aanvraag: elke 30 s op de achtergrond controleren
    LaunchedEffect(pending?.requestId) {
        while (pending != null) {
            delay(30_000)
            refresh()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.error)
        Text("Geen geldige licentie", style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.error)
        Text(status.message.ifBlank { "Deze app vereist een geldige licentie." },
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center)

        val p = pending
        if (p != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Aanvraag verstuurd", style = MaterialTheme.typography.titleMedium)
                    Text("Op ${java.text.SimpleDateFormat("d MMM yyyy HH:mm", java.util.Locale("nl", "NL"))
                        .format(java.util.Date(p.sentAt))} voor ${p.name} (${p.email}).",
                        style = MaterialTheme.typography.bodyMedium)
                    Text("Zodra de aanvraag is goedgekeurd, opent de app vanzelf. Heb je dit account al op een " +
                        "andere telefoon gebruikt? Dan kun je daarna een eerdere backup terugzetten.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy, onClick = { scope.launch { refresh() } }) { Text("Opnieuw controleren") }
                        TextButton(enabled = !busy, onClick = {
                            licenseManager.clearPendingActivation(); pending = null; info = null
                        }) { Text("Nieuwe aanvraag") }
                    }
                }
            }
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Activatie aanvragen", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(value = name, onValueChange = { name = it.take(80) }, label = { Text("Naam") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = email, onValueChange = { email = it.take(254); accountPicked = false },
                        label = { Text("E-mailadres (je account)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        supportingText = { Text("Met hetzelfde adres kun je later je backup op een nieuwe telefoon terugzetten.") },
                        modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = {
                        accountPicker.launch(AccountManager.newChooseAccountIntent(
                            null, null, arrayOf("com.google"), null, null, null, null))
                    }) {
                        Icon(Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Kies Google-account")
                    }
                    OutlinedTextField(value = note, onValueChange = { note = it.take(300) },
                        label = { Text("Opmerking (optioneel)") }, modifier = Modifier.fillMaxWidth())
                    Button(
                        enabled = !busy && name.isNotBlank() && EMAIL_RE.matches(email.trim()),
                        onClick = { error = null; showPreview = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Bekijk wat er verstuurd wordt") }
                }
            }
        }

        info?.let { Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center) }
        error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center) }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Toestel-ID", style = MaterialTheme.typography.labelSmall)
                    Text(licenseManager.deviceHash, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                IconButton(onClick = {
                    val clip = android.content.ClipData.newPlainText("Toestel-ID", licenseManager.deviceHash)
                    (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(clip)
                }) { Icon(Icons.Default.ContentCopy, contentDescription = "Kopieer toestel-ID") }
            }
        }
        if (status.error != null) {
            Text("Laatste controle: ${status.error}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (showPreview) {
        val device = remember(accountPicked) { licenseManager.collectDeviceInfo(accountPicked) }
        AlertDialog(
            onDismissRequest = { if (!busy) showPreview = false },
            title = { Text("Dit wordt verstuurd") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Naar iCt Horse (RandomRingtone-beheer), alleen om je licentie te beoordelen en je account " +
                        "aan dit toestel te koppelen:", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    PreviewRow("Naam", name.trim())
                    PreviewRow("E-mailadres", email.trim())
                    if (note.isNotBlank()) PreviewRow("Opmerking", note.trim())
                    PreviewRow("Toestel-ID", licenseManager.deviceHash)
                    FIELD_LABELS.forEach { (k, label) -> device[k]?.takeIf { it.isNotBlank() }?.let { PreviewRow(label, it) } }
                    PreviewRow("IP-adres", "zoals de server het ziet")
                }
            },
            confirmButton = {
                Button(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        val err = licenseManager.sendActivationRequest(name, email, note, accountPicked)
                        busy = false
                        showPreview = false
                        if (err == null) {
                            pending = licenseManager.pendingActivation()
                            info = "Aanvraag verstuurd. Je krijgt toegang zodra hij is goedgekeurd."
                        } else error = err
                    }
                }) { Text(if (busy) "Versturen…" else "Versturen") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { showPreview = false }) { Text("Annuleren") } }
        )
    }
}

@Composable
private fun PreviewRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f))
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.58f))
    }
}
