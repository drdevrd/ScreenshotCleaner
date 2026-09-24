package com.drdevrd.screenshotcleaner

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class CachedAnalysis(
    val ocrText: String,
    val category: Category,
    val pHash: Long
)

/**
 * Local SQLite cache for analysis results, keyed by (media_id, media_type).
 * A file, once analyzed, never needs re-analysis unless the user forces it.
 */
class MediaCacheDb(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE analysis (
                media_id   INTEGER NOT NULL,
                media_type TEXT    NOT NULL,
                ocr_text   TEXT,
                category   TEXT,
                phash      INTEGER,
                analyzed_at INTEGER,
                PRIMARY KEY (media_id, media_type)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_type ON analysis(media_type)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS analysis")
        onCreate(db)
    }

    fun getAllForType(type: MediaType): Map<Long, CachedAnalysis> {
        val map = HashMap<Long, CachedAnalysis>()
        readableDatabase.rawQuery(
            "SELECT media_id, ocr_text, category, phash FROM analysis WHERE media_type = ?",
            arrayOf(type.name)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val ocrText = cursor.getString(1) ?: ""
                val categoryName = cursor.getString(2) ?: "OTHER"
                val phash = cursor.getLong(3)
                val category = try { Category.valueOf(categoryName) } catch (_: Exception) { Category.OTHER }
                map[id] = CachedAnalysis(ocrText, category, phash)
            }
        }
        return map
    }

    fun save(item: MediaItem) {
        val values = ContentValues().apply {
            put("media_id", item.id)
            put("media_type", item.type.name)
            put("ocr_text", item.ocrText)
            put("category", item.category.name)
            put("phash", item.pHash)
            put("analyzed_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "analysis", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun clearAll() {
        writableDatabase.execSQL("DELETE FROM analysis")
    }

    companion object {
        private const val DB_NAME = "media_cache.db"
        private const val DB_VERSION = 1
    }
}
