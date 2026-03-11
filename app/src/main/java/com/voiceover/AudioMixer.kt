package com.voiceover

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

class AudioMixer(private val context: Context) {

    companion object {
        private const val TAG = "AudioMixer"
    }

    var lastMergeLog: String = ""
        private set

    fun mergeAudioVideo(videoUri: Uri, audioFile: File, outputFile: File): Boolean {
        val log = StringBuilder()
        var muxer: MediaMuxer? = null
        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var videoFd: android.os.ParcelFileDescriptor? = null

        try {
            log.appendLine("audioFile exists=${audioFile.exists()} size=${audioFile.length()}")
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Preserve original video rotation
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, videoUri)
                val rotation = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
                )?.toIntOrNull() ?: 0
                log.appendLine("rotation=$rotation")
                if (rotation != 0) {
                    muxer.setOrientationHint(rotation)
                }
            } finally {
                retriever.release()
            }

            // Extract video track
            videoFd = context.contentResolver.openFileDescriptor(videoUri, "r")
            if (videoFd == null) { log.appendLine("FAIL: video fd null"); lastMergeLog = log.toString(); return false }
            videoExtractor = MediaExtractor().apply { setDataSource(videoFd.fileDescriptor) }

            val videoTrackIndex = findTrack(videoExtractor, "video/")
            if (videoTrackIndex < 0) { log.appendLine("FAIL: no video track"); lastMergeLog = log.toString(); return false }
            videoExtractor.selectTrack(videoTrackIndex)
            val videoFormat = videoExtractor.getTrackFormat(videoTrackIndex)
            log.appendLine("videoFmt: ${videoFormat.getString(MediaFormat.KEY_MIME)}")
            val muxerVideoTrack = muxer.addTrack(videoFormat)

            // Extract audio track from pre-mixed audio file
            audioExtractor = MediaExtractor().apply { setDataSource(audioFile.absolutePath) }
            val audioTrackIndex = findTrack(audioExtractor, "audio/")
            if (audioTrackIndex < 0) { log.appendLine("FAIL: no audio track in merged file"); lastMergeLog = log.toString(); return false }
            audioExtractor.selectTrack(audioTrackIndex)
            val audioFormat = audioExtractor.getTrackFormat(audioTrackIndex)
            log.appendLine("audioFmt: ${audioFormat.getString(MediaFormat.KEY_MIME)}")
            val muxerAudioTrack = muxer.addTrack(audioFormat)

            muxer.start()
            log.appendLine("Writing video samples...")
            writeSamples(videoExtractor, muxer, muxerVideoTrack)
            log.appendLine("Writing audio samples...")
            writeSamples(audioExtractor, muxer, muxerAudioTrack)
            muxer.stop()

            log.appendLine("OK output size=${outputFile.length()}")
            lastMergeLog = log.toString()
            return true
        } catch (e: Exception) {
            log.appendLine("EXCEPTION: ${e.message}")
            log.appendLine(e.stackTraceToString().take(500))
            lastMergeLog = log.toString()
            return false
        } finally {
            try { muxer?.release() } catch (_: Exception) {}
            try { videoExtractor?.release() } catch (_: Exception) {}
            try { audioExtractor?.release() } catch (_: Exception) {}
            try { videoFd?.close() } catch (_: Exception) {}
        }
    }

    private fun findTrack(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) return i
        }
        return -1
    }

    private fun writeSamples(extractor: MediaExtractor, muxer: MediaMuxer, trackIndex: Int) {
        val MAX_BUFFER_SIZE = 8 * 1024 * 1024 // 8MB
        var buffer = ByteBuffer.allocate(1024 * 1024) // 1MB initial
        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            try {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = extractor.sampleTime
                bufferInfo.flags = extractor.sampleFlags

                muxer.writeSampleData(trackIndex, buffer, bufferInfo)
                extractor.advance()
            } catch (e: IllegalArgumentException) {
                // Buffer too small for this sample, double it and retry
                val newSize = buffer.capacity() * 2
                if (newSize > MAX_BUFFER_SIZE) {
                    Log.e(TAG, "Buffer size exceeded max limit of $MAX_BUFFER_SIZE bytes, aborting write")
                    break
                }
                buffer = ByteBuffer.allocate(newSize)
            }
        }
    }
}
