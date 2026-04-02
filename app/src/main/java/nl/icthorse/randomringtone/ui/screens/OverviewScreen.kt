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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.icthorse.randomringtone.data.*

@Composable
fun OverviewScreen(
    db: RingtoneDatabase,
    snackbarHostState: SnackbarHostState
) {
    val scope = rememberCoroutineScope()
    val conflictResolver = remember { ConflictResolver(db) }
    var settings by remember { mutableStateOf<List<ConflictResolver.ActiveSetting>>(emptyList()) }
    var conflicts by remember { mutableStateOf<List<String>>(emptyList()) }

    fun refresh() {
        scope.launch {
            settings = conflictResolver.getActiveSettings()
            // Check conflicten per kanaal
            val allConflicts = mutableListOf<String>()
            Channel.entries.forEach { channel ->
                allConflicts.addAll(conflictResolver.checkConflicts(channel, null))
            }
            conflicts = allConflicts
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Overzicht", style = MaterialTheme.typography.headlineMedium)

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            "Actieve ringtone-instellingen per kanaal",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (settings.isEmpty() && conflicts.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Dashboard,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Geen actieve instellingen",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Maak een playlist aan en activeer deze",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Conflictwaarschuwingen
                if (conflicts.isNotEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "Conflicten",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                conflicts.forEach { conflict ->
                                    Text(
                                        text = conflict,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                    }
                }

                // Huidige instellingen header
                item {
                    Text(
                        "HUIDIGE INSTELLINGEN",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // Globale instellingen per kanaal
                val globalSettings = settings.filter { it.contactName == null }
                    .sortedBy { it.channel.ordinal }
                val contactSettings = settings.filter { it.contactName != null }
                    .sortedWith(compareBy({ it.channel.ordinal }, { it.contactName }))

                // Toon alle 4 kanalen (ook als niet ingesteld)
                items(Channel.entries.toList()) { channel ->
                    val setting = globalSettings.find { it.channel == channel }
                    ChannelCard(channel = channel, setting = setting, contactName = null)
                }

                // Per-contact instellingen
                if (contactSettings.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "PER CONTACT",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    items(contactSettings) { setting ->
                        ChannelCard(
                            channel = setting.channel,
                            setting = setting,
                            contactName = setting.contactName
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelCard(
    channel: Channel,
    setting: ConflictResolver.ActiveSetting?,
    contactName: String?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (setting != null) CardDefaults.cardColors()
        else CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = when (channel) {
                    Channel.CALL -> Icons.Default.Phone
                    Channel.NOTIFICATION -> Icons.Default.Notifications
                    Channel.SMS -> Icons.Default.Sms
                    Channel.WHATSAPP -> Icons.Default.Chat
                },
                contentDescription = null,
                tint = if (setting != null) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = buildString {
                        append(channel.overviewLabel())
                        if (contactName != null) {
                            append(" — $contactName")
                        } else {
                            append(" (globaal)")
                        }
                    },
                    style = MaterialTheme.typography.titleSmall
                )

                if (setting != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Playlist: ${setting.sourceName} (${when (setting.mode) {
                            Mode.FIXED -> "Vast"
                            Mode.REAL_RANDOM -> "Real"
                            Mode.SEMI_RANDOM -> "Semi"
                            Mode.QUASI_RANDOM -> "Quasi"
                        }})",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Schema: ${setting.schedule.overviewLabel()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (setting.trackTitle != null) {
                        Text(
                            text = "Huidige track: ${setting.trackTitle}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = "${setting.trackCount} ringtone${if (setting.trackCount != 1) "s" else ""} in playlist",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Niet ingesteld — systeem standaard",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// --- Display helpers ---

private fun Channel.overviewLabel(): String = when (this) {
    Channel.CALL -> "Telefoon"
    Channel.NOTIFICATION -> "Systeemmelding"
    Channel.SMS -> "SMS"
    Channel.WHATSAPP -> "WhatsApp"
}

private fun Schedule.overviewLabel(): String = when (this) {
    Schedule.MANUAL -> "Handmatig"
    Schedule.EVERY_CALL -> "Bij elk gesprek"
    Schedule.HOURLY_1 -> "Elk uur"
    Schedule.HOURLY_2 -> "Elke 2 uur"
    Schedule.HOURLY_4 -> "Elke 4 uur"
    Schedule.HOURLY_8 -> "Elke 8 uur"
    Schedule.HOURLY_12 -> "Elke 12 uur"
    Schedule.DAILY -> "Dagelijks"
    Schedule.WEEKLY -> "Wekelijks"
}
