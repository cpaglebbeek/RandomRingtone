package nl.icthorse.randomringtone.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.ContactsContract
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import nl.icthorse.randomringtone.R
import nl.icthorse.randomringtone.data.RemoteLogger
import nl.icthorse.randomringtone.ui.VideoRingActivity
import java.io.File

/**
 * VideoRing — hybride aanpak:
 * - Scherm uit/locked → full-screen intent → VideoRingActivity (beeldvullend)
 * - Scherm aan/unlocked → kleine overlay bovenin scherm (SYSTEM_ALERT_WINDOW)
 *   met FLAG_NOT_TOUCHABLE zodat Samsung answer/reject knoppen bereikbaar blijven
 */
object VideoRingtoneService {

    private const val CHANNEL_ID = "videoring_fullscreen"
    private const val NOTIFICATION_ID = 42

    @Volatile
    var isShowing = false
        private set

    // Overlay state (voor unlocked scherm)
    private var windowManager: WindowManager? = null
    private var overlayView: FrameLayout? = null

    fun show(context: Context, videoPath: String, callerName: String?, callerNumber: String?) {
        val appContext = context.applicationContext

        var name = callerName ?: "Onbekend"
        if (name == "Onbekend" && !callerNumber.isNullOrBlank()) {
            name = resolveContactName(appContext, callerNumber) ?: "Onbekend"
        }

        ensureChannel(appContext)

        // Full-screen intent voor locked scherm
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

        // Hybride: als scherm aan staat → overlay i.p.v. Activity
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (powerManager.isInteractive && Settings.canDrawOverlays(appContext)) {
            showOverlay(appContext, videoPath, name, callerNumber ?: "")
        } else {
            RemoteLogger.i("VideoRing", "Full-screen intent notification", mapOf(
                "caller" to name, "interactive" to powerManager.isInteractive.toString(),
                "canDrawOverlays" to Settings.canDrawOverlays(appContext).toString()
            ))
        }
    }

    fun dismiss(context: Context) {
        val nm = context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
        isShowing = false

        // Sluit overlay als actief
        removeOverlay()

        // Sluit VideoRingActivity als actief (locked scherm case)
        VideoRingActivity.activeInstance?.finish()

        RemoteLogger.d("VideoRing", "Dismiss: notification + overlay + activity")
    }

    /**
     * Overlay voor unlocked scherm — bovenste 55% van het scherm.
     * Toont video-thumbnail (betrouwbaarder dan SurfaceView in overlay) + beller-info.
     * FLAG_NOT_TOUCHABLE: alle touches gaan naar het Samsung belscherm eronder.
     */
    private fun showOverlay(context: Context, videoPath: String, callerName: String, callerNumber: String) {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val displayMetrics = context.resources.displayMetrics
        val overlayHeight = (displayMetrics.heightPixels * 0.55).toInt()

        // Thumbnail als achtergrond (SurfaceView werkt niet betrouwbaar in overlay)
        val thumbnail = extractThumbnail(videoPath)

        overlayView = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)

            // Video thumbnail als ImageView (beeldvullend)
            if (thumbnail != null) {
                val imageView = android.widget.ImageView(context).apply {
                    setImageBitmap(thumbnail)
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                }
                addView(imageView, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))
            }

            // Caller info
            val callerText = TextView(context).apply {
                text = buildString {
                    append(callerName)
                    if (callerNumber.isNotBlank() && callerNumber != callerName) {
                        append("\n$callerNumber")
                    }
                }
                setTextColor(Color.WHITE)
                textSize = 24f
                setShadowLayer(8f, 2f, 2f, Color.BLACK)
                gravity = Gravity.CENTER
                setPadding(24, 16, 24, 16)
            }
            val textParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; bottomMargin = 24 }
            addView(callerText, textParams)
        }

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
        }

        try {
            // Voorkom dubbele overlay
            if (overlayView != null) {
                RemoteLogger.d("VideoRing", "Overlay al actief, skip")
                return
            }
            windowManager?.addView(overlayView, layoutParams)
            RemoteLogger.d("VideoRing", "Overlay addView OK", mapOf("height" to overlayHeight.toString()))
        } catch (e: Exception) {
            RemoteLogger.e("VideoRing", "Overlay tonen mislukt", mapOf(
                "error" to (e.message ?: ""),
                "class" to e.javaClass.simpleName,
                "canDraw" to Settings.canDrawOverlays(context).toString()
            ))
        }
    }

    private fun removeOverlay() {
        try {
            overlayView?.let { windowManager?.removeView(it) }
            overlayView = null
            windowManager = null
        } catch (_: Exception) {}
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.deleteNotificationChannel("videoring_channel")
            nm.deleteNotificationChannel("videoring_incoming")
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "Video Ringtone", NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Toont video bij inkomend gesprek"
                    setShowBadge(false); enableVibration(false); setSound(null, null)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun extractThumbnail(videoPath: String): Bitmap? {
        return try {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(videoPath)
                r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        } catch (_: Exception) { null }
    }

    private fun resolveContactName(context: Context, number: String): String? {
        phoneLookup(context, number)?.let { return it }
        if (number.startsWith("+31")) phoneLookup(context, "0" + number.removePrefix("+31"))?.let { return it }
        if (number.startsWith("0") && number.length >= 10) phoneLookup(context, "+31" + number.removePrefix("0"))?.let { return it }
        return null
    }

    private fun phoneLookup(context: Context, number: String): String? = try {
        val uri = android.net.Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(number)
        )
        context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (_: Exception) { null }
}
