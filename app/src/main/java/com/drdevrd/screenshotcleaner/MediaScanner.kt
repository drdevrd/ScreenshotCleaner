package com.drdevrd.screenshotcleaner

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore

object MediaScanner {

    /** Screenshots: images whose folder (bucket) is exactly "Screenshots". */
    fun scanScreenshots(resolver: ContentResolver): List<MediaItem> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED
        )
        val selection = "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?"
        val args = arrayOf("Screenshots")
        return queryImages(resolver, collection, projection, selection, args, MediaType.SCREENSHOT)
    }

    /** Camera / gallery photos: everything whose folder is NOT "Screenshots". */
    fun scanPhotos(resolver: ContentResolver): List<MediaItem> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED
        )
        val selection = "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} IS NULL OR " +
                "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} != ?"
        val args = arrayOf("Screenshots")
        return queryImages(resolver, collection, projection, selection, args, MediaType.PHOTO)
    }

    /** All videos on the device. */
    fun scanVideos(resolver: ContentResolver): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATE_ADDED
        )
        resolver.query(
            collection, projection, null, null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val dateAdded = cursor.getLong(dateCol)
                items.add(
                    MediaItem(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        type = MediaType.VIDEO,
                        dateAddedSec = dateAdded
                    )
                )
            }
        }
        return items
    }

    private fun queryImages(
        resolver: ContentResolver,
        collection: Uri,
        projection: Array<String>,
        selection: String,
        args: Array<String>,
        type: MediaType
    ): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        resolver.query(
            collection, projection, selection, args,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val dateAdded = cursor.getLong(dateCol)
                items.add(
                    MediaItem(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        type = type,
                        dateAddedSec = dateAdded
                    )
                )
            }
        }
        return items
    }
}
