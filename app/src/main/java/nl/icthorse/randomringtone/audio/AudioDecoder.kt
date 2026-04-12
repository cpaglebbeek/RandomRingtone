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
 * Gebruikt on-the-fly downsampling om geheugengebruik begrensd te houden.
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
     * Geheugengebruik is O(targetPoints), niet O(totalSamples).
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

            // Schat totaal aantal mono-samples voor on-the-fly downsampling
            val estimatedTotalSamples = if (durationUs > 0) {
                (durationUs / 1_000_000.0 * sampleRate).toLong()
            } else {
                // Schat op basis van bestandsgrootte (128kbps MP3 ≈ 16KB/s)
                (file.length() / 16_000.0 * sampleRate).toLong()
            }
            val samplesPerBucket = max(1L, estimatedTotalSamples / targetPoints)

            RemoteLogger.d(TAG, "Waveform laden", mapOf(
                "file" to file.name,
                "size" to "${file.length() / 1024}KB",
                "duration" to "${durationMs}ms",
                "sampleRate" to sampleRate.toString(),
                "channels" to channels.toString(),
                "estimatedSamples" to estimatedTotalSamples.toString(),
                "samplesPerBucket" to samplesPerBucket.toString()
            ))

            // Decoder opzetten
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            // On-the-fly downsampling: vaste array van targetPoints buckets
            val buckets = FloatArray(targetPoints)
            var bucketIndex = 0
            var bucketMax = 0f
            var samplesInBucket = 0L
            var totalSamplesProcessed = 0L
            var globalMax = 1f

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
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

                // Read output (PCM samples) — downsample on-the-fly
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

                        var channelSum = 0f
                        var channelCount = 0
                        for (i in 0 until sampleCount) {
                            val sample = abs(shortBuffer.get().toFloat())
                            channelSum += sample
                            channelCount++

                            // Mix kanalen naar mono
                            if (channelCount >= channels) {
                                val monoSample = channelSum / channelCount
                                bucketMax = max(bucketMax, monoSample)
                                samplesInBucket++
                                totalSamplesProcessed++
                                channelSum = 0f
                                channelCount = 0

                                // Bucket vol → opslaan en door naar volgende
                                if (samplesInBucket >= samplesPerBucket && bucketIndex < targetPoints) {
                                    buckets[bucketIndex] = bucketMax
                                    globalMax = max(globalMax, bucketMax)
                                    bucketIndex++
                                    bucketMax = 0f
                                    samplesInBucket = 0
                                }
                            }
                        }
                    }

                    codec.releaseOutputBuffer(outputBufferIndex, false)
                }
            }

            // Laatste bucket opslaan
            if (samplesInBucket > 0 && bucketIndex < targetPoints) {
                buckets[bucketIndex] = bucketMax
                globalMax = max(globalMax, bucketMax)
                bucketIndex++
            }

            onProgress?.invoke(1f)

            // Normaliseer naar 0.0-1.0
            val usedBuckets = bucketIndex
            val amplitudes = if (usedBuckets > 0) {
                buckets.take(usedBuckets).map { it / globalMax }
            } else {
                List(targetPoints) { 0f }
            }

            RemoteLogger.d(TAG, "Waveform geladen", mapOf(
                "file" to file.name,
                "buckets" to usedBuckets.toString(),
                "totalSamples" to totalSamplesProcessed.toString()
            ))

            WaveformData(
                amplitudes = amplitudes,
                durationMs = durationMs,
                sampleRate = sampleRate
            )
        } catch (e: Throwable) {
            RemoteLogger.e(TAG, "Waveform crash", mapOf(
                "file" to file.name,
                "error" to (e.message ?: "unknown"),
                "type" to e.javaClass.simpleName
            ))
            WaveformData(emptyList(), 0, 44100, error = e.message ?: "Decode fout")
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { extractor?.release() } catch (_: Throwable) {}
        }
    }
}
