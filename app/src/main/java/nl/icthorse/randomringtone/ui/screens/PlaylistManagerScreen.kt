package nl.icthorse.randomringtone.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.icthorse.randomringtone.data.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistManagerScreen(
    db: RingtoneDatabase,
    snackbarHostState: SnackbarHostState
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val contactsRepo = remember { ContactsRepository(context) }

    val backupManager = remember { BackupManager(context) }
    val conflictResolver = remember { ConflictResolver(db) }
    var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var trackCounts by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var editPlaylist by remember { mutableStateOf<Playlist?>(null) }
    var showAddTracksFor by remember { mutableStateOf<Playlist?>(null) }

    fun refresh() {
        scope.launch {
            playlists = db.playlistDao().getAll()
            trackCounts = playlists.associate { it.id to db.playlistTrackDao().getTrackCount(it.id) }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    // Export/Import state
    var pendingExportPlaylist by remember { mutableStateOf<Playlist?>(null) }
    var pendingExportJson by remember { mutableStateOf<String?>(null) }

    // SAF export: maak bestand aan
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null && pendingExportJson != null) {
            scope.launch {
                val success = backupManager.writePlaylistExport(uri, pendingExportJson!!)
                pendingExportJson = null
                pendingExportPlaylist = null
                snackbarHostState.showSnackbar(
                    if (success) "Playlist geexporteerd" else "Export mislukt"
                )
            }
        } else {
            pendingExportJson = null
            pendingExportPlaylist = null
        }
    }

    // SAF import: kies bestand
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = backupManager.importPlaylist(uri, db)
                refresh()
                snackbarHostState.showSnackbar(result.message)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Playlists", style = MaterialTheme.typography.headlineMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Import")
                }
                FilledTonalButton(onClick = { showCreateDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Nieuw")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (playlists.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.QueueMusic, contentDescription = null,
                        modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Nog geen playlists", style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Maak een playlist en voeg ringtones toe", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(playlists) { playlist ->
                    PlaylistCard(
                        playlist = playlist,
                        trackCount = trackCounts[playlist.id] ?: 0,
                        onExport = {
                            scope.launch {
                                val jsonContent = backupManager.exportPlaylist(playlist.id, db)
                                if (jsonContent != null) {
                                    pendingExportPlaylist = playlist
                                    pendingExportJson = jsonContent
                                    val safeName = playlist.name.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
                                    exportLauncher.launch("playlist_${safeName}.json")
                                } else {
                                    snackbarHostState.showSnackbar("Export mislukt")
                                }
                            }
                        },
                        onToggleActive = {
                            scope.launch {
                                val updated = playlist.copy(isActive = !playlist.isActive)
                                db.playlistDao().update(updated)
                                if (updated.isActive) {
                                    // Enforce 1-actief-per-kanaal+scope
                                    conflictResolver.enforceOneActivePerChannelScope(updated.id)
                                    // Direct ringtone instellen voor CALL playlists
                                    if (updated.channel == Channel.CALL) {
                                        val resolver = TrackResolver(db, AppRingtoneManager(context), context)
                                        val result = resolver.applyCallPlaylist(updated)
                                        val target = if (updated.contactUri != null) updated.contactName ?: "contact" else "globaal"
                                        if (result.success) snackbarHostState.showSnackbar("Ringtone ingesteld voor $target")
                                        else snackbarHostState.showSnackbar("Mislukt ($target): ${result.error}", duration = SnackbarDuration.Long)
                                    }
                                    // Schedule workers
                                    nl.icthorse.randomringtone.worker.RingtoneWorker.scheduleAll(context)
                                }
                                refresh()
                            }
                        },
                        onEdit = { editPlaylist = playlist },
                        onAddTracks = { showAddTracksFor = playlist },
                        onDelete = {
                            scope.launch {
                                db.playlistTrackDao().removeAll(playlist.id)
                                db.playlistDao().delete(playlist)
                                refresh()
                                snackbarHostState.showSnackbar("Playlist '${playlist.name}' verwijderd")
                            }
                        }
                    )
                }
            }
        }
    }

    // Aanmaken / Bewerken dialoog
    if (showCreateDialog || editPlaylist != null) {
        PlaylistEditDialog(
            existing = editPlaylist,
            contactsRepo = contactsRepo,
            onDismiss = { showCreateDialog = false; editPlaylist = null },
            onSave = { playlist ->
                scope.launch {
                    val id = if (editPlaylist != null) {
                        db.playlistDao().update(playlist)
                        playlist.id
                    } else {
                        db.playlistDao().insert(playlist)
                    }
                    // Enforce 1-actief-per-kanaal+scope
                    if (playlist.isActive) {
                        conflictResolver.enforceOneActivePerChannelScope(id)
                        // Direct ringtone instellen voor CALL playlists
                        if (playlist.channel == Channel.CALL) {
                            val saved = db.playlistDao().getById(id)
                            if (saved != null) {
                                val resolver = TrackResolver(db, AppRingtoneManager(context), context)
                                val result = resolver.applyCallPlaylist(saved)
                                if (!result.success) {
                                    snackbarHostState.showSnackbar("Ringtone: ${result.error}", duration = SnackbarDuration.Long)
                                }
                            }
                        }
                    }
                    // Schedule workers
                    nl.icthorse.randomringtone.worker.RingtoneWorker.scheduleAll(context)
                    val wasEdit = editPlaylist != null
                    showCreateDialog = false
                    editPlaylist = null
                    refresh()
                    snackbarHostState.showSnackbar(
                        if (wasEdit) "Playlist bijgewerkt" else "Playlist '${playlist.name}' aangemaakt"
                    )
                }
            }
        )
    }

    // Tracks toevoegen dialoog
    showAddTracksFor?.let { playlist ->
        AddTracksDialog(
            playlist = playlist,
            db = db,
            onDismiss = { showAddTracksFor = null },
            onAdded = {
                showAddTracksFor = null
                refresh()
                scope.launch { snackbarHostState.showSnackbar("Tracks toegevoegd aan '${playlist.name}'") }
            }
        )
    }
}

