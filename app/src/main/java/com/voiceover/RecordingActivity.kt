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
        IDLE, RECORDING, PREVIEWING, PREVIEW_PAUSED
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
    private lateinit var saveButton: MaterialButton
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
    private var activeJob: Job? = null

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
            }
            handler.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        playOverlay.setOnClickListener { startPreview() }
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
                    mp.isLooping = false
                    val duration = videoView.duration
                    seekBar.max = duration
                    timeText.text = "${formatTime(position)} / ${formatTime(duration)}"
                    segmentTimeline.setVideoDuration(duration.toLong())
                    videoView.seekTo(position)
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
        playOverlay.setOnClickListener { startPreview() }
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

            if (wasPlaying && (savedState == State.RECORDING || savedState == State.PREVIEWING)) {
                videoView.start()
                handler.post(updateProgress)
            }
        }
        videoView.setOnCompletionListener {
            when (currentState) {
                State.RECORDING -> stopSegmentRecording()
                State.PREVIEWING -> stopPreview()
                else -> {}
            }
        }

        // Restore UI state
        transitionTo(savedState)
        updateTimeline()
    }

    private fun setupVideoPlayer() {
        videoView.setVideoURI(videoUri)
        videoView.setOnPreparedListener { mp ->
            videoMediaPlayer = mp
            mp.isLooping = false
            val duration = videoView.duration
            seekBar.max = duration
            timeText.text = "00:00 / ${formatTime(duration)}"
            segmentTimeline.setVideoDuration(duration.toLong())
            applyVolumeLevels()
            // Show first frame instead of black screen
            videoView.seekTo(1)
        }
        videoView.setOnCompletionListener {
            when (currentState) {
                State.RECORDING -> stopSegmentRecording()
                State.PREVIEWING -> stopPreview()
                else -> {}
            }
        }
    }

    private fun setupControls() {
        recordFab.setOnClickListener {
            when (currentState) {
                State.IDLE -> startSegmentRecording()
                State.RECORDING -> stopSegmentRecording()
                State.PREVIEWING -> pausePreview()
                State.PREVIEW_PAUSED -> resumePreview()
            }
        }

        stopButton.setOnClickListener {
            when (currentState) {
                State.RECORDING -> stopSegmentRecording()
                State.PREVIEWING, State.PREVIEW_PAUSED -> stopPreview()
                else -> {}
            }
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && currentState != State.RECORDING) {
                    videoView.seekTo(progress)
                    audioPlayer?.seekTo(progress)
                    timeText.text = "${formatTime(progress)} / ${formatTime(videoView.duration)}"
                    segmentTimeline.setCurrentPosition(progress.toLong())
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
        // During recording, always mute video audio so mic doesn't pick it up
        // Volume sliders only affect the final saved/previewed output uniformly
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

        when (state) {
            State.IDLE -> {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                handler.removeCallbacks(updateProgress)
                recordFab.setImageResource(R.drawable.ic_mic)
                recordFab.backgroundTintList = ColorStateList.valueOf(harmonizedRed)

                if (hasSegments) {
                    statusText.text = getString(R.string.tap_to_record)
                    statusText.setTextColor(MaterialColors.getColor(this@RecordingActivity, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY))
                    reRecordButton.visibility = View.VISIBLE
                    saveButton.visibility = View.VISIBLE
                    stopButton.visibility = View.GONE
                    segmentCountText.text = getString(R.string.segments_count, segmentManager.segmentCount)
                    segmentCountText.visibility = View.VISIBLE
                    playOverlay.visibility = View.VISIBLE
                } else {
                    statusText.text = getString(R.string.start_recording)
                    statusText.setTextColor(MaterialColors.getColor(this@RecordingActivity, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY))
                    reRecordButton.visibility = View.GONE
                    saveButton.visibility = View.GONE
                    stopButton.visibility = View.GONE
                    segmentCountText.visibility = View.GONE
                    playOverlay.visibility = View.GONE
                }

                hidePulse()
                updateTimeline()
            }
            State.RECORDING -> {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
                recordFab.setImageResource(R.drawable.ic_stop)
                recordFab.backgroundTintList = ColorStateList.valueOf(harmonizedRed)
                statusText.text = getString(R.string.recording)
                statusText.setTextColor(getColor(R.color.record_red))
                stopButton.visibility = View.GONE
                reRecordButton.visibility = View.GONE
                saveButton.visibility = View.GONE
                segmentCountText.visibility = View.GONE
                playOverlay.visibility = View.GONE
                showPulse()
            }
            State.PREVIEWING -> {
                recordFab.setImageResource(R.drawable.ic_pause)
                recordFab.backgroundTintList = ColorStateList.valueOf(secondaryColor)
                statusText.text = getString(R.string.previewing)
                statusText.setTextColor(secondaryColor)
                stopButton.visibility = View.VISIBLE
                stopButton.text = "Stop"
                reRecordButton.visibility = View.GONE
                saveButton.visibility = View.GONE
                playOverlay.visibility = View.GONE
                hidePulse()
            }
            State.PREVIEW_PAUSED -> {
                recordFab.setImageResource(R.drawable.ic_play)
                recordFab.backgroundTintList = ColorStateList.valueOf(secondaryColor)
                statusText.text = getString(R.string.preview_paused)
                statusText.setTextColor(MaterialColors.getColor(this@RecordingActivity, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY))
                playOverlay.visibility = View.GONE
            }
        }
    }

    // --- Segment Recording ---

    private fun startSegmentRecording() {
        recordingStartPositionMs = videoView.currentPosition.toLong()
        videoView.seekTo(recordingStartPositionMs.toInt())

        audioRecorder = AudioRecorderManager(this)
        audioRecorder?.startRecording()

        // Mute video during recording so mic doesn't pick up speaker audio
        videoMediaPlayer?.setVolume(0f, 0f)
        videoView.start()
        handler.post(updateProgress)

        transitionTo(State.RECORDING)
    }

    private fun stopSegmentRecording() {
        val recordingFile = audioRecorder?.recordingFile
        val durationMs = videoView.currentPosition.toLong() - recordingStartPositionMs

        audioRecorder?.stopRecording()
        videoView.pause()
        handler.removeCallbacks(updateProgress)

        // Save segment if it has meaningful duration
        if (recordingFile != null && durationMs > 200) {
            val segment = RecordedSegment(
                audioFile = recordingFile,
                startPositionMs = recordingStartPositionMs,
                durationMs = durationMs
            )
            segmentManager.addSegment(segment)
        }

        audioRecorder = null
        transitionTo(State.IDLE)
    }

    private fun clearAllSegments() {
        releaseAudioPlayer()
        segmentManager.clearAll()
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

        activeJob = scope.launch {
            try {
                // Merge voice segments only (no original audio mixed in) for independent volume control
                val mergedFile = File(cacheDir, "preview_voice_${System.currentTimeMillis()}.m4a")
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
                audioPlayer = MediaPlayer().apply {
                    setDataSource(mergedFile.absolutePath)
                    prepare()
                    seekTo(0)
                }

                videoView.setVideoURI(videoUri)
                videoView.setOnPreparedListener { mp ->
                    videoMediaPlayer = mp
                    mp.isLooping = false
                    applyVolumeLevels() // Set both volumes from sliders
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

    private fun pausePreview() {
        videoView.pause()
        audioPlayer?.pause()
        handler.removeCallbacks(updateProgress)
        transitionTo(State.PREVIEW_PAUSED)
    }

    private fun resumePreview() {
        videoView.start()
        audioPlayer?.start()
        handler.post(updateProgress)
        transitionTo(State.PREVIEWING)
    }

    private fun stopPreview() {
        videoView.pause()
        releaseAudioPlayer()
        handler.removeCallbacks(updateProgress)

        // Restore video audio
        videoView.setVideoURI(videoUri)
        videoView.setOnPreparedListener { mp ->
            videoMediaPlayer = mp
            mp.isLooping = false
            seekBar.max = videoView.duration
            segmentTimeline.setVideoDuration(videoView.duration.toLong())
            applyVolumeLevels()
        }
        videoView.setOnCompletionListener {
            when (currentState) {
                State.RECORDING -> stopSegmentRecording()
                State.PREVIEWING -> stopPreview()
                else -> {}
            }
        }

        transitionTo(State.IDLE)
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
        when (currentState) {
            State.RECORDING -> stopSegmentRecording()
            State.PREVIEWING -> pausePreview()
            else -> {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activeJob?.cancel()
        handler.removeCallbacks(updateProgress)
        releaseAudioPlayer()
        videoMediaPlayer = null
        audioRecorder?.release()
        segmentManager.clearAll()
        scope.cancel()
    }
}
