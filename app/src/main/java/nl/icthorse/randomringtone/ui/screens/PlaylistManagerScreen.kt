package nl.icthorse.randomringtone.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Playlists", style = MaterialTheme.typography.headlineMedium)
            FilledTonalButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Nieuw")
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
                        onToggleActive = {
                            scope.launch {
                                db.playlistDao().update(playlist.copy(isActive = !playlist.isActive))
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
                    if (editPlaylist != null) {
                        db.playlistDao().update(playlist)
                    } else {
                        db.playlistDao().insert(playlist)
                    }
                    showCreateDialog = false
                    editPlaylist = null
                    refresh()
                    snackbarHostState.showSnackbar(
                        if (editPlaylist != null) "Playlist bijgewerkt" else "Playlist '${playlist.name}' aangemaakt"
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
    var showContactList by remember { mutableStateOf(false) }
    var contacts by remember { mutableStateOf<List<ContactInfo>>(emptyList()) }

    LaunchedEffect(Unit) {
        try { contacts = contactsRepo.getContacts() } catch (_: Exception) {}
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing != null) "Playlist bewerken" else "Nieuwe playlist") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
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

                // Scope
                Text("Scope:", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = isGlobal, onClick = { isGlobal = true; selectedContact = null },
                        label = { Text("Globaal") })
                    FilterChip(selected = !isGlobal, onClick = { isGlobal = false },
                        label = { Text("Per contact") })
                }

                if (!isGlobal) {
                    OutlinedTextField(
                        value = selectedContact?.name ?: contactQuery,
                        onValueChange = { contactQuery = it; selectedContact = null; showContactList = true },
                        label = { Text("Contact") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (showContactList && contactQuery.isNotBlank()) {
                        contacts.filter { it.name.contains(contactQuery, ignoreCase = true) }.take(5).forEach { c ->
                            TextButton(onClick = {
                                selectedContact = c; contactQuery = c.name; showContactList = false
                            }) { Text(c.name) }
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
        allRingtones = db.savedTrackDao().getAll()
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
