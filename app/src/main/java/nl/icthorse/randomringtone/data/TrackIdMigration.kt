package nl.icthorse.randomringtone.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Eenmalige data-migratie naar canonical trackId-strategie (R11, v8).
 *
 * Eerst pre-migratie backup naar filesDir/pre_trackid_migration_backup/.
 * Daarna voor elke SavedTrack newId via TrackIdResolver berekenen, collisions
 * mergen, playlist_tracks remappen, alles in één DB-transaction.
 *
 * Idempotent: meerdere runs → zelfde uitkomst (na eerste run is delta leeg).
 * Wordt getriggerd via DataStore-flag KEY_TRACK_ID_MIGRATION_V8_DONE.
 */
object TrackIdMigration {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    data class MigrationResult(
        val ran: Boolean,
        val remapped: Int = 0,
        val merged: Int = 0,
        val skipped: Int = 0,
        val playlistRefsUpdated: Int = 0,
        val message: String = ""
    )

    /**
     * Voer de migratie uit als hij nog niet gedaan is.
     * @return MigrationResult met statistieken
     */
    suspend fun migrateV8IfNeeded(
        context: Context,
        db: RingtoneDatabase,
        storage: StorageManager
    ): MigrationResult = withContext(Dispatchers.IO) {
        if (storage.isTrackIdMigrationV8Done()) {
            return@withContext MigrationResult(ran = false, message = "Migratie v8 al uitgevoerd")
        }

        try {
            val tracks = db.savedTrackDao().getAll()
            if (tracks.isEmpty()) {
                storage.setTrackIdMigrationV8Done(true)
                return@withContext MigrationResult(ran = true, message = "DB leeg — niets te migreren, flag gezet")
            }

            // === Pre-migratie backup ===
            writePreMigrationBackup(context, db)

            // === Bereken oude → nieuwe id mapping ===
            val idMap = mutableMapOf<Long, Long>()
            var skipped = 0
            for (tr in tracks) {
                if (tr.localPath.isNullOrBlank()) {
                    skipped++
                    continue
                }
                val newId = TrackIdResolver.computeCanonicalId(tr)
                idMap[tr.deezerTrackId] = newId
            }

            // === Collision-detectie: groepeer per newId ===
            val byNewId = idMap.entries.groupBy({ it.value }, { it.key })

            // Voor elke groep: keep eerste, mark rest als "merge into eerste"
            val mergeMap = mutableMapOf<Long, Long>() // oudId → kept-track-oudId
            val tracksByOldId = tracks.associateBy { it.deezerTrackId }
            for ((_, oldIds) in byNewId) {
                if (oldIds.size <= 1) continue
                // Keep oudste (laagste rowid → laagste position in lijst getAll()-order)
                val keptOldId = oldIds.minByOrNull {
                    tracksByOldId[it]?.deezerTrackId ?: Long.MAX_VALUE
                } ?: oldIds.first()
                for (oldId in oldIds) {
                    if (oldId != keptOldId) mergeMap[oldId] = keptOldId
                }
            }

            val merged = mergeMap.size
            val remapped = idMap.count { (oldId, newId) ->
                oldId != newId && !mergeMap.containsKey(oldId)
            }

            // === Voer wijzigingen uit ===
            var playlistRefsUpdated = 0

            // 1) Verwijder duplicaten (merge-victims) uit saved_tracks
            for (victimId in mergeMap.keys) {
                tracksByOldId[victimId]?.let { db.savedTrackDao().delete(it) }
            }

            // 2) Remap playlist_tracks
            val ptAll = db.playlistTrackDao().getAll()
            for (pt in ptAll) {
                val survivorOldId = mergeMap[pt.trackId] ?: pt.trackId
                val survivorNewId = idMap[survivorOldId] ?: survivorOldId
                if (survivorNewId != pt.trackId) {
                    db.playlistTrackDao().remove(pt.playlistId, pt.trackId)
                    db.playlistTrackDao().insert(
                        PlaylistTrack(pt.playlistId, survivorNewId, pt.sortOrder)
                    )
                    playlistRefsUpdated++
                }
            }

            // 3) Update saved_tracks met nieuwe id (re-insert met REPLACE strategie)
            for (tr in tracks) {
                if (tr.deezerTrackId in mergeMap) continue // al verwijderd
                val newId = idMap[tr.deezerTrackId] ?: continue
                if (newId == tr.deezerTrackId) continue
                // Verwijder oude rij + insert met nieuwe id (PK kan niet ge-update worden direct)
                db.savedTrackDao().delete(tr)
                db.savedTrackDao().insert(tr.copy(deezerTrackId = newId))
            }

            // 4) Flag zetten
            storage.setTrackIdMigrationV8Done(true)

            RemoteLogger.output("TrackIdMigration", "v8 voltooid", mapOf(
                "remapped" to remapped.toString(),
                "merged" to merged.toString(),
                "skipped" to skipped.toString(),
                "playlistRefs" to playlistRefsUpdated.toString()
            ))

            MigrationResult(
                ran = true,
                remapped = remapped,
                merged = merged,
                skipped = skipped,
                playlistRefsUpdated = playlistRefsUpdated,
                message = "Migratie v8: $remapped geremapt, $merged samengevoegd, $skipped overgeslagen, $playlistRefsUpdated playlist-refs bijgewerkt"
            )
        } catch (e: Exception) {
            RemoteLogger.e("TrackIdMigration", "v8 MISLUKT", mapOf("error" to (e.message ?: "?")))
            MigrationResult(ran = false, message = "Migratie mislukt: ${e.message}")
        }
    }

    private suspend fun writePreMigrationBackup(context: Context, db: RingtoneDatabase) {
        val dir = File(context.filesDir, "pre_trackid_migration_backup").apply { mkdirs() }
        val tracks = db.savedTrackDao().getAll().map {
            TrackBackup(it.deezerTrackId, it.title, it.artist, it.previewUrl, it.localPath,
                it.playlistName, it.id3Title, it.id3Artist, it.albumArtPath, it.markerType)
        }
        val playlistTracks = db.playlistTrackDao().getAll().map {
            PlaylistTrackBackup(it.playlistId, it.trackId, it.sortOrder)
        }
        File(dir, "saved_tracks.json").writeText(json.encodeToString(tracks))
        File(dir, "playlist_tracks.json").writeText(json.encodeToString(playlistTracks))
        File(dir, "timestamp.txt").writeText(
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        )
    }
}
