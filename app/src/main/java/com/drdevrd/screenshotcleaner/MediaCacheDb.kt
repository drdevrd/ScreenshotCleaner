package com.drdevrd.screenshotcleaner

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class CachedAnalysis(
    val ocrText: String,
    val labels: String,
    val category: Category,
    val pHash: Long,
    val embedding: FloatArray?
)

/**
 * Local SQLite cache. Version history:
 *  v1 - initial: id, type, ocr_text, category, phash
 *  v2 - added: labels column
 *  v3 - added: embedding BLOB column (CLIP 512-dim vector for semantic search)
 */
class MediaCacheDb(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE analysis (
                media_id   INTEGER NOT NULL,
                media_type TEXT    NOT NULL,
                ocr_text   TEXT,
                labels     TEXT,
                category   TEXT,
                phash      INTEGER,
                embedding  BLOB,
                analyzed_at INTEGER,
                PRIMARY KEY (media_id, media_type)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_type ON analysis(media_type)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            try { db.execSQL("ALTER TABLE analysis ADD COLUMN labels TEXT") } catch (_: Exception) {}
        }
        if (oldVersion < 3) {
            try { db.execSQL("ALTER TABLE analysis ADD COLUMN embedding BLOB") } catch (_: Exception) {}
        }
    }

    fun getAllForType(type: MediaType): Map<Long, CachedAnalysis> {
        val map = HashMap<Long, CachedAnalysis>()
        readableDatabase.rawQuery(
            "SELECT media_id, ocr_text, labels, category, phash, embedding FROM analysis WHERE media_type = ?",
            arrayOf(type.name)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val ocrText = cursor.getString(1) ?: ""
                val labels = cursor.getString(2) ?: ""
                val categoryName = cursor.getString(3) ?: "OTHER"
                val phash = cursor.getLong(4)
                val embeddingBytes: ByteArray? = if (cursor.isNull(5)) null else cursor.getBlob(5)
                val embedding = embeddingBytes?.let { ClipEncoder.bytesToEmbedding(it) }
                val category = try { Category.valueOf(categoryName) } catch (_: Exception) { Category.OTHER }
                map[id] = CachedAnalysis(ocrText, labels, category, phash, embedding)
            }
        }
        return map
    }

    fun save(item: MediaItem) {
        val values = ContentValues().apply {
            put("media_id", item.id)
            put("media_type", item.type.name)
            put("ocr_text", item.ocrText)
            put("labels", item.labels)
            put("category", item.category.name)
            put("phash", item.pHash)
            item.embedding?.let { put("embedding", ClipEncoder.embeddingToBytes(it)) }
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
        private const val DB_VERSION = 3
    }
}
