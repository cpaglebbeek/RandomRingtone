package nl.icthorse.randomringtone.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.icthorse.randomringtone.data.*
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
            val db = RingtoneDatabase.getInstance(applicationContext)
            val ringtoneManager = AppRingtoneManager(applicationContext)
            val resolver = TrackResolver(db, ringtoneManager, applicationContext)
            val tags = this@RingtoneWorker.tags

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

            // Zoek actieve playlists met RANDOM modus en matching schedule
            val playlists = db.playlistDao().getActive().filter {
                it.mode == Mode.RANDOM && it.schedule in targetSchedules
            }

            if (playlists.isEmpty()) {
                Log.d(TAG, "No active playlists for schedules $targetSchedules")
                return@withContext Result.success()
            }

            for (playlist in playlists) {
                val result = resolver.resolveForPlaylist(playlist) ?: continue
                val (file, track) = result
                val deezerTrack = track.toDeezerTrack()

                when (playlist.channel) {
                    Channel.CALL -> {
                        if (playlist.contactUri != null) {
                            // Per-contact ringtone
                            val uri = ringtoneManager.addToMediaStorePublic(deezerTrack, file)
                            if (uri != null) {
                                ContactsRepository(applicationContext)
                                    .setContactRingtone(playlist.contactUri, uri)
                            }
                        } else {
                            // Globale ringtone
                            ringtoneManager.setAsRingtone(deezerTrack, file)
                        }
                    }
                    Channel.NOTIFICATION -> {
                        ringtoneManager.setAsNotification(deezerTrack, file)
                    }
                    // SMS en WhatsApp worden afgehandeld door NotificationService
                    Channel.SMS, Channel.WHATSAPP -> { /* handled by NotificationService */ }
                }

                resolver.updateLastPlayed(playlist, track.deezerTrackId)
                Log.i(TAG, "Set ${playlist.channel} ringtone to ${track.artist} - ${track.title} (playlist: ${playlist.name})")
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Worker failed", e)
            Result.retry()
        }
    }
}
