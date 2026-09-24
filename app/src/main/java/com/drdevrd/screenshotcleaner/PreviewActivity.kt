package com.drdevrd.screenshotcleaner

import android.app.Activity
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.MediaController
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import coil.load

class PreviewActivity : AppCompatActivity() {

    private lateinit var uri: Uri
    private var isVideo: Boolean = false

    private val trashLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(this, "Moved to trash", Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK)
            finish()
        } else {
            Toast.makeText(this, "Cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)

        val uriStr = intent.getStringExtra(EXTRA_URI)
        if (uriStr.isNullOrEmpty()) {
            finish()
            return
        }
        uri = Uri.parse(uriStr)
        isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)
        val ocrPreview = intent.getStringExtra(EXTRA_OCR_PREVIEW).orEmpty()
        val category = intent.getStringExtra(EXTRA_CATEGORY).orEmpty()

        val imageView = findViewById<ImageView>(R.id.previewImage)
        val videoView = findViewById<VideoView>(R.id.previewVideo)
        val closeButton = findViewById<ImageButton>(R.id.closeButton)
        val trashButton = findViewById<Button>(R.id.trashButton)
        val infoText = findViewById<TextView>(R.id.previewInfo)

        val infoParts = mutableListOf<String>()
        if (category.isNotEmpty()) infoParts.add(category)
        if (ocrPreview.isNotEmpty()) infoParts.add(ocrPreview.take(120).replace('\n', ' '))
        infoText.text = infoParts.joinToString(" · ").ifEmpty { "" }

        if (isVideo) {
            imageView.visibility = ImageView.GONE
            videoView.visibility = VideoView.VISIBLE
            val controller = MediaController(this)
            controller.setAnchorView(videoView)
            videoView.setMediaController(controller)
            videoView.setVideoURI(uri)
            videoView.setOnPreparedListener { mp -> mp.isLooping = false; videoView.start() }
        } else {
            imageView.load(uri) {
                crossfade(true)
            }
        }

        closeButton.setOnClickListener { finish() }
        trashButton.setOnClickListener { moveToTrash() }
    }

    private fun moveToTrash() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val pendingIntent = MediaStore.createTrashRequest(
                    contentResolver, listOf(uri), true
                )
                trashLauncher.launch(
                    IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Trash unavailable: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            // Older Android: fall back to standard delete confirmation
            try {
                val n = contentResolver.delete(uri, null, null)
                if (n > 0) {
                    Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                    finish()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Delete failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_IS_VIDEO = "isVideo"
        const val EXTRA_OCR_PREVIEW = "ocrPreview"
        const val EXTRA_CATEGORY = "category"
    }
}
