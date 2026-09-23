package com.drdevrd.screenshotcleaner

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/**
 * All analysis happens on-device:
 *  - ML Kit Text Recognition (bundled model, no network call)
 *  - A simple average-hash (aHash) for perceptual similarity, computed locally
 * Nothing here touches the network. The app declares no INTERNET permission.
 */
object Analyzer {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val otpRegex = Regex("""\b\d{4,8}\b.{0,20}(otp|code|verification|verify)""", RegexOption.IGNORE_CASE)
    private val otpRegex2 = Regex("""(otp|verification code|one[- ]time password).{0,20}\b\d{4,8}\b""", RegexOption.IGNORE_CASE)
    private val paymentRegex = Regex("""(₹|rs\.?|inr|paid|payment|debited|credited|upi|transaction|invoice|receipt|amount)""", RegexOption.IGNORE_CASE)
    private val chatRegex = Regex("""(whatsapp|typing\.\.\.|online|last seen|read \d|:\)|😂|😊)""", RegexOption.IGNORE_CASE)

    suspend fun analyze(resolver: ContentResolver, item: ScreenshotItem) {
        val bitmap = loadDownscaled(resolver, item.uri) ?: return

        // ---- Label via OCR text patterns ----
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = recognizer.process(image).await()
            val text = result.text

            item.label = when {
                otpRegex.containsMatchIn(text) || otpRegex2.containsMatchIn(text) -> Label.OTP_CODE
                paymentRegex.containsMatchIn(text) -> Label.PAYMENT
                chatRegex.containsMatchIn(text) -> Label.CHAT
                text.length > 400 -> Label.ARTICLE
                else -> Label.OTHER
            }
        } catch (_: Exception) {
            item.label = Label.OTHER
        }

        // ---- Perceptual hash for "similar screenshots" grouping ----
        item.pHash = averageHash(bitmap)
    }

    /** Groups items whose hashes are within a small Hamming distance of each other. */
    fun groupBySimilarity(items: List<ScreenshotItem>, maxDistance: Int = 8) {
        val ungrouped = items.toMutableList()
        var groupCounter = 0
        while (ungrouped.isNotEmpty()) {
            val seed = ungrouped.removeAt(0)
            val groupKey = "grp_${groupCounter++}"
            seed.groupKey = groupKey
            val toRemove = mutableListOf<ScreenshotItem>()
            for (other in ungrouped) {
                if (hammingDistance(seed.pHash, other.pHash) <= maxDistance) {
                    other.groupKey = groupKey
                    toRemove.add(other)
                }
            }
            ungrouped.removeAll(toRemove)
        }
    }

    private fun loadDownscaled(resolver: ContentResolver, uri: Uri): Bitmap? {
        return try {
            resolver.openInputStream(uri)?.use { input ->
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeStream(input, null, opts)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Simple 8x8 average hash — good enough to cluster visually near-identical screenshots. */
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
        return hash
    }

    private fun hammingDistance(a: Long, b: Long): Int {
        return java.lang.Long.bitCount(a xor b)
    }
}
