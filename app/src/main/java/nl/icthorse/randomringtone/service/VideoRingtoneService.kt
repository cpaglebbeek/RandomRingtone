package nl.icthorse.randomringtone.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.provider.ContactsContract
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import nl.icthorse.randomringtone.R
import nl.icthorse.randomringtone.data.RemoteLogger
import java.io.File

/**
 * Foreground Service die een video-ringtone toont als overlay bij inkomend gesprek.
 *
 * - Always on top (SYSTEM_ALERT_WINDOW)
 * - Beeldvullend, muted (geen audio conflict)
 * - Beller-info als tekst-overlay
 * - Stopt bij opnemen/afwijzen
 */
class VideoRingtoneService : Service() {

    companion object {
        const val EXTRA_VIDEO_PATH = "video_path"
        const val EXTRA_CALLER_NAME = "caller_name"
        const val EXTRA_CALLER_NUMBER = "caller_number"
        private const val CHANNEL_ID = "videoring_channel"
        private const val NOTIFICATION_ID = 42

        fun start(context: Context, videoPath: String, callerName: String?, callerNumber: String?) {
            val intent = Intent(context, VideoRingtoneService::class.java).apply {
                putExtra(EXTRA_VIDEO_PATH, videoPath)
                putExtra(EXTRA_CALLER_NAME, callerName ?: "Onbekend")
                putExtra(EXTRA_CALLER_NUMBER, callerNumber ?: "")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VideoRingtoneService::class.java))
        }
    }

    private var windowManager: WindowManager? = null
    private var overlayView: FrameLayout? = null
    private var mediaPlayer: MediaPlayer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val videoPath = intent?.getStringExtra(EXTRA_VIDEO_PATH) ?: run {
            stopSelf(); return START_NOT_STICKY
        }
        val callerName = intent.getStringExtra(EXTRA_CALLER_NAME) ?: "Onbekend"
        val callerNumber = intent.getStringExtra(EXTRA_CALLER_NUMBER) ?: ""

        val notification = buildNotification(callerName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        showOverlay(videoPath, callerName, callerNumber)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removeOverlay()
        super.onDestroy()
    }

    private fun showOverlay(videoPath: String, callerName: String, callerNumber: String) {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            RemoteLogger.w("VideoRing", "Geen overlay permissie")
            stopSelf()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Overlay layout: video + tekst
        overlayView = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)

            // Video surface
            val surfaceView = SurfaceView(this@VideoRingtoneService)
            addView(surfaceView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))

            surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    startVideoPlayback(holder, videoPath)
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    mediaPlayer?.release()
                    mediaPlayer = null
                }
            })

            // Caller info overlay
            val callerText = TextView(this@VideoRingtoneService).apply {
                text = buildString {
                    append(callerName)
                    if (callerNumber.isNotBlank() && callerNumber != callerName) {
                        append("\n$callerNumber")
                    }
                }
                setTextColor(Color.WHITE)
                textSize = 28f
                setShadowLayer(8f, 2f, 2f, Color.BLACK)
                gravity = Gravity.CENTER
                setPadding(32, 32, 32, 32)
            }
            val textParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; bottomMargin = 120 }
            addView(callerText, textParams)
        }

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        try {
            windowManager?.addView(overlayView, layoutParams)
            RemoteLogger.i("VideoRing", "Overlay getoond", mapOf(
                "caller" to callerName, "video" to File(videoPath).name
            ))
        } catch (e: Exception) {
            RemoteLogger.e("VideoRing", "Overlay tonen mislukt", mapOf("error" to (e.message ?: "")))
            stopSelf()
        }
    }

    private fun startVideoPlayback(holder: SurfaceHolder, videoPath: String) {
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(videoPath)
                setSurface(holder.surface)
                setVolume(0f, 0f) // Muted — geen audio conflict
                isLooping = true
                setOnPreparedListener { mp ->
                    // Schaal video beeldvullend
                    mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                    mp.start()
                    RemoteLogger.d("VideoRing", "Video gestart", mapOf("path" to File(videoPath).name))
                }
                setOnErrorListener { _, what, extra ->
                    RemoteLogger.e("VideoRing", "MediaPlayer error", mapOf(
                        "what" to what.toString(), "extra" to extra.toString()
                    ))
                    false
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            RemoteLogger.e("VideoRing", "Video starten mislukt", mapOf("error" to (e.message ?: "")))
        }
    }

    private fun removeOverlay() {
        try {
            mediaPlayer?.apply { if (isPlaying) stop(); release() }
            mediaPlayer = null
            overlayView?.let { windowManager?.removeView(it) }
            overlayView = null
            RemoteLogger.d("VideoRing", "Overlay verwijderd")
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Video Ringtone",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Toont video bij inkomend gesprek" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(callerName: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Video Ringtone")
            .setContentText("Inkomend gesprek: $callerName")
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
    }
}
