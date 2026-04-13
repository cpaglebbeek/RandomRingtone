package nl.icthorse.randomringtone.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.ContactsContract
import androidx.core.app.NotificationCompat
import nl.icthorse.randomringtone.R
import nl.icthorse.randomringtone.data.RemoteLogger
import java.io.File

/**
 * VideoRing notification helper — toont een heads-up notification met video-thumbnail
 * en beller-info bij een inkomend gesprek.
 *
 * Geen foreground service, geen overlay, geen SYSTEM_ALERT_WINDOW.
 * Werkt puur via het Android notificatiesysteem (IMPORTANCE_HIGH = heads-up).
 */
object VideoRingtoneService {

    private const val CHANNEL_ID = "videoring_incoming"
    private const val NOTIFICATION_ID = 42

    @Volatile
    var isShowing = false
        private set

    /**
     * Toon heads-up notification met video-thumbnail en beller-info.
     */
    fun show(context: Context, videoPath: String, callerName: String?, callerNumber: String?) {
        val appContext = context.applicationContext

        // Resolve caller name als nog onbekend
        var name = callerName ?: "Onbekend"
        if (name == "Onbekend" && !callerNumber.isNullOrBlank()) {
            name = resolveContactName(appContext, callerNumber) ?: "Onbekend"
        }

        ensureChannel(appContext)

        // Extract video thumbnail
        val thumbnail = extractThumbnail(videoPath)

        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(name)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(false)
            .setOngoing(true)

        // Beller-nummer als subtekst
        if (!callerNumber.isNullOrBlank() && callerNumber != name) {
            builder.setContentText(callerNumber)
        }

        // Video thumbnail als grote afbeelding
        if (thumbnail != null) {
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(thumbnail)
                    .setSummaryText(if (!callerNumber.isNullOrBlank() && callerNumber != name) callerNumber else "Inkomend gesprek")
            )
            builder.setLargeIcon(thumbnail)
        } else {
            builder.setContentText(
                buildString {
                    if (!callerNumber.isNullOrBlank() && callerNumber != name) {
                        append(callerNumber)
                        append(" — ")
                    }
                    append("Video Ringtone")
                }
            )
        }

        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, builder.build())
        isShowing = true

        RemoteLogger.i("VideoRing", "Heads-up notification getoond", mapOf(
            "caller" to name,
            "number" to (callerNumber ?: "?"),
            "thumbnail" to (if (thumbnail != null) "ja" else "nee"),
            "video" to File(videoPath).name
        ))
    }

    /**
     * Verwijder de notification.
     */
    fun dismiss(context: Context) {
        if (!isShowing) return
        val nm = context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
        isShowing = false
        RemoteLogger.d("VideoRing", "Notification verwijderd")
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Video Ringtone",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Toont beller-info met video bij inkomend gesprek"
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null) // Geen eigen geluid — de ringtone speelt al
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun extractThumbnail(videoPath: String): Bitmap? {
        return try {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(videoPath)
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        } catch (e: Exception) {
            RemoteLogger.w("VideoRing", "Thumbnail extractie mislukt", mapOf("error" to (e.message ?: "")))
            null
        }
    }

    private fun resolveContactName(context: Context, number: String): String? {
        return try {
            val uri = android.net.Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                android.net.Uri.encode(number)
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }
}
