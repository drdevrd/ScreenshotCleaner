package com.drdevrd.screenshotcleaner

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/**
 * All analysis is on-device (no INTERNET permission):
 *  - ML Kit Text Recognition (OCR)
 *  - ML Kit Image Labeling (~400 scene labels)
 *  - Perceptual hash for similarity grouping
 *
 * Category is decided by combining OCR text and image-labeler signals.
 * Text patterns (bill/OTP/chat) take priority, so a photographed receipt is a "Bill"
 * even if labels also say "Paper". Scene labels handle photos that have no meaningful text.
 */
object Analyzer {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder().setConfidenceThreshold(0.6f).build()
    )

    // -------- Text patterns --------
    private val otpRegex1 = Regex("""\b\d{4,8}\b.{0,20}(otp|code|verification|verify)""", RegexOption.IGNORE_CASE)
    private val otpRegex2 = Regex("""(otp|verification code|one[- ]time password).{0,20}\b\d{4,8}\b""", RegexOption.IGNORE_CASE)
    private val paymentRegex = Regex("""(₹|rs\.?\s?\d|inr|paid|payment|debited|credited|upi|transaction|invoice|receipt|amount\s*[:\-]?\s*[₹rs\d]|tax\s*invoice|gst|bill\s*no)""", RegexOption.IGNORE_CASE)
    private val chatRegex = Regex("""(whatsapp|typing\.\.\.|online|last seen|delivered|seen at|:\)|😂|😊|👍|❤️)""", RegexOption.IGNORE_CASE)
    private val productAdRegex = Regex("""(add to cart|buy now|rating|reviews?|shop now|₹\d+\s*off|% off|delivery|shipping|amazon|flipkart)""", RegexOption.IGNORE_CASE)

    // -------- Scene label groups (matched case-insensitive) --------
    private val personLabels = setOf("person", "face", "smile", "child", "portrait", "people", "baby", "hair", "hand", "selfie")
    private val foodLabels = setOf("food", "dish", "cuisine", "meal", "dessert", "fruit", "vegetable", "drink", "coffee", "tea", "cake", "bread")
    private val placeLabels = setOf("building", "skyscraper", "architecture", "house", "monument", "temple", "church", "street", "road", "city", "bridge", "tower", "castle")
    private val natureLabels = setOf("plant", "tree", "flower", "leaf", "landscape", "water", "sky", "mountain", "beach", "sea", "forest", "sunset", "sunrise", "cloud", "grass", "garden")
    private val vehicleLabels = setOf("car", "vehicle", "motorcycle", "bicycle", "bike", "truck", "bus", "train", "airplane", "boat", "ship")
    private val animalLabels = setOf("dog", "cat", "bird", "animal", "pet", "wildlife", "fish", "insect", "butterfly", "cow", "horse")

    suspend fun analyze(context: Context, item: MediaItem) {
        val bitmap = when (item.type) {
            MediaType.VIDEO -> loadVideoFrame(context, item.uri)
            else -> loadImageDownscaled(context.contentResolver, item.uri)
        } ?: return

        val image = try {
            InputImage.fromBitmap(bitmap, 0)
        } catch (_: Exception) {
            null
        }

        var text = ""
        val labels = mutableSetOf<String>()

        if (image != null) {
            // Run OCR
            try {
                text = recognizer.process(image).await().text
                item.ocrText = text
            } catch (_: Exception) { /* leave empty */ }

            // Run image labeling
            try {
                val results = labeler.process(image).await()
                results.forEach { labels.add(it.text.lowercase()) }
            } catch (_: Exception) { /* leave empty */ }
        }

        item.category = categorize(text, labels)
        item.pHash = averageHash(bitmap)

        if (!bitmap.isRecycled) bitmap.recycle()
    }

    private fun categorize(text: String, labels: Set<String>): Category {
        val hasText = text.length > 20
        val hasLotsOfText = text.length > 300

        // Text-content categories (priority — a photographed bill is still a bill)
        if (hasText) {
            if (otpRegex1.containsMatchIn(text) || otpRegex2.containsMatchIn(text)) return Category.OTP_CODE
            if (paymentRegex.containsMatchIn(text)) return Category.BILL_RECEIPT
            if (chatRegex.containsMatchIn(text)) return Category.CHAT
            if (productAdRegex.containsMatchIn(text)) return Category.PRODUCT_AD
        }

        // Scene-content categories
        if (labels.any { it in personLabels }) return Category.PEOPLE
        if (labels.any { it in foodLabels }) return Category.FOOD
        if (labels.any { it in vehicleLabels }) return Category.VEHICLE
        if (labels.any { it in animalLabels }) return Category.ANIMAL
        if (labels.any { it in placeLabels }) return Category.PLACES
        if (labels.any { it in natureLabels }) return Category.NATURE

        // Text-heavy without a known pattern → generic document
        if (hasLotsOfText) return Category.DOCUMENT

        return Category.OTHER
    }

    fun groupBySimilarity(items: List<MediaItem>, maxDistance: Int = 8) {
        val ungrouped = items.toMutableList()
        var groupCounter = 0
        while (ungrouped.isNotEmpty()) {
            val seed = ungrouped.removeAt(0)
            val groupKey = "grp_${groupCounter++}"
            seed.groupKey = groupKey
            val toRemove = mutableListOf<MediaItem>()
            for (other in ungrouped) {
                if (hammingDistance(seed.pHash, other.pHash) <= maxDistance) {
                    other.groupKey = groupKey
                    toRemove.add(other)
                }
            }
            ungrouped.removeAll(toRemove)
        }
    }

    private fun loadImageDownscaled(resolver: ContentResolver, uri: Uri): Bitmap? {
        return try {
            resolver.openInputStream(uri)?.use { input ->
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeStream(input, null, opts)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun loadVideoFrame(context: Context, uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val frame = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (frame != null && (frame.width > 500 || frame.height > 500)) {
                val scaled = Bitmap.createScaledBitmap(frame, frame.width / 3, frame.height / 3, true)
                if (scaled !== frame) frame.recycle()
                scaled
            } else {
                frame
            }
        } catch (_: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun averageHash(src: Bitmap): Long {
        val scaled = Bitmap.createScaledBitmap(src, 8, 8, true)
        val gray = IntArray(64)
        var sum = 0L
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val pixel = scaled.getPixel(x, y)
                val g = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3
                gray[y * 8 + x] = g
                sum += g
            }
        }
        val avg = sum / 64
        var hash = 0L
        for (i in 0 until 64) {
            if (gray[i] >= avg) hash = hash or (1L shl i)
        }
        if (scaled !== src) scaled.recycle()
        return hash
    }

    private fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)
}
