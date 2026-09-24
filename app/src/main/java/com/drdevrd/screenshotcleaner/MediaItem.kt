package com.drdevrd.screenshotcleaner

import android.net.Uri

enum class MediaType { SCREENSHOT, PHOTO, VIDEO }

/**
 * What the media actually shows, decided by analyzing content (OCR text + image labels).
 * Not decided by folder location.
 */
enum class Category(val display: String) {
    OTP_CODE("OTP / Codes"),
    BILL_RECEIPT("Bills / Receipts"),
    CHAT("Chat / Messages"),
    DOCUMENT("Documents / Text"),
    PRODUCT_AD("Products / Ads"),
    PEOPLE("People"),
    PLACES("Places / Buildings"),
    FOOD("Food"),
    NATURE("Nature / Outdoors"),
    VEHICLE("Vehicles"),
    ANIMAL("Animals"),
    OTHER("Other")
}

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val type: MediaType,
    val dateAddedSec: Long,
    var ocrText: String = "",
    var category: Category = Category.OTHER,
    var groupKey: String = "",   // items with the same groupKey are "similar"
    var pHash: Long = 0L,
    var selected: Boolean = false
)
