package io.github.kgcaudit.reader.data.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.data.RoomBookmarkRepository
import io.github.kgcaudit.reader.data.RoomProgressRepository
import io.github.kgcaudit.reader.data.db.BookEntity
import io.github.kgcaudit.reader.data.db.ReaderDatabase
import io.github.kgcaudit.reader.document.BookFormat
import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.Bookmark
import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.document.ReadingProgress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 스캔 결과가 라이브러리·최근 목록에 어떻게 반영되는가. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build()
    private val library = Library(db)
    private val folder = "content://fake/tree/root"

    @After
    fun close() = db.close()

    private fun book(name: String, size: Long? = 100L, modified: Long? = 1L) =
        ScannedBook(FakeTree.uriOf(name), name, BookFormat.fromFileName(name)!!, size, modified)

    private suspend fun visible() = library.books().first().map { it.displayName }

    @Test
    fun `a book that disappears is hidden but its bookmarks and progress come back with it`() = runTest {
        // SD 카드를 뺐다 끼우는 흔한 경우. 책갈피가 스캔 한 번에 사라지면 안 된다.
        val bookmarks = RoomBookmarkRepository(db.bookmarks())
        val progress = RoomProgressRepository(db.progress())
        val epub = book("책.epub")
        val id = BookId(epub.uri)

        library.applyScan(folder, ScanResult(listOf(epub, book("다른.txt")), complete = true), nowEpochMs = 10)
        bookmarks.add(Bookmark(Bookmark.NO_ID, id, Locator.Reflow(2, 30), null, 1))
        progress.save(ReadingProgress(id, Locator.Reflow(2, 40), 12f, 1))
        library.updateMetadata(id, "어린 왕자", "생텍쥐페리")

        library.applyScan(folder, ScanResult(listOf(book("다른.txt")), complete = true), nowEpochMs = 20)
        assertEquals(listOf("다른.txt"), visible())

        library.applyScan(folder, ScanResult(listOf(epub, book("다른.txt")), complete = true), nowEpochMs = 30)
        assertEquals(listOf("다른.txt", "책.epub"), visible())
        assertEquals(listOf(Locator.Reflow(2, 30)), bookmarks.forBook(id).map { it.locator })
        assertEquals(Locator.Reflow(2, 40), progress.get(id)?.locator)

        val back = library.get(id)!!
        assertEquals("어린 왕자", back.label)
        assertEquals(10L, db.books().get(id.value)!!.addedAtEpochMs, "처음 등록한 시각이 유지돼야 한다")
    }

    @Test
    fun `an incomplete scan never hides a book`() = runTest {
        library.applyScan(folder, ScanResult(listOf(book("a.epub"), book("b.epub")), complete = true), 1)
        library.applyScan(folder, ScanResult(emptyList(), complete = false), 2)
        assertEquals(listOf("a.epub", "b.epub"), visible())
    }

    @Test
    fun `a replaced file forgets the title it had`() = runTest {
        // 같은 이름으로 다른 책을 덮어썼는데 옛 제목이 계속 보이면 안 된다. 바뀌지 않은
        // 파일은 제목을 유지해야 한다 — 매번 버리면 스캔할 때마다 목록이 파일 이름으로 돌아간다.
        library.applyScan(folder, ScanResult(listOf(book("a.epub"), book("b.epub")), complete = true), 1)
        library.updateMetadata(BookId(book("a.epub").uri), "제목 A", null)
        library.updateMetadata(BookId(book("b.epub").uri), "제목 B", null)

        library.applyScan(
            folder,
            ScanResult(listOf(book("a.epub"), book("b.epub", size = 999L)), complete = true),
            2,
        )
        assertEquals(listOf("b.epub", "제목 A"), library.books().first().map { it.label }.sorted())
    }

    @Test
    fun `a scan of one folder leaves other folders alone`() = runTest {
        val elsewhere = "content://fake/tree/other"
        library.applyScan(folder, ScanResult(listOf(book("a.epub")), complete = true), 1)
        library.applyScan(elsewhere, ScanResult(listOf(book("x.pdf")), complete = true), 1)

        library.applyScan(folder, ScanResult(emptyList(), complete = true), 2)
        assertEquals(listOf("x.pdf"), visible())
    }

    @Test
    fun `a file handed over by another app is matched to its library copy by name and size`() = runTest {
        // 파일 관리자의 "연결 프로그램" 으로 온 책. URI 는 달라도 같은 파일이면 라이브러리의 책으로
        // 열어야 진도·책갈피가 한 벌로 이어진다.
        library.applyScan(folder, ScanResult(listOf(book("책.epub", size = 1234), book("책.txt", size = 1234)), complete = true), 1)
        assertEquals(FakeTree.uriOf("책.epub"), library.findByFile("책.epub", 1234)?.id?.value)

        // 이름만 같은 다른 판, 크기를 모르는 경우, 숨겨진 책은 짝짓지 않는다.
        assertNull(library.findByFile("책.epub", 999))
        assertNull(library.findByFile("책.epub", null))
        library.applyScan(folder, ScanResult(listOf(book("책.txt", size = 1234)), complete = true), 2)
        assertNull(library.findByFile("책.epub", 1234), "숨겨진 책의 URI 는 지금 열리지 않을 수 있다")
    }

    @Test
    fun `recent books are newest first and skip hidden ones`() = runTest {
        val a = book("a.epub"); val b = book("b.txt"); val c = book("c.pdf")
        library.applyScan(folder, ScanResult(listOf(a, b, c), complete = true), 1)
        library.markOpened(BookId(a.uri), 10)
        library.markOpened(BookId(c.uri), 20)
        library.markOpened(BookId(b.uri), 30)
        library.markOpened(BookId(a.uri), 40)
        assertEquals(listOf("a.epub", "b.txt", "c.pdf"), library.recent(limit = 20).first().map { it.displayName })
        assertEquals(listOf("a.epub", "b.txt"), library.recent(limit = 2).first().map { it.displayName })

        // 눌러도 열리지 않는 책을 최근 목록에 두지 않는다.
        library.applyScan(folder, ScanResult(listOf(a, c), complete = true), 50)
        assertEquals(listOf("a.epub", "c.pdf"), library.recent(limit = 20).first().map { it.displayName })
    }

    @Test
    fun `forgetting a folder keeps the reading position for when it comes back`() = runTest {
        val a = book("a.epub")
        val progress = RoomProgressRepository(db.progress())
        library.applyScan(folder, ScanResult(listOf(a), complete = true), 1)
        progress.save(ReadingProgress(BookId(a.uri), Locator.Reflow(1, 1), 5f, 1))

        library.forgetFolder(folder)
        assertTrue(visible().isEmpty())
        assertNull(library.get(BookId(a.uri)))

        library.applyScan(folder, ScanResult(listOf(a), complete = true), 2)
        assertEquals(Locator.Reflow(1, 1), progress.get(BookId(a.uri))?.locator)
    }

    @Test
    fun `the list shows how far each book has been read`() = runTest {
        val a = book("a.epub"); val b = book("b.txt")
        library.applyScan(folder, ScanResult(listOf(a, b), complete = true), 1)
        val progress = RoomProgressRepository(db.progress())
        progress.save(ReadingProgress(BookId(a.uri), Locator.Reflow(3, 0), 37.5f, 1))

        // 읽지 않은 책은 항목이 없다(0% 와 구별한다 — "아직 안 폄" 과 "처음에 멈춤" 은 다르다).
        assertEquals(mapOf(BookId(a.uri) to 37.5f), library.percents().first())
    }

    @Test
    fun `a row with an unknown format is left out instead of breaking the list`() = runTest {
        // 다음 버전이 쓴 포맷 이름(예: CBZ)을 옛 버전이 읽는 경우.
        library.applyScan(folder, ScanResult(listOf(book("a.epub")), complete = true), 1)
        db.books().upsert(
            listOf(BookEntity("content://x", folder, "만화.cbz", "CBZ", 1, 1, null, null, 1, missing = false)),
        )
        assertEquals(listOf("a.epub"), visible())
    }
}
