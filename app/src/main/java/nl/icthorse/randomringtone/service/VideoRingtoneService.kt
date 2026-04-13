package nl.icthorse.randomringtone.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.ContactsContract
import androidx.core.app.NotificationCompat
import nl.icthorse.randomringtone.R
import nl.icthorse.randomringtone.data.RemoteLogger
import nl.icthorse.randomringtone.ui.VideoRingActivity
import java.io.File

/**
 * VideoRing notification helper — toont een full-screen intent notification
 * die VideoRingActivity opent boven het lockscreen bij inkomend gesprek.
 */
object VideoRingtoneService {

    private const val CHANNEL_ID = "videoring_fullscreen"
    private const val NOTIFICATION_ID = 42

    @Volatile
    var isShowing = false
        private set

    /**
     * Toon full-screen notification die VideoRingActivity lanceert.
     */
    fun show(context: Context, videoPath: String, callerName: String?, callerNumber: String?) {
        val appContext = context.applicationContext

        // Resolve caller name
        var name = callerName ?: "Onbekend"
        if (name == "Onbekend" && !callerNumber.isNullOrBlank()) {
            name = resolveContactName(appContext, callerNumber) ?: "Onbekend"
        }

        ensureChannel(appContext)

        // Full-screen intent → opent VideoRingActivity boven lockscreen
        val fullScreenIntent = Intent(appContext, VideoRingActivity::class.java).apply {
            putExtra(VideoRingActivity.EXTRA_VIDEO_PATH, videoPath)
            putExtra(VideoRingActivity.EXTRA_CALLER_NAME, name)
            putExtra(VideoRingActivity.EXTRA_CALLER_NUMBER, callerNumber ?: "")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val fullScreenPending = PendingIntent.getActivity(
            appContext, 0, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Thumbnail voor notification
        val thumbnail = extractThumbnail(videoPath)

        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(name)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPending, true)
            .setAutoCancel(false)
            .setOngoing(true)

        if (!callerNumber.isNullOrBlank() && callerNumber != name) {
            builder.setContentText(callerNumber)
        }

        if (thumbnail != null) {
            builder.setLargeIcon(thumbnail)
        }

        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, builder.build())
        isShowing = true

        RemoteLogger.i("VideoRing", "Full-screen notification getoond", mapOf(
            "caller" to name,
            "number" to (callerNumber ?: "?"),
            "thumbnail" to (if (thumbnail != null) "ja" else "nee"),
            "video" to File(videoPath).name
        ))
    }

    /**
     * Verwijder notification + sluit VideoRingActivity.
     */
    fun dismiss(context: Context) {
        val nm = context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
        isShowing = false

        // Sluit VideoRingActivity als die actief is
        VideoRingActivity.activeInstance?.finish()

        RemoteLogger.d("VideoRing", "Notification + Activity verwijderd")
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // Verwijder oude channels
            nm.deleteNotificationChannel("videoring_channel")
            nm.deleteNotificationChannel("videoring_incoming")
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Video Ringtone",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Toont video bij inkomend gesprek"
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
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
        // Probeer PhoneLookup met origineel nummer
        val result = phoneLookup(context, number)
        if (result != null) return result

        // Fallback: als nummer begint met +31, probeer ook met 0
        if (number.startsWith("+31")) {
            val local = "0" + number.removePrefix("+31")
            val localResult = phoneLookup(context, local)
            if (localResult != null) return localResult
        }
        // Fallback: als nummer begint met 0, probeer ook met +31
        if (number.startsWith("0") && number.length >= 10) {
            val intl = "+31" + number.removePrefix("0")
            val intlResult = phoneLookup(context, intl)
            if (intlResult != null) return intlResult
        }

        RemoteLogger.w("VideoRing", "Contact niet gevonden", mapOf(
            "number" to number,
            "READ_CONTACTS" to (android.content.pm.PackageManager.PERMISSION_GRANTED ==
                context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS)).toString()
        ))
        return null
    }

    private fun phoneLookup(context: Context, number: String): String? {
        return try {
            val uri = android.net.Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                android.net.Uri.encode(number)
            )
            context.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) { null }
    }
}
