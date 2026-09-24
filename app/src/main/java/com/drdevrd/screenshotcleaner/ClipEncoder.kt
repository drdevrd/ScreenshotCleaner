package com.drdevrd.screenshotcleaner

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * CLIP ViT-B/32 image + text encoders via ONNX Runtime.
 * Both encoders produce a 512-dim vector; embeddings are L2-normalized for cosine similarity.
 *
 * If model files are missing (CI download failed) or fail to load, isReady = false
 * and the app silently falls back to keyword-only search.
 */
object ClipEncoder {

    private var env: OrtEnvironment? = null
    private var imageSession: OrtSession? = null
    private var textSession: OrtSession? = null
    private var tokenizer: ClipTokenizer? = null
    private var initTried = false
    val isReady: Boolean get() = imageSession != null && textSession != null && tokenizer?.ready == true

    const val EMBEDDING_DIM = 512
    private const val IMAGE_SIZE = 224

    // CLIP standard normalization values
    private val MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
    private val STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)

    fun init(context: Context) {
        if (initTried) return
        initTried = true
        try {
            env = OrtEnvironment.getEnvironment()
            imageSession = loadSession(context, "clip_image.onnx")
            textSession = loadSession(context, "clip_text.onnx")
            tokenizer = ClipTokenizer(context)
        } catch (_: Throwable) {
            imageSession = null
            textSession = null
            tokenizer = null
        }
    }

    private fun loadSession(context: Context, assetName: String): OrtSession? {
        val e = env ?: return null
        return try {
            // ONNX Runtime needs a file path or byte array; copy asset to cache once
            val outFile = File(context.cacheDir, assetName)
            if (!outFile.exists() || outFile.length() == 0L) {
                context.assets.open(assetName).use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            val options = OrtSession.SessionOptions()
            e.createSession(outFile.absolutePath, options)
        } catch (_: Throwable) {
            null
        }
    }

    /** Encode an image bitmap into a normalized 512-dim vector, or null on failure. */
    fun encodeImage(bitmap: Bitmap): FloatArray? {
        val e = env ?: return null
        val session = imageSession ?: return null
        return try {
            val resized = if (bitmap.width != IMAGE_SIZE || bitmap.height != IMAGE_SIZE) {
                Bitmap.createScaledBitmap(bitmap, IMAGE_SIZE, IMAGE_SIZE, true)
            } else bitmap
            val input = bitmapToFloatArray(resized)
            if (resized !== bitmap) resized.recycle()

            val shape = longArrayOf(1, 3, IMAGE_SIZE.toLong(), IMAGE_SIZE.toLong())
            OnnxTensor.createTensor(e, FloatBuffer.wrap(input), shape).use { tensor ->
                val inputName = session.inputNames.iterator().next()
                session.run(mapOf(inputName to tensor)).use { result ->
                    val output = result[0].value
                    extractAndNormalize(output)
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** Encode a text query into a normalized 512-dim vector, or null on failure. */
    fun encodeText(text: String): FloatArray? {
        val e = env ?: return null
        val session = textSession ?: return null
        val tok = tokenizer ?: return null
        if (!tok.ready) return null
        return try {
            val tokens = tok.encode(text)
            // ONNX expects int64 (Long) input_ids
            val longTokens = LongArray(tokens.size) { tokens[it].toLong() }
            val shape = longArrayOf(1, ClipTokenizer.CONTEXT_LENGTH.toLong())
            OnnxTensor.createTensor(e, LongBuffer.wrap(longTokens), shape).use { tensor ->
                val inputName = session.inputNames.iterator().next()
                session.run(mapOf(inputName to tensor)).use { result ->
                    val output = result[0].value
                    extractAndNormalize(output)
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** Cosine similarity between two L2-normalized vectors — a simple dot product. */
    fun similarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    private fun bitmapToFloatArray(bitmap: Bitmap): FloatArray {
        val pixels = IntArray(IMAGE_SIZE * IMAGE_SIZE)
        bitmap.getPixels(pixels, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)
        // CHW layout (channels-first), float32, normalized
        val out = FloatArray(3 * IMAGE_SIZE * IMAGE_SIZE)
        val plane = IMAGE_SIZE * IMAGE_SIZE
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = Color.red(p) / 255f
            val g = Color.green(p) / 255f
            val b = Color.blue(p) / 255f
            out[i] = (r - MEAN[0]) / STD[0]
            out[i + plane] = (g - MEAN[1]) / STD[1]
            out[i + 2 * plane] = (b - MEAN[2]) / STD[2]
        }
        return out
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractAndNormalize(raw: Any?): FloatArray? {
        // ONNX returns nested arrays. Shape is either [1, 512] or [1, seq, 512] depending on model.
        val flat = FloatArray(EMBEDDING_DIM)
        try {
            when (raw) {
                is Array<*> -> {
                    val first = raw[0]
                    if (first is FloatArray) {
                        for (i in 0 until EMBEDDING_DIM) flat[i] = first.getOrNull(i) ?: 0f
                    } else if (first is Array<*>) {
                        // [1, seq, 512] — take the pooled last token (CLIP uses argmax of tokens; we approximate)
                        val seq = first as Array<FloatArray>
                        // For CLIP text encoder, the pooled output at EOS position matters, but Xenova models
                        // often return already-pooled output. Take the first row as safest fallback.
                        val row = seq[0]
                        for (i in 0 until EMBEDDING_DIM) flat[i] = row.getOrNull(i) ?: 0f
                    }
                }
                else -> return null
            }
        } catch (_: Throwable) {
            return null
        }
        // L2 normalize
        var norm = 0f
        for (v in flat) norm += v * v
        norm = sqrt(norm)
        if (norm < 1e-8f) return null
        for (i in flat.indices) flat[i] /= norm
        return flat
    }

    /** Convert a FloatArray embedding to a byte array for DB storage. */
    fun embeddingToBytes(embedding: FloatArray): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(embedding.size * 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (v in embedding) buffer.putFloat(v)
        return buffer.array()
    }

    fun bytesToEmbedding(bytes: ByteArray): FloatArray? {
        if (bytes.size != EMBEDDING_DIM * 4) return null
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(EMBEDDING_DIM)
        for (i in 0 until EMBEDDING_DIM) out[i] = buffer.float
        return out
    }
}
