package io.github.kgcaudit.reader.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.data.db.BookmarkEntity
import io.github.kgcaudit.reader.data.db.ProgressEntity
import io.github.kgcaudit.reader.data.db.ReaderDatabase
import io.github.kgcaudit.reader.data.library.ComicLibrary
import io.github.kgcaudit.reader.data.library.Library
import io.github.kgcaudit.reader.data.library.ScanResult
import io.github.kgcaudit.reader.data.library.ScannedBook
import io.github.kgcaudit.reader.data.library.ScannedComic
import io.github.kgcaudit.reader.document.BookFormat
import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.document.comic.ComicUnitKind
import io.github.kgcaudit.reader.document.comic.ComicView
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 파일을 옮겨도 읽던 자리가 따라온다(0.50.0). 열쇠가 파일 주소라, 폴더를 옮기거나 이름을 바꾸면 진도 · 책갈피 · 형광펜이 숨은
 * 옛 행에 남고 새 자리는 처음부터였다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MovedRecordsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir = createTempDirectory("moved-records").toFile()
    private val db: ReaderDatabase = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build()
    private val library = Library(db)
    private val comics = ComicLibrary(db)
    private val records = RecordsBackup(db, File(dir, "pending.json")) { 5_000L }

    @After
    fun close() {
        db.close()
        dir.deleteRecursively()
    }

    private fun book(folder: String, name: String, size: Long?) = ScannedBook("$folder/$name", name, BookFormat.EPUB, size, 1L)

    private suspend fun scanBooks(folder: String, vararg books: ScannedBook) =
        library.applyScan(folder, ScanResult(books.toList(), complete = true), 1L)

    private suspend fun readTo(id: String, chapter: Int) {
        db.progress().upsert(ProgressEntity(id, Locator.encode(Locator.Reflow(chapter, 100)), 40f, 2_000L))
        db.bookmarks().insert(BookmarkEntity(0, id, Locator.encode(Locator.Reflow(chapter, 50)), chapter, 50, 0, "책갈피", 2_000L))
        library.markOpened(BookId(id), 2_000L)
    }

    @Test
    fun `a book moved to another folder keeps its place and bookmarks`() = runTest {
        scanBooks("A", book("A", "어린 왕자.epub", 9000))
        readTo("A/어린 왕자.epub", 3)
        // 같은 파일을 B 로 옮겼다: A 에서는 사라지고 B 에서 나타났다.
        scanBooks("A")
        scanBooks("B", book("B", "어린 왕자.epub", 9000))

        val moved = records.adoptMoved()
        assertEquals(listOf("A/어린 왕자.epub" to "B/어린 왕자.epub"), moved.books)
        assertEquals(Locator.Reflow(3, 100), Locator.decodeOrNull(db.progress().get("B/어린 왕자.epub")!!.locator))
        assertEquals(1, db.bookmarks().forBook("B/어린 왕자.epub").size)
        // 책장(읽는 중)에도 새 자리로 선다. 옛 자리는 비었다 — 두 번 잇지 않는다.
        assertEquals(listOf("어린 왕자.epub"), library.shelf().first().map { it.book.displayName })
        assertNull(db.progress().get("A/어린 왕자.epub"))
        assertTrue(records.adoptMoved().isEmpty)
    }

    @Test
    fun `a book read at the new place too keeps the further position and both bookmarks`() = runTest {
        scanBooks("A", book("A", "책.epub", 100))
        readTo("A/책.epub", 5)
        scanBooks("A")
        scanBooks("B", book("B", "책.epub", 100))
        // 기록을 잇기 전에 새 자리에서 앞부분을 조금 읽었다.
        db.progress().upsert(ProgressEntity("B/책.epub", Locator.encode(Locator.Reflow(1, 0)), 5f, 3_000L))
        db.bookmarks().insert(BookmarkEntity(0, "B/책.epub", Locator.encode(Locator.Reflow(1, 0)), 1, 0, 0, null, 3_000L))

        records.adoptMoved()
        assertEquals(Locator.Reflow(5, 100), Locator.decodeOrNull(db.progress().get("B/책.epub")!!.locator))
        assertEquals(2, db.bookmarks().forBook("B/책.epub").size)
    }

    @Test
    fun `nothing moves when the match is not certain`() = runTest {
        // 망가뜨린 경우 1: 같은 이름 · 크기가 새 쪽에 둘 — 어느 쪽인지 모른다.
        scanBooks("A", book("A", "1권.epub", 100))
        readTo("A/1권.epub", 2)
        scanBooks("A")
        scanBooks("B", book("B", "1권.epub", 100), book("B", "사본/1권.epub", 100).copy(displayName = "1권.epub"))
        // 망가뜨린 경우 2: 크기를 모르는 책 — 이름만 같은 다른 판일 수 있다.
        scanBooks("C", book("C", "2권.epub", null))
        readTo("C/2권.epub", 4)
        scanBooks("C")
        scanBooks("D", book("D", "2권.epub", null))
        // 망가뜨린 경우 3: 크기가 다르면 다른 판이다.
        scanBooks("E", book("E", "3권.epub", 100))
        readTo("E/3권.epub", 4)
        scanBooks("E")
        scanBooks("F", book("F", "3권.epub", 101))

        assertTrue(records.adoptMoved().isEmpty)
        assertEquals(Locator.Reflow(2, 100), Locator.decodeOrNull(db.progress().get("A/1권.epub")!!.locator))
        assertNull(db.progress().get("B/1권.epub"))
        assertNull(db.progress().get("D/2권.epub"))
        assertNull(db.progress().get("F/3권.epub"))
    }

    @Test
    fun `two vanished copies of the same file are not guessed between`() = runTest {
        // 망가뜨린 경우: 같은 이름 · 크기의 책이 두 곳에서 사라졌고 하나만 나타났다 — 어느 쪽 기록인지 모른다.
        scanBooks("A", book("A", "책.epub", 100))
        scanBooks("B", book("B", "책.epub", 100))
        readTo("A/책.epub", 2)
        readTo("B/책.epub", 7)
        scanBooks("A")
        scanBooks("B")
        scanBooks("C", book("C", "책.epub", 100))
        assertTrue(records.adoptMoved().isEmpty)
        assertNull(db.progress().get("C/책.epub"))
    }

    @Test
    fun `a book still visible in its old place is a copy and keeps its own records`() = runTest {
        scanBooks("A", book("A", "책.epub", 100))
        readTo("A/책.epub", 5)
        scanBooks("B", book("B", "책.epub", 100))
        assertTrue(records.adoptMoved().isEmpty)
        assertNull(db.progress().get("B/책.epub"))
    }

    private fun comic(folder: String, name: String, size: Long?, kind: ComicUnitKind = ComicUnitKind.ARCHIVE) =
        ScannedComic("$folder/$name", name, listOf("Comic"), kind, if (kind == ComicUnitKind.ARCHIVE) "cbz" else "", size, 1L)

    private suspend fun scanComics(folder: String, vararg units: ScannedComic) =
        comics.applyScan(folder, ScanResult(emptyList(), complete = true, units.toList()), 1L)

    @Test
    fun `a moved comic volume keeps its page, bookmarks and the volume pulled out of its work`() = runTest {
        scanComics("A", comic("A", "별 1권.cbz", 5000))
        comics.saveProgress("A/별 1권.cbz", 40, 120, 2_000)
        comics.toggleBookmark("A/별 1권.cbz", 12, 2_000)
        // 이 권을 작품에서 따로 빼고 보는 방식을 정했다 — 그 작품 열쇠에는 단위 주소가 든다.
        val entry = comics.works().first().single().entries.single()
        comics.split(entry)
        val own = comics.works().first().single()
        comics.setView(own, ComicView.WEBTOON)

        scanComics("A")
        scanComics("B", comic("B", "별 1권.cbz", 5000))
        assertEquals(listOf("A/별 1권.cbz" to "B/별 1권.cbz"), records.adoptMoved().comics)

        assertEquals(40, comics.progressOf("B/별 1권.cbz")?.page)
        assertEquals(listOf(12), comics.bookmarks("B/별 1권.cbz").first())
        // 따로 뺀 작품 · 그 작품의 보는 방식도 따라온다.
        val work = comics.works().first().single()
        assertEquals(ComicLibrary.OWN_PREFIX + "B/별 1권.cbz", work.key)
        assertEquals(ComicView.WEBTOON, work.view)
    }

    @Test
    fun `a picture folder is matched by its page count because it has no size`() = runTest {
        scanComics("A", comic("A", "제1화", null, ComicUnitKind.IMAGE_FOLDER))
        db.comics().unitsOf(listOf("A/제1화")).single().let { db.comics().upsert(listOf(it.copy(pageCount = 30))) }
        comics.saveProgress("A/제1화", 7, 30, 2_000)
        scanComics("A")
        scanComics("B", comic("B", "제1화", null, ComicUnitKind.IMAGE_FOLDER))
        // 쪽 수를 모르면(아직 안 셈) 옮기지 않는다.
        assertTrue(records.adoptMoved().isEmpty)
        db.comics().unitsOf(listOf("B/제1화")).single().let { db.comics().upsert(listOf(it.copy(pageCount = 30))) }
        assertEquals(listOf("A/제1화" to "B/제1화"), records.adoptMoved().comics)
        assertEquals(7, comics.progressOf("B/제1화")?.page)
    }
}
