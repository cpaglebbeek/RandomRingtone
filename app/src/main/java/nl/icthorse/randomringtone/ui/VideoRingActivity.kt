package nl.icthorse.randomringtone.ui

import android.app.KeyguardManager
import android.provider.ContactsContract
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.graphics.Color
import android.graphics.Typeface
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
        var callerName = intent?.getStringExtra(EXTRA_CALLER_NAME) ?: "Onbekend"
        val callerNumber = intent?.getStringExtra(EXTRA_CALLER_NUMBER) ?: ""

        // Fallback contact lookup als naam nog "Onbekend" is
        if (callerName == "Onbekend" && callerNumber.isNotBlank()) {
            val resolved = resolveContact(callerNumber)
            if (resolved != null) callerName = resolved
        }

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

        // "Tik om op te nemen" hint bovenaan
        val hintText = TextView(this).apply {
            text = "Tik om te beantwoorden"
            setTextColor(Color.argb(180, 255, 255, 255))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(6f, 1f, 1f, Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(32, 48, 32, 32)
        }
        val hintParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }
        root.addView(hintText, hintParams)

        setContentView(root)

        RemoteLogger.i("VideoRing", "VideoRingActivity gestart", mapOf(
            "caller" to callerName,
            "video" to File(videoPath).name
        ))
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event?.action == MotionEvent.ACTION_UP) {
            RemoteLogger.i("VideoRing", "Tap-to-dismiss: Activity gesloten door gebruiker")
            releasePlayer()
            finish()
            return true
        }
        return super.onTouchEvent(event)
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

    private fun resolveContact(number: String): String? {
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
