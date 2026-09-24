package com.drdevrd.screenshotcleaner

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageclassifier.ImageClassifier

/**
 * On-device TFLite image classifier using MediaPipe Tasks Vision.
 * Uses EfficientNet-Lite0 trained on ImageNet (1000 classes) bundled in assets.
 *
 * Adds finer-grained labels (e.g. "microphone", "podium", "necktie", "golden retriever")
 * on top of ML Kit's ~400 general labels. Both label sources are used together
 * for categorization and full-text search.
 *
 * If the model file isn't present in assets (CI download failed), classify()
 * silently returns an empty list and the app falls back to ML Kit only.
 */
object TfLiteClassifier {

    private var classifier: ImageClassifier? = null
    private var initTried: Boolean = false

    /** Call once, typically from Application/Activity onCreate. Safe to call repeatedly. */
    fun init(context: Context) {
        if (initTried) return
        initTried = true
        classifier = try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("classifier.tflite")
                .build()
            val options = ImageClassifier.ImageClassifierOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setMaxResults(5)
                .setScoreThreshold(0.15f)
                .build()
            ImageClassifier.createFromOptions(context.applicationContext, options)
        } catch (_: Throwable) {
            null
        }
    }

    /** Returns lowercase label names, or empty list on failure / when model isn't loaded. */
    fun classify(bitmap: Bitmap): List<String> {
        val c = classifier ?: return emptyList()
        return try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = c.classify(mpImage) ?: return emptyList()
            val classifications = result.classificationResult().classifications()
            if (classifications.isEmpty()) return emptyList()
            classifications[0].categories().mapNotNull { cat ->
                cat.categoryName()?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }
}
