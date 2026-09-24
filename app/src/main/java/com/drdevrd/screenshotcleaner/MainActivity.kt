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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.drdevrd.screenshotcleaner.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: MediaAdapter
    private lateinit var cacheDb: MediaCacheDb

    private var currentType: MediaType = MediaType.SCREENSHOT
    /** All items for the current tab (unfiltered). */
    private val allCurrentItems = mutableListOf<MediaItem>()
    /** Current search query. */
    private var currentQuery: String = ""
    /** Active scan job, so we can cancel on tab switch. */
    private var scanJob: Job? = null

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
            Toast.makeText(this, "Moved to trash", Toast.LENGTH_SHORT).show()
            loadCached()
        } else {
            Toast.makeText(this, "Cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    private val previewLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Item was trashed from preview — refresh list
            loadCached()
        }
    }

    private fun openPreview(item: MediaItem) {
        val intent = android.content.Intent(this, PreviewActivity::class.java).apply {
            putExtra(PreviewActivity.EXTRA_URI, item.uri.toString())
            putExtra(PreviewActivity.EXTRA_IS_VIDEO, item.type == MediaType.VIDEO)
            putExtra(PreviewActivity.EXTRA_OCR_PREVIEW, item.ocrText)
            putExtra(PreviewActivity.EXTRA_CATEGORY, item.category.display)
        }
        previewLauncher.launch(intent)
    }

    private var dragActive = false
    private var lastDragPosition = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cacheDb = MediaCacheDb(applicationContext)

        adapter = MediaAdapter(
            onSelectionChanged = ::updateStatus,
            onDragStart = { dragActive = true },
            onPreview = ::openPreview
        )
        val spanCount = 3
        val layoutManager = GridLayoutManager(this, spanCount)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int = adapter.spanSize(position, spanCount)
        }
        binding.recyclerView.layoutManager = layoutManager
        binding.recyclerView.adapter = adapter

        // Drag-select: after long-press starts drag mode, moving finger toggles items
        binding.recyclerView.addOnItemTouchListener(object : androidx.recyclerview.widget.RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: androidx.recyclerview.widget.RecyclerView, e: android.view.MotionEvent): Boolean {
                if (!dragActive) return false
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val child = rv.findChildViewUnder(e.x, e.y) ?: return true
                        val pos = rv.getChildAdapterPosition(child)
                        if (pos >= 0 && pos != lastDragPosition) {
                            adapter.setSelectedAt(pos, true)
                            lastDragPosition = pos
                        }
                        return true
                    }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        dragActive = false
                        lastDragPosition = -1
                    }
                }
                return false
            }
            override fun onTouchEvent(rv: androidx.recyclerview.widget.RecyclerView, e: android.view.MotionEvent) {
                if (!dragActive) return
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val child = rv.findChildViewUnder(e.x, e.y) ?: return
                        val pos = rv.getChildAdapterPosition(child)
                        if (pos >= 0 && pos != lastDragPosition) {
                            adapter.setSelectedAt(pos, true)
                            lastDragPosition = pos
                        }
                    }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        dragActive = false
                        lastDragPosition = -1
                    }
                }
            }
            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        })

        binding.scanButton.setOnClickListener { checkPermissionAndScan() }
        binding.scanButton.setOnLongClickListener {
            confirmClearCache()
            true
        }
        binding.selectAllButton.setOnClickListener {
            val anySelected = adapter.allItems().any { it.selected }
            adapter.selectAll(!anySelected)
        }
        binding.deleteButton.setOnClickListener { deleteSelected() }

        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                currentQuery = query?.trim().orEmpty()
                applyFilter()
                return true
            }
            override fun onQueryTextChange(newText: String?): Boolean {
                currentQuery = newText?.trim().orEmpty()
                applyFilter()
                return true
            }
        })

        binding.bottomNav.setOnItemSelectedListener { menuItem ->
            currentType = when (menuItem.itemId) {
                R.id.nav_screenshots -> MediaType.SCREENSHOT
                R.id.nav_photos -> MediaType.PHOTO
                R.id.nav_videos -> MediaType.VIDEO
                else -> MediaType.SCREENSHOT
            }
            scanJob?.cancel()
            binding.searchView.setQuery("", false)
            binding.searchView.clearFocus()
            currentQuery = ""
            loadCached()
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

    /** Load cached items and, if new items exist, silently start analyzing them. */
    private fun loadCached() {
        val typeAtStart = currentType
        binding.statusText.text = "Loading..."
        lifecycleScope.launch {
            val fromStore = withContext(Dispatchers.IO) { scanForType(typeAtStart) }
            val cache = withContext(Dispatchers.IO) { cacheDb.getAllForType(typeAtStart) }
            val items = fromStore.mapNotNull { item ->
                val cached = cache[item.id] ?: return@mapNotNull null
                item.apply {
                    ocrText = cached.ocrText
                    labels = cached.labels
                    category = cached.category
                    pHash = cached.pHash
                }
            }
            Analyzer.groupBySimilarity(items)
            if (typeAtStart != currentType) return@launch
            allCurrentItems.clear()
            allCurrentItems.addAll(items)
            applyFilter()

            val newCount = fromStore.size - items.size
            binding.statusText.text = when {
                items.isEmpty() && newCount == 0 -> "No ${labelFor(typeAtStart)}"
                items.isEmpty() -> "$newCount ${labelFor(typeAtStart)} · tap Scan"
                newCount == 0 -> "${items.size} ${labelFor(typeAtStart)}"
                else -> "${items.size} ${labelFor(typeAtStart)} · $newCount new"
            }
            // Auto-analyze new items if cache already exists — user shouldn't have to tap Scan
            if (items.isNotEmpty() && newCount > 0) {
                runScan()
            }
        }
    }

    private fun scanForType(type: MediaType): List<MediaItem> = when (type) {
        MediaType.SCREENSHOT -> MediaScanner.scanScreenshots(contentResolver)
        MediaType.PHOTO -> MediaScanner.scanPhotos(contentResolver)
        MediaType.VIDEO -> MediaScanner.scanVideos(contentResolver)
    }

    /**
     * Full scan flow:
     * 1) Query MediaStore → all items on device
     * 2) Load cache → hydrate items that were analyzed before
     * 3) Analyze new items one by one, save to DB, update UI in batches
     */
    private fun runScan() {
        val typeAtStart = currentType
        scanJob?.cancel()
        scanJob = lifecycleScope.launch {
            val fromStore = withContext(Dispatchers.IO) { scanForType(typeAtStart) }
            val cache = withContext(Dispatchers.IO) { cacheDb.getAllForType(typeAtStart) }

            val cached = mutableListOf<MediaItem>()
            val toAnalyze = mutableListOf<MediaItem>()
            for (item in fromStore) {
                val hit = cache[item.id]
                if (hit != null) {
                    item.ocrText = hit.ocrText
                    item.labels = hit.labels
                    item.category = hit.category
                    item.pHash = hit.pHash
                    cached.add(item)
                } else {
                    toAnalyze.add(item)
                }
            }

            // Show cached ones straight away
            allCurrentItems.clear()
            allCurrentItems.addAll(cached)
            Analyzer.groupBySimilarity(allCurrentItems)
            applyFilter()

            if (toAnalyze.isEmpty()) {
                binding.statusText.text = "${cached.size} ${labelFor(typeAtStart)} · all analyzed"
                return@launch
            }

            binding.statusText.text = "Analyzing 0/${toAnalyze.size} new..."
            val batchSize = 20
            for ((index, item) in toAnalyze.withIndex()) {
                if (typeAtStart != currentType) return@launch
                withContext(Dispatchers.IO) {
                    Analyzer.analyze(this@MainActivity, item)
                    cacheDb.save(item)
                }
                allCurrentItems.add(item)
                if ((index + 1) % batchSize == 0 || index == toAnalyze.size - 1) {
                    Analyzer.groupBySimilarity(allCurrentItems)
                    applyFilter()
                    binding.statusText.text = "Analyzing ${index + 1}/${toAnalyze.size} new..."
                }
            }
            binding.statusText.text = "${allCurrentItems.size} ${labelFor(typeAtStart)} · done"
        }
    }

    private fun applyFilter() {
        val filtered = if (currentQuery.isEmpty()) {
            allCurrentItems.toList()
        } else {
            val q = currentQuery.lowercase()
            val qStem = if (q.length > 3 && q.endsWith("s")) q.dropLast(1) else q
            val esc = Regex.escape(q)
            val escStem = if (q != qStem) Regex.escape(qStem) else esc
            // Word-boundary regex — "tile" won't match "percentile", "car" won't match "cardiac"
            val pattern = if (q != qStem) {
                Regex("\\b($esc|$escStem)\\b", RegexOption.IGNORE_CASE)
            } else {
                Regex("\\b$esc\\b", RegexOption.IGNORE_CASE)
            }
            allCurrentItems.filter { item ->
                pattern.containsMatchIn(item.ocrText) ||
                        pattern.containsMatchIn(item.labels) ||
                        pattern.containsMatchIn(item.category.display)
            }
        }
        adapter.submit(filtered)
        updateStatus()
    }

    private fun updateStatus() {
        val total = adapter.allItems().size
        val selected = adapter.allItems().count { it.selected }
        val label = labelFor(currentType)
        val prefix = if (currentQuery.isNotEmpty()) "\"$currentQuery\": " else ""
        binding.statusText.text = "$prefix$total $label · $selected selected"
    }

    private fun deleteSelected() {
        val toDelete: List<Uri> = adapter.allItems().filter { it.selected }.map { it.uri }
        if (toDelete.isEmpty()) {
            Toast.makeText(this, "Nothing selected", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                // createTrashRequest → OS Trash (30-day recovery via gallery app)
                val pendingIntent = MediaStore.createTrashRequest(contentResolver, toDelete, true)
                deleteLauncher.launch(
                    androidx.activity.result.IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                )
            } catch (e: Exception) {
                // Fallback to permanent delete request if trash is not available
                val pendingIntent = MediaStore.createDeleteRequest(contentResolver, toDelete)
                deleteLauncher.launch(
                    androidx.activity.result.IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                )
            }
        } else {
            var count = 0
            toDelete.forEach { uri ->
                try {
                    if (contentResolver.delete(uri, null, null) > 0) count++
                } catch (_: Exception) { }
            }
            Toast.makeText(this, "Deleted $count", Toast.LENGTH_SHORT).show()
            loadCached()
        }
    }

    private fun confirmClearCache() {
        AlertDialog.Builder(this)
            .setTitle("Clear analysis cache?")
            .setMessage("Force full re-analysis on next Scan. Media files are not deleted.")
            .setPositiveButton("Clear") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { cacheDb.clearAll() }
                    Toast.makeText(this@MainActivity, "Cache cleared", Toast.LENGTH_SHORT).show()
                    loadCached()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
