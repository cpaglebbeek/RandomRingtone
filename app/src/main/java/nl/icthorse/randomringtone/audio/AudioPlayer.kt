package nl.icthorse.randomringtone.audio

import android.media.MediaPlayer
import kotlinx.coroutines.*
import java.io.File

/**
 * Speelt een selectie van een audiobestand af voor preview.
 */
class AudioPlayer {

    private var mediaPlayer: MediaPlayer? = null
    private var stopJob: Job? = null

    var isPlaying: Boolean = false
        private set

    var currentPositionMs: Long = 0L
        private set

    /**
     * Speel een audiobestand af vanaf startMs tot endMs.
     */
    fun playSelection(file: File, startMs: Long, endMs: Long, onComplete: () -> Unit = {}) {
        stop()

        mediaPlayer = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            prepare()
            seekTo(startMs.toInt())
            start()
        }
        isPlaying = true

        // Stop na de selectie-duur
        stopJob = CoroutineScope(Dispatchers.Main).launch {
            val duration = endMs - startMs
            delay(duration)
            stop()
            onComplete()
        }
    }

    /**
     * Stop het afspelen.
     */
    fun stop() {
        stopJob?.cancel()
        stopJob = null
        mediaPlayer?.let {
            if (it.isPlaying) it.stop()
            it.release()
        }
        mediaPlayer = null
        isPlaying = false
        currentPositionMs = 0
    }

    /**
     * Haal de huidige afspeelpositie op.
     */
    fun getCurrentPosition(): Long {
        return mediaPlayer?.currentPosition?.toLong() ?: 0L
    }

    fun release() {
        stop()
    }
}