// --- Playlist Card ---

@Composable
private fun PlaylistCard(
    playlist: Playlist,
    trackCount: Int,
    onExport: () -> Unit,
    onToggleActive: () -> Unit,
    onEdit: () -> Unit,
    onAddTracks: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (playlist.isActive) CardDefaults.cardColors()
        else CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when (playlist.channel) {
                        Channel.CALL -> Icons.Default.Phone
                        Channel.NOTIFICATION -> Icons.Default.Notifications
                        Channel.SMS -> Icons.Default.Sms
                        Channel.WHATSAPP -> Icons.Default.Chat
                    },
                    contentDescription = null,
                    tint = if (playlist.isActive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(playlist.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        buildString {
                            append(playlist.channel.displayLabel())
                            append(" • ")
                            append(if (playlist.mode == Mode.RANDOM) "Random" else "Vast")
                            append(" • ")
                            append(playlist.schedule.displayLabel())
                            if (playlist.contactName != null) {
                                append(" • ")
                                append(playlist.contactName)
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(checked = playlist.isActive, onCheckedChange = { onToggleActive() })
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                "$trackCount ringtone${if (trackCount != 1) "s" else ""}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = onAddTracks, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Tracks", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Bewerk", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(onClick = onExport) {
                    Icon(Icons.Default.FileUpload, contentDescription = null,
                        modifier = Modifier.size(16.dp))
                }
                OutlinedButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = null,
                        modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

// --- Playlist Edit Dialog ---

@Composable
private fun PlaylistEditDialog(
    existing: Playlist?,
    contactsRepo: ContactsRepository,
    onDismiss: () -> Unit,
    onSave: (Playlist) -> Unit
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var channel by remember { mutableStateOf(existing?.channel ?: Channel.CALL) }
    var mode by remember { mutableStateOf(existing?.mode ?: Mode.RANDOM) }
    var schedule by remember { mutableStateOf(existing?.schedule ?: Schedule.EVERY_CALL) }
    var isGlobal by remember { mutableStateOf(existing?.contactUri == null) }
    var selectedContact by remember { mutableStateOf<ContactInfo?>(
        if (existing?.contactUri != null) ContactInfo(existing.contactUri, existing.contactName ?: "") else null
    ) }
    var contactQuery by remember { mutableStateOf(existing?.contactName ?: "") }
    var contacts by remember { mutableStateOf<List<ContactInfo>>(emptyList()) }
    var contactsLoading by remember { mutableStateOf(false) }
    var contactsError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var contactsPermissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(
            contactsRepo.context, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED)
    }

    fun loadContacts() {
        contactsLoading = true
        contactsError = null
        scope.launch {
            try {
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    contactsRepo.getContacts()
                }
                contacts = result
                contactsError = if (result.isEmpty()) "Geen contacten gevonden" else null
            } catch (e: Exception) {
                contactsError = "Contacten laden mislukt: ${e.message}"
            } finally {
                contactsLoading = false
            }
        }
    }

    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        contactsPermissionGranted = granted
        if (granted) loadContacts()
    }

    LaunchedEffect(Unit) {
        if (contactsPermissionGranted) loadContacts()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing != null) "Playlist bewerken" else "Nieuwe playlist") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // Naam
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Playlist naam") },
                    placeholder = { Text("bijv. Rock, Werk, Chill...") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )

                // Trigger (kanaal)
                Text("Trigger:", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Channel.entries.forEach { ch ->
                        FilterChip(
                            selected = channel == ch,
                            onClick = { channel = ch },
                            label = { Text(ch.shortLabel()) }
                        )
                    }
                }

                // Scope (vóór modus zodat het altijd zichtbaar is)
                Text("Scope:", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = isGlobal, onClick = { isGlobal = true; selectedContact = null },
                        label = { Text("Globaal") })
                    FilterChip(selected = !isGlobal, onClick = { isGlobal = false },
                        label = { Text("Per contact") })
                }

                if (!isGlobal && !contactsPermissionGranted) {
                    // Permissie nog niet verleend
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Text("Contacten-permissie vereist", style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                            FilledTonalButton(onClick = {
                                contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                            }) { Text("Permissie verlenen") }
                        }
                    }
                }

                if (!isGlobal && contactsPermissionGranted) {
                    // Loading indicator
                    if (contactsLoading) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Contacten laden...", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    // Error feedback
                    if (contactsError != null && !contactsLoading) {
                        Text(
                            text = contactsError!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    // Zoekbaar contactveld
                    if (contacts.isNotEmpty()) {
                        OutlinedTextField(
                            value = if (selectedContact != null) selectedContact!!.name else contactQuery,
                            onValueChange = { contactQuery = it; selectedContact = null },
                            label = { Text("Zoek contact (${contacts.size} contacten)") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            trailingIcon = {
                                if (contactQuery.isNotBlank() || selectedContact != null) {
                                    IconButton(onClick = { contactQuery = ""; selectedContact = null }) {
                                        Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(20.dp))
                                    }
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Contactenlijst (gefilterd)
                        val filtered = contacts.filter {
                            contactQuery.isBlank() || it.name.contains(contactQuery, ignoreCase = true)
                        }.take(10)

                        if (selectedContact == null && filtered.isNotEmpty()) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    filtered.forEach { c ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { selectedContact = c; contactQuery = c.name }
                                                .padding(horizontal = 16.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.Person, contentDescription = null,
                                                modifier = Modifier.size(20.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Text(c.name, style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Modus
                Text("Modus:", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = mode == Mode.RANDOM, onClick = { mode = Mode.RANDOM },
                        label = { Text("Random") })
                    FilterChip(selected = mode == Mode.FIXED, onClick = { mode = Mode.FIXED },
                        label = { Text("Vast (eerste track)") })
                }

                // Schema (alleen bij Random)
                if (mode == Mode.RANDOM) {
                    Text("Schema:", style = MaterialTheme.typography.labelLarge)
                    Column {
                        Schedule.entries.forEach { s ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = schedule == s, onClick = { schedule = s })
                                Text(s.displayLabel(), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        (existing ?: Playlist(name = "")).copy(
                            name = name.trim(),
                            channel = channel,
                            mode = mode,
                            schedule = if (mode == Mode.RANDOM) schedule else Schedule.MANUAL,
                            contactUri = if (isGlobal) null else selectedContact?.uri,
                            contactName = if (isGlobal) null else selectedContact?.name
                        )
                    )
                },
                enabled = name.isNotBlank() && (isGlobal || selectedContact != null)
            ) { Text("Opslaan") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuleren") } }
    )
}

// --- Add Tracks Dialog ---

@Composable
private fun AddTracksDialog(
    playlist: Playlist,
    db: RingtoneDatabase,
    onDismiss: () -> Unit,
    onAdded: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var allRingtones by remember { mutableStateOf<List<SavedTrack>>(emptyList()) }
    var currentTrackIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }

    LaunchedEffect(playlist.id) {
        // Filter: alleen tracks met bestaand lokaal bestand
        allRingtones = db.savedTrackDao().getAll().filter { track ->
            track.localPath != null && java.io.File(track.localPath).exists()
        }
        val current = db.playlistTrackDao().getTracksForPlaylist(playlist.id)
        currentTrackIds = current.map { it.deezerTrackId }.toSet()
        selectedIds = currentTrackIds.toMutableSet()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tracks in '${playlist.name}'") },
        text = {
            if (allRingtones.isEmpty()) {
                Text("Nog geen ringtones in de bibliotheek. Download en trim eerst een nummer.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(allRingtones) { track ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Checkbox(
                                checked = track.deezerTrackId in selectedIds,
                                onCheckedChange = { checked ->
                                    selectedIds = if (checked) selectedIds + track.deezerTrackId
                                    else selectedIds - track.deezerTrackId
                                }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(track.title, style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (track.artist.isNotBlank()) {
                                    Text(track.artist, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                scope.launch {
                    // Verwijder tracks die niet meer geselecteerd zijn
                    val toRemove = currentTrackIds - selectedIds
                    toRemove.forEach { db.playlistTrackDao().remove(playlist.id, it) }

                    // Voeg nieuwe tracks toe
                    val toAdd = selectedIds - currentTrackIds
                    toAdd.forEach { trackId ->
                        val sortOrder = db.playlistTrackDao().getNextSortOrder(playlist.id)
                        db.playlistTrackDao().insert(PlaylistTrack(playlist.id, trackId, sortOrder))
                    }

                    onAdded()
                }
            }) { Text("Opslaan") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuleren") } }
    )
}

// --- Display helpers ---

private fun Channel.displayLabel(): String = when (this) {
    Channel.CALL -> "Telefoon"
    Channel.NOTIFICATION -> "Systeemmelding"
    Channel.SMS -> "SMS"
    Channel.WHATSAPP -> "WhatsApp"
}

private fun Channel.shortLabel(): String = when (this) {
    Channel.CALL -> "Tel"
    Channel.NOTIFICATION -> "Systeem"
    Channel.SMS -> "SMS"
    Channel.WHATSAPP -> "WA"
}

private fun Schedule.displayLabel(): String = when (this) {
    Schedule.MANUAL -> "Handmatig"
    Schedule.EVERY_CALL -> "Bij elk gesprek (niet dezelfde)"
    Schedule.HOURLY_1 -> "Elk uur"
    Schedule.HOURLY_2 -> "Elke 2 uur"
    Schedule.HOURLY_4 -> "Elke 4 uur"
    Schedule.HOURLY_8 -> "Elke 8 uur"
    Schedule.HOURLY_12 -> "Elke 12 uur"
    Schedule.DAILY -> "Dagelijks"
    Schedule.WEEKLY -> "Wekelijks"
}
