package nl.icthorse.randomringtone.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.icthorse.randomringtone.data.RemoteLogger
import java.io.File
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/**
 * Decodeert een MP3/M4A naar amplitude-samples voor waveform visualisatie.
 */
object AudioDecoder {

    private const val TAG = "AudioDecoder"

    data class WaveformData(
        val amplitudes: List<Float>,   // 0.0 - 1.0 genormaliseerd
        val durationMs: Long,
        val sampleRate: Int,
        val error: String? = null
    )

    /**
     * Extraheer amplitude-data uit een audiobestand.
     * @param file Audio bestand (MP3/M4A)
     * @param targetPoints Aantal datapunten voor de waveform (breedte in pixels)
     * @param onProgress Callback met voortgang 0.0-1.0
     */
    suspend fun extractWaveform(
        file: File,
        targetPoints: Int = 500,
        onProgress: ((Float) -> Unit)? = null
    ): WaveformData = withContext(Dispatchers.Default) {

        if (!file.exists() || !file.canRead()) {
            RemoteLogger.e(TAG, "Bestand niet leesbaar", mapOf("file" to file.name))
            return@withContext WaveformData(emptyList(), 0, 44100, error = "Bestand niet leesbaar")
        }

        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null

        try {
            extractor = MediaExtractor()
            extractor.setDataSource(file.absolutePath)

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
                RemoteLogger.w(TAG, "Geen audio track gevonden", mapOf("file" to file.name))
                return@withContext WaveformData(emptyList(), 0, 44100, error = "Geen audio track")
            }

            extractor.selectTrack(audioTrackIndex)

            val durationUs = try { format.getLong(MediaFormat.KEY_DURATION) } catch (_: Exception) { 0L }
            val durationMs = durationUs / 1000
            val sampleRate = try { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) } catch (_: Exception) { 44100 }
            val channels = try { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } catch (_: Exception) { 2 }
            val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/mpeg"

            RemoteLogger.d(TAG, "Waveform laden", mapOf(
                "file" to file.name,
                "duration" to "${durationMs}ms",
                "sampleRate" to sampleRate.toString(),
                "channels" to channels.toString(),
                "mime" to mime
            ))

            // Decoder opzetten
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val allAmplitudes = mutableListOf<Float>()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var maxAmplitude = 1f
            var lastProgress = 0f

            while (!outputDone) {
                // Feed input
                if (!inputDone) {
                    val inputBufferIndex = codec.dequeueInputBuffer(10_000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferIndex) ?: continue
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputBufferIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            if (onProgress != null && durationUs > 0) {
                                val progress = (extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f)
                                if (progress - lastProgress >= 0.02f) {
                                    lastProgress = progress
                                    onProgress.invoke(progress)
                                }
                            }
                            codec.queueInputBuffer(
                                inputBufferIndex, 0, sampleSize,
                                extractor.sampleTime, 0
                            )
                            extractor.advance()
                        }
                    }
                }

                // Read output (PCM samples)
                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outputBufferIndex >= 0) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }

                    val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                        val shortBuffer = outputBuffer.asShortBuffer()
                        val sampleCount = shortBuffer.remaining()

                        var sum = 0.0
                        var count = 0
                        for (i in 0 until sampleCount) {
                            val sample = abs(shortBuffer.get().toFloat())
                            sum += sample
                            count++
                            if (count >= channels) {
                                val avg = (sum / count).toFloat()
                                allAmplitudes.add(avg)
                                maxAmplitude = max(maxAmplitude, avg)
                                sum = 0.0
                                count = 0
                            }
                        }
                    }

                    codec.releaseOutputBuffer(outputBufferIndex, false)
                }
            }

            onProgress?.invoke(1f)

            RemoteLogger.d(TAG, "Waveform geladen", mapOf(
                "file" to file.name,
                "samples" to allAmplitudes.size.toString(),
                "targetPoints" to targetPoints.toString()
            ))

            // Downsample naar targetPoints
            val downsampled = downsample(allAmplitudes, targetPoints, maxAmplitude)

            WaveformData(
                amplitudes = downsampled,
                durationMs = durationMs,
                sampleRate = sampleRate
            )
        } catch (e: Exception) {
            RemoteLogger.e(TAG, "Waveform crash", mapOf(
                "file" to file.name,
                "error" to (e.message ?: "unknown"),
                "type" to e.javaClass.simpleName
            ))
            WaveformData(emptyList(), 0, 44100, error = e.message ?: "Decode fout")
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }
    }

    /**
     * Reduceer het aantal samples naar targetPoints door per blok het maximum te nemen.
     * Normaliseer naar 0.0 - 1.0.
     */
    private fun downsample(
        samples: List<Float>,
        targetPoints: Int,
        maxAmplitude: Float
    ): List<Float> {
        if (samples.isEmpty()) return List(targetPoints) { 0f }
        if (samples.size <= targetPoints) {
            return samples.map { it / maxAmplitude }
        }

        val blockSize = samples.size / targetPoints
        if (blockSize == 0) return samples.map { it / maxAmplitude }

        return (0 until targetPoints).map { i ->
            val start = i * blockSize
            val end = minOf(start + blockSize, samples.size)
            val blockMax = samples.subList(start, end).maxOrNull() ?: 0f
            blockMax / maxAmplitude
        }
    }
}
