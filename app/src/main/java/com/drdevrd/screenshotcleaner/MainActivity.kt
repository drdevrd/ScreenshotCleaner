package com.drdevrd.screenshotcleaner

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.drdevrd.screenshotcleaner.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: MediaAdapter
    private var currentType: MediaType = MediaType.SCREENSHOT

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            runScan()
        } else {
            Toast.makeText(this, "Permission needed to read media", Toast.LENGTH_LONG).show()
        }
    }

    private val deleteLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
            runScan()
        } else {
            Toast.makeText(this, "Delete cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = MediaAdapter(onSelectionChanged = ::updateStatus)
        val spanCount = 3
        val layoutManager = GridLayoutManager(this, spanCount)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int = adapter.spanSize(position, spanCount)
        }
        binding.recyclerView.layoutManager = layoutManager
        binding.recyclerView.adapter = adapter

        binding.scanButton.setOnClickListener { checkPermissionAndScan() }
        binding.selectAllButton.setOnClickListener {
            val anySelected = adapter.allItems().any { it.selected }
            adapter.selectAll(!anySelected)
        }
        binding.deleteButton.setOnClickListener { deleteSelected() }

        binding.bottomNav.setOnItemSelectedListener { menuItem ->
            currentType = when (menuItem.itemId) {
                R.id.nav_screenshots -> MediaType.SCREENSHOT
                R.id.nav_photos -> MediaType.PHOTO
                R.id.nav_videos -> MediaType.VIDEO
                else -> MediaType.SCREENSHOT
            }
            adapter.submit(emptyList())
            binding.statusText.text = "Tap Scan to find ${labelFor(currentType)}"
            true
        }
        binding.bottomNav.selectedItemId = R.id.nav_screenshots
    }

    private fun labelFor(type: MediaType) = when (type) {
        MediaType.SCREENSHOT -> "screenshots"
        MediaType.PHOTO -> "photos"
        MediaType.VIDEO -> "videos"
    }

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun checkPermissionAndScan() {
        val perms = requiredPermissions()
        val missing = perms.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            runScan()
        } else {
            permissionsLauncher.launch(perms)
        }
    }

    private fun runScan() {
        binding.statusText.text = "Scanning ${labelFor(currentType)}..."
        val typeAtStart = currentType
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                val found = when (typeAtStart) {
                    MediaType.SCREENSHOT -> MediaScanner.scanScreenshots(contentResolver)
                    MediaType.PHOTO -> MediaScanner.scanPhotos(contentResolver)
                    MediaType.VIDEO -> MediaScanner.scanVideos(contentResolver)
                }
                // Cap analysis to keep first scan responsive — 800 items is plenty per tab
                val limited = found.take(800)
                limited.forEach { Analyzer.analyze(this@MainActivity, it) }
                Analyzer.groupBySimilarity(limited)
                limited
            }
            // Guard against tab switch mid-scan
            if (typeAtStart == currentType) {
                adapter.submit(items)
                updateStatus()
            }
        }
    }

    private fun updateStatus() {
        val total = adapter.allItems().size
        val selected = adapter.allItems().count { it.selected }
        binding.statusText.text = "$total ${labelFor(currentType)} · $selected selected"
    }

    private fun deleteSelected() {
        val toDelete: List<Uri> = adapter.allItems().filter { it.selected }.map { it.uri }
        if (toDelete.isEmpty()) {
            Toast.makeText(this, "Nothing selected", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val pendingIntent = MediaStore.createDeleteRequest(contentResolver, toDelete)
            deleteLauncher.launch(
                androidx.activity.result.IntentSenderRequest.Builder(pendingIntent.intentSender).build()
            )
        } else {
            var count = 0
            toDelete.forEach { uri ->
                try {
                    if (contentResolver.delete(uri, null, null) > 0) count++
                } catch (_: Exception) { }
            }
            Toast.makeText(this, "Deleted $count", Toast.LENGTH_SHORT).show()
            runScan()
        }
    }
}
