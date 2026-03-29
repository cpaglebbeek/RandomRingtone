package nl.icthorse.randomringtone.service

import android.app.Notification
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.*
import nl.icthorse.randomringtone.data.*
import java.io.File

/**
 * Onderschept notificaties van SMS en WhatsApp apps.
 * Speelt custom ringtone af op basis van afzender en kanaal.
 */
class NotificationService : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var db: RingtoneDatabase
    private lateinit var ringtoneManager: AppRingtoneManager

    companion object {
        private const val TAG = "NotificationService"
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val SAMSUNG_SMS_PACKAGE = "com.samsung.android.messaging"
        private const val GOOGLE_SMS_PACKAGE = "com.google.android.apps.messaging"
    }

    override fun onCreate() {
        super.onCreate()
        db = RingtoneDatabase.getInstance(this)
        ringtoneManager = AppRingtoneManager(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        val channel = when (sbn.packageName) {
            WHATSAPP_PACKAGE -> Channel.WHATSAPP
            SAMSUNG_SMS_PACKAGE, GOOGLE_SMS_PACKAGE -> Channel.SMS
            else -> return // Niet relevant
        }

        val contactName = extractContactName(sbn, channel) ?: return

        scope.launch {
            try {
                val assignment = findAssignment(contactName, channel) ?: return@launch
                val trackFile = resolveTrackFile(assignment) ?: return@launch
                playCustomSound(trackFile)
                // Demp de originele notificatie geluid
                cancelNotificationSound(sbn)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling notification for $contactName", e)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Niet nodig
    }

    /**
     * Haal contactnaam uit de notificatie.
     */
    private fun extractContactName(sbn: StatusBarNotification, channel: Channel): String? {
        val extras = sbn.notification.extras
        return when (channel) {
            Channel.WHATSAPP -> extras.getString(Notification.EXTRA_TITLE)
            Channel.SMS -> extras.getString(Notification.EXTRA_TITLE)
            else -> null
        }
    }

    /**
     * Zoek de juiste assignment: eerst per contact, dan globaal fallback.
     */
    private suspend fun findAssignment(contactName: String, channel: Channel): RingtoneAssignment? {
        // Zoek per-contact assignment op naam
        val allAssignments = db.assignmentDao().getAll()
        val contactAssignment = allAssignments.firstOrNull {
            it.channel == channel && it.contactName.equals(contactName, ignoreCase = true)
        }
        if (contactAssignment != null) return contactAssignment

        // Fallback: globale assignment
        return db.assignmentDao().getGlobalForChannel(channel)
    }

    /**
     * Bepaal welk track-bestand afgespeeld moet worden.
     */
    private suspend fun resolveTrackFile(assignment: RingtoneAssignment): File? {
        return when (assignment.mode) {
            Mode.FIXED -> {
                val trackId = assignment.fixedTrackId ?: return null
                val savedTrack = db.savedTrackDao().getById(trackId) ?: return null
                val localPath = savedTrack.localPath
                if (localPath != null && File(localPath).exists()) {
                    File(localPath)
                } else {
                    // Download on-the-fly
                    val deezerTrack = DeezerTrack(
                        id = savedTrack.deezerTrackId,
                        title = savedTrack.title,
                        artist = DeezerArtist(name = savedTrack.artist),
                        preview = savedTrack.previewUrl
                    )
                    ringtoneManager.downloadPreview(deezerTrack)
                }
            }
            Mode.RANDOM -> {
                val playlistName = assignment.playlistName ?: return null
                val tracks = db.savedTrackDao().getByPlaylist(playlistName)
                if (tracks.isEmpty()) return null
                val randomTrack = tracks.random()
                val localPath = randomTrack.localPath
                if (localPath != null && File(localPath).exists()) {
                    File(localPath)
                } else {
                    val deezerTrack = DeezerTrack(
                        id = randomTrack.deezerTrackId,
                        title = randomTrack.title,
                        artist = DeezerArtist(name = randomTrack.artist),
                        preview = randomTrack.previewUrl
                    )
                    ringtoneManager.downloadPreview(deezerTrack)
                }
            }
        }
    }

    /**
     * Speel custom geluid af.
     */
    private fun playCustomSound(file: File) {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            setDataSource(file.absolutePath)
            prepare()
            start()
            setOnCompletionListener { release() }
        }
    }

    /**
     * Probeer het originele notificatiegeluid te dempen door
     * de notificatie opnieuw te posten zonder geluid.
     */
    private fun cancelNotificationSound(sbn: StatusBarNotification) {
        try {
            snoozeNotification(sbn.key, 1) // Snooze 1ms = effectief cancel geluid
        } catch (_: Exception) {
            // Niet alle Android versies ondersteunen snooze
        }
    }

    override fun onDestroy() {
        scope.cancel()
        mediaPlayer?.release()
        super.onDestroy()
    }
}
