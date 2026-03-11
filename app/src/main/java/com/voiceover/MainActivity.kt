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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private var selectedVideoUri: Uri? = null

    private lateinit var videoThumbnail: ImageView
    private lateinit var placeholderText: TextView
    private lateinit var selectButton: MaterialButton
    private lateinit var recordButton: MaterialButton

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

        // Show thumbnail
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(this, uri)
            val bitmap = retriever.frameAtTime
            retriever.release()

            if (bitmap != null) {
                videoThumbnail.setImageBitmap(bitmap)
                videoThumbnail.visibility = android.view.View.VISIBLE
                placeholderText.visibility = android.view.View.GONE
            }
        } catch (e: Exception) {
            placeholderText.text = "Video selected"
            placeholderText.visibility = android.view.View.VISIBLE
        }

        recordButton.isEnabled = true
    }
}
