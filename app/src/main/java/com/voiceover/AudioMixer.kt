package com.voiceover

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

class AudioMixer(private val context: Context) {

    fun mergeAudioVideo(videoUri: Uri, audioFile: File, outputFile: File): Boolean {
        var muxer: MediaMuxer? = null
        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null

        try {
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Extract video track
            videoExtractor = MediaExtractor().apply {
                val fd = context.contentResolver.openFileDescriptor(videoUri, "r")
                    ?: return false
                setDataSource(fd.fileDescriptor)
                fd.close()
            }

            val videoTrackIndex = findTrack(videoExtractor, "video/")
            if (videoTrackIndex < 0) return false

            videoExtractor.selectTrack(videoTrackIndex)
            val videoFormat = videoExtractor.getTrackFormat(videoTrackIndex)
            val muxerVideoTrack = muxer.addTrack(videoFormat)

            // Extract audio track from recorded audio
            audioExtractor = MediaExtractor().apply {
                setDataSource(audioFile.absolutePath)
            }

            val audioTrackIndex = findTrack(audioExtractor, "audio/")
            if (audioTrackIndex < 0) return false

            audioExtractor.selectTrack(audioTrackIndex)
            val audioFormat = audioExtractor.getTrackFormat(audioTrackIndex)
            val muxerAudioTrack = muxer.addTrack(audioFormat)

            muxer.start()

            // Write video track
            writeSamples(videoExtractor, muxer, muxerVideoTrack)

            // Write audio track
            writeSamples(audioExtractor, muxer, muxerAudioTrack)

            muxer.stop()
            return true

        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            try { muxer?.release() } catch (_: Exception) {}
            try { videoExtractor?.release() } catch (_: Exception) {}
            try { audioExtractor?.release() } catch (_: Exception) {}
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
        val buffer = ByteBuffer.allocate(1024 * 1024)
        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break

            bufferInfo.offset = 0
            bufferInfo.size = sampleSize
            bufferInfo.presentationTimeUs = extractor.sampleTime
            bufferInfo.flags = extractor.sampleFlags

            muxer.writeSampleData(trackIndex, buffer, bufferInfo)
            extractor.advance()
        }
    }
}
