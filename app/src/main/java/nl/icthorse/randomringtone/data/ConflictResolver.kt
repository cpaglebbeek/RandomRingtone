package nl.icthorse.randomringtone.data

/**
 * Centrale conflictdetectie en -resolutie.
 *
 * Hiërarchie (hoog → laag):
 * 1. Per-contact playlist
 * 2. Globale playlist
 *
 * Regels:
 * - Maximaal 1 actieve playlist per kanaal+scope combinatie
 * - Per-contact overschrijft globaal (via Android ContactsContract voor CALL,
 *   via NotificationService lookup voor SMS/WA)
 */
class ConflictResolver(private val db: RingtoneDatabase) {

    data class ActiveSetting(
        val channel: Channel,
        val source: SettingSource,
        val sourceName: String,
        val trackTitle: String?,
        val contactName: String?,
        val schedule: Schedule,
        val mode: Mode,
        val trackCount: Int,
        val playlistId: Long
    )

    enum class SettingSource {
        CONTACT_PLAYLIST,
        GLOBAL_PLAYLIST
    }

    /**
     * Haal alle actieve instellingen op, gegroepeerd per kanaal+scope.
     */
    suspend fun getActiveSettings(): List<ActiveSetting> {
        val settings = mutableListOf<ActiveSetting>()
        val activePlaylists = db.playlistDao().getActive()

        for (playlist in activePlaylists) {
            val tracks = db.playlistTrackDao().getTracksForPlaylist(playlist.id)
            val currentTrack = when (playlist.mode) {
                Mode.FIXED -> tracks.firstOrNull()
                Mode.REAL_RANDOM, Mode.SEMI_RANDOM, Mode.QUASI_RANDOM -> {
                    if (playlist.lastPlayedTrackId != null) {
                        tracks.find { it.deezerTrackId == playlist.lastPlayedTrackId }
                            ?: tracks.firstOrNull()
                    } else {
                        tracks.firstOrNull()
                    }
                }
            }

            settings.add(
                ActiveSetting(
                    channel = playlist.channel,
                    source = if (playlist.contactUri != null)
                        SettingSource.CONTACT_PLAYLIST else SettingSource.GLOBAL_PLAYLIST,
                    sourceName = playlist.name,
                    trackTitle = currentTrack?.let { "${it.artist} - ${it.title}" },
                    contactName = playlist.contactName,
                    schedule = playlist.schedule,
                    mode = playlist.mode,
                    trackCount = tracks.size,
                    playlistId = playlist.id
                )
            )
        }

        return settings
    }

    /**
     * Check of er conflicten zijn voor een bepaald kanaal+scope.
     * Returns lijst met waarschuwingen (leeg = geen conflicten).
     */
    suspend fun checkConflicts(channel: Channel, contactUri: String?): List<String> {
        val conflicts = mutableListOf<String>()

        val existing = if (contactUri != null) {
            db.playlistDao().getActive().filter {
                it.channel == channel && it.contactUri == contactUri
            }
        } else {
            db.playlistDao().getActiveGlobalForChannel(channel)
        }

        if (existing.size > 1) {
            val names = existing.joinToString(", ") { "'${it.name}'" }
            conflicts.add("Meerdere actieve playlists voor ${channel.displayName()}: $names")
        }

        return conflicts
    }

    /**
     * Enforce maximaal 1 actieve playlist per kanaal+scope.
     * Deactiveert alle andere playlists met dezelfde kanaal+scope.
     */
    suspend fun enforceOneActivePerChannelScope(playlistId: Long) {
        val playlist = db.playlistDao().getById(playlistId) ?: return
        if (!playlist.isActive) return

        RemoteLogger.i("ConflictResolver", "enforceOneActive", mapOf(
            "playlist" to playlist.name, "channel" to playlist.channel.name,
            "scope" to if (playlist.contactUri != null) "contact:${playlist.contactName}" else "globaal"
        ))

        if (playlist.contactUri != null) {
            db.playlistDao().deactivateOtherForContact(
                channel = playlist.channel,
                contactUri = playlist.contactUri,
                excludeId = playlist.id
            )
        } else {
            db.playlistDao().deactivateOtherGlobal(
                channel = playlist.channel,
                excludeId = playlist.id
            )
        }
        RemoteLogger.d("ConflictResolver", "Conflicterende playlists gedeactiveerd")
    }
}

private fun Channel.displayName(): String = when (this) {
    Channel.CALL -> "Telefoon"
    Channel.NOTIFICATION -> "Systeemmelding"
    Channel.SMS -> "SMS"
    Channel.WHATSAPP -> "WhatsApp"
}
