package io.github.kgcaudit.reader.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.data.db.ReaderDatabase
import io.github.kgcaudit.reader.document.Annotation
import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.HighlightColor
import io.github.kgcaudit.reader.document.Locator
import kotlinx.coroutines.flow.first
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

    private fun writeVersion1(): Unit = writeVersion(1) { db ->
        db.execSQL(
            "INSERT INTO bookmarks (bookId, locator, orderMajor, orderMinor, orderPatch, snippet, createdAtEpochMs) " +
                "VALUES ('${book.value}', 'r:3:120', 3, 120, 0, '옛 책갈피', 5)",
        )
        db.execSQL("INSERT INTO progress (bookId, locator, percent, updatedAtEpochMs) VALUES ('${book.value}', 'r:3:130', 12.5, 6)")
    }

    private fun writeVersion(version: Int, fill: (SQLiteDatabase) -> Unit) {
        val schema = JSONObject(File("schemas/io.github.kgcaudit.reader.data.db.ReaderDatabase/$version.json").readText())
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
            fill(db)
            db.version = version
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
            assertEquals(7, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }

    @Test
    fun `upgrading from version 2 keeps the recent shelf and every book starts as being read`() = runTest {
        // 0.22 까지(스키마 2)의 최근 목록에는 다 읽은 때가 없다. 올린 뒤 모두 "읽는 중" 으로 남고, 다 읽음을 적을 수 있다.
        writeVersion(2) { db ->
            db.execSQL(
                "INSERT INTO books (id, folderUri, displayName, format, sizeBytes, lastModifiedEpochMs, title, author, addedAtEpochMs, missing) " +
                    "VALUES ('${book.value}', 'content://books/tree/root', 'a.epub', 'EPUB', 10, 1, NULL, NULL, 1, 0)",
            )
            db.execSQL("INSERT INTO recent (bookId, openedAtEpochMs) VALUES ('${book.value}', 7)")
        }
        val db = ReaderDatabase.open(context)
        try {
            val library = io.github.kgcaudit.reader.data.library.Library(db)
            val shelf = library.shelf().first()
            assertEquals(listOf("a.epub"), shelf.map { it.book.displayName })
            assertEquals(null, shelf.single().finishedAtEpochMs)
            library.setFinished(book, 9, nowEpochMs = 9)
            assertEquals(9L, library.shelf().first().single().finishedAtEpochMs)
        } finally {
            db.close()
        }
    }

    @Test
    fun `upgrading from version 3 keeps the books and starts with an empty comic shelf`() = runTest {
        // 0.32 까지(스키마 3)에는 만화 표가 없다. 올린 뒤 책 · 진도는 그대로이고, 만화를 훑어 넣을 수 있어야 한다.
        writeVersion(3) { db ->
            db.execSQL(
                "INSERT INTO books (id, folderUri, displayName, format, sizeBytes, lastModifiedEpochMs, title, author, addedAtEpochMs, missing) " +
                    "VALUES ('${book.value}', 'content://books/tree/root', 'a.epub', 'EPUB', 10, 1, NULL, NULL, 1, 0)",
            )
            db.execSQL("INSERT INTO progress (bookId, locator, percent, updatedAtEpochMs) VALUES ('${book.value}', 'r:3:130', 12.5, 6)")
        }
        val db = ReaderDatabase.open(context)
        try {
            assertEquals(listOf("a.epub"), io.github.kgcaudit.reader.data.library.Library(db).books().first().map { it.displayName })
            assertEquals(Locator.Reflow(3, 130), RoomProgressRepository(db.progress()).get(book)?.locator)
            val comics = io.github.kgcaudit.reader.data.library.ComicLibrary(db)
            assertEquals(emptyList(), comics.works().first())
            val unit = io.github.kgcaudit.reader.data.library.ScannedComic(
                "content://c/별 01권.cbz", "별 01권.cbz", listOf("C"), io.github.kgcaudit.reader.document.comic.ComicUnitKind.ARCHIVE, "cbz", 10, 1,
            )
            comics.applyScan("content://c", io.github.kgcaudit.reader.data.library.ScanResult(emptyList(), true, listOf(unit)), 5)
            assertEquals(listOf("별"), comics.works().first().map { it.title })
            assertEquals(7, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }

    @Test
    fun `upgrading from version 4 keeps the comics and their hand fixes and starts with no reading position`() = runTest {
        // 0.33 (스키마 4)에는 만화 진도 표가 없다. 올린 뒤 만화 · 손 고침은 그대로이고, 진도 · 책갈피를 적을 수 있어야 한다.
        writeVersion(4) { db ->
            db.execSQL(
                "INSERT INTO comic_units (id, folderUri, name, kind, extension, folders, sizeBytes, lastModifiedEpochMs, addedAtEpochMs, missing, probed, notComic) " +
                    "VALUES ('content://c/a.cbz', 'content://c', '별 01권.cbz', 'ARCHIVE', 'cbz', 'C', 10, 1, 1, 0, 1, 0)",
            )
            db.execSQL("INSERT INTO comic_overrides (kind, subject, value) VALUES ('TITLE', '별', '별(보관용)')")
        }
        val db = ReaderDatabase.open(context)
        try {
            val comics = io.github.kgcaudit.reader.data.library.ComicLibrary(db)
            assertEquals(listOf("별(보관용)"), comics.works().first().map { it.title })
            assertEquals(null, comics.progressOf("content://c/a.cbz"))
            comics.saveProgress("content://c/a.cbz", 4, 10, 7)
            assertEquals(4, comics.progressOf("content://c/a.cbz")?.page)
            assertEquals(true, comics.toggleBookmark("content://c/a.cbz", 4, 7))
            assertEquals(7, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }

    @Test
    fun `upgrading from version 5 keeps comic reading positions and adds the webtoon offset`() = runTest {
        writeVersion(5) { db ->
            db.execSQL("INSERT INTO comic_progress (unitId, page, pageCount, updatedAtEpochMs, finishedAtEpochMs) VALUES ('content://c/a.cbz', 4, 10, 7, NULL)")
        }
        val db = ReaderDatabase.open(context)
        try {
            val comics = io.github.kgcaudit.reader.data.library.ComicLibrary(db)
            val kept = comics.progressOf("content://c/a.cbz")
            assertEquals(4, kept?.page)
            assertEquals(null, kept?.offset, "옛 진도에 웹툰 위치가 생겼다")
            comics.saveProgress("content://c/a.cbz", 2, 10, 8, offset = 0.25f)
            assertEquals(0.25f, comics.progressOf("content://c/a.cbz")?.offset)
            assertEquals(7, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }

    @Test
    fun `upgrading from version 6 keeps every record and starts an empty reading time log`() = runTest {
        // 0.50 (스키마 6)에는 읽은 시간 표가 없다. 올린 뒤 진도 · 만화 진도는 그대로이고, 읽은 시간을 적고 읽을 수 있어야 한다.
        writeVersion(6) { db ->
            db.execSQL("INSERT INTO progress (bookId, locator, percent, updatedAtEpochMs) VALUES ('${book.value}', 'r:3:130', 12.5, 6)")
            db.execSQL("INSERT INTO comic_progress (unitId, page, pageCount, updatedAtEpochMs, finishedAtEpochMs, offset) VALUES ('content://c/a.cbz', 4, 10, 7, 9, NULL)")
        }
        val db = ReaderDatabase.open(context)
        try {
            assertEquals(Locator.Reflow(3, 130), RoomProgressRepository(db.progress()).get(book)?.locator)
            assertEquals(9L, io.github.kgcaudit.reader.data.library.ComicLibrary(db).progressOf("content://c/a.cbz")?.finishedAtEpochMs)
            val time = ReadingTime(db) { java.time.ZoneId.of("Asia/Seoul") }
            assertEquals(emptyList(), time.all())
            time.record(TimeItem.BOOK, book.value, TimeMode.READ, 60_000, 1_790_000_000_000)
            assertEquals(60_000, time.all().single().millis)
            assertEquals(7, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }
}
