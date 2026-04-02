package nl.icthorse.randomringtone.service

import android.app.Notification
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.*
import nl.icthorse.randomringtone.data.*
import java.io.File

/**
 * Onderschept notificaties van SMS en WhatsApp apps.
 * Speelt custom ringtone af op basis van afzender en kanaal.
 *
 * Gebruikt PlaylistDao + TrackResolver (v0.5.0: gemigreerd van legacy assignments).
 */
class NotificationService : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var db: RingtoneDatabase
    private lateinit var ringtoneManager: AppRingtoneManager
    private lateinit var trackResolver: TrackResolver

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
        trackResolver = TrackResolver(db, ringtoneManager, this)
        RemoteLogger.init(this)
        RemoteLogger.i("NotifService", "NotificationService onCreate — service gestart")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        val channel = when (sbn.packageName) {
            WHATSAPP_PACKAGE -> Channel.WHATSAPP
            SAMSUNG_SMS_PACKAGE, GOOGLE_SMS_PACKAGE -> Channel.SMS
            else -> return
        }

        val contactName = extractContactName(sbn, channel) ?: return

        RemoteLogger.trigger("NotifService", "Notificatie ontvangen", mapOf(
            "channel" to channel.name,
            "package" to sbn.packageName,
            "contact" to contactName
        ))

        scope.launch {
            try {
                val playlist = findPlaylist(contactName, channel)
                if (playlist == null) {
                    RemoteLogger.d("NotifService", "Geen playlist gevonden voor $contactName/$channel")
                    return@launch
                }
                RemoteLogger.i("NotifService", "Playlist gevonden: ${playlist.name}", mapOf("contact" to contactName, "channel" to channel.name))

                val result = trackResolver.resolveForPlaylist(playlist)
                if (result == null) {
                    RemoteLogger.w("NotifService", "Geen track beschikbaar voor playlist ${playlist.name}")
                    return@launch
                }
                val (file, track) = result

                RemoteLogger.i("NotifService", "Custom geluid afspelen", mapOf("track" to "${track.artist} - ${track.title}", "file" to file.name))
                playCustomSound(file)
                trackResolver.updateLastPlayed(playlist, track.deezerTrackId)
                cancelNotificationSound(sbn)

                RemoteLogger.output("NotifService", "CUSTOM RINGTONE AFGESPEELD", mapOf(
                    "channel" to channel.name,
                    "contact" to contactName,
                    "track" to "${track.artist} - ${track.title}",
                    "playlist" to playlist.name
                ))
            } catch (e: Exception) {
                RemoteLogger.e("NotifService", "FOUT bij notificatie", mapOf("contact" to contactName, "error" to (e.message ?: "unknown")))
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
     * Zoek de juiste playlist: eerst per contact, dan globaal fallback.
     * Hiërarchie: per-contact > globaal (conform ConflictResolver).
     */
    private suspend fun findPlaylist(contactName: String, channel: Channel): Playlist? {
        // Zoek per-contact playlist op naam
        val contactPlaylists = db.playlistDao()
            .getActiveForContactAndChannel(contactName, channel)
        if (contactPlaylists.isNotEmpty()) return contactPlaylists.first()

        // Fallback: globale playlist
        val globalPlaylists = db.playlistDao()
            .getActiveGlobalForChannel(channel)
        return globalPlaylists.firstOrNull()
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
     * Probeer het originele notificatiegeluid te dempen.
     */
    private fun cancelNotificationSound(sbn: StatusBarNotification) {
        try {
            snoozeNotification(sbn.key, 1)
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
