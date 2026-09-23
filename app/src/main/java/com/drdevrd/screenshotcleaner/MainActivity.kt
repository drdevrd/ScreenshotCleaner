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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.drdevrd.screenshotcleaner.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ScreenshotAdapter
    private var currentItems: List<ScreenshotItem> = emptyList()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) runScan() else
            Toast.makeText(this, "Permission needed to read screenshots", Toast.LENGTH_LONG).show()
    }

    // Handles the system delete-confirmation dialog required by MediaStore.createDeleteRequest
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

        adapter = ScreenshotAdapter(onSelectionChanged = ::updateStatus)
        val spanCount = 3
        val layoutManager = GridLayoutManager(this, spanCount)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = adapter.spanSize(position, spanCount)
        }
        binding.recyclerView.layoutManager = layoutManager
        binding.recyclerView.adapter = adapter

        binding.scanButton.setOnClickListener { checkPermissionAndScan() }
        binding.selectAllButton.setOnClickListener {
            val anySelected = adapter.allItems().any { it.selected }
            adapter.selectAll(!anySelected)
        }
        binding.deleteButton.setOnClickListener { deleteSelected() }
    }

    private fun checkPermissionAndScan() {
        val permission = if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.READ_MEDIA_IMAGES
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

        if (ActivityCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            runScan()
        } else {
            permissionLauncher.launch(permission)
        }
    }

    private fun runScan() {
        binding.statusText.text = "Scanning..."
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                val found = ScreenshotScanner.scan(contentResolver)
                found.forEach { Analyzer.analyze(contentResolver, it) }
                Analyzer.groupBySimilarity(found)
                found
            }
            currentItems = items
            adapter.submit(items)
            updateStatus()
        }
    }

    private fun updateStatus() {
        val total = adapter.allItems().size
        val selected = adapter.allItems().count { it.selected }
        binding.statusText.text = "$total screenshots · $selected selected"
    }

    private fun deleteSelected() {
        val toDelete: List<Uri> = adapter.allItems().filter { it.selected }.map { it.uri }
        if (toDelete.isEmpty()) {
            Toast.makeText(this, "Nothing selected", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 30) {
            // System-level confirmation dialog — the OS, not this app, performs the delete
            val pendingIntent = MediaStore.createDeleteRequest(contentResolver, toDelete)
            deleteLauncher.launch(
                androidx.activity.result.IntentSenderRequest.Builder(pendingIntent.intentSender).build()
            )
        } else {
            // Pre-Android 11 fallback: direct delete (app must own the files, which screenshots are)
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
