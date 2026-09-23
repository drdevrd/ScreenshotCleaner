package com.drdevrd.screenshotcleaner

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/**
 * All analysis is on-device:
 *  - ML Kit Text Recognition (bundled model) for screenshots only
 *  - Average-hash perceptual hash for similarity grouping (all media)
 * No network calls, no INTERNET permission declared.
 */
object Analyzer {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val otpRegex1 = Regex("""\b\d{4,8}\b.{0,20}(otp|code|verification|verify)""", RegexOption.IGNORE_CASE)
    private val otpRegex2 = Regex("""(otp|verification code|one[- ]time password).{0,20}\b\d{4,8}\b""", RegexOption.IGNORE_CASE)
    private val paymentRegex = Regex("""(₹|rs\.?|inr|paid|payment|debited|credited|upi|transaction|invoice|receipt|amount)""", RegexOption.IGNORE_CASE)
    private val chatRegex = Regex("""(whatsapp|typing\.\.\.|online|last seen|read \d|:\)|😂|😊)""", RegexOption.IGNORE_CASE)

    suspend fun analyze(context: Context, item: MediaItem) {
        val bitmap = when (item.type) {
            MediaType.VIDEO -> loadVideoFrame(context, item.uri)
            else -> loadImageDownscaled(context.contentResolver, item.uri)
        } ?: return

        if (item.type == MediaType.SCREENSHOT) {
            item.label = try {
                val image = InputImage.fromBitmap(bitmap, 0)
                val text = recognizer.process(image).await().text
                when {
                    otpRegex1.containsMatchIn(text) || otpRegex2.containsMatchIn(text) -> Label.OTP_CODE
                    paymentRegex.containsMatchIn(text) -> Label.PAYMENT
                    chatRegex.containsMatchIn(text) -> Label.CHAT
                    text.length > 400 -> Label.ARTICLE
                    else -> Label.OTHER
                }
            } catch (_: Exception) {
                Label.OTHER
            }
        }

        item.pHash = averageHash(bitmap)

        if (!bitmap.isRecycled) bitmap.recycle()
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
                val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
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
            if (frame != null && (frame.width > 400 || frame.height > 400)) {
                val scaled = Bitmap.createScaledBitmap(frame, frame.width / 4, frame.height / 4, true)
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
