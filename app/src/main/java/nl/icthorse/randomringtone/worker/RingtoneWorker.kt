package nl.icthorse.randomringtone.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.icthorse.randomringtone.data.*
import nl.icthorse.randomringtone.data.RemoteLogger
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker die periodiek een random ringtone selecteert en instelt.
 * Wordt aangestuurd op basis van Schedule (HOURLY_*, DAILY, WEEKLY).
 *
 * v0.5.0: Gemigreerd van legacy assignments naar PlaylistDao + TrackResolver.
 * EVERY_CALL wordt afgehandeld door CallStateReceiver, niet door deze worker.
 */
class RingtoneWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "RingtoneWorker"
        const val WORK_NAME_HOURLY = "ringtone_hourly"
        const val WORK_NAME_DAILY = "ringtone_daily"
        const val WORK_NAME_WEEKLY = "ringtone_weekly"

        /**
         * Plan periodieke workers. Alle drie worden altijd gepland;
         * de worker checkt zelf of er actieve playlists zijn.
         */
        fun scheduleAll(context: Context) {
            val wm = WorkManager.getInstance(context)

            wm.cancelUniqueWork(WORK_NAME_HOURLY)
            wm.cancelUniqueWork(WORK_NAME_DAILY)
            wm.cancelUniqueWork(WORK_NAME_WEEKLY)

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            // Hourly
            wm.enqueueUniquePeriodicWork(
                WORK_NAME_HOURLY,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<RingtoneWorker>(1, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .addTag("schedule_hourly")
                    .build()
            )

            // Daily
            wm.enqueueUniquePeriodicWork(
                WORK_NAME_DAILY,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<RingtoneWorker>(1, TimeUnit.DAYS)
                    .setConstraints(constraints)
                    .addTag("schedule_daily")
                    .build()
            )

            // Weekly
            wm.enqueueUniquePeriodicWork(
                WORK_NAME_WEEKLY,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<RingtoneWorker>(7, TimeUnit.DAYS)
                    .setConstraints(constraints)
                    .addTag("schedule_weekly")
                    .build()
            )

            Log.i(TAG, "Scheduled hourly, daily and weekly ringtone workers")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            RemoteLogger.init(applicationContext)
            val db = RingtoneDatabase.getInstance(applicationContext)
            val ringtoneManager = AppRingtoneManager(applicationContext)
            val resolver = TrackResolver(db, ringtoneManager, applicationContext)
            val tags = this@RingtoneWorker.tags
            RemoteLogger.trigger("Worker", "RingtoneWorker.doWork()", mapOf("tags" to tags.joinToString()))

            // Bepaal welke schedules bij deze worker tag horen
            val targetSchedules = when {
                tags.contains("schedule_hourly") -> listOf(
                    Schedule.HOURLY_1, Schedule.HOURLY_2, Schedule.HOURLY_4,
                    Schedule.HOURLY_8, Schedule.HOURLY_12
                )
                tags.contains("schedule_daily") -> listOf(Schedule.DAILY)
                tags.contains("schedule_weekly") -> listOf(Schedule.WEEKLY)
                else -> return@withContext Result.success()
            }

            // Log ALLE actieve playlists vóór filter
            val allActive = db.playlistDao().getActive()
            RemoteLogger.i("Worker", "Alle actieve playlists", mapOf(
                "count" to allActive.size.toString(),
                "playlists" to allActive.joinToString { "${it.name}[${it.channel}/${it.schedule}/${it.mode}/${if (it.contactUri != null) "contact:${it.contactName}" else "globaal"}]" }
            ))

            // Filter op RANDOM modus en matching schedule
            val playlists = allActive.filter {
                it.mode == Mode.RANDOM && it.schedule in targetSchedules
            }

            if (playlists.isEmpty()) {
                RemoteLogger.d("Worker", "Geen actieve playlists voor schedules $targetSchedules")
                Log.d(TAG, "No active playlists for schedules $targetSchedules")
                return@withContext Result.success()
            }

            RemoteLogger.i("Worker", "Playlists na filter", mapOf(
                "count" to playlists.size.toString(),
                "schedules" to targetSchedules.joinToString(),
                "playlists" to playlists.joinToString { "${it.name}[${if (it.contactUri != null) "contact:${it.contactName}" else "globaal"}]" }
            ))

            for (playlist in playlists) {
                try {
                    RemoteLogger.i("Worker", "Verwerk playlist: ${playlist.name}", mapOf(
                        "channel" to playlist.channel.name, "mode" to playlist.mode.name,
                        "contact" to (playlist.contactName ?: "globaal")
                    ))
                    val result = resolver.resolveForPlaylist(playlist)
                    if (result == null) {
                        RemoteLogger.w("Worker", "SKIP playlist — geen track beschikbaar", mapOf("playlist" to playlist.name))
                        continue
                    }
                    val (file, track) = result
                    val deezerTrack = track.toDeezerTrack()

                    when (playlist.channel) {
                        Channel.CALL -> {
                            if (playlist.contactUri != null) {
                                val contactName = playlist.contactName ?: "contact"
                                RemoteLogger.i("Worker", "SWAP contact ringtone", mapOf("contact" to contactName, "track" to "${track.artist} - ${track.title}"))
                                val success = ringtoneManager.swapContactRingtone(file, contactName, playlist.contactUri)
                                RemoteLogger.output("Worker", "Contact swap result", mapOf(
                                    "playlist" to playlist.name, "contact" to contactName,
                                    "track" to "${track.artist} - ${track.title}", "success" to success.toString()
                                ))
                            } else {
                                RemoteLogger.i("Worker", "SWAP globale ringtone", mapOf("track" to "${track.artist} - ${track.title}"))
                                val success = ringtoneManager.swapGlobalRingtone(file)
                                RemoteLogger.output("Worker", "Globale swap result", mapOf(
                                    "playlist" to playlist.name,
                                    "track" to "${track.artist} - ${track.title}", "success" to success.toString()
                                ))
                            }
                        }
                        Channel.NOTIFICATION -> {
                            ringtoneManager.setAsNotification(deezerTrack, file)
                        }
                        Channel.SMS, Channel.WHATSAPP -> {
                            RemoteLogger.d("Worker", "SMS/WA: overgeslagen (handled by NotificationService)")
                        }
                    }

                    resolver.updateLastPlayed(playlist, track.deezerTrackId)
                    Log.i(TAG, "Set ${playlist.channel} ringtone to ${track.artist} - ${track.title} (playlist: ${playlist.name})")
                } catch (e: Exception) {
                    RemoteLogger.e("Worker", "EXCEPTION bij playlist ${playlist.name}", mapOf(
                        "error" to (e.message ?: "unknown"),
                        "contact" to (playlist.contactName ?: "globaal")
                    ))
                    Log.e(TAG, "Error bij playlist ${playlist.name}", e)
                }
            }

            RemoteLogger.i("Worker", "Worker klaar — alle playlists verwerkt", mapOf("verwerkt" to playlists.size.toString()))
            Result.success()
        } catch (e: Exception) {
            RemoteLogger.e("Worker", "Worker FAILED", mapOf("error" to (e.message ?: "unknown")))
            Log.e(TAG, "Worker failed", e)
            Result.retry()
        }
    }
}
