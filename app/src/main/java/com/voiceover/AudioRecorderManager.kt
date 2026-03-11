package com.voiceover

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

class AudioRecorderManager(private val context: Context) {

    companion object {
        private const val TAG = "AudioRecorderManager"
    }

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    var isPaused = false
        private set

    val recordingFile: File? get() = outputFile

    fun startRecording(): File {
        // Release any existing recorder first
        if (recorder != null) {
            stopRecording()
        }

        val file = File(context.cacheDir, "voice_recording_${System.currentTimeMillis()}.m4a")
        outputFile = file
        isPaused = false

        val newRecorder = createRecorder()
        try {
            newRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(file.absolutePath)
                prepare()
            }
            recorder = newRecorder
            newRecorder.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            try { newRecorder.release() } catch (_: Exception) {}
            recorder = null
            throw e
        }

        return file
    }

    fun pauseRecording() {
        try {
            recorder?.pause()
            isPaused = true
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing recording", e)
        }
    }

    fun resumeRecording() {
        try {
            recorder?.resume()
            isPaused = false
        } catch (e: Exception) {
            Log.e(TAG, "Error resuming recording", e)
        }
    }

    fun stopRecording() {
        try {
            recorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
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
