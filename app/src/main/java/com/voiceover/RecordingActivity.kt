package com.voiceover

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class RecordingActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "RecordingActivity"
    }

    private enum class State {
        IDLE, RECORDING, RECORDING_PAUSED, PREVIEWING
    }

    private lateinit var videoView: VideoView
    private lateinit var seekBar: SeekBar
    private lateinit var segmentTimeline: SegmentTimelineView
    private lateinit var timeText: TextView
    private lateinit var segmentCountText: TextView
    private lateinit var statusText: TextView
    private lateinit var recordFab: FloatingActionButton
    private lateinit var recordPulse: View
    private lateinit var stopButton: MaterialButton
    private lateinit var reRecordButton: MaterialButton
    private lateinit var saveButton: FloatingActionButton
    private lateinit var playOverlay: ImageView
    private lateinit var loadingSpinner: View
    private lateinit var volumeControls: View
    private lateinit var originalVolumeSlider: com.google.android.material.slider.Slider
    private lateinit var voiceVolumeSlider: com.google.android.material.slider.Slider

    private val segmentManager = SegmentManager()
    private var audioRecorder: AudioRecorderManager? = null
    private var audioPlayer: MediaPlayer? = null
    private var videoMediaPlayer: android.media.MediaPlayer? = null
    private var videoUri: Uri? = null
    private var currentState = State.IDLE
    private var recordingStartPositionMs: Long = 0
    private var lastKnownPositionMs: Long = 0
    private var activeJob: Job? = null
    private var previewTempFile: File? = null
    private var suppressAutoPlay = false
    private var videoPrepared = false

    private val handler = Handler(Looper.getMainLooper())
    private val scope = MainScope()

    private var pulseAnimatorX: ObjectAnimator? = null
    private var pulseAnimatorY: ObjectAnimator? = null

    private var harmonizedRed: Int = Color.RED
    private var secondaryColor: Int = Color.CYAN

    private val updateProgress = object : Runnable {
        override fun run() {
            val current = videoView.currentPosition
            val duration = videoView.duration
            if (duration > 0) {
                seekBar.max = duration
                seekBar.progress = current
                timeText.text = "${formatTime(current)} / ${formatTime(duration)}"
                segmentTimeline.setCurrentPosition(current.toLong())
                lastKnownPositionMs = current.toLong()

                // Show live recording segment growing in real-time
                if (currentState == State.RECORDING && current.toLong() > recordingStartPositionMs) {
                    val liveDuration = current.toLong() - recordingStartPositionMs
                    if (liveDuration > 0) {
                        val liveSegment = RecordedSegment(
                            audioFile = java.io.File(""),
                            startPositionMs = recordingStartPositionMs,
                            durationMs = liveDuration
                        )
                        segmentTimeline.setSegments(segmentManager.segments + liveSegment)
                    }
                }
            }
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Global crash catcher - write to file for display on next launch
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "UNCAUGHT EXCEPTION", throwable)
            try {
                val crashFile = File(cacheDir, "last_crash.txt")
                crashFile.writeText("${throwable.javaClass.simpleName}: ${throwable.message}\n\n${throwable.stackTraceToString().take(2000)}")
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // Show last crash if any
        val crashFile = File(cacheDir, "last_crash.txt")
        if (crashFile.exists()) {
            val crashInfo = crashFile.readText()
            crashFile.delete()
            android.app.AlertDialog.Builder(this)
                .setTitle("Previous Crash Report")
                .setMessage(crashInfo)
                .setPositiveButton("OK", null)
                .show()
        }

        setContentView(R.layout.activity_recording)

        videoView = findViewById(R.id.videoView)
        seekBar = findViewById(R.id.seekBar)
        segmentTimeline = findViewById(R.id.segmentTimeline)
        timeText = findViewById(R.id.timeText)
        segmentCountText = findViewById(R.id.segmentCountText)
        statusText = findViewById(R.id.statusText)
        recordFab = findViewById(R.id.recordFab)
        recordPulse = findViewById(R.id.recordPulse)
        stopButton = findViewById(R.id.stopButton)
        reRecordButton = findViewById(R.id.reRecordButton)
        saveButton = findViewById(R.id.saveButton)
        playOverlay = findViewById(R.id.playOverlay)
        playOverlay.setOnClickListener {
            when (currentState) {
                State.IDLE -> if (segmentManager.segmentCount > 0) startPreview()
                State.RECORDING_PAUSED -> startPreview()
                else -> {}
            }
        }
        loadingSpinner = findViewById(R.id.loadingSpinner)
        volumeControls = findViewById(R.id.volumeControls)
        originalVolumeSlider = findViewById(R.id.originalVolumeSlider)
        voiceVolumeSlider = findViewById(R.id.voiceVolumeSlider)

        harmonizedRed = MaterialColors.harmonize(
            ContextCompat.getColor(this, R.color.record_red),
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, Color.BLACK)
        )
        secondaryColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSecondary, Color.CYAN)

        val uriString = intent.getStringExtra("video_uri") ?: run {
            finish()
            return
        }
        videoUri = Uri.parse(uriString)

        // Clean up old preview temp files
        cacheDir.listFiles()?.filter {
            it.name.startsWith("preview_voice_") || it.name.startsWith("merged_audio_") || it.name.startsWith("voiceover_")
        }?.forEach { it.delete() }

        setupVideoPlayer()
        setupControls()
        transitionTo(State.IDLE)

        if (savedInstanceState != null) {
            val position = savedInstanceState.getInt("videoPosition", 0)
            val uriStr = savedInstanceState.getString("videoUri")
            if (uriStr != null) {
                videoUri = Uri.parse(uriStr)
                videoView.setVideoURI(videoUri)
                videoView.setOnPreparedListener { mp ->
                    videoMediaPlayer = mp
                    mp.isLooping = false
                    val duration = videoView.duration
                    seekBar.max = duration
                    timeText.text = "${formatTime(position)} / ${formatTime(duration)}"
                    segmentTimeline.setVideoDuration(duration.toLong())
                    applyVolumeLevels()
                    videoView.seekTo(position)
                    if (suppressAutoPlay) {
                        suppressAutoPlay = false
                        videoView.pause()
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("videoPosition", videoView.currentPosition)
        outState.putInt("state", currentState.ordinal)
        outState.putString("videoUri", videoUri?.toString())
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)

        activeJob?.cancel()

        // Save current state before re-layout
        val currentPosition = videoView.currentPosition
        val wasPlaying = videoView.isPlaying
        val savedState = currentState
        val origSliderVal = originalVolumeSlider.value
        val voiceSliderVal = voiceVolumeSlider.value

        // Stop any ongoing playback tracking
        handler.removeCallbacks(updateProgress)

        // Re-inflate the correct layout (portrait or landscape)
        setContentView(R.layout.activity_recording)

        // Re-bind all views
        videoView = findViewById(R.id.videoView)
        seekBar = findViewById(R.id.seekBar)
        segmentTimeline = findViewById(R.id.segmentTimeline)
        timeText = findViewById(R.id.timeText)
        segmentCountText = findViewById(R.id.segmentCountText)
        statusText = findViewById(R.id.statusText)
        recordFab = findViewById(R.id.recordFab)
        recordPulse = findViewById(R.id.recordPulse)
        stopButton = findViewById(R.id.stopButton)
        reRecordButton = findViewById(R.id.reRecordButton)
        saveButton = findViewById(R.id.saveButton)
        playOverlay = findViewById(R.id.playOverlay)
        playOverlay.setOnClickListener {
            when (currentState) {
                State.IDLE -> if (segmentManager.segmentCount > 0) startPreview()
                State.RECORDING_PAUSED -> startPreview()
                else -> {}
            }
        }
        loadingSpinner = findViewById(R.id.loadingSpinner)
        volumeControls = findViewById(R.id.volumeControls)
        originalVolumeSlider = findViewById(R.id.originalVolumeSlider)
        voiceVolumeSlider = findViewById(R.id.voiceVolumeSlider)

        // Restore slider values
        originalVolumeSlider.value = origSliderVal
        voiceVolumeSlider.value = voiceSliderVal

        // Re-setup controls (listeners)
        setupControls()

        // Re-setup video
        videoView.setVideoURI(videoUri)
        videoView.setOnPreparedListener { mp ->
            videoMediaPlayer = mp
            mp.isLooping = false
            val duration = videoView.duration
            seekBar.max = duration
            segmentTimeline.setVideoDuration(duration.toLong())
            applyVolumeLevels()
            videoView.seekTo(currentPosition)

            if (suppressAutoPlay) {
                suppressAutoPlay = false
                videoView.pause()
            } else if (wasPlaying && (savedState == State.RECORDING || savedState == State.PREVIEWING)) {
                videoView.start()
                handler.post(updateProgress)
            }
        }
        videoView.setOnCompletionListener {
            when (currentState) {
                State.RECORDING -> finishRecording()
                State.PREVIEWING -> stopPreview()
                else -> {}
            }
        }

        // Restore UI state
        transitionTo(savedState)
        updateTimeline()
    }

    private fun setupVideoPlayer() {
        videoPrepared = false
        videoView.setVideoURI(videoUri)
        videoView.setOnPreparedListener { mp ->
            videoPrepared = true
            videoMediaPlayer = mp
            mp.isLooping = false
            val duration = videoView.duration
            seekBar.max = duration
            timeText.text = "00:00 / ${formatTime(duration)}"
            segmentTimeline.setVideoDuration(duration.toLong())
            applyVolumeLevels()
            if (suppressAutoPlay) {
                suppressAutoPlay = false
                videoView.pause()
                if (lastKnownPositionMs > 0) {
                    videoView.seekTo(lastKnownPositionMs.toInt())
                }
            } else {
                // Show first frame instead of black screen
                videoView.seekTo(1)
            }
        }
        videoView.setOnCompletionListener {
            when (currentState) {
                State.RECORDING -> finishRecording()
                State.PREVIEWING -> stopPreview()
                else -> {}
            }
        }
    }

    private fun setupControls() {
        recordFab.setOnClickListener {
            when (currentState) {
                State.IDLE -> {
                    if (segmentManager.segmentCount > 0) startPreview()
                    else startRecording()
                }
                State.RECORDING -> pauseRecording()
                State.RECORDING_PAUSED -> resumeRecording()
                State.PREVIEWING -> stopPreview()
            }
        }

        stopButton.setOnClickListener {
            when (currentState) {
                State.RECORDING, State.RECORDING_PAUSED -> finishRecording()
                State.PREVIEWING -> stopPreview()
                else -> {}
            }
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && (currentState == State.PREVIEWING || currentState == State.IDLE)) {
                    videoView.seekTo(progress)
                    audioPlayer?.seekTo(progress)
                    timeText.text = "${formatTime(progress)} / ${formatTime(videoView.duration)}"
                    segmentTimeline.setCurrentPosition(progress.toLong())
                    lastKnownPositionMs = progress.toLong()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        reRecordButton.setOnClickListener {
            clearAllSegments()
        }

        saveButton.setOnClickListener {
            saveVideo()
        }

        val volumeListener = com.google.android.material.slider.Slider.OnChangeListener { _, _, fromUser ->
            if (fromUser) applyVolumeLevels()
        }
        originalVolumeSlider.addOnChangeListener(volumeListener)
        voiceVolumeSlider.addOnChangeListener(volumeListener)
    }

    private fun applyVolumeLevels() {
        // During active recording, mute video audio so mic doesn't pick it up
        // RECORDING_PAUSED is fine - mic is stopped
        if (currentState == State.RECORDING) {
            videoMediaPlayer?.setVolume(0f, 0f)
        } else {
            val origVol = originalVolumeSlider.value / 100f
            videoMediaPlayer?.setVolume(origVol, origVol)
        }
        val voiceVol = voiceVolumeSlider.value / 100f
        audioPlayer?.setVolume(voiceVol, voiceVol)
    }

    private fun transitionTo(state: State) {
        currentState = state
        val hasSegments = segmentManager.segmentCount > 0
        val subtleColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)

        // Seekbar: only interactive during idle and preview
        seekBar.isEnabled = state == State.IDLE || state == State.PREVIEWING

        // Volume controls: hide during recording (irrelevant - audio muted)
        volumeControls.visibility = if (state == State.RECORDING || state == State.RECORDING_PAUSED) View.GONE else View.VISIBLE

        // Play overlay: only in IDLE with segments and RECORDING_PAUSED
        playOverlay.visibility = when {
            state == State.IDLE && hasSegments -> View.VISIBLE
            state == State.RECORDING_PAUSED && hasSegments -> View.VISIBLE
            else -> View.GONE
        }

        when (state) {
            State.IDLE -> {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                handler.removeCallbacks(updateProgress)
                hidePulse()

                if (hasSegments) {
                    // Done recording: [Restart]  [▶ Play]  [Save]
                    recordFab.setImageResource(R.drawable.ic_play)
                    recordFab.backgroundTintList = ColorStateList.valueOf(secondaryColor)
                    statusText.text = getString(R.string.recording_complete)
                    reRecordButton.visibility = View.VISIBLE
                    saveButton.visibility = View.VISIBLE
                    stopButton.visibility = View.GONE
                } else {
                    // Fresh start: just the mic FAB
                    recordFab.setImageResource(R.drawable.ic_mic)
                    recordFab.backgroundTintList = ColorStateList.valueOf(harmonizedRed)
                    statusText.text = getString(R.string.start_recording)
                    reRecordButton.visibility = View.GONE
                    saveButton.visibility = View.GONE
                    stopButton.visibility = View.GONE
                }
                statusText.setTextColor(subtleColor)
                segmentCountText.visibility = View.GONE
                updateTimeline()
            }
            State.RECORDING -> {
                // Recording: [● Pause]  + Done button
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
                recordFab.setImageResource(R.drawable.ic_pause)
                recordFab.backgroundTintList = ColorStateList.valueOf(harmonizedRed)
                statusText.text = getString(R.string.recording)
                statusText.setTextColor(getColor(R.color.record_red))
                stopButton.visibility = View.VISIBLE
                stopButton.text = getString(R.string.done)
                reRecordButton.visibility = View.GONE
                saveButton.visibility = View.GONE
                segmentCountText.visibility = View.GONE
                showPulse()
            }
            State.RECORDING_PAUSED -> {
                // Paused: [🎤 Resume]  + Done button + play overlay on video
                recordFab.setImageResource(R.drawable.ic_mic)
                recordFab.backgroundTintList = ColorStateList.valueOf(harmonizedRed)
                statusText.text = getString(R.string.paused)
                statusText.setTextColor(subtleColor)
                stopButton.visibility = View.VISIBLE
                stopButton.text = getString(R.string.done)
                reRecordButton.visibility = View.GONE
                saveButton.visibility = View.GONE
                segmentCountText.visibility = View.GONE
                hidePulse()
                updateTimeline()
            }
            State.PREVIEWING -> {
                // Previewing: [⏹ Stop]
                recordFab.setImageResource(R.drawable.ic_stop)
                recordFab.backgroundTintList = ColorStateList.valueOf(secondaryColor)
                statusText.text = getString(R.string.previewing)
                statusText.setTextColor(secondaryColor)
                stopButton.visibility = View.GONE
                reRecordButton.visibility = View.GONE
                saveButton.visibility = View.GONE
                segmentCountText.visibility = View.GONE
                hidePulse()
            }
        }
    }

    // --- Recording ---
    // Each pause stops the recorder and saves a segment.
    // Resume creates a new recorder continuing from recordingPausePositionMs.
    // This allows preview of recorded segments while paused.
    private var inRecordingSession = false
    private var recordingPausePositionMs: Long = 0
    private var segmentStartTimeReal: Long = 0  // SystemClock time when segment began
    private val minSegmentDurationMs = 1000L     // Minimum 1s before pause is allowed

    private fun startRecording() {
        val videoDuration = videoView.duration.toLong()

        // Guard: video not prepared yet
        if (videoDuration <= 0 || !videoPrepared) {
            Toast.makeText(this, "Video loading, please wait...", Toast.LENGTH_SHORT).show()
            return
        }

        // Start fresh recording from beginning
        segmentManager.clearAll()
        lastKnownPositionMs = 0
        recordingStartPositionMs = 0
        recordingPausePositionMs = 0
        videoView.seekTo(0)
        seekBar.progress = 0
        inRecordingSession = true

        beginRecordingSegment()
    }

    private fun beginRecordingSegment() {
        suppressAutoPlay = false

        try {
            audioRecorder = AudioRecorderManager(this)
            audioRecorder?.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            Toast.makeText(this, getString(R.string.error_recording), Toast.LENGTH_SHORT).show()
            audioRecorder = null
            return
        }

        videoMediaPlayer?.setVolume(0f, 0f)

        // Only seek if the video isn't already at the target position.
        // seekTo() snaps to the nearest keyframe which can jump back 1-2s,
        // so skip it when resuming from a direct pause (video is already there).
        val currentPos = videoView.currentPosition.toLong()
        val needsSeek = recordingStartPositionMs > 0 &&
                kotlin.math.abs(currentPos - recordingStartPositionMs) > 500

        if (needsSeek && videoMediaPlayer != null) {
            videoMediaPlayer?.setOnSeekCompleteListener {
                videoMediaPlayer?.setOnSeekCompleteListener(null)
                videoView.start()
                handler.post(updateProgress)
            }
            // Use SEEK_CLOSEST on API 26+ for frame-accurate seeking
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                videoMediaPlayer?.seekTo(recordingStartPositionMs, android.media.MediaPlayer.SEEK_CLOSEST)
            } else {
                videoView.seekTo(recordingStartPositionMs.toInt())
            }
        } else {
            videoView.start()
            handler.post(updateProgress)
        }

        segmentStartTimeReal = android.os.SystemClock.elapsedRealtime()
        transitionTo(State.RECORDING)

        // Briefly disable pause to ensure minimum segment duration
        recordFab.isEnabled = false
        handler.postDelayed({ recordFab.isEnabled = true }, minSegmentDurationMs)
    }

    private fun pauseRecording() {
        // Guard: enforce minimum segment duration
        val elapsed = android.os.SystemClock.elapsedRealtime() - segmentStartTimeReal
        if (elapsed < minSegmentDurationMs) return
        // Capture position before stopping
        val currentPos = videoView.currentPosition.toLong()
        val videoDuration = videoView.duration.toLong()
        val endPos = if (currentPos > recordingStartPositionMs) currentPos
                     else if (lastKnownPositionMs > recordingStartPositionMs) lastKnownPositionMs
                     else recordingStartPositionMs
        val snappedEnd = if (videoDuration - endPos < 500) videoDuration else endPos

        // Stop recorder and save segment
        val recordingFile = audioRecorder?.recordingFile
        audioRecorder?.stopRecording()
        audioRecorder = null
        videoView.pause()
        handler.removeCallbacks(updateProgress)

        val durationMs = snappedEnd - recordingStartPositionMs
        if (recordingFile != null && durationMs > 200) {
            segmentManager.addSegment(RecordedSegment(
                audioFile = recordingFile,
                startPositionMs = recordingStartPositionMs,
                durationMs = durationMs
            ))
        }
        lastKnownPositionMs = snappedEnd
        // Save position separately so preview playback can't overwrite it
        recordingPausePositionMs = snappedEnd

        transitionTo(State.RECORDING_PAUSED)
    }

    private fun resumeRecording() {
        val videoDuration = videoView.duration.toLong()

        // If at end of video, finish instead
        if (recordingPausePositionMs >= videoDuration - 500) {
            finishRecording()
            return
        }

        // Restore position from recording pause (not preview position)
        lastKnownPositionMs = recordingPausePositionMs
        recordingStartPositionMs = recordingPausePositionMs
        beginRecordingSegment()
    }

    private fun finishRecording() {
        // If actively recording, save final segment
        if (currentState == State.RECORDING) {
            val currentPos = videoView.currentPosition.toLong()
            val videoDuration = videoView.duration.toLong()
            val endPos = maxOf(currentPos, lastKnownPositionMs)
            val snappedEnd = if (videoDuration - endPos < 500) videoDuration else endPos

            val recordingFile = audioRecorder?.recordingFile
            audioRecorder?.stopRecording()
            audioRecorder = null
            videoView.pause()
            handler.removeCallbacks(updateProgress)

            val durationMs = snappedEnd - recordingStartPositionMs
            if (recordingFile != null && durationMs > 200) {
                segmentManager.addSegment(RecordedSegment(
                    audioFile = recordingFile,
                    startPositionMs = recordingStartPositionMs,
                    durationMs = durationMs
                ))
            }
            lastKnownPositionMs = snappedEnd
        } else {
            audioRecorder?.stopRecording()
            audioRecorder = null
        }

        inRecordingSession = false
        transitionTo(State.IDLE)
    }

    private fun clearAllSegments() {
        releaseAudioPlayer()
        audioRecorder?.stopRecording()
        audioRecorder = null
        segmentManager.clearAll()
        inRecordingSession = false
        lastKnownPositionMs = 0
        recordingPausePositionMs = 0
        videoView.seekTo(0)
        seekBar.progress = 0
        timeText.text = "00:00 / ${formatTime(videoView.duration)}"
        transitionTo(State.IDLE)
    }

    // --- Preview ---

    private fun startPreview() {
        if (segmentManager.segmentCount == 0) return

        statusText.text = "Preparing preview..."
        recordFab.isEnabled = false
        loadingSpinner.visibility = View.VISIBLE

        activeJob?.cancel()
        activeJob = scope.launch {
            try {
                // Merge voice segments only (no original audio mixed in) for independent volume control
                previewTempFile?.delete()
                val mergedFile = File(cacheDir, "preview_voice_${System.currentTimeMillis()}.m4a")
                previewTempFile = mergedFile
                val success = withContext(Dispatchers.IO) {
                    segmentManager.mergeToFile(
                        videoView.duration.toLong(),
                        mergedFile,
                        voiceVolume = 1.0f,
                        originalVolume = 0f
                    )
                }

                recordFab.isEnabled = true
                loadingSpinner.visibility = View.GONE

                if (!success) {
                    Toast.makeText(this@RecordingActivity, "Preview merge failed", Toast.LENGTH_SHORT).show()
                    transitionTo(State.IDLE)
                    return@launch
                }

                // Play video with original audio + voice as separate streams
                // Both controlled by sliders in real-time
                releaseAudioPlayer()
                try {
                    audioPlayer = MediaPlayer().apply {
                        setDataSource(mergedFile.absolutePath)
                        prepare()
                        seekTo(0)
                    }
                } catch (e: Exception) {
                    audioPlayer?.release()
                    audioPlayer = null
                    throw e
                }

                videoPrepared = false
                videoView.setVideoURI(videoUri)
                videoView.setOnPreparedListener { mp ->
                    videoPrepared = true
                    videoMediaPlayer = mp
                    mp.isLooping = false
                    applyVolumeLevels() // Always apply volumes to new MediaPlayer
                    if (suppressAutoPlay) {
                        suppressAutoPlay = false
                        videoView.pause()
                        return@setOnPreparedListener
                    }
                    videoView.start()
                    audioPlayer?.start()
                    handler.post(updateProgress)
                    transitionTo(State.PREVIEWING)
                }
                videoView.setOnCompletionListener {
                    stopPreview()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Preview failed", e)
                Toast.makeText(this@RecordingActivity, "Preview failed", Toast.LENGTH_SHORT).show()
                recordFab.isEnabled = true
                loadingSpinner.visibility = View.GONE
                transitionTo(State.IDLE)
            }
        }
    }

    private fun stopPreview() {
        videoView.pause()
        releaseAudioPlayer()
        previewTempFile?.delete()
        previewTempFile = null
        handler.removeCallbacks(updateProgress)

        // Restore video for normal use
        suppressAutoPlay = true
        setupVideoPlayer()

        // Return to recording paused if in an active recording session
        if (inRecordingSession) {
            transitionTo(State.RECORDING_PAUSED)
        } else {
            transitionTo(State.IDLE)
        }
    }

    private fun releaseAudioPlayer() {
        audioPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
                it.release()
            } catch (_: Exception) {}
        }
        audioPlayer = null
    }

    // --- Save ---

    private fun saveVideo() {
        if (segmentManager.segmentCount == 0) return
        val uri = videoUri ?: return

        saveButton.isEnabled = false
        reRecordButton.isEnabled = false
        recordFab.isEnabled = false
        statusText.text = getString(R.string.saving)

        activeJob?.cancel()
        activeJob = scope.launch {
            try {
                // First merge segments into single audio
                val mergedAudio = File(cacheDir, "merged_audio_${System.currentTimeMillis()}.m4a")
                val origVol = originalVolumeSlider.value / 100f
                val voiceVol = voiceVolumeSlider.value / 100f
                val mergeSuccess = withContext(Dispatchers.IO) {
                    segmentManager.mergeToFile(
                        videoView.duration.toLong(),
                        mergedAudio,
                        voiceVolume = voiceVol,
                        originalVolume = origVol,
                        videoUri = uri,
                        context = this@RecordingActivity
                    )
                }

                if (!mergeSuccess) {
                    showDebugDialog("Merge Failed", segmentManager.lastMergeLog)
                    saveButton.isEnabled = true
                    reRecordButton.isEnabled = true
                    recordFab.isEnabled = true
                    return@launch
                }

                // Then mux audio with video
                val outputFile = File(cacheDir, "voiceover_${System.currentTimeMillis()}.mp4")
                val mixer = AudioMixer(this@RecordingActivity)
                val success = withContext(Dispatchers.IO) {
                    mixer.mergeAudioVideo(uri, mergedAudio, outputFile)
                }
                mergedAudio.delete()

                if (success) {
                    val savedUri = withContext(Dispatchers.IO) {
                        saveToMediaStore(outputFile)
                    }
                    outputFile.delete()

                    if (savedUri != null) {
                        statusText.text = getString(R.string.saved)
                        val rootView = findViewById<View>(android.R.id.content)
                        Snackbar.make(rootView, getString(R.string.saved), Snackbar.LENGTH_LONG)
                            .setAction("Share") {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "video/mp4"
                                    putExtra(Intent.EXTRA_STREAM, savedUri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                startActivity(Intent.createChooser(shareIntent, "Share video"))
                            }
                            .show()
                    } else {
                        statusText.text = "Save failed"
                        Toast.makeText(this@RecordingActivity, "Failed to save", Toast.LENGTH_LONG).show()
                    }
                } else {
                    outputFile.delete()
                    showDebugDialog("Mux Failed", "SegmentMerge:\n${segmentManager.lastMergeLog}\n\nAudioMixer:\n${mixer.lastMergeLog}")
                }

                saveButton.isEnabled = true
                reRecordButton.isEnabled = true
                recordFab.isEnabled = true
            } catch (e: Exception) {
                showDebugDialog("Save Exception", "${e.message}\n${e.stackTraceToString().take(800)}")
                saveButton.isEnabled = true
                reRecordButton.isEnabled = true
                recordFab.isEnabled = true
            }
        }
    }

    private fun saveToMediaStore(file: File): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "VoiceOver_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VoiceOver")
        }

        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null

        contentResolver.openOutputStream(uri)?.use { output ->
            file.inputStream().use { input ->
                input.copyTo(output)
            }
        }

        return uri
    }

    private fun showDebugDialog(title: String, message: String) {
        statusText.text = "Failed"
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    // --- Timeline ---

    private fun updateTimeline() {
        segmentTimeline.setSegments(segmentManager.segments)
    }

    // --- Pulse Animation ---

    private fun showPulse() {
        recordPulse.visibility = View.VISIBLE
        pulseAnimatorX = ObjectAnimator.ofFloat(recordPulse, "scaleX", 1f, 1.4f).apply {
            duration = 800
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        pulseAnimatorY = ObjectAnimator.ofFloat(recordPulse, "scaleY", 1f, 1.4f).apply {
            duration = 800
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun hidePulse() {
        pulseAnimatorX?.cancel()
        pulseAnimatorY?.cancel()
        recordPulse.visibility = View.INVISIBLE
    }

    // --- Utilities ---

    private fun formatTime(ms: Int): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateProgress)
        when (currentState) {
            State.RECORDING -> pauseRecording()
            State.PREVIEWING -> stopPreview()
            else -> {}
        }
        // Flag to prevent VideoView auto-play when surface is re-created in onResume
        suppressAutoPlay = true
    }

    override fun onResume() {
        super.onResume()
        // suppressAutoPlay is checked in all OnPreparedListener callbacks
        // to prevent VideoView from auto-playing after surface re-creation
    }

    override fun onDestroy() {
        super.onDestroy()
        activeJob?.cancel()
        handler.removeCallbacks(updateProgress)
        releaseAudioPlayer()
        previewTempFile?.delete()
        videoMediaPlayer = null
        audioRecorder?.release()
        segmentManager.clearAll()
        scope.cancel()

        // Clean up all temp files
        cacheDir.listFiles()?.filter {
            it.name.startsWith("preview_voice_") || it.name.startsWith("merged_audio_") || it.name.startsWith("voiceover_")
        }?.forEach { it.delete() }
    }
}
