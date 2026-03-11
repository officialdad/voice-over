package com.voiceover

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        private const val KEY_SELECTED_VIDEO_URI = "selected_video_uri"
    }

    private var selectedVideoUri: Uri? = null

    private lateinit var videoThumbnail: ImageView
    private lateinit var placeholderText: TextView
    private lateinit var selectButton: MaterialButton
    private lateinit var recordButton: MaterialButton

    private val scope = MainScope()

    private val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            openVideoPicker()
        } else {
            Toast.makeText(this, getString(R.string.permission_required), Toast.LENGTH_LONG).show()
        }
    }

    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onVideoSelected(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        videoThumbnail = findViewById(R.id.videoThumbnail)
        placeholderText = findViewById(R.id.placeholderText)
        selectButton = findViewById(R.id.selectButton)
        recordButton = findViewById(R.id.recordButton)

        selectButton.setOnClickListener {
            if (hasPermissions()) {
                openVideoPicker()
            } else {
                permissionLauncher.launch(requiredPermissions)
            }
        }

        recordButton.setOnClickListener {
            selectedVideoUri?.let { uri ->
                val intent = Intent(this, RecordingActivity::class.java).apply {
                    putExtra("video_uri", uri.toString())
                }
                startActivity(intent)
            }
        }

        // Restore selected video URI after rotation
        savedInstanceState?.getString(KEY_SELECTED_VIDEO_URI)?.let { uriString ->
            val uri = Uri.parse(uriString)
            onVideoSelected(uri)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        selectedVideoUri?.let { uri ->
            outState.putString(KEY_SELECTED_VIDEO_URI, uri.toString())
        }
    }

    private fun hasPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun openVideoPicker() {
        videoPickerLauncher.launch("video/*")
    }

    private fun onVideoSelected(uri: Uri) {
        selectedVideoUri = uri

        // Take persistable permission so RecordingActivity can access it
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Not all providers support persistable permissions
        }

        // Load thumbnail off main thread
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(this@MainActivity, uri)
                        retriever.frameAtTime
                    } finally {
                        retriever.release()
                    }
                } catch (_: Exception) {
                    null
                }
            }

            if (bitmap != null) {
                videoThumbnail.setImageBitmap(bitmap)
                videoThumbnail.visibility = android.view.View.VISIBLE
                placeholderText.visibility = android.view.View.GONE
            } else {
                placeholderText.text = "Video selected"
                placeholderText.visibility = android.view.View.VISIBLE
            }
        }

        recordButton.isEnabled = true
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
