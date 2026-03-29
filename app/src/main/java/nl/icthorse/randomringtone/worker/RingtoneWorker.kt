package nl.icthorse.randomringtone.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.icthorse.randomringtone.data.*
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker die periodiek een random ringtone selecteert en instelt.
 * Wordt aangestuurd op basis van Schedule (EVERY_HOUR, EVERY_DAY, EVERY_WEEK).
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
         * Plan periodieke workers op basis van alle RANDOM assignments met een tijdschema.
         */
        fun scheduleAll(context: Context) {
            val wm = WorkManager.getInstance(context)

            // Cancel alles eerst
            wm.cancelUniqueWork(WORK_NAME_HOURLY)
            wm.cancelUniqueWork(WORK_NAME_DAILY)
            wm.cancelUniqueWork(WORK_NAME_WEEKLY)

            val db = RingtoneDatabase.getInstance(context)

            // Check async welke schedules actief zijn
            // WorkManager vereist dat we dit synchron plannen,
            // dus we doen een synchrone DB check via runBlocking niet — we plannen alle drie
            // en de worker checkt zelf of er assignments zijn.

            // Hourly
            val hourly = PeriodicWorkRequestBuilder<RingtoneWorker>(1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag("schedule_hourly")
                .build()

            wm.enqueueUniquePeriodicWork(
                WORK_NAME_HOURLY,
                ExistingPeriodicWorkPolicy.KEEP,
                hourly
            )

            // Daily
            val daily = PeriodicWorkRequestBuilder<RingtoneWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag("schedule_daily")
                .build()

            wm.enqueueUniquePeriodicWork(
                WORK_NAME_DAILY,
                ExistingPeriodicWorkPolicy.KEEP,
                daily
            )

            // Weekly
            val weekly = PeriodicWorkRequestBuilder<RingtoneWorker>(7, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag("schedule_weekly")
                .build()

            wm.enqueueUniquePeriodicWork(
                WORK_NAME_WEEKLY,
                ExistingPeriodicWorkPolicy.KEEP,
                weekly
            )

            Log.i(TAG, "Scheduled hourly, daily and weekly ringtone workers")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val db = RingtoneDatabase.getInstance(applicationContext)
            val ringtoneManager = AppRingtoneManager(applicationContext)
            val tags = this@RingtoneWorker.tags

            // Bepaal welk schedule dit is
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

            // Zoek alle RANDOM assignments + playlists met matching schedule
            val assignments = db.assignmentDao().getAll().filter {
                it.mode == Mode.RANDOM && it.schedule in targetSchedules
            }

            if (assignments.isEmpty()) {
                Log.d(TAG, "No RANDOM assignments for schedules $targetSchedules")
                return@withContext Result.success()
            }

            for (assignment in assignments) {
                val playlistName = assignment.playlistName ?: continue
                val tracks = db.savedTrackDao().getByPlaylist(playlistName)
                if (tracks.isEmpty()) continue

                val randomTrack = tracks.random()
                val deezerTrack = DeezerTrack(
                    id = randomTrack.deezerTrackId,
                    title = randomTrack.title,
                    artist = DeezerArtist(name = randomTrack.artist),
                    preview = randomTrack.previewUrl
                )

                val file = ringtoneManager.downloadPreview(deezerTrack)

                when (assignment.channel) {
                    Channel.CALL -> {
                        if (assignment.contactUri != null) {
                            // Per-contact ringtone
                            val uri = ringtoneManager.addToMediaStorePublic(deezerTrack, file)
                            if (uri != null) {
                                ContactsRepository(applicationContext)
                                    .setContactRingtone(assignment.contactUri, uri)
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
                    // bij elke binnenkomende notificatie
                    Channel.SMS, Channel.WHATSAPP -> { /* handled by NotificationService */ }
                }

                Log.i(TAG, "Set ${assignment.channel} ringtone to ${randomTrack.artist} - ${randomTrack.title}")
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Worker failed", e)
            Result.retry()
        }
    }
}
