package com.drdevrd.screenshotcleaner

import android.net.Uri

enum class MediaType { SCREENSHOT, PHOTO, VIDEO }

enum class Label(val display: String) {
    OTP_CODE("OTP / Code"),
    PAYMENT("Payment / Receipt"),
    CHAT("Chat / Message"),
    ARTICLE("Article / Long text"),
    OTHER("Other")
}

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val type: MediaType,
    val dateAddedSec: Long,
    var label: Label = Label.OTHER,
    var groupKey: String = "",   // items with the same groupKey are "similar"
    var pHash: Long = 0L,
    var selected: Boolean = false
)
