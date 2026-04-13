package nl.icthorse.randomringtone.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.MediaMetadataRetriever
import android.provider.ContactsContract
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import nl.icthorse.randomringtone.data.RemoteLogger

/**
 * AccessibilityService die een video-overlay kan tonen bovenop ALLES,
 * inclusief Samsung's InCallUI. Gebruikt TYPE_ACCESSIBILITY_OVERLAY
 * (hogere z-order dan TYPE_APPLICATION_OVERLAY).
 *
 * Wordt alleen gebruikt bij unlocked scherm. Bij locked scherm wordt
 * VideoRingActivity via full-screen intent gebruikt.
 */
class VideoRingAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: VideoRingAccessibilityService? = null
            private set

        fun showOverlay(context: Context, videoPath: String, callerName: String?, callerNumber: String?, preExtractedThumbnail: Bitmap? = null) {
            val svc = instance
            if (svc == null) {
                RemoteLogger.w("VideoRing", "AccessibilityService niet actief — kan overlay niet tonen")
                return
            }
            // addView MOET op main thread
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                svc.showVideoOverlay(videoPath, callerName, callerNumber, preExtractedThumbnail)
            }
        }

        fun dismissOverlay() {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                instance?.removeVideoOverlay()
            }
        }

        val isAvailable: Boolean
            get() = instance != null
    }

    private var overlayView: FrameLayout? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = 0 // We luisteren niet naar events, alleen overlay-functionaliteit
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 0
        }

        RemoteLogger.i("VideoRing", "AccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Niet gebruikt — we gebruiken de service alleen voor overlay-permissie
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        removeVideoOverlay()
        instance = null
        RemoteLogger.d("VideoRing", "AccessibilityService destroyed")
        super.onDestroy()
    }

    fun showVideoOverlay(videoPath: String, callerName: String?, callerNumber: String?, preExtractedThumbnail: Bitmap? = null) {
        if (overlayView != null) {
            RemoteLogger.d("VideoRing", "Overlay al actief, skip")
            return
        }

        var name = callerName ?: "Onbekend"
        if (name == "Onbekend" && !callerNumber.isNullOrBlank()) {
            name = resolveContactName(callerNumber) ?: "Onbekend"
        }

        val displayMetrics = resources.displayMetrics
        val overlayHeight = (displayMetrics.heightPixels * 0.55).toInt()

        // Gebruik pre-extracted thumbnail (van IO thread) of probeer lokaal
        val thumbnail = preExtractedThumbnail ?: extractThumbnail(videoPath)

        RemoteLogger.d("VideoRing", "showVideoOverlay", mapOf(
            "name" to name,
            "number" to (callerNumber ?: "?"),
            "videoPath" to videoPath,
            "thumbnailAvailable" to (thumbnail != null).toString(),
            "preExtracted" to (preExtractedThumbnail != null).toString()
        ))

        overlayView = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)

            if (thumbnail != null) {
                val imageView = ImageView(this@VideoRingAccessibilityService).apply {
                    setImageBitmap(thumbnail)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                addView(imageView, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))
            }

            val callerText = TextView(this@VideoRingAccessibilityService).apply {
                text = buildString {
                    append(name)
                    if (!callerNumber.isNullOrBlank() && callerNumber != name) {
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
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
        }

        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.addView(overlayView, layoutParams)
            RemoteLogger.i("VideoRing", "Accessibility overlay getoond", mapOf(
                "caller" to name,
                "height" to overlayHeight.toString(),
                "thumbnail" to (if (thumbnail != null) "ja" else "nee")
            ))
        } catch (e: Exception) {
            RemoteLogger.e("VideoRing", "Accessibility overlay mislukt", mapOf(
                "error" to (e.message ?: "onbekend"),
                "class" to e.javaClass.simpleName,
                "stacktrace" to e.stackTraceToString().take(500)
            ))
            overlayView = null
        }
    }

    fun removeVideoOverlay() {
        try {
            overlayView?.let {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                wm.removeView(it)
                RemoteLogger.d("VideoRing", "Accessibility overlay verwijderd")
            }
        } catch (_: Exception) {}
        overlayView = null
    }

    private fun extractThumbnail(videoPath: String): Bitmap? = try {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(videoPath)
            r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }
    } catch (_: Exception) { null }

    private fun resolveContactName(number: String): String? {
        fun lookup(num: String): String? = try {
            val uri = android.net.Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                android.net.Uri.encode(num)
            )
            contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (_: Exception) { null }

        lookup(number)?.let { return it }
        if (number.startsWith("+31")) lookup("0" + number.removePrefix("+31"))?.let { return it }
        if (number.startsWith("0") && number.length >= 10) lookup("+31" + number.removePrefix("0"))?.let { return it }
        return null
    }
}
