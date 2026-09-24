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
    private val idCardRegex = Regex(
        """(election\s*commission|voter\s*id|elector[' ]?s?\s*name|\bpassport\s*(no|number)?\b|republic\s*of\s*india|\baadhaar\b|\baadhar\b|unique\s*identification|\bpan\s*card\b|permanent\s*account\s*number|driving\s*licen|driver[' ]?s?\s*licen|husband[' ]?s?\s*name|\bidentity\s*card\b|\bidentification\s*card\b|govt\.?\s*of\s*india\b|government\s*of\s*india)""",
        RegexOption.IGNORE_CASE
    )
    private val paymentRegex = Regex(
        """(₹\s*\d|\brs\.?\s*\d|\binr\s*\d|\bpaid\s+(successfully|to|₹|rs)|\bpayment\b|\bdebited\b|\bcredited\b|\bupi\b|\btransaction\s*(id|no)|\binvoice\s*(no|number|#|:)|\breceipt\s*(no|number|#|:)|\bamount\s*[:\-]?\s*[₹rs\d]|tax\s*invoice|\bgst\s*(no|number|in|:)|\bbill\s*(no|number|:))""",
        RegexOption.IGNORE_CASE
    )
    private val chatRegex = Regex("""(whatsapp|typing\.\.\.|online|last seen|delivered|seen at|:\)|😂|😊|👍|❤️)""", RegexOption.IGNORE_CASE)
    private val productAdRegex = Regex("""(add to cart|buy now|rating|reviews?|shop now|₹\d+\s*off|% off|delivery|shipping|amazon|flipkart)""", RegexOption.IGNORE_CASE)

    // -------- Scene label groups (matched case-insensitive) --------
    // PEOPLE — includes ImageNet indirect signals (people wear suits, hold microphones, etc.)
    private val personLabels = setOf(
        "person", "face", "smile", "child", "portrait", "people", "baby", "selfie",
        // ImageNet indirect: attire and event-context
        "suit", "tuxedo", "military uniform", "academic gown", "gown", "necktie", "bow tie",
        "microphone", "podium", "stage", "ballplayer", "groom", "bride", "wig", "sunglasses"
    )
    // Explicit document / paper indicators — checked before people so handwritten notes don't get filed as People
    private val documentLabels = setOf(
        "paper", "handwriting", "writing", "notebook", "envelope", "font", "letter",
        "document", "text", "book", "page", "receipt",
        // ImageNet
        "menu", "web site", "crossword puzzle", "scoreboard", "notebook computer"
    )
    private val foodLabels = setOf(
        "food", "dish", "cuisine", "meal", "dessert", "fruit", "vegetable", "drink",
        "coffee", "tea", "cake", "bread",
        // ImageNet specifics
        "pizza", "hamburger", "hotdog", "burrito", "guacamole", "banana", "orange", "apple",
        "strawberry", "lemon", "pineapple", "mushroom", "broccoli", "ice cream", "espresso",
        "cheeseburger", "carbonara", "chocolate sauce", "trifle", "consomme", "hot pot",
        "meat loaf", "plate"
    )
    private val placeLabels = setOf(
        "building", "skyscraper", "architecture", "house", "monument", "temple", "church",
        "street", "road", "city", "bridge", "tower", "castle", "room", "interior",
        "bathroom", "kitchen", "wall", "floor", "tile", "ceiling",
        // ImageNet
        "palace", "mosque", "boathouse", "barn", "greenhouse", "library", "supermarket",
        "restaurant", "movie theater", "beach house", "prison", "planetarium", "obelisk",
        "fountain", "cliff dwelling"
    )
    private val natureLabels = setOf(
        "plant", "tree", "flower", "leaf", "landscape", "water", "sky", "mountain",
        "beach", "sea", "forest", "sunset", "sunrise", "cloud", "grass", "garden",
        // ImageNet
        "daisy", "rose", "sunflower", "tulip", "orchid", "cabbage", "coral reef", "geyser",
        "lakeshore", "seashore", "valley", "volcano", "alp", "sandbar", "promontory"
    )
    private val vehicleLabels = setOf(
        "car", "vehicle", "motorcycle", "bicycle", "bike", "truck", "bus", "train",
        "airplane", "boat", "ship",
        // ImageNet
        "sports car", "convertible", "minivan", "ambulance", "beach wagon", "cab",
        "fire engine", "garbage truck", "jeep", "limousine", "pickup", "police van",
        "trailer truck", "moving van", "school bus", "tow truck", "trolleybus",
        "mountain bike", "unicycle", "moped", "scooter", "canoe", "yawl", "catamaran",
        "container ship", "fireboat", "lifeboat", "speedboat", "gondola", "aircraft carrier",
        "airliner", "warplane", "space shuttle", "helicopter", "balloon"
    )
    private val animalLabels = setOf(
        "dog", "cat", "bird", "animal", "pet", "wildlife", "fish", "insect", "butterfly",
        "cow", "horse",
        // ImageNet — common breeds and animals
        "puppy", "kitten", "golden retriever", "labrador", "german shepherd", "poodle",
        "bulldog", "beagle", "husky", "chihuahua", "pug", "rottweiler", "dalmatian",
        "persian cat", "siamese cat", "tabby", "tiger cat",
        "elephant", "zebra", "giraffe", "lion", "tiger", "bear", "monkey", "ape",
        "sheep", "goat", "pig", "rabbit", "squirrel", "hamster", "guinea pig",
        "eagle", "owl", "peacock", "parrot", "hen", "duck", "swan", "flamingo",
        "goldfish", "shark", "starfish", "jellyfish", "crab", "lobster",
        "spider", "bee", "ant", "grasshopper", "dragonfly", "beetle"
    )

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

            // Run ML Kit image labeling (general labels: person, food, dog, plant...)
            try {
                val results = labeler.process(image).await()
                results.forEach { labels.add(it.text.lowercase()) }
            } catch (_: Exception) { /* leave empty */ }
        }

        // Run TFLite EfficientNet classifier (1000 ImageNet classes: microphone, suit,
        // golden retriever, espresso...). Skipped silently if model didn't load.
        try {
            val tfLabels = TfLiteClassifier.classify(bitmap)
            tfLabels.forEach { labels.add(it) }
        } catch (_: Exception) { /* leave empty */ }

        // Dominant-color detection so "blue tiles", "green plant" etc. work in search
        try {
            val colors = detectDominantColors(bitmap)
            colors.forEach { labels.add(it) }
        } catch (_: Exception) { /* leave empty */ }

        item.labels = labels.joinToString(",")

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
            if (idCardRegex.containsMatchIn(text)) return Category.ID_CARD
            if (paymentRegex.containsMatchIn(text)) return Category.BILL_RECEIPT
            if (chatRegex.containsMatchIn(text)) return Category.CHAT
            if (productAdRegex.containsMatchIn(text)) return Category.PRODUCT_AD
        }

        // Scene-content categories
        // Strong PEOPLE signals win — a person / face / portrait means a people photo,
        // even if banners or signage in the frame contain text/font labels.
        val strongPerson = labels.any { it in setOf("person", "face", "portrait", "child", "baby", "selfie", "people") }
        if (strongPerson) return Category.PEOPLE

        // Strong document signals — paper / handwriting / notebook mean a document,
        // even if a hand is visible holding it.
        val strongDocument = labels.any { it in setOf("paper", "handwriting", "notebook", "envelope", "page", "letter") }
        if (strongDocument) return Category.DOCUMENT

        if (labels.any { it in foodLabels }) return Category.FOOD
        if (labels.any { it in vehicleLabels }) return Category.VEHICLE
        if (labels.any { it in animalLabels }) return Category.ANIMAL
        if (labels.any { it in placeLabels }) return Category.PLACES
        if (labels.any { it in natureLabels }) return Category.NATURE

        // Weaker signals — after scene categories
        if (labels.any { it in personLabels }) return Category.PEOPLE
        if (labels.any { it in documentLabels }) return Category.DOCUMENT

        // Text-heavy without a known pattern → generic document
        if (hasLotsOfText) return Category.DOCUMENT

        return Category.OTHER
    }

    /**
     * Samples 400 pixels from a downscaled version of the image and returns
     * every color name that makes up more than ~15% of the pixels. That gives
     * "blue" + "white" for a mostly-blue photo with a bit of grout, etc.
     */
    private fun detectDominantColors(src: Bitmap): Set<String> {
        val small = if (src.width > 20 || src.height > 20) {
            Bitmap.createScaledBitmap(src, 20, 20, true)
        } else src
        val counts = HashMap<String, Int>()
        for (y in 0 until small.height) {
            for (x in 0 until small.width) {
                val name = colorNameFor(small.getPixel(x, y))
                counts[name] = (counts[name] ?: 0) + 1
            }
        }
        if (small !== src) small.recycle()
        val total = counts.values.sum()
        val threshold = (total * 0.15).toInt().coerceAtLeast(1)
        return counts.filterValues { it >= threshold }.keys
    }

    /**
     * Maps an ARGB pixel to a human color name (red, orange, yellow, green,
     * cyan, blue, purple, pink, black, gray, white, brown).
     */
    private fun colorNameFor(argb: Int): String {
        val r = Color.red(argb)
        val g = Color.green(argb)
        val b = Color.blue(argb)
        val hsv = FloatArray(3)
        Color.RGBToHSV(r, g, b, hsv)
        val h = hsv[0]
        val s = hsv[1]
        val v = hsv[2]
        // Grayscale range
        if (s < 0.15f) {
            return when {
                v < 0.15f -> "black"
                v < 0.75f -> "gray"
                else -> "white"
            }
        }
        // Brownish tones — warm hue, low value, moderate saturation
        if (h in 15f..45f && v < 0.55f) return "brown"
        return when {
            h < 15f || h >= 345f -> "red"
            h < 45f -> "orange"
            h < 65f -> "yellow"
            h < 165f -> "green"
            h < 200f -> "cyan"
            h < 250f -> "blue"
            h < 290f -> "purple"
            h < 345f -> "pink"
            else -> "red"
        }
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

    /** Public helper to load a downscaled bitmap for a media item — used by Deep Categorize. */
    fun loadBitmap(context: android.content.Context, item: MediaItem): android.graphics.Bitmap? {
        return when (item.type) {
            MediaType.VIDEO -> loadVideoFrame(context, item.uri)
            else -> loadImageDownscaled(context.contentResolver, item.uri)
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
