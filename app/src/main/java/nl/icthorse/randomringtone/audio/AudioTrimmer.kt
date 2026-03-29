package nl.icthorse.randomringtone.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * Trimt een audiobestand op frame-niveau met MediaExtractor + MediaMuxer.
 * Geen re-encoding — lossless copy van de geselecteerde frames.
 */
object AudioTrimmer {

    /**
     * Trim een audiobestand naar het opgegeven tijdsbereik.
     * @param input Bronbestand (MP3)
     * @param output Doelbestand (MP3)
     * @param startMs Startpositie in milliseconden
     * @param endMs Eindpositie in milliseconden
     * @return Het getrimde bestand
     */
    suspend fun trim(input: File, output: File, startMs: Long, endMs: Long): File =
        withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            extractor.setDataSource(input.absolutePath)

            // Zoek audio track
            var audioTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = trackFormat
                    break
                }
            }

            if (audioTrackIndex == -1 || format == null) {
                extractor.release()
                throw IllegalArgumentException("Geen audio track gevonden in ${input.name}")
            }

            extractor.selectTrack(audioTrackIndex)

            // Muxer opzetten
            val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrackIndex = muxer.addTrack(format)
            muxer.start()

            // Seek naar startpositie
            val startUs = startMs * 1000L
            val endUs = endMs * 1000L
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            // Kopieer frames binnen het bereik
            val buffer = ByteBuffer.allocate(1024 * 1024) // 1 MB buffer
            val bufferInfo = MediaCodec.BufferInfo()

            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                val sampleTime = extractor.sampleTime
                if (sampleTime > endUs) break

                if (sampleTime >= startUs) {
                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    bufferInfo.presentationTimeUs = sampleTime - startUs // Normaliseer naar 0
                    bufferInfo.flags = extractor.sampleFlags
                    muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                }

                extractor.advance()
            }

            muxer.stop()
            muxer.release()
            extractor.release()

            output
        }

    /**
     * Trim met standaard ringtone-duur (20 seconden) vanaf een startpunt.
     */
    suspend fun trimForRingtone(
        input: File,
        output: File,
        startMs: Long,
        durationMs: Long = 20_000
    ): File {
        return trim(input, output, startMs, startMs + durationMs)
    }
}
