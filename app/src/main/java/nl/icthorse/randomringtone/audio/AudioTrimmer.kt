package nl.icthorse.randomringtone.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Trimt een audiobestand op frame-niveau met MediaExtractor.
 * Geen re-encoding — lossless copy van de geselecteerde frames.
 *
 * Ondersteunt MP3 (direct frame copy) en AAC/M4A (via MediaMuxer).
 * Met fade: decode → PCM bewerking → re-encode als AAC/M4A.
 */
object AudioTrimmer {

    /**
     * Trim een audiobestand naar het opgegeven tijdsbereik (lossless).
     * @param onProgress Callback met voortgang 0.0-1.0
     */
    suspend fun trim(
        input: File,
        output: File,
        startMs: Long,
        endMs: Long,
        onProgress: ((Float) -> Unit)? = null
    ): File =
        withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            extractor.setDataSource(input.absolutePath)

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
                trimMp3Direct(extractor, output, startMs, endMs, onProgress)
            } else {
                trimWithMuxer(extractor, format, output, startMs, endMs, onProgress)
            }

            extractor.release()
            onProgress?.invoke(1f)
            output
        }

    /**
     * Trim met fade-in en/of fade-out.
     * Decodeert naar PCM, past fade envelope toe, en encodeert als AAC in M4A container.
     * @param onProgress Callback met voortgang 0.0-1.0 (decode=0-50%, encode=50-100%)
     */
    suspend fun trimWithFade(
        input: File,
        output: File,
        startMs: Long,
        endMs: Long,
        fadeInMs: Long = 0,
        fadeOutMs: Long = 0,
        onProgress: ((Float) -> Unit)? = null
    ): File = withContext(Dispatchers.IO) {
        // 1. Decode selectie naar PCM (0% - 50%)
        val (samples, sampleRate, channels) = decodeToPcm(input, startMs, endMs) { p ->
            onProgress?.invoke(p * 0.5f)
        }

        // 2. Fade envelope toepassen (instant)
        if (fadeInMs > 0) {
            val fadeInSamples = (fadeInMs * sampleRate / 1000 * channels).toInt()
                .coerceAtMost(samples.size)
            for (i in 0 until fadeInSamples) {
                val factor = i.toFloat() / fadeInSamples
                samples[i] = (samples[i] * factor).toInt().toShort()
            }
        }
        if (fadeOutMs > 0) {
            val fadeOutSamples = (fadeOutMs * sampleRate / 1000 * channels).toInt()
                .coerceAtMost(samples.size)
            for (i in 0 until fadeOutSamples) {
                val idx = samples.size - 1 - i
                val factor = i.toFloat() / fadeOutSamples
                samples[idx] = (samples[idx] * factor).toInt().toShort()
            }
        }
        onProgress?.invoke(0.5f)

        // 3. Encode naar AAC en schrijf M4A (50% - 100%)
        encodePcmToM4a(output, samples, sampleRate, channels) { p ->
            onProgress?.invoke(0.5f + p * 0.5f)
        }
        onProgress?.invoke(1f)

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

    // --- MP3 trim (lossless, direct frame copy) ---

    private fun trimMp3Direct(
        extractor: MediaExtractor,
        output: File,
        startMs: Long,
        endMs: Long,
        onProgress: ((Float) -> Unit)? = null
    ) {
        val startUs = startMs * 1000L
        val endUs = endMs * 1000L
        val totalUs = endUs - startUs
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

        val buffer = ByteBuffer.allocate(1024 * 1024)
        var lastProgress = 0f

        FileOutputStream(output).use { fos ->
            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                val sampleTime = extractor.sampleTime
                if (sampleTime > endUs) break

                if (sampleTime >= startUs) {
                    val bytes = ByteArray(sampleSize)
                    buffer.position(0)
                    buffer.get(bytes, 0, sampleSize)
                    fos.write(bytes)

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
        }
    }

    // --- AAC/M4A trim (lossless, via MediaMuxer) ---

    private fun trimWithMuxer(
        extractor: MediaExtractor,
        format: MediaFormat,
        output: File,
        startMs: Long,
        endMs: Long,
        onProgress: ((Float) -> Unit)? = null
    ) {
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val muxerTrackIndex = muxer.addTrack(format)
        muxer.start()

        val startUs = startMs * 1000L
        val endUs = endMs * 1000L
        val totalUs = endUs - startUs
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

        val buffer = ByteBuffer.allocate(1024 * 1024)
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
    }

    // --- PCM decode (voor fade) ---

    private data class PcmResult(
        val samples: ShortArray,
        val sampleRate: Int,
        val channels: Int
    )

    private fun decodeToPcm(
        input: File,
        startMs: Long,
        endMs: Long,
        onProgress: ((Float) -> Unit)? = null
    ): PcmResult {
        val extractor = MediaExtractor()
        extractor.setDataSource(input.absolutePath)

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
            throw IllegalArgumentException("Geen audio track gevonden")
        }

        extractor.selectTrack(audioTrackIndex)
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/mpeg"

        val startUs = startMs * 1000L
        val endUs = endMs * 1000L
        val totalUs = endUs - startUs
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val allSamples = mutableListOf<Short>()
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var lastProgress = 0f

        while (!outputDone) {
            if (!inputDone) {
                val inputBufferIndex = codec.dequeueInputBuffer(10_000)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputBufferIndex)!!
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0 || extractor.sampleTime > endUs) {
                        codec.queueInputBuffer(
                            inputBufferIndex, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputDone = true
                    } else {
                        if (onProgress != null && totalUs > 0) {
                            val progress = ((extractor.sampleTime - startUs).toFloat() / totalUs).coerceIn(0f, 1f)
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

            val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            if (outputBufferIndex >= 0) {
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    outputDone = true
                }

                val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                if (outputBuffer != null && bufferInfo.size > 0) {
                    outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                    val shortBuffer = outputBuffer.asShortBuffer()
                    while (shortBuffer.hasRemaining()) {
                        allSamples.add(shortBuffer.get())
                    }
                }
                codec.releaseOutputBuffer(outputBufferIndex, false)
            }
        }

        codec.stop()
        codec.release()
        extractor.release()

        return PcmResult(allSamples.toShortArray(), sampleRate, channels)
    }

    // --- AAC encode (voor fade output) ---

    private fun encodePcmToM4a(
        output: File,
        samples: ShortArray,
        sampleRate: Int,
        channels: Int,
        onProgress: ((Float) -> Unit)? = null
    ) {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerTrackIndex = -1
        var muxerStarted = false

        val bufferInfo = MediaCodec.BufferInfo()
        var inputOffset = 0
        var inputDone = false
        var outputDone = false
        val totalSamples = samples.size
        var lastProgress = 0f

        while (!outputDone) {
            // Feed PCM input
            if (!inputDone) {
                val inputBufferIndex = encoder.dequeueInputBuffer(10_000)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = encoder.getInputBuffer(inputBufferIndex)!!
                    inputBuffer.clear()

                    val remaining = samples.size - inputOffset
                    if (remaining <= 0) {
                        encoder.queueInputBuffer(
                            inputBufferIndex, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputDone = true
                    } else {
                        val capacity = inputBuffer.capacity() / 2
                        val samplesToWrite = minOf(remaining, capacity)
                        val shortBuffer = inputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        shortBuffer.put(samples, inputOffset, samplesToWrite)

                        val presentationTimeUs = inputOffset.toLong() / channels * 1_000_000L / sampleRate
                        encoder.queueInputBuffer(
                            inputBufferIndex, 0, samplesToWrite * 2,
                            presentationTimeUs, 0
                        )
                        inputOffset += samplesToWrite

                        if (onProgress != null && totalSamples > 0) {
                            val progress = (inputOffset.toFloat() / totalSamples).coerceIn(0f, 1f)
                            if (progress - lastProgress >= 0.02f) {
                                lastProgress = progress
                                onProgress.invoke(progress)
                            }
                        }
                    }
                }
            }

            // Read encoded AAC output
            val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    muxerTrackIndex = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outputBufferIndex >= 0 -> {
                    val outputBuffer = encoder.getOutputBuffer(outputBufferIndex)!!
                    if (muxerStarted && bufferInfo.size > 0) {
                        muxer.writeSampleData(muxerTrackIndex, outputBuffer, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(outputBufferIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }
            }
        }

        encoder.stop()
        encoder.release()
        if (muxerStarted) {
            muxer.stop()
            muxer.release()
        }
    }
}
