package nl.icthorse.randomringtone.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/**
 * Decodeert een MP3 naar amplitude-samples voor waveform visualisatie.
 */
object AudioDecoder {

    data class WaveformData(
        val amplitudes: List<Float>,   // 0.0 - 1.0 genormaliseerd
        val durationMs: Long,
        val sampleRate: Int
    )

    /**
     * Extraheer amplitude-data uit een audiobestand.
     * @param file MP3 bestand
     * @param targetPoints Aantal datapunten voor de waveform (breedte in pixels)
     */
    suspend fun extractWaveform(file: File, targetPoints: Int = 500): WaveformData =
        withContext(Dispatchers.Default) {
            val extractor = MediaExtractor()
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
                extractor.release()
                return@withContext WaveformData(emptyList(), 0, 44100)
            }

            extractor.selectTrack(audioTrackIndex)

            val durationUs = format.getLong(MediaFormat.KEY_DURATION)
            val durationMs = durationUs / 1000
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/mpeg"

            // Decoder opzetten
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val allAmplitudes = mutableListOf<Float>()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var maxAmplitude = 1f

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

                        // Bereken RMS amplitude per blok
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

            codec.stop()
            codec.release()
            extractor.release()

            // Downsample naar targetPoints
            val downsampled = downsample(allAmplitudes, targetPoints, maxAmplitude)

            WaveformData(
                amplitudes = downsampled,
                durationMs = durationMs,
                sampleRate = sampleRate
            )
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
        return (0 until targetPoints).map { i ->
            val start = i * blockSize
            val end = minOf(start + blockSize, samples.size)
            val blockMax = samples.subList(start, end).maxOrNull() ?: 0f
            blockMax / maxAmplitude
        }
    }
}
