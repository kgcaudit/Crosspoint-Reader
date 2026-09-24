package io.github.kgcaudit.reader.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import io.github.kgcaudit.reader.document.BookFormat
import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.BookMeta
import io.github.kgcaudit.reader.document.Bookmark
import io.github.kgcaudit.reader.document.BookmarkRepository
import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.document.ProgressRepository
import io.github.kgcaudit.reader.document.ReadingProgress
import io.github.kgcaudit.reader.document.TocEntry
import io.github.kgcaudit.reader.document.pdf.TestPdf
import io.github.kgcaudit.reader.document.pdf.TestPdf.Companion.pages
import io.github.kgcaudit.reader.document.pdf.TestPdf.Companion.utf16
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileInputStream
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
    fun `a pdf opens with the contents, title and author written in the file`() = runTest {
        // 쪽은 엔진이 그리지만 목차·제목은 파일 구조에서 먼저 읽는다. 읽은 뒤에도 엔진의 디스크립터는
        // 열린 채 처음부터 읽을 수 있어야 한다(복제본만 닫는다).
        val file = File.createTempFile("book", ".pdf").apply {
            deleteOnExit()
            writeBytes(outlinedPdf())
        }
        var engineSaw: ByteArray? = null
        val book = PdfBook.open(id, "[한강] 소년이 온다.pdf", ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) { pfd ->
            engineSaw = FileInputStream(pfd.fileDescriptor).readNBytes(8)
            FakeSource(6)
        }

        assertEquals("%PDF-1.4", engineSaw?.toString(Charsets.ISO_8859_1))
        assertEquals("소년이 온다", book.meta.title)
        assertEquals("한강", book.meta.author)
        assertTrue(book.hasOwnTitle)
        // 엔진이 센 쪽 수(6)를 넘는 항목("부록" → 8쪽)은 누르면 빈 화면이라 뺀다.
        val contents = book.outline()
        assertEquals(listOf("1장 어린 새" to 1, "2장 검은 숨" to 3, "2-1 새벽" to 4), contents.map { it.label to (it.locator as Locator.FixedPage).page })
        assertEquals(listOf(0, 0, 1), contents.map { it.depth })

        val r = PdfReader(book, bookmarks, progress, Dispatchers.Unconfined) { 1_000L }
        r.open()
        r.goTo(contents[1])
        assertEquals(3, r.state.value.page)
    }

    @Test
    fun `a pdf without contents or a title falls back to the file name`() = runTest {
        val file = File.createTempFile("plain", ".pdf").apply {
            deleteOnExit()
            writeBytes(ByteArray(64) { 7 })
        }
        val book = PdfBook.open(id, "설명서.pdf", ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) { FakeSource(2) }
        assertEquals("설명서", book.meta.title)
        assertFalse(book.hasOwnTitle)
        assertTrue(book.outline().isEmpty())
    }

    @Test
    fun `printed page numbers name the pages and the bookmarks`() = runTest {
        // 머리말을 로마 숫자로 세는 책: 넷째 쪽의 책갈피는 "iv쪽", 다섯째는 "1쪽" 이라야 종이책과 맞는다.
        val file = File.createTempFile("labelled", ".pdf").apply {
            deleteOnExit()
            writeBytes(
                TestPdf().run {
                    pages(10, 6)
                    obj(1, "<< /Type /Catalog /Pages 10 0 R /PageLabels << /Nums [0 << /S /r >> 4 << /S /D >>] >> >>")
                    classic()
                },
            )
        }
        val book = PdfBook.open(id, "책.pdf", ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) { FakeSource(6) }
        assertEquals(listOf("i", "ii", "iii", "iv", "1", "2"), (0 until 6).map { book.pageLabel(it) })

        val r = PdfReader(book, bookmarks, progress, Dispatchers.Unconfined) { 1_000L }
        r.open()
        r.goTo(3)
        r.toggleBookmark()
        assertEquals("iv쪽", r.bookmarks().single().snippet)
    }

    @Test
    fun `the contents highlight the chapter the page belongs to`() {
        fun entry(page: Int) = TocEntry("p$page", Locator.FixedPage(page))
        val entries = listOf(entry(2), entry(5), entry(5), entry(9))
        assertEquals(-1, currentContentsIndex(entries, 0), "표지(첫 항목 앞)에는 불이 없다")
        assertEquals(0, currentContentsIndex(entries, 2))
        assertEquals(0, currentContentsIndex(entries, 4))
        // 같은 쪽에서 시작하는 장과 절이면 더 깊은(뒤의) 절.
        assertEquals(2, currentContentsIndex(entries, 7))
        assertEquals(3, currentContentsIndex(entries, 100))
    }

    @Test
    fun `the pages left in a section count up to the next entry that starts later`() {
        fun entry(page: Int) = TocEntry("p$page", Locator.FixedPage(page))
        // 잡지 목차는 쪽 순서가 뒤섞여 있다(특집 40쪽을 맨 앞에 적음). "목록의 다음 항목" 으로 세면 10쪽에서
        // 40쪽 특집 다음 항목(12쪽)을 보고 1쪽이 아니라 엉뚱한 값을 낸다 — 뒤에서 가장 가까운 시작을 쓴다.
        val entries = listOf(entry(40), entry(2), entry(12), entry(30))
        assertEquals(1, pagesLeftInSection(entries, 10, 84), "12쪽 앞까지 11 하나")
        assertEquals(0, pagesLeftInSection(entries, 11, 84), "다음 쪽이 새 항목이면 마지막 쪽")
        assertEquals(43, pagesLeftInSection(entries, 40, 84), "마지막 항목은 파일 끝까지")
        assertEquals(83, pagesLeftInSection(emptyList(), 0, 84), "목차가 없으면 파일 끝까지")
    }

    @Test
    fun `closing the reader closes the file`() = runTest {
        val source = FakeSource(1)
        reader(source).close()
        assertTrue(source.closed)
    }
}

/** 목차 · 문서 정보가 있는 PDF(쪽 8장). 엔진은 가짜라 쪽 내용은 없어도 된다. */
private fun outlinedPdf(): ByteArray = TestPdf().run {
    val p = pages(10, 8)
    obj(1, "<< /Type /Catalog /Pages 10 0 R /Outlines 2 0 R >>")
    obj(2, "<< /Type /Outlines /First 50 0 R >>")
    obj(50, "<< /Title ${utf16("1장 어린 새")} /Next 51 0 R /Dest [${p[1]} 0 R /Fit] >>")
    obj(51, "<< /Title ${utf16("2장 검은 숨")} /Next 52 0 R /First 53 0 R /Dest [${p[3]} 0 R /Fit] >>")
    obj(53, "<< /Title ${utf16("2-1 새벽")} /Dest [${p[4]} 0 R /Fit] >>")
    obj(52, "<< /Title ${utf16("부록")} /Dest [${p[7]} 0 R /Fit] >>")
    obj(30, "<< /Title ${utf16("소년이 온다")} /Author ${utf16("한강")} >>")
    classic(trailerExtra = "/Info 30 0 R")
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
