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
     */
    suspend fun trim(input: File, output: File, startMs: Long, endMs: Long): File =
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
                trimMp3Direct(extractor, output, startMs, endMs)
            } else {
                trimWithMuxer(extractor, format, output, startMs, endMs)
            }

            extractor.release()
            output
        }

    /**
     * Trim met fade-in en/of fade-out.
     * Decodeert naar PCM, past fade envelope toe, en encodeert als AAC in M4A container.
     *
     * @param fadeInMs  Fade-in duur in ms (0 = geen fade-in)
     * @param fadeOutMs Fade-out duur in ms (0 = geen fade-out)
     */
    suspend fun trimWithFade(
        input: File,
        output: File,
        startMs: Long,
        endMs: Long,
        fadeInMs: Long = 0,
        fadeOutMs: Long = 0
    ): File = withContext(Dispatchers.IO) {
        // 1. Decode selectie naar PCM
        val (samples, sampleRate, channels) = decodeToPcm(input, startMs, endMs)

        // 2. Fade envelope toepassen
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

        // 3. Encode naar AAC en schrijf M4A
        encodePcmToM4a(output, samples, sampleRate, channels)

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
                    val bytes = ByteArray(sampleSize)
                    buffer.position(0)
                    buffer.get(bytes, 0, sampleSize)
                    fos.write(bytes)
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

    // --- PCM decode (voor fade) ---

    private data class PcmResult(
        val samples: ShortArray,
        val sampleRate: Int,
        val channels: Int
    )

    private fun decodeToPcm(input: File, startMs: Long, endMs: Long): PcmResult {
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
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val allSamples = mutableListOf<Short>()
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

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

    private fun encodePcmToM4a(output: File, samples: ShortArray, sampleRate: Int, channels: Int) {
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
                        val capacity = inputBuffer.capacity() / 2  // bytes to shorts
                        val samplesToWrite = minOf(remaining, capacity)
                        val shortBuffer = inputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        shortBuffer.put(samples, inputOffset, samplesToWrite)

                        val presentationTimeUs = inputOffset.toLong() / channels * 1_000_000L / sampleRate
                        encoder.queueInputBuffer(
                            inputBufferIndex, 0, samplesToWrite * 2,
                            presentationTimeUs, 0
                        )
                        inputOffset += samplesToWrite
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
