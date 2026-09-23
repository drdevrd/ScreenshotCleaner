package com.drdevrd.screenshotcleaner

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore

object ScreenshotScanner {

    /**
     * Finds screenshots either by folder name (Screenshots) or filename pattern,
     * whichever the device/manufacturer uses. Read-only query via MediaStore —
     * no filesystem path guessing, works across Android versions.
     */
    fun scan(resolver: ContentResolver): List<ScreenshotItem> {
        val items = mutableListOf<ScreenshotItem>()

        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED
        )

        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR " +
                "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("%Screenshots%", "Screenshot_%")

        resolver.query(
            collection, projection, selection, args,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val dateAdded = cursor.getLong(dateCol)
                val uri: Uri = ContentUris.withAppendedId(collection, id)
                items.add(ScreenshotItem(id = id, uri = uri, dateAddedSec = dateAdded))
            }
        }
        return items
    }
}
