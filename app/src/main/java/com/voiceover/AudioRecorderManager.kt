package com.voiceover

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

class AudioRecorderManager(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    var isPaused = false
        private set

    val recordingFile: File? get() = outputFile

    fun startRecording(): File {
        val file = File(context.cacheDir, "voice_recording_${System.currentTimeMillis()}.m4a")
        outputFile = file

        isPaused = false
        recorder = createRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128000)
            setAudioSamplingRate(44100)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }

        return file
    }

    fun pauseRecording() {
        try {
            recorder?.pause()
            isPaused = true
        } catch (_: Exception) {}
    }

    fun resumeRecording() {
        try {
            recorder?.resume()
            isPaused = false
        } catch (_: Exception) {}
    }

    fun stopRecording() {
        try {
            recorder?.apply {
                stop()
                release()
            }
        } catch (_: Exception) {
            // May throw if stopped too quickly
        }
        recorder = null
        isPaused = false
    }

    fun release() {
        stopRecording()
        outputFile?.delete()
        outputFile = null
    }

    private fun createRecorder(): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
    }
}
