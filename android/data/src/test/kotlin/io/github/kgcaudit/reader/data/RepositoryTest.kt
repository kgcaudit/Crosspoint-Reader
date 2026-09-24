package io.github.kgcaudit.reader.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.data.db.AnnotationEntity
import io.github.kgcaudit.reader.data.db.BookmarkEntity
import io.github.kgcaudit.reader.data.db.ProgressEntity
import io.github.kgcaudit.reader.data.db.ReaderDatabase
import io.github.kgcaudit.reader.document.Annotation
import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.HighlightColor
import io.github.kgcaudit.reader.document.Bookmark
import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.document.ReadingProgress
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Room 보관소가 :document 의 계약을 지키는가.
 *
 * 계약은 `ReadingSessionTest` 의 가짜 보관소가 먼저 못 박았다. 여기서는 그 성질이
 * SQLite 위에서도 성립하는지, 그리고 가짜가 흉내 내지 않은 것(여러 챕터·PDF 순서,
 * 상한 행, 앱 재시작)을 본다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build()
    private val bookmarks get() = RoomBookmarkRepository(db.bookmarks())
    private val progress get() = RoomProgressRepository(db.progress())
    private val notes get() = RoomAnnotationRepository(db.annotations())

    private val book = BookId("content://books/tree/root/document/root%2Fa.epub")
    private val other = BookId("content://books/tree/root/document/root%2Fb.epub")

    @After
    fun close() = db.close()

    private fun mark(locator: Locator, bookId: BookId = book, at: Long = 1L) =
        Bookmark(Bookmark.NO_ID, bookId, locator, snippet = "…", createdAtEpochMs = at)

    // ── 책갈피 ──────────────────────────────────────────────────────

    @Test
    fun `bookmarks come back in reading order across chapters`() = runTest {
        // 위치 문자열로 정렬하면 "r:10:5" 가 "r:2:900" 보다 앞에 온다 — 10장이 2장보다
        // 먼저 나오는 목록이 된다. 글자 오프셋도 사전순이면 100 이 20 보다 앞선다.
        val inserted = listOf(
            Locator.Reflow(10, 5),
            Locator.Reflow(2, 900),
            Locator.Reflow(2, 100),
            Locator.Reflow(2, 20),
            Locator.Reflow(0, 0),
        )
        inserted.forEach { bookmarks.add(mark(it)) }

        val order = bookmarks.forBook(book).map { it.locator }
        assertEquals(inserted.sortedWith(compareBy({ (it as Locator.Reflow).spine }, { (it as Locator.Reflow).charOffset })), order)
    }

    @Test
    fun `pdf bookmarks come back page by page, top to bottom`() = runTest {
        // 같은 페이지에서는 위쪽이 먼저 읽힌다. 가로를 먼저 보면 오른쪽 위 책갈피가
        // 왼쪽 아래보다 뒤로 밀린다.
        bookmarks.add(mark(Locator.FixedPage(page = 3, xPermille = 0, yPermille = 900)))
        bookmarks.add(mark(Locator.FixedPage(page = 3, xPermille = 900, yPermille = 100)))
        bookmarks.add(mark(Locator.FixedPage(page = 12)))
        bookmarks.add(mark(Locator.FixedPage(page = 1, xPermille = 500, yPermille = 500)))

        assertEquals(
            listOf(
                Locator.FixedPage(1, 500, 500),
                Locator.FixedPage(3, 900, 100),
                Locator.FixedPage(3, 0, 900),
                Locator.FixedPage(12),
            ),
            bookmarks.forBook(book).map { it.locator },
        )
    }

    @Test
    fun `each bookmark gets its own id and removing one leaves the others`() = runTest {
        val first = bookmarks.add(mark(Locator.Reflow(0, 10)))
        // 호출부가 실수로 id 를 채워 보내도 기존 책갈피를 덮어쓰면 안 된다.
        val second = bookmarks.add(mark(Locator.Reflow(0, 20)).copy(id = first.id))

        assertNotEquals(Bookmark.NO_ID, first.id)
        assertNotEquals(first.id, second.id)

        bookmarks.remove(first.id)
        assertEquals(listOf(second), bookmarks.forBook(book))
    }

    @Test
    fun `bookmarks belong to their own book`() = runTest {
        bookmarks.add(mark(Locator.Reflow(0, 10), bookId = book))
        bookmarks.add(mark(Locator.Reflow(0, 20), bookId = other))

        assertEquals(listOf(Locator.Reflow(0, 10)), bookmarks.forBook(book).map { it.locator })
        assertEquals(listOf(Locator.Reflow(0, 20)), bookmarks.forBook(other).map { it.locator })
    }

    @Test
    fun `a corrupted bookmark is skipped and the rest still load`() = runTest {
        // 옛 형식이나 손상된 행 하나 때문에 책갈피 목록 전체가 열리지 않으면 안 된다.
        bookmarks.add(mark(Locator.Reflow(0, 10)))
        db.bookmarks().insert(BookmarkEntity(0, book.value, "r:oops", 0, 5, 0, null, 1L))
        db.bookmarks().insert(BookmarkEntity(0, book.value, "", 0, 6, 0, null, 1L))
        bookmarks.add(mark(Locator.Reflow(0, 30)))

        assertEquals(
            listOf(Locator.Reflow(0, 10), Locator.Reflow(0, 30)),
            bookmarks.forBook(book).map { it.locator },
        )
    }

    // ── 형광펜 · 메모 ───────────────────────────────────────────────

    private fun pen(spine: Int, from: Int, to: Int, color: HighlightColor = HighlightColor.Yellow, note: String? = null) =
        Annotation(Annotation.NO_ID, book, Locator.Reflow(spine, from), Locator.Reflow(spine, to), color, note, "글$from", 1L)

    @Test
    fun `highlights come back in reading order across chapters`() = runTest {
        // 독서노트가 책 순서로 보인다(N6). 10장이 2장보다 먼저 나오면 안 된다.
        notes.add(pen(10, 5, 9))
        notes.add(pen(2, 900, 910))
        notes.add(pen(2, 20, 30))

        assertEquals(listOf(2 to 20, 2 to 900, 10 to 5), notes.forBook(book).map { it.start.spine to it.start.charOffset })
    }

    @Test
    fun `changing color and memo keeps the place and the other highlights`() = runTest {
        val first = notes.add(pen(1, 10, 20))
        val second = notes.add(pen(1, 30, 40))

        notes.update(first.copy(color = HighlightColor.Pink).withNote("  고친 메모  "))
        val back = notes.forBook(book)
        assertEquals(HighlightColor.Pink, back[0].color)
        assertEquals("고친 메모", back[0].note)
        assertEquals(Locator.Reflow(1, 10), back[0].start)
        assertEquals(second, back[1])

        // 메모 칸을 비우고 저장하면 메모가 없어진다 — 빈 메모 상자가 남지 않는다.
        notes.update(back[0].withNote("   "))
        assertNull(notes.forBook(book)[0].note)

        notes.remove(first.id)
        assertEquals(listOf(second), notes.forBook(book))
    }

    @Test
    fun `a broken highlight row is skipped and an unknown color still shows`() = runTest {
        // 상한 행 하나 때문에 독서노트 전체가 안 열리면 안 된다. 모르는 색은 메모를 살리려고 노랑으로 읽는다.
        notes.add(pen(0, 1, 5))
        db.annotations().insert(AnnotationEntity(0, book.value, "r:oops", "r:0:9", 0, 2, "Yellow", null, "x", 1L))
        db.annotations().insert(AnnotationEntity(0, book.value, "r:0:9", "r:0:3", 0, 3, "Yellow", null, "x", 1L))
        db.annotations().insert(AnnotationEntity(0, book.value, "p:1:0:0", "p:1:0:0", 0, 4, "Yellow", null, "x", 1L))
        db.annotations().insert(AnnotationEntity(0, book.value, "r:0:20", "r:0:25", 0, 20, "Violet", "남은 메모", "y", 1L))

        val back = notes.forBook(book)
        assertEquals(listOf(1, 20), back.map { it.start.charOffset })
        assertEquals(HighlightColor.Yellow, back[1].color)
        assertEquals("남은 메모", back[1].note)
    }

    // ── 진도 ────────────────────────────────────────────────────────

    @Test
    fun `saving progress again replaces it instead of keeping a history`() = runTest {
        progress.save(ReadingProgress(book, Locator.Reflow(0, 10), 1f, 100L))
        progress.save(ReadingProgress(book, Locator.Reflow(3, 40), 42f, 200L))

        assertEquals(ReadingProgress(book, Locator.Reflow(3, 40), 42f, 200L), progress.get(book))
        assertNull(progress.get(other))

        progress.remove(book)
        assertNull(progress.get(book))
    }

    @Test
    fun `a corrupted progress row opens the book at the start instead of failing`() = runTest {
        db.progress().upsert(ProgressEntity(book.value, "garbage", 10f, 1L))
        assertNull(progress.get(book))
    }

    @Test
    fun `an out of range percent does not stop the book from opening`() = runTest {
        // 퍼센트는 표시용이다. ReadingProgress 의 범위 검사에 걸려 예외가 나면 위치는
        // 멀쩡한데 책이 안 열린다.
        db.progress().upsert(ProgressEntity(book.value, "r:1:5", 150f, 1L))
        db.progress().upsert(ProgressEntity(other.value, "r:1:5", -3f, 1L))

        assertEquals(100f, progress.get(book)?.percent)
        assertEquals(Locator.Reflow(1, 5), progress.get(book)?.locator)
        assertEquals(0f, progress.get(other)?.percent)
    }

    // ── 재시작 ──────────────────────────────────────────────────────

    @Test
    fun `bookmarks and progress survive closing and reopening the app`() = runTest {
        // 인메모리가 아니라 실제 파일 DB 로 본다. R1 완료 판정이 "앱을 껐다 켜도 같은
        // 자리로 돌아온다" 다.
        context.deleteDatabase(ReaderDatabase.FILE_NAME)
        db.close()
        db = ReaderDatabase.open(context)
        bookmarks.add(mark(Locator.Reflow(4, 44)))
        progress.save(ReadingProgress(book, Locator.Reflow(4, 50), 30f, 9L))
        db.close()

        db = ReaderDatabase.open(context)
        assertEquals(listOf(Locator.Reflow(4, 44)), bookmarks.forBook(book).map { it.locator })
        assertEquals(Locator.Reflow(4, 50), progress.get(book)?.locator)
        assertTrue(context.getDatabasePath(ReaderDatabase.FILE_NAME).exists())
        db.close()
        context.deleteDatabase(ReaderDatabase.FILE_NAME)
        db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build()
    }
}
