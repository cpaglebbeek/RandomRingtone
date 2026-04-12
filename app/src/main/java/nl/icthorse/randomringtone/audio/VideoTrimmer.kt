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
 * Trimt een MP4 videobestand op frame-niveau met MediaMuxer.
 * Kopieert alleen de video track (geen audio) — lossless.
 */
object VideoTrimmer {

    /**
     * Trim een MP4 video naar het opgegeven tijdsbereik (alleen video, geen audio).
     * @param onProgress Callback met voortgang 0.0-1.0
     */
    suspend fun trim(
        input: File,
        output: File,
        startMs: Long,
        endMs: Long,
        onProgress: ((Float) -> Unit)? = null
    ): File = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        extractor.setDataSource(input.absolutePath)

        // Zoek video track
        var videoTrackIndex = -1
        var videoFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                videoTrackIndex = i
                videoFormat = format
                break
            }
        }

        if (videoTrackIndex == -1 || videoFormat == null) {
            extractor.release()
            throw IllegalArgumentException("Geen video track gevonden in ${input.name}")
        }

        extractor.selectTrack(videoTrackIndex)

        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val muxerTrackIndex = muxer.addTrack(videoFormat)
        muxer.start()

        val startUs = startMs * 1000L
        val endUs = endMs * 1000L
        val totalUs = endUs - startUs
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

        val buffer = ByteBuffer.allocate(2 * 1024 * 1024) // 2MB buffer voor video frames
        val bufferInfo = MediaCodec.BufferInfo()
        var lastProgress = 0f

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

                if (onProgress != null && totalUs > 0) {
                    val progress = ((sampleTime - startUs).toFloat() / totalUs).coerceIn(0f, 1f)
                    if (progress - lastProgress >= 0.02f) {
                        lastProgress = progress
                        onProgress.invoke(progress)
                    }
                }
            }

            extractor.advance()
        }

        muxer.stop()
        muxer.release()
        extractor.release()
        onProgress?.invoke(1f)
        output
    }

    /**
     * Haal de duur op van een video bestand in milliseconden.
     */
    fun getDurationMs(file: File): Long {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (_: Exception) { 0L }
        finally { try { retriever.release() } catch (_: Exception) {} }
    }

    /**
     * Genereer een thumbnail op een bepaald tijdstip.
     */
    fun extractThumbnail(file: File, timeUs: Long = 0): android.graphics.Bitmap? {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.getFrameAtTime(timeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (_: Exception) { null }
        finally { try { retriever.release() } catch (_: Exception) {} }
    }
}
