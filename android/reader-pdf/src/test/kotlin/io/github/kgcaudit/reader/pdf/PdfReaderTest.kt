package io.github.kgcaudit.reader.pdf

import android.graphics.Bitmap
import android.graphics.Color
import io.github.kgcaudit.reader.document.BookFormat
import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.BookMeta
import io.github.kgcaudit.reader.document.Bookmark
import io.github.kgcaudit.reader.document.BookmarkRepository
import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.document.ProgressRepository
import io.github.kgcaudit.reader.document.ReadingProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Robolectric 에는 PDF 엔진이 없다. 쪽마다 다른 색으로 칠하는 가짜로 리더의 동작만 본다. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PdfReaderTest {

    private val id = BookId("content://books/manual.pdf")
    private val bookmarks = FakeBookmarks()
    private val progress = FakeProgress()

    private fun reader(source: FakeSource = FakeSource(10)) =
        PdfReader(PdfBook(BookMeta(id, BookFormat.PDF, "설명서"), source), bookmarks, progress, Dispatchers.Unconfined) { 1_000L }

    @Test
    fun `turning pages saves the place and reopening returns to it`() = runTest {
        val first = reader()
        first.open()
        assertEquals(0, first.state.value.page)
        repeat(3) { first.next() }
        first.close()

        // 다시 열면 넷째 쪽. 저장은 쪽 번호(FixedPage)다.
        val again = reader()
        again.open()
        assertEquals(3, again.state.value.page)
        assertEquals(Locator.FixedPage(3), progress.get(id)?.locator)
        assertEquals(40f, again.state.value.percent)
    }

    @Test
    fun `paging stops at both ends instead of wrapping`() = runTest {
        val r = reader(FakeSource(2))
        r.open()
        r.previous()
        assertEquals(0, r.state.value.page)
        r.next(); r.next(); r.next()
        assertEquals(1, r.state.value.page)
        assertEquals(100f, r.state.value.percent)
    }

    @Test
    fun `a saved place past the end of a shorter file opens the last page`() = runTest {
        // 같은 이름으로 쪽 수가 줄어든 파일. 없는 쪽을 가리키면 빈 화면이 된다.
        progress.save(ReadingProgress(id, Locator.FixedPage(50), 90f, 0L))
        val r = reader(FakeSource(5))
        r.open()
        assertEquals(4, r.state.value.page)
    }

    @Test
    fun `the progress bar lands on the page its label shows`() = runTest {
        val r = reader(FakeSource(11))
        r.open()
        for (fraction in listOf(0f, 0.04f, 0.05f, 0.5f, 0.96f, 1f)) {
            r.seek(fraction)
            assertEquals(pageAt(fraction, 11), r.state.value.page, "fraction $fraction")
        }
        r.seek(1f)
        assertEquals(10, r.state.value.page)
    }

    @Test
    fun `a bookmark marks this page only and toggles off again`() = runTest {
        val r = reader()
        r.open()
        r.goTo(4)
        r.toggleBookmark()
        assertTrue(r.state.value.bookmarked)
        assertEquals("5쪽", r.bookmarks().single().snippet)
        r.next()
        assertFalse(r.state.value.bookmarked, "다른 쪽에는 책갈피가 없다")

        r.goTo(r.bookmarks().single())
        assertEquals(4, r.state.value.page)
        assertTrue(r.state.value.bookmarked)
        r.toggleBookmark()
        assertTrue(r.bookmarks().isEmpty())
        assertFalse(r.state.value.bookmarked)
    }

    @Test
    fun `a page is drawn once and kept for flipping back`() = runTest {
        val source = FakeSource(10)
        val r = reader(source)
        r.open()
        val page = r.page(0, 100, 141)
        assertNotNull(page)
        assertEquals(Color.rgb(0, 0, 0), page.getPixel(50, 70))
        assertSame(page, r.page(0, 100, 141))
        assertSame(page, r.cachedPage(0, 100, 141))
        assertEquals(1, source.renders)
        // 다른 크기(화면이 돌아감)면 다시 그린다.
        r.page(0, 141, 100)
        assertEquals(2, source.renders)
    }

    @Test
    fun `a broken page is blank while the rest of the book still reads`() = runTest {
        // 쪽 하나가 깨졌다고 책이 멈추면 안 된다. 그 쪽만 빈 종이, 다음 쪽은 그대로 넘긴다.
        val r = reader(FakeSource(3, broken = setOf(1)))
        r.open()
        r.next()
        assertNull(r.page(1, 100, 141))
        assertEquals(PageViewport.DEFAULT_ASPECT, r.book.pageAspectRatio(1))
        r.next()
        assertEquals(2, r.state.value.page)
        assertNotNull(r.page(2, 100, 141))
    }

    @Test
    fun `the zoomed region is drawn from the right part of the page`() = runTest {
        val source = FakeSource(1)
        val r = reader(source)
        r.open()
        val region = PageRegion(0.5f, 0.25f, 1f, 0.75f)
        assertNotNull(r.region(0, region, 50, 50))
        assertEquals(region, source.lastRegion)
        // 비어 있는 구역·크기는 그리지 않는다(0×0 비트맵은 예외다).
        assertNull(r.region(0, PageRegion(0.5f, 0.5f, 0.5f, 0.8f), 50, 50))
        assertNull(r.region(0, region, 0, 50))
    }

    @Test
    fun `closing the reader closes the file`() = runTest {
        val source = FakeSource(1)
        reader(source).close()
        assertTrue(source.closed)
    }
}

/** 쪽 번호로 회색 농도를 정해 칠한다(0쪽은 검정). [broken] 쪽은 PdfRenderer 처럼 예외를 던진다. */
private class FakeSource(override val pageCount: Int, private val broken: Set<Int> = emptySet()) : PdfSource {
    var renders = 0
    var lastRegion: PageRegion? = null
    var closed = false

    override fun pageSize(index: Int): Pair<Int, Int> {
        if (index in broken) throw IllegalStateException("broken page $index")
        return 595 to 842
    }

    override fun render(index: Int, target: Bitmap, region: PageRegion) {
        if (index in broken) throw IllegalStateException("broken page $index")
        renders++
        lastRegion = region
        val grey = (index * 20).coerceAtMost(255)
        target.eraseColor(Color.rgb(grey, grey, grey))
    }

    override fun close() {
        closed = true
    }
}

private class FakeBookmarks : BookmarkRepository {
    private val rows = ArrayList<Bookmark>()
    private var nextId = 1L

    override suspend fun forBook(bookId: BookId): List<Bookmark> =
        rows.filter { it.bookId == bookId }.sortedBy { (it.locator as Locator.FixedPage).page }

    override suspend fun add(bookmark: Bookmark): Bookmark = bookmark.copy(id = nextId++).also(rows::add)

    override suspend fun remove(id: Long) {
        rows.removeAll { it.id == id }
    }
}

private class FakeProgress : ProgressRepository {
    private val rows = HashMap<BookId, ReadingProgress>()

    override suspend fun get(bookId: BookId): ReadingProgress? = rows[bookId]

    override suspend fun save(progress: ReadingProgress) {
        rows[progress.bookId] = progress
    }

    override suspend fun remove(bookId: BookId) {
        rows.remove(bookId)
    }
}
