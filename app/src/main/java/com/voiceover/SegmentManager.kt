package com.voiceover

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SegmentManager {

    companion object {
        private const val TAG = "SegmentManager"
        private const val SAMPLE_RATE = 44100
        private const val CHANNELS = 1
        private const val BIT_RATE = 128000
        private const val MIN_SEGMENT_DURATION_MS = 100L
        private const val MAX_DURATION_MS = 30L * 60 * 1000 // 30 minutes
    }

    private val _segments = mutableListOf<RecordedSegment>()
    val segments: List<RecordedSegment> get() = _segments.toList()
    val segmentCount: Int get() = _segments.size

    fun addSegment(segment: RecordedSegment) {
        handleOverlap(segment)
        _segments.add(segment)
        _segments.sortBy { it.startPositionMs }
    }

    fun removeSegment(index: Int) {
        if (index in _segments.indices) {
            _segments[index].audioFile.delete()
            _segments.removeAt(index)
        }
    }

    fun clearAll() {
        _segments.forEach { it.audioFile.delete() }
        _segments.clear()
    }

    fun getSegmentAt(positionMs: Long): RecordedSegment? {
        return _segments.find { positionMs in it.startPositionMs until it.endPositionMs }
    }

    private fun handleOverlap(newSegment: RecordedSegment) {
        val toRemove = mutableListOf<Int>()
        val toAdd = mutableListOf<RecordedSegment>()

        for (i in _segments.indices) {
            val existing = _segments[i]

            // No overlap
            if (newSegment.endPositionMs <= existing.startPositionMs ||
                newSegment.startPositionMs >= existing.endPositionMs) {
                continue
            }

            // New segment completely covers existing — remove existing
            if (newSegment.startPositionMs <= existing.startPositionMs &&
                newSegment.endPositionMs >= existing.endPositionMs) {
                toRemove.add(i)
                continue
            }

            // New segment overlaps the end of existing — trim existing
            if (newSegment.startPositionMs > existing.startPositionMs &&
                newSegment.startPositionMs < existing.endPositionMs) {
                toRemove.add(i)
                val trimmedDuration = newSegment.startPositionMs - existing.startPositionMs
                if (trimmedDuration > MIN_SEGMENT_DURATION_MS) {
                    toAdd.add(existing.copy(durationMs = trimmedDuration))
                }
            }

            // New segment overlaps the start of existing — trim existing
            if (newSegment.endPositionMs > existing.startPositionMs &&
                newSegment.endPositionMs < existing.endPositionMs) {
                toRemove.add(i)
                val trimStart = newSegment.endPositionMs - existing.startPositionMs
                val trimmedDuration = existing.durationMs - trimStart
                if (trimmedDuration > MIN_SEGMENT_DURATION_MS) {
                    toAdd.add(existing.copy(
                        startPositionMs = newSegment.endPositionMs,
                        durationMs = trimmedDuration
                    ))
                }
            }
        }

        // Remove in reverse order to preserve indices
        toRemove.sortedDescending().forEach { _segments.removeAt(it) }
        _segments.addAll(toAdd)
    }

    /** Collects debug info from the last mergeToFile call */
    var lastMergeLog: String = ""
        private set

    fun mergeToFile(
        videoDurationMs: Long,
        outputFile: File,
        voiceVolume: Float = 1.0f,
        originalVolume: Float = 0f,
        videoUri: Uri? = null,
        context: Context? = null
    ): Boolean {
        val log = StringBuilder()
        if (_segments.isEmpty()) { lastMergeLog = "No segments"; return false }

        try {
            log.appendLine("mergeToFile: segs=${_segments.size}, dur=${videoDurationMs}ms, voiceVol=$voiceVolume, origVol=$originalVolume")
            for ((i, seg) in _segments.withIndex()) {
                log.appendLine("  seg[$i]: start=${seg.startPositionMs}ms, dur=${seg.durationMs}ms, exists=${seg.audioFile.exists()}, size=${seg.audioFile.length()}")
            }

            // Cap to prevent OOM
            val effectiveDurationMs = minOf(videoDurationMs, MAX_DURATION_MS)
            val totalSamples = (effectiveDurationMs * SAMPLE_RATE / 1000).toInt()

            // Check memory availability before allocating
            log.appendLine("totalSamples=$totalSamples, effectiveDur=$effectiveDurationMs")
            val requiredBytes = totalSamples.toLong() * 2
            val runtime = Runtime.getRuntime()
            val availableMemory = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()
            if (requiredBytes > availableMemory / 2) {
                log.appendLine("OOM: need ${requiredBytes}B, avail ${availableMemory}B")
                lastMergeLog = log.toString(); return false
            }

            val pcmBuffer = ShortArray(totalSamples)

            // Decode each segment and place at correct position
            for ((i, segment) in _segments.withIndex()) {
                val segmentPcm = decodeToPcm(segment.audioFile, SAMPLE_RATE)
                if (segmentPcm == null) {
                    log.appendLine("  seg[$i] decode FAILED")
                    continue
                }
                log.appendLine("  seg[$i] decoded ${segmentPcm.size} samples")
                val offset = (segment.startPositionMs * SAMPLE_RATE / 1000).toInt()
                val copyLength = minOf(segmentPcm.size, totalSamples - offset)
                if (copyLength > 0) {
                    segmentPcm.copyInto(pcmBuffer, destinationOffset = offset, endIndex = copyLength)
                }
            }

            // Apply voice volume
            if (voiceVolume != 1.0f) {
                for (i in pcmBuffer.indices) {
                    pcmBuffer[i] = (pcmBuffer[i] * voiceVolume).toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
            }

            // Mix in original video audio if volume > 0
            if (originalVolume > 0f && videoUri != null && context != null) {
                log.appendLine("Decoding original audio...")
                val origPcm = decodeOriginalAudio(context, videoUri)
                log.appendLine("Original audio: ${origPcm?.size ?: "null"} samples")
                if (origPcm != null) {
                    val mixLength = minOf(pcmBuffer.size, origPcm.size)
                    for (i in 0 until mixLength) {
                        val mixed = pcmBuffer[i].toInt() + (origPcm[i] * originalVolume).toInt()
                        pcmBuffer[i] = mixed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    }
                }
            }

            // Encode PCM to AAC file
            log.appendLine("Encoding ${pcmBuffer.size} samples to AAC...")
            val result = encodeToAac(pcmBuffer, SAMPLE_RATE, CHANNELS, outputFile)
            log.appendLine("Encode result=$result, exists=${outputFile.exists()}, size=${outputFile.length()}")
            lastMergeLog = log.toString()
            return result
        } catch (e: Exception) {
            log.appendLine("EXCEPTION: ${e.message}")
            log.appendLine(e.stackTraceToString().take(500))
            lastMergeLog = log.toString()
            return false
        }
    }

    private fun decodeToPcm(audioFile: File, targetSampleRate: Int): ShortArray? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(audioFile.absolutePath)

            var audioTrack = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    break
                }
            }
            if (audioTrack < 0) return null

            extractor.selectTrack(audioTrack)
            val format = extractor.getTrackFormat(audioTrack)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val pcmChunks = mutableListOf<ShortArray>()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                // Feed input
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        if (inputBuffer == null) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                        } else {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, sampleSize,
                                    extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                // Read output
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                if (outputIndex >= 0) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null) {
                        val shortBuffer = outputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val samples = ShortArray(shortBuffer.remaining())
                        shortBuffer.get(samples)

                        // Convert to mono if stereo
                        val monoSamples = if (channelCount > 1) {
                            ShortArray(samples.size / channelCount) { i ->
                                var sum = 0L
                                for (ch in 0 until channelCount) {
                                    sum += samples[i * channelCount + ch]
                                }
                                (sum / channelCount).toShort()
                            }
                        } else {
                            samples
                        }

                        // Resample if needed (linear interpolation)
                        val resampled = if (sampleRate != targetSampleRate) {
                            val ratio = sampleRate.toDouble() / targetSampleRate
                            val outputSize = (monoSamples.size / ratio).toInt()
                            ShortArray(outputSize) { i ->
                                val srcPos = i * ratio
                                val srcIndex = srcPos.toInt()
                                val fraction = srcPos - srcIndex
                                val s0 = monoSamples[minOf(srcIndex, monoSamples.size - 1)]
                                val s1 = monoSamples[minOf(srcIndex + 1, monoSamples.size - 1)]
                                (s0 + (s1 - s0) * fraction).toInt().toShort()
                            }
                        } else {
                            monoSamples
                        }

                        pcmChunks.add(resampled)
                    }
                    codec.releaseOutputBuffer(outputIndex, false) // ALWAYS release
                }
            }

            codec.stop()
            codec.release()
            extractor.release()

            // Concatenate chunks
            val totalSize = pcmChunks.sumOf { it.size }
            val result = ShortArray(totalSize)
            var pos = 0
            for (chunk in pcmChunks) {
                chunk.copyInto(result, pos)
                pos += chunk.size
            }
            return result

        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode audio to PCM", e)
            extractor.release()
            return null
        }
    }

    private fun encodeToAac(pcmData: ShortArray, sampleRate: Int, channels: Int, outputFile: File): Boolean {
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val mime = MediaFormat.MIMETYPE_AUDIO_AAC
        val format = MediaFormat.createAudioFormat(mime, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
        }

        val codec = MediaCodec.createEncoderByType(mime)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val bufferInfo = MediaCodec.BufferInfo()
        var muxerTrack = -1
        var muxerStarted = false

        // Convert shorts to bytes
        val byteBuffer = ByteBuffer.allocate(pcmData.size * 2).order(ByteOrder.nativeOrder())
        byteBuffer.asShortBuffer().put(pcmData)
        val pcmBytes = byteBuffer.array()

        var inputOffset = 0
        var inputDone = false
        var outputDone = false

        while (!outputDone) {
            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(10000)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                    if (inputBuffer == null) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                    } else {
                        val remaining = pcmBytes.size - inputOffset
                        if (remaining <= 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val chunkSize = minOf(remaining, inputBuffer.capacity())
                            inputBuffer.clear()
                            inputBuffer.put(pcmBytes, inputOffset, chunkSize)
                            val pts = (inputOffset.toLong() / 2) * 1_000_000L / sampleRate
                            codec.queueInputBuffer(inputIndex, 0, chunkSize, pts, 0)
                            inputOffset += chunkSize
                        }
                    }
                }
            }

            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!muxerStarted) {
                    muxerTrack = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
            } else if (outputIndex >= 0) {
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    outputDone = true
                }
                if (bufferInfo.size > 0 && muxerStarted) {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null) {
                        muxer.writeSampleData(muxerTrack, outputBuffer, bufferInfo)
                    }
                }
                codec.releaseOutputBuffer(outputIndex, false) // ALWAYS release
            }
        }

        codec.stop()
        codec.release()
        if (muxerStarted) {
            muxer.stop()
        }
        muxer.release()
        return true
    }

    private fun decodeOriginalAudio(context: Context, videoUri: Uri): ShortArray? {
        val fd = context.contentResolver.openFileDescriptor(videoUri, "r") ?: return null
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(fd.fileDescriptor)

            var audioTrack = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    break
                }
            }
            if (audioTrack < 0) return null

            extractor.selectTrack(audioTrack)
            val format = extractor.getTrackFormat(audioTrack)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val pcmChunks = mutableListOf<ShortArray>()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        if (inputBuffer == null) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                        } else {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, sampleSize,
                                    extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                if (outputIndex >= 0) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        val shortBuffer = outputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val samples = ShortArray(shortBuffer.remaining())
                        shortBuffer.get(samples)

                        val monoSamples = if (channelCount > 1) {
                            ShortArray(samples.size / channelCount) { i ->
                                var sum = 0L
                                for (ch in 0 until channelCount) {
                                    sum += samples[i * channelCount + ch]
                                }
                                (sum / channelCount).toShort()
                            }
                        } else {
                            samples
                        }

                        val resampled = if (sampleRate != SAMPLE_RATE) {
                            val ratio = sampleRate.toDouble() / SAMPLE_RATE
                            val outputSize = (monoSamples.size / ratio).toInt()
                            ShortArray(outputSize) { i ->
                                val srcPos = i * ratio
                                val srcIndex = srcPos.toInt()
                                val fraction = srcPos - srcIndex
                                val s0 = monoSamples[minOf(srcIndex, monoSamples.size - 1)]
                                val s1 = monoSamples[minOf(srcIndex + 1, monoSamples.size - 1)]
                                (s0 + (s1 - s0) * fraction).toInt().toShort()
                            }
                        } else {
                            monoSamples
                        }

                        pcmChunks.add(resampled)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            }

            codec.stop()
            codec.release()

            val totalSize = pcmChunks.sumOf { it.size }
            val result = ShortArray(totalSize)
            var pos = 0
            for (chunk in pcmChunks) {
                chunk.copyInto(result, pos)
                pos += chunk.size
            }
            return result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode original audio", e)
            return null
        } finally {
            extractor.release()
            try { fd.close() } catch (_: Exception) {}
        }
    }
}
