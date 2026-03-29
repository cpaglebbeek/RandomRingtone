package nl.icthorse.randomringtone.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Trimt een audiobestand op frame-niveau met MediaExtractor.
 * Geen re-encoding — lossless copy van de geselecteerde frames.
 *
 * Ondersteunt MP3 (direct frame copy) en AAC/M4A (via MediaMuxer).
 */
object AudioTrimmer {

    /**
     * Trim een audiobestand naar het opgegeven tijdsbereik.
     * @param input Bronbestand (MP3/M4A)
     * @param output Doelbestand
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

            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            extractor.selectTrack(audioTrackIndex)

            if (mime == "audio/mpeg") {
                // MP3: direct frame copy (MediaMuxer ondersteunt geen MP3)
                trimMp3Direct(extractor, output, startMs, endMs)
            } else {
                // AAC/andere: MediaMuxer approach
                trimWithMuxer(extractor, format, output, startMs, endMs)
            }

            extractor.release()
            output
        }

    /**
     * MP3 trim: lees frames via MediaExtractor, schrijf direct naar bestand.
     * MP3 frames zijn zelfstandig — geen container nodig.
     */
    private fun trimMp3Direct(
        extractor: MediaExtractor,
        output: File,
        startMs: Long,
        endMs: Long
    ) {
        val startUs = startMs * 1000L
        val endUs = endMs * 1000L
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

        val buffer = ByteBuffer.allocate(1024 * 1024)

        FileOutputStream(output).use { fos ->
            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                val sampleTime = extractor.sampleTime
                if (sampleTime > endUs) break

                if (sampleTime >= startUs) {
                    // Schrijf raw MP3 frame data direct
                    val bytes = ByteArray(sampleSize)
                    buffer.position(0)
                    buffer.get(bytes, 0, sampleSize)
                    fos.write(bytes)
                }

                extractor.advance()
            }
        }
    }

    /**
     * AAC/M4A trim: gebruik MediaMuxer voor container-gebaseerde formats.
     */
    private fun trimWithMuxer(
        extractor: MediaExtractor,
        format: MediaFormat,
        output: File,
        startMs: Long,
        endMs: Long
    ) {
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val muxerTrackIndex = muxer.addTrack(format)
        muxer.start()

        val startUs = startMs * 1000L
        val endUs = endMs * 1000L
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

        val buffer = ByteBuffer.allocate(1024 * 1024)
        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break

            val sampleTime = extractor.sampleTime
            if (sampleTime > endUs) break

            if (sampleTime >= startUs) {
                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = sampleTime - startUs
                bufferInfo.flags = extractor.sampleFlags
                muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
            }

            extractor.advance()
        }

        muxer.stop()
        muxer.release()
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
