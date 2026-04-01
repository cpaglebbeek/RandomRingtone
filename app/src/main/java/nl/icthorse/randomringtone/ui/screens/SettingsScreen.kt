package nl.icthorse.randomringtone.ui.screens

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import nl.icthorse.randomringtone.data.AppRingtoneManager
import nl.icthorse.randomringtone.data.RingtoneDatabase
import nl.icthorse.randomringtone.data.SavedTrack
import nl.icthorse.randomringtone.data.SpotifyConverter
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    ringtoneManager: AppRingtoneManager,
    db: RingtoneDatabase? = null,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var canWriteSettings by remember { mutableStateOf(ringtoneManager.canWriteSettings()) }
    var notificationAccessEnabled by remember { mutableStateOf(isNotificationListenerEnabled(context)) }
    var phoneStateGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED)
    }
    var writeContactsGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED)
    }

    val phoneStatePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        phoneStateGranted = granted
    }
    val writeContactsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        writeContactsGranted = granted
    }

    // Hercheck permissies wanneer gebruiker terugkeert van Settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canWriteSettings = ringtoneManager.canWriteSettings()
                notificationAccessEnabled = isNotificationListenerEnabled(context)
                phoneStateGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
                writeContactsGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "Instellingen",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Permissies sectie
        Text(
            text = "Permissies",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(12.dp))

        // WRITE_SETTINGS permissie
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (canWriteSettings) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (canWriteSettings)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Systeeminstellingen wijzigen",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = if (canWriteSettings)
                            "Toegestaan — ringtone kan worden ingesteld"
                        else
                            "Vereist om ringtone te kunnen wijzigen",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!canWriteSettings) {
                    FilledTonalButton(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                            )
                        }
                    ) {
                        Text("Toestaan")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // NotificationListener permissie (voor SMS/WhatsApp custom ringtones)
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (notificationAccessEnabled) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (notificationAccessEnabled)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Notificatie-toegang",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = if (notificationAccessEnabled)
                            "Toegestaan — SMS en WhatsApp ringtones actief"
                        else
                            "Vereist voor custom SMS en WhatsApp ringtones",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!notificationAccessEnabled) {
                    FilledTonalButton(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            )
                        }
                    ) {
                        Text("Toestaan")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // READ_PHONE_STATE permissie (voor EVERY_CALL ringtone wissel)
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (phoneStateGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (phoneStateGranted)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Telefoonstate lezen",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = if (phoneStateGranted)
                            "Toegestaan — ringtone wisselt na elk gesprek"
                        else
                            "Vereist voor 'bij elk gesprek' ringtone wissel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!phoneStateGranted) {
                    FilledTonalButton(
                        onClick = {
                            phoneStatePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                        }
                    ) {
                        Text("Toestaan")
                    }
                }
            }
        }

        // WRITE_CONTACTS permissie (voor per-contact ringtone)
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (writeContactsGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (writeContactsGranted)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Contacten schrijven",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = if (writeContactsGranted)
                            "Toegestaan — per-contact ringtone instellen werkt"
                        else
                            "Vereist voor per-contact ringtone toewijzing",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!writeContactsGranted) {
                    FilledTonalButton(
                        onClick = {
                            writeContactsPermissionLauncher.launch(Manifest.permission.WRITE_CONTACTS)
                        }
                    ) {
                        Text("Toestaan")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Opslag sectie
        Text(
            text = "Opslag",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(12.dp))

        var downloadPath by remember { mutableStateOf("") }
        var ringtonePath by remember { mutableStateOf("") }
        var diskUsage by remember { mutableStateOf<nl.icthorse.randomringtone.data.DiskUsage?>(null) }

        // SAF picker state
        var pendingDownloadPath by remember { mutableStateOf<String?>(null) }
        var pendingRingtonePath by remember { mutableStateOf<String?>(null) }
        var isMovingFiles by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            downloadPath = ringtoneManager.storage.getDownloadDir().absolutePath
            ringtonePath = ringtoneManager.storage.getRingtoneDir().absolutePath
            diskUsage = ringtoneManager.storage.getDiskUsage()
        }

        // SAF directory pickers
        val downloadDirPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            if (uri != null) {
                val path = safUriToFilePath(uri)
                if (path != null && path != downloadPath) {
                    pendingDownloadPath = path
                } else if (path == null) {
                    scope.launch { snackbarHostState.showSnackbar("Kies een lokale map (geen cloud)") }
                }
            }
        }

        val ringtoneDirPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            if (uri != null) {
                val path = safUriToFilePath(uri)
                if (path != null && path != ringtonePath) {
                    pendingRingtonePath = path
                } else if (path == null) {
                    scope.launch { snackbarHostState.showSnackbar("Kies een lokale map (geen cloud)") }
                }
            }
        }

        // Downloads locatie
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Downloads (tijdelijk)", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = downloadPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                diskUsage?.let {
                    Text(
                        text = "${it.downloadCount} bestanden, ${it.downloadFormatted()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = { downloadDirPicker.launch(null) },
                        enabled = !isMovingFiles
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Wijzig")
                    }
                    TextButton(onClick = {
                        scope.launch {
                            ringtoneManager.clearDownloads()
                            diskUsage = ringtoneManager.storage.getDiskUsage()
                            snackbarHostState.showSnackbar("Downloads gewist")
                        }
                    }) { Text("Wissen", color = MaterialTheme.colorScheme.error) }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Ringtones locatie
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Ringtones (permanent)", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = ringtonePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                diskUsage?.let {
                    Text(
                        text = "${it.ringtoneCount} bestanden, ${it.ringtoneFormatted()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = { ringtoneDirPicker.launch(null) },
                        enabled = !isMovingFiles
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Wijzig")
                    }
                    TextButton(onClick = {
                        scope.launch {
                            ringtoneManager.clearRingtones()
                            diskUsage = ringtoneManager.storage.getDiskUsage()
                            snackbarHostState.showSnackbar("Ringtones gewist")
                        }
                    }) { Text("Wissen", color = MaterialTheme.colorScheme.error) }
                }
            }
        }

        diskUsage?.let {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Totaal schijfgebruik: ${it.totalFormatted()}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = {
                scope.launch {
                    ringtoneManager.storage.resetToDefaults()
                    downloadPath = ringtoneManager.storage.getDownloadDir().absolutePath
                    ringtonePath = ringtoneManager.storage.getRingtoneDir().absolutePath
                    diskUsage = ringtoneManager.storage.getDiskUsage()
                    snackbarHostState.showSnackbar("Paden gereset naar standaard")
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Reset naar standaard locaties") }

        Spacer(modifier = Modifier.height(8.dp))

        // FileMoveDialog — Downloads
        if (pendingDownloadPath != null) {
            FileMoveDialog(
                title = "Download locatie wijzigen",
                oldPath = downloadPath,
                newPath = pendingDownloadPath!!,
                fileCount = diskUsage?.downloadCount ?: 0,
                onDismiss = { pendingDownloadPath = null },
                onAction = { action ->
                    val newPath = pendingDownloadPath!!
                    val oldDir = File(downloadPath)
                    val newDir = File(newPath)
                    pendingDownloadPath = null
                    scope.launch {
                        isMovingFiles = true
                        when (action) {
                            FileMoveAction.COPY -> {
                                val count = ringtoneManager.storage.copyFilesToNewDir(oldDir, newDir)
                                snackbarHostState.showSnackbar("$count bestanden gekopieerd")
                            }
                            FileMoveAction.MOVE -> {
                                val count = ringtoneManager.storage.moveFilesToNewDir(oldDir, newDir)
                                snackbarHostState.showSnackbar("$count bestanden verplaatst")
                            }
                            FileMoveAction.SKIP -> { /* alleen pad wijzigen */ }
                        }
                        ringtoneManager.storage.setDownloadDir(newPath)
                        downloadPath = newPath
                        diskUsage = ringtoneManager.storage.getDiskUsage()
                        isMovingFiles = false
                    }
                }
            )
        }

        // FileMoveDialog — Ringtones
        if (pendingRingtonePath != null) {
            FileMoveDialog(
                title = "Ringtone locatie wijzigen",
                oldPath = ringtonePath,
                newPath = pendingRingtonePath!!,
                fileCount = diskUsage?.ringtoneCount ?: 0,
                onDismiss = { pendingRingtonePath = null },
                onAction = { action ->
                    val newPath = pendingRingtonePath!!
                    val oldDir = File(ringtonePath)
                    val newDir = File(newPath)
                    pendingRingtonePath = null
                    scope.launch {
                        isMovingFiles = true
                        when (action) {
                            FileMoveAction.COPY -> {
                                val count = ringtoneManager.storage.copyFilesToNewDir(oldDir, newDir)
                                snackbarHostState.showSnackbar("$count bestanden gekopieerd")
                            }
                            FileMoveAction.MOVE -> {
                                val count = ringtoneManager.storage.moveFilesToNewDir(oldDir, newDir)
                                snackbarHostState.showSnackbar("$count bestanden verplaatst")
                            }
                            FileMoveAction.SKIP -> { /* alleen pad wijzigen */ }
                        }
                        ringtoneManager.storage.setRingtoneDir(newPath)
                        ringtonePath = newPath
                        diskUsage = ringtoneManager.storage.getDiskUsage()
                        isMovingFiles = false
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Spotify Converter sectie
        Text(
            text = "Spotify Converter",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Kies de service om Spotify tracks naar MP3 te converteren",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(12.dp))

        var selectedConverterId by remember { mutableStateOf("") }
        var converterExpanded by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            selectedConverterId = ringtoneManager.storage.getSpotifyConverter()
        }

        val selectedConverter = SpotifyConverter.findById(selectedConverterId)

        ExposedDropdownMenuBox(
            expanded = converterExpanded,
            onExpandedChange = { converterExpanded = it }
        ) {
            OutlinedTextField(
                value = "${selectedConverter.name} (${selectedConverter.type})",
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = converterExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor()
            )
            ExposedDropdownMenu(
                expanded = converterExpanded,
                onDismissRequest = { converterExpanded = false }
            ) {
                SpotifyConverter.ALL.forEach { converter ->
                    DropdownMenuItem(
                        text = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(converter.name)
                                Text(
                                    text = converter.type,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {
                            selectedConverterId = converter.id
                            converterExpanded = false
                            scope.launch {
                                ringtoneManager.storage.setSpotifyConverter(converter.id)
                                snackbarHostState.showSnackbar("Converter: ${converter.name}")
                            }
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = selectedConverter.url,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Directe API download toggle
        var directApiEnabled by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            directApiEnabled = ringtoneManager.storage.isDirectApiEnabled()
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Directe download (SpotMate API)",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = if (directApiEnabled)
                            "MP3 wordt automatisch gedownload zonder converter-site"
                        else
                            "Handmatig via converter WebView",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = directApiEnabled,
                    onCheckedChange = { enabled ->
                        directApiEnabled = enabled
                        scope.launch {
                            ringtoneManager.storage.setDirectApiEnabled(enabled)
                            snackbarHostState.showSnackbar(
                                if (enabled) "Directe API download ingeschakeld"
                                else "WebView converter ingeschakeld"
                            )
                        }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // App info
        Text(text = "Over", style = MaterialTheme.typography.titleMedium)

        Spacer(modifier = Modifier.height(12.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                InfoRow("App", "RandomRingtone")
                InfoRow("Versie", "${nl.icthorse.randomringtone.BuildConfig.VERSION_NAME} \"Prince\"")
                InfoRow("Release", "I_Wanna_Be_Your_Lover (Build 29)")
                InfoRow("Muziekbron", "Spotify Web + converter")
                InfoRow("Ringtone duur", "Instelbaar via editor")
            }
        }

    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private enum class FileMoveAction { COPY, MOVE, SKIP }

@Composable
private fun FileMoveDialog(
    title: String,
    oldPath: String,
    newPath: String,
    fileCount: Int,
    onDismiss: () -> Unit,
    onAction: (FileMoveAction) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.DriveFileMove, contentDescription = null) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Van:", style = MaterialTheme.typography.labelMedium)
                Text(oldPath, style = MaterialTheme.typography.bodySmall)
                Text("Naar:", style = MaterialTheme.typography.labelMedium)
                Text(newPath, style = MaterialTheme.typography.bodySmall)
                if (fileCount > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Er zijn $fileCount bestanden op de huidige locatie.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            if (fileCount > 0) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = { onAction(FileMoveAction.MOVE) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.DriveFileMove, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Verplaatsen")
                    }
                    OutlinedButton(onClick = { onAction(FileMoveAction.COPY) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Kopieren")
                    }
                    TextButton(onClick = { onAction(FileMoveAction.SKIP) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Alleen pad wijzigen")
                    }
                }
            } else {
                Button(onClick = { onAction(FileMoveAction.SKIP) }) {
                    Text("Pad wijzigen")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuleren") }
        }
    )
}

/**
 * Converteer SAF tree URI naar lokaal bestandspad.
 * Werkt voor primaire externe opslag (bijv. /storage/emulated/0/...).
 */
private fun safUriToFilePath(uri: Uri): String? {
    return try {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val parts = docId.split(":", limit = 2)
        if (parts.size == 2 && parts[0] == "primary") {
            "${Environment.getExternalStorageDirectory().absolutePath}/${parts[1]}"
        } else null
    } catch (_: Exception) {
        null
    }
}

private fun isNotificationListenerEnabled(context: android.content.Context): Boolean {
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    if (!TextUtils.isEmpty(flat)) {
        val names = flat.split(":")
        val cn = ComponentName(context, nl.icthorse.randomringtone.service.NotificationService::class.java)
        return names.any { ComponentName.unflattenFromString(it) == cn }
    }
    return false
}
