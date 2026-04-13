package nl.icthorse.randomringtone.ui

import android.app.KeyguardManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.graphics.Color
import android.view.Gravity
import androidx.activity.ComponentActivity
import nl.icthorse.randomringtone.data.RemoteLogger
import java.io.File

/**
 * Full-screen Activity die een video-ringtone toont bij inkomend gesprek.
 * Verschijnt boven het lockscreen via full-screen intent notification.
 * Wordt automatisch gesloten bij ophangen/opnemen (via finish() vanuit CallStateReceiver).
 */
class VideoRingActivity : ComponentActivity() {

    companion object {
        const val EXTRA_VIDEO_PATH = "video_path"
        const val EXTRA_CALLER_NAME = "caller_name"
        const val EXTRA_CALLER_NUMBER = "caller_number"

        // Referentie naar actieve instance voor dismiss vanuit CallStateReceiver
        @Volatile
        var activeInstance: VideoRingActivity? = null
            private set
    }

    private var mediaPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeInstance = this

        // Toon boven lockscreen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
            km.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        val videoPath = intent?.getStringExtra(EXTRA_VIDEO_PATH) ?: run {
            finish(); return
        }
        val callerName = intent?.getStringExtra(EXTRA_CALLER_NAME) ?: "Onbekend"
        val callerNumber = intent?.getStringExtra(EXTRA_CALLER_NUMBER) ?: ""

        // Layout: video + caller info
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        val surfaceView = SurfaceView(this)
        root.addView(surfaceView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                startVideo(holder, videoPath)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                releasePlayer()
            }
        })

        // Caller info overlay
        val callerText = TextView(this).apply {
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
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = 200
        }
        root.addView(callerText, textParams)

        setContentView(root)

        RemoteLogger.i("VideoRing", "VideoRingActivity gestart", mapOf(
            "caller" to callerName,
            "video" to File(videoPath).name
        ))
    }

    private fun startVideo(holder: SurfaceHolder, videoPath: String) {
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(videoPath)
                setSurface(holder.surface)
                setVolume(0f, 0f) // Muted
                isLooping = true
                setOnPreparedListener { mp ->
                    mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                    mp.start()
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

    private fun releasePlayer() {
        try {
            mediaPlayer?.apply { if (isPlaying) stop(); release() }
            mediaPlayer = null
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        activeInstance = null
        releasePlayer()
        RemoteLogger.d("VideoRing", "VideoRingActivity gestopt")
        super.onDestroy()
    }
}
