package io.github.kgcaudit.reader.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.data.db.ReaderDatabase
import io.github.kgcaudit.reader.document.Annotation
import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.HighlightColor
import io.github.kgcaudit.reader.document.Locator
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals

/**
 * 0.14 이하(스키마 1)를 쓰던 사람이 0.15 로 올려도 책갈피 · 진도가 남고 형광펜을 쓸 수 있는가.
 *
 * 옛 DB 는 저장소에 커밋된 `schemas/…/1.json` 의 SQL 로 **그대로** 만든다 — 지금 코드의 엔티티로 만들면
 * 옛 사용자의 파일이 아니라 새 파일을 시험하는 셈이다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val book = BookId("content://books/tree/root/document/root%2Fa.epub")

    @Before
    fun clean() {
        context.deleteDatabase(ReaderDatabase.FILE_NAME)
    }

    @After
    fun cleanUp() {
        context.deleteDatabase(ReaderDatabase.FILE_NAME)
    }

    private fun writeVersion1(): Unit {
        val schema = JSONObject(File("schemas/io.github.kgcaudit.reader.data.db.ReaderDatabase/1.json").readText())
            .getJSONObject("database")
        val file = context.getDatabasePath(ReaderDatabase.FILE_NAME).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL(
                "INSERT INTO bookmarks (bookId, locator, orderMajor, orderMinor, orderPatch, snippet, createdAtEpochMs) " +
                    "VALUES ('${book.value}', 'r:3:120', 3, 120, 0, '옛 책갈피', 5)",
            )
            db.execSQL("INSERT INTO progress (bookId, locator, percent, updatedAtEpochMs) VALUES ('${book.value}', 'r:3:130', 12.5, 6)")
            db.version = 1
        }
    }

    @Test
    fun `upgrading from version 1 keeps bookmarks and progress and adds highlights`() = runTest {
        writeVersion1()

        // 마이그레이션이 빠졌거나 틀리면 여기서 열다가 죽는다(fallbackToDestructiveMigration 을 쓰지 않는다).
        val db = ReaderDatabase.open(context)
        try {
            assertEquals(listOf(Locator.Reflow(3, 120)), RoomBookmarkRepository(db.bookmarks()).forBook(book).map { it.locator })
            assertEquals(Locator.Reflow(3, 130), RoomProgressRepository(db.progress()).get(book)?.locator)

            val notes = RoomAnnotationRepository(db.annotations())
            notes.add(Annotation(0, book, Locator.Reflow(3, 10), Locator.Reflow(3, 20), HighlightColor.Green, "메모", "칠한 글", 7))
            assertEquals(listOf("칠한 글"), notes.forBook(book).map { it.snippet })
            assertEquals(2, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }
}
