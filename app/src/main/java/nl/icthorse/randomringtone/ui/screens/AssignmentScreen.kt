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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.icthorse.randomringtone.data.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssignmentScreen(
    db: RingtoneDatabase,
    onNavigateToContactPicker: () -> Unit,
    onNavigateToTrackPicker: (assignmentId: Long) -> Unit
) {
    val scope = rememberCoroutineScope()
    var assignments by remember { mutableStateOf<List<RingtoneAssignment>>(emptyList()) }
    var showAddDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        assignments = db.assignmentDao().getAll()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Toewijzingen", style = MaterialTheme.typography.headlineMedium)
            FilledTonalButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Nieuw")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (assignments.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Tune,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Nog geen toewijzingen",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Stel in welke ringtone per contact en kanaal klinkt",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            // Groepeer op contact
            val grouped = assignments.groupBy { it.contactName ?: "Globaal" }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                grouped.forEach { (contactName, contactAssignments) ->
                    item {
                        Text(
                            text = contactName,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                    }
                    items(contactAssignments) { assignment ->
                        AssignmentCard(
                            assignment = assignment,
                            db = db,
                            onDelete = {
                                scope.launch {
                                    db.assignmentDao().delete(assignment)
                                    assignments = db.assignmentDao().getAll()
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddAssignmentDialog(
            db = db,
            onDismiss = { showAddDialog = false },
            onSaved = {
                showAddDialog = false
                scope.launch { assignments = db.assignmentDao().getAll() }
            }
        )
    }
}

@Composable
private fun AssignmentCard(
    assignment: RingtoneAssignment,
    db: RingtoneDatabase,
    onDelete: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var trackName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(assignment) {
        if (assignment.mode == Mode.FIXED && assignment.fixedTrackId != null) {
            val track = db.savedTrackDao().getById(assignment.fixedTrackId)
            trackName = track?.let { "${it.artist} - ${it.title}" }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (assignment.channel) {
                    Channel.CALL -> Icons.Default.Phone
                    Channel.NOTIFICATION -> Icons.Default.Notifications
                    Channel.SMS -> Icons.Default.Sms
                    Channel.WHATSAPP -> Icons.Default.Chat
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = assignment.channel.displayName(),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = when (assignment.mode) {
                        Mode.FIXED -> trackName ?: "Vast nummer (kies via Zoeken)"
                        Mode.RANDOM -> "Random uit: ${assignment.playlistName} — ${assignment.schedule.displayName()}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Verwijderen",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddAssignmentDialog(
    db: RingtoneDatabase,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val contactsRepo = remember { ContactsRepository(context) }

    var isGlobal by remember { mutableStateOf(true) }
    var selectedContact by remember { mutableStateOf<ContactInfo?>(null) }
    var selectedChannel by remember { mutableStateOf(Channel.CALL) }
    var selectedMode by remember { mutableStateOf(Mode.FIXED) }
    var selectedSchedule by remember { mutableStateOf(Schedule.EVERY_CALL) }
    var selectedPlaylist by remember { mutableStateOf("") }
    var contacts by remember { mutableStateOf<List<ContactInfo>>(emptyList()) }
    var playlists by remember { mutableStateOf<List<String>>(emptyList()) }
    var contactSearchQuery by remember { mutableStateOf("") }
    var showContactList by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try { contacts = contactsRepo.getContacts() } catch (_: Exception) { }
        playlists = db.savedTrackDao().getPlaylistNames()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nieuwe toewijzing") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Globaal of per contact
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Scope:", modifier = Modifier.width(80.dp))
                    FilterChip(
                        selected = isGlobal,
                        onClick = { isGlobal = true; selectedContact = null },
                        label = { Text("Globaal") }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FilterChip(
                        selected = !isGlobal,
                        onClick = { isGlobal = false },
                        label = { Text("Contact") }
                    )
                }

                // Contact selectie
                if (!isGlobal) {
                    OutlinedTextField(
                        value = selectedContact?.name ?: contactSearchQuery,
                        onValueChange = {
                            contactSearchQuery = it
                            selectedContact = null
                            showContactList = true
                        },
                        label = { Text("Kies contact") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    if (showContactList && contactSearchQuery.isNotBlank()) {
                        val filtered = contacts.filter {
                            it.name.contains(contactSearchQuery, ignoreCase = true)
                        }.take(5)
                        filtered.forEach { contact ->
                            TextButton(
                                onClick = {
                                    selectedContact = contact
                                    contactSearchQuery = contact.name
                                    showContactList = false
                                }
                            ) {
                                Text(contact.name)
                            }
                        }
                    }
                }

                // Kanaal selectie
                Text("Kanaal:", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Channel.entries.forEach { channel ->
                        FilterChip(
                            selected = selectedChannel == channel,
                            onClick = { selectedChannel = channel },
                            label = { Text(channel.shortName()) }
                        )
                    }
                }

                // Modus selectie
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Modus:", modifier = Modifier.width(80.dp))
                    FilterChip(
                        selected = selectedMode == Mode.FIXED,
                        onClick = { selectedMode = Mode.FIXED },
                        label = { Text("Vast") }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FilterChip(
                        selected = selectedMode == Mode.RANDOM,
                        onClick = { selectedMode = Mode.RANDOM },
                        label = { Text("Random") }
                    )
                }

                // Playlist selectie (bij RANDOM)
                if (selectedMode == Mode.RANDOM) {
                    if (playlists.isEmpty()) {
                        Text(
                            "Nog geen playlists. Voeg eerst tracks toe via Zoeken.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Text("Playlist:", style = MaterialTheme.typography.labelLarge)
                        playlists.forEach { name ->
                            FilterChip(
                                selected = selectedPlaylist == name,
                                onClick = { selectedPlaylist = name },
                                label = { Text(name) }
                            )
                        }
                    }
                }

                // Schema selectie (bij RANDOM)
                if (selectedMode == Mode.RANDOM) {
                    Text("Wanneer wisselen:", style = MaterialTheme.typography.labelLarge)
                    Column {
                        Schedule.entries.forEach { schedule ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = selectedSchedule == schedule,
                                    onClick = { selectedSchedule = schedule }
                                )
                                Text(
                                    text = schedule.displayName(),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }

                if (selectedMode == Mode.FIXED) {
                    Text(
                        "Na opslaan: kies een track via het Zoeken-tabblad",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch {
                        val assignment = RingtoneAssignment(
                            contactUri = if (isGlobal) null else selectedContact?.uri,
                            contactName = if (isGlobal) null else selectedContact?.name,
                            channel = selectedChannel,
                            mode = selectedMode,
                            schedule = if (selectedMode == Mode.RANDOM) selectedSchedule else Schedule.MANUAL,
                            playlistName = if (selectedMode == Mode.RANDOM) selectedPlaylist else null
                        )
                        // Start WorkManager schedules
                        nl.icthorse.randomringtone.worker.RingtoneWorker.scheduleAll(context)
                        db.assignmentDao().insert(assignment)
                        onSaved()
                    }
                },
                enabled = when {
                    !isGlobal && selectedContact == null -> false
                    selectedMode == Mode.RANDOM && selectedPlaylist.isBlank() -> false
                    else -> true
                }
            ) {
                Text("Opslaan")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuleren") }
        }
    )
}

private fun Channel.displayName(): String = when (this) {
    Channel.CALL -> "Telefoonoproep"
    Channel.NOTIFICATION -> "Notificatie"
    Channel.SMS -> "SMS"
    Channel.WHATSAPP -> "WhatsApp"
}

private fun Channel.shortName(): String = when (this) {
    Channel.CALL -> "Bel"
    Channel.NOTIFICATION -> "Notif."
    Channel.SMS -> "SMS"
    Channel.WHATSAPP -> "WA"
}

private fun Schedule.displayName(): String = when (this) {
    Schedule.MANUAL -> "Handmatig"
    Schedule.EVERY_CALL -> "Bij elke oproep"
    Schedule.EVERY_HOUR -> "Elk uur"
    Schedule.EVERY_DAY -> "Elke dag"
    Schedule.EVERY_WEEK -> "Elke week"
}
