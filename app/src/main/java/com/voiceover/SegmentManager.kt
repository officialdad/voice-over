package com.voiceover

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SegmentManager {

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
                if (trimmedDuration > 100) { // keep if > 100ms
                    toAdd.add(existing.copy(durationMs = trimmedDuration))
                }
            }

            // New segment overlaps the start of existing — trim existing
            if (newSegment.endPositionMs > existing.startPositionMs &&
                newSegment.endPositionMs < existing.endPositionMs) {
                toRemove.add(i)
                val trimStart = newSegment.endPositionMs - existing.startPositionMs
                val trimmedDuration = existing.durationMs - trimStart
                if (trimmedDuration > 100) {
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

    fun mergeToFile(videoDurationMs: Long, outputFile: File): Boolean {
        if (_segments.isEmpty()) return false

        try {
            val sampleRate = 44100
            val channels = 1
            val totalSamples = (videoDurationMs * sampleRate / 1000).toInt()
            val pcmBuffer = ShortArray(totalSamples)

            // Decode each segment and place at correct position
            for (segment in _segments) {
                val segmentPcm = decodeTopcm(segment.audioFile, sampleRate)
                    ?: continue
                val offset = (segment.startPositionMs * sampleRate / 1000).toInt()
                val copyLength = minOf(segmentPcm.size, totalSamples - offset)
                if (copyLength > 0) {
                    segmentPcm.copyInto(pcmBuffer, destinationOffset = offset, endIndex = copyLength)
                }
            }

            // Encode PCM to AAC file
            return encodeToAac(pcmBuffer, sampleRate, channels, outputFile)
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private fun decodeTopcm(audioFile: File, targetSampleRate: Int): ShortArray? {
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
                        val inputBuffer = codec.getInputBuffer(inputIndex) ?: continue
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

                // Read output
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                if (outputIndex >= 0) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                    val outputBuffer = codec.getOutputBuffer(outputIndex) ?: continue
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

                    // Resample if needed (simple nearest-neighbor)
                    val resampled = if (sampleRate != targetSampleRate) {
                        val ratio = sampleRate.toDouble() / targetSampleRate
                        ShortArray((monoSamples.size / ratio).toInt()) { i ->
                            monoSamples[minOf((i * ratio).toInt(), monoSamples.size - 1)]
                        }
                    } else {
                        monoSamples
                    }

                    pcmChunks.add(resampled)
                    codec.releaseOutputBuffer(outputIndex, false)
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
            e.printStackTrace()
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
            setInteger(MediaFormat.KEY_BIT_RATE, 128000)
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
        var presentationTimeUs = 0L

        while (!outputDone) {
            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(10000)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex) ?: continue
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
                    val outputBuffer = codec.getOutputBuffer(outputIndex) ?: continue
                    muxer.writeSampleData(muxerTrack, outputBuffer, bufferInfo)
                }
                codec.releaseOutputBuffer(outputIndex, false)
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
}
