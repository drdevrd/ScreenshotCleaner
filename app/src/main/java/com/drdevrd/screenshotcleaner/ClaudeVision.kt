package com.drdevrd.screenshotcleaner

import android.graphics.Bitmap
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Optional deep categorization via the Anthropic Claude API. Runs only when
 * the user explicitly triggers Deep Categorize with their own API key.
 *
 * Photos are sent to Anthropic's servers for this feature. Not used unless
 * the user opts in. All other analysis (OCR, scene labeling, hashing, cache)
 * remains fully on-device.
 */
object ClaudeVision {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
    private const val MODEL = "claude-haiku-4-5-20251001"
    private const val ANTHROPIC_VERSION = "2023-06-01"

    private val PROMPT = """
Look at this image and pick the single best category for it. Answer with ONLY the category name in ALL_CAPS from the list. No punctuation, no explanation, no other words.

Categories:
- OTP_CODE: verification codes, one-time passwords, 2FA
- ID_CARD: voter ID, passport, Aadhaar, driving licence, PAN card, other government ID
- BILL_RECEIPT: bills, invoices, receipts, payment confirmations, prescriptions, medical reports
- CHAT: WhatsApp, Telegram, SMS or other messaging screenshots
- DOCUMENT: text-heavy documents, handwritten notes, articles, forms, letters
- PRODUCT_AD: shopping pages, product listings, advertisements, promotional offers
- PEOPLE: photos with people or faces prominently visible
- PLACES: buildings, streets, monuments, house or hospital exteriors, rooms, interiors, tiles, floors
- FOOD: meals, drinks, dishes, cooking
- NATURE: landscapes, trees, water, mountains, sky, plants, flowers
- VEHICLE: cars, bikes, trucks, planes, boats
- ANIMAL: pets, wildlife, birds, fish
- OTHER: anything not clearly fitting the categories above

Answer:
""".trimIndent()

    /**
     * Returns the categorized [Category] for a bitmap, or null if the API
     * call fails. Runs synchronously — call from a background dispatcher.
     */
    fun categorize(apiKey: String, bitmap: Bitmap): Category? {
        val scaled = downscaleForApi(bitmap)
        val base64 = bitmapToBase64Jpeg(scaled)
        if (scaled !== bitmap) scaled.recycle()

        val bodyJson = JSONObject().apply {
            put("model", MODEL)
            put("max_tokens", 20)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", JSONArray()
                    .put(JSONObject().apply {
                        put("type", "image")
                        put("source", JSONObject().apply {
                            put("type", "base64")
                            put("media_type", "image/jpeg")
                            put("data", base64)
                        })
                    })
                    .put(JSONObject().apply {
                        put("type", "text")
                        put("text", PROMPT)
                    })
                )
            }))
        }

        val request = Request.Builder()
            .url(ENDPOINT)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .addHeader("content-type", "application/json")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val responseBody = response.body?.string() ?: return null
                val json = JSONObject(responseBody)
                val contentArray = json.optJSONArray("content") ?: return null
                if (contentArray.length() == 0) return null
                val text = contentArray.getJSONObject(0).optString("text", "")
                parseCategory(text)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseCategory(text: String): Category? {
        val cleaned = text.trim().uppercase().replace(Regex("[^A-Z_]"), "")
        if (cleaned.isEmpty()) return null
        return try {
            Category.valueOf(cleaned)
        } catch (_: Exception) {
            null
        }
    }

    private fun downscaleForApi(bitmap: Bitmap): Bitmap {
        val maxDim = 1024
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxDim && h <= maxDim) return bitmap
        val ratio = maxDim.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(bitmap, (w * ratio).toInt(), (h * ratio).toInt(), true)
    }

    private fun bitmapToBase64Jpeg(bitmap: Bitmap): String {
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output)
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }
}
