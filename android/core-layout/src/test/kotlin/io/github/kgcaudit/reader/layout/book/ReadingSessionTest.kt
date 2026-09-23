package io.github.kgcaudit.reader.layout.book

import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.Bookmark
import io.github.kgcaudit.reader.document.BookmarkRepository
import io.github.kgcaudit.reader.document.ByteSource
import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.document.ProgressRepository
import io.github.kgcaudit.reader.document.ReadingProgress
import io.github.kgcaudit.reader.document.TxtDocument
import io.github.kgcaudit.reader.layout.FakeMeasurer
import io.github.kgcaudit.reader.layout.Insets
import io.github.kgcaudit.reader.layout.LayoutSpec
import io.github.kgcaudit.reader.layout.TextAlign
import io.github.kgcaudit.reader.layout.cache.PageStore
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 시험용 보관소. 진짜 구현(SQLite)이 지켜야 할 계약을 그대로 흉내 낸다. */
private class FakeBookmarks : BookmarkRepository {
    private val rows = ArrayList<Bookmark>()
    private var nextId = 1L

    override suspend fun forBook(bookId: BookId): List<Bookmark> =
        rows.filter { it.bookId == bookId }.sortedBy { (it.locator as Locator.Reflow).charOffset }

    override suspend fun add(bookmark: Bookmark): Bookmark =
        bookmark.copy(id = nextId++).also(rows::add)

    override suspend fun remove(id: Long) {
        rows.removeAll { it.id == id }
    }
}

private class FakeProgress : ProgressRepository {
    private val rows = HashMap<BookId, ReadingProgress>()
    var saveCount = 0
        private set

    override suspend fun get(bookId: BookId): ReadingProgress? = rows[bookId]

    override suspend fun save(progress: ReadingProgress) {
        rows[progress.bookId] = progress
        saveCount++
    }

    override suspend fun remove(bookId: BookId) {
        rows.remove(bookId)
    }
}

class ReadingSessionTest {

    private val root: File = File.createTempFile("session", "").let { it.delete(); it.mkdirs(); it }
    private val store = PageStore(root)

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private val spec = LayoutSpec(
        viewportWidthPx = 300f,
        viewportHeightPx = 160f,
        margin = Insets.all(20f),
        baseSizePx = 10f,
        align = TextAlign.Justify,
    )

    private val bookmarks = FakeBookmarks()
    private val progress = FakeProgress()
    private var now = 1_700_000_000_000L

    private val content = (1..60).joinToString("\n") {
        "$it 번째 줄, 어린 왕자는 장미 한 송이를 별에 두고 왔다."
    }

    private suspend fun ReadingSession.forward(steps: Int, from: ReadingPosition): ReadingPosition {
        var position = from
        repeat(steps) { position = layoutOf(this).next(position) ?: position }
        return position
    }

    /** 테스트가 페이지를 넘기려면 레이아웃이 필요하다. 세션과 같은 것을 쓴다. */
    private val layouts = HashMap<ReadingSession, BookLayout>()
    private fun layoutOf(session: ReadingSession): BookLayout = layouts.getValue(session)

    private fun newSession(layoutSpec: LayoutSpec = spec): ReadingSession {
        val doc = TxtDocument.open(BookId("txt-1"), "책.txt", ByteSource.of(content.toByteArray()))
        val layout = BookLayout(doc, layoutSpec, store, FakeMeasurer(baseSizePx = layoutSpec.baseSizePx))
        val session = ReadingSession(layout, bookmarks, progress) { now }
        layouts[session] = layout
        return session
    }

    // ── 이어읽기 ────────────────────────────────────────────────────

    @Test
    fun `a fresh book starts at the beginning`() = runTest {
        val position = newSession().restore()
        assertEquals(0, position.spineIndex)
        assertEquals(0, position.pageIndex)
        assertTrue(position.pageCount > 1)
    }

    @Test
    fun `the saved place comes back when the book is reopened`() = runTest {
        val first = newSession()
        val here = first.forward(4, first.restore())
        val saved = first.saveProgress(here)

        assertEquals(saved.locator, progress.get(BookId("txt-1"))!!.locator)
        assertTrue(saved.percent > 0f)
        assertEquals(now, saved.updatedAtEpochMs)

        assertEquals(here.pageIndex, newSession().restore().pageIndex)
    }

    @Test
    fun `the saved place survives a change of font size`() = runTest {
        val small = newSession()
        val here = small.forward(5, small.restore())
        val saved = small.saveProgress(here)

        val bigger = newSession(spec.copy(baseSizePx = 14f))
        val restored = bigger.restore()
        val page = layoutOf(bigger).page(restored.spineIndex, restored.pageIndex)!!

        val offset = (saved.locator as Locator.Reflow).charOffset
        assertTrue(
            offset in page.startChar until page.endCharExclusive,
            "저장한 글자 $offset 가 복원된 페이지 ${page.startChar}..${page.endCharExclusive} 밖이다",
        )
    }

    @Test
    fun `saving twice keeps one place, not a history`() = runTest {
        val session = newSession()
        session.saveProgress(session.restore())
        now += 60_000
        val later = session.forward(2, session.restore())
        val second = session.saveProgress(later)

        assertEquals(2, progress.saveCount)
        assertEquals(second, progress.get(BookId("txt-1")))
    }

    // ── 책갈피 ──────────────────────────────────────────────────────

    @Test
    fun `a bookmark remembers the place and a readable snippet`() = runTest {
        val session = newSession()
        val here = session.forward(3, session.restore())
        val bookmark = session.addBookmark(here)

        assertTrue(bookmark.id != Bookmark.NO_ID, "id 가 부여되지 않았다")
        assertEquals(BookId("txt-1"), bookmark.bookId)
        assertEquals(now, bookmark.createdAtEpochMs)

        val snippet = assertNotNull(bookmark.snippet)
        assertTrue(snippet.isNotBlank())
        assertFalse(snippet.any { it < ' ' }, "미리보기에 제어 문자가 섞였다: $snippet")
        assertEquals(snippet, snippet.trim())

        // 미리보기는 그 페이지가 시작하는 글로 뜬다.
        val text = layoutOf(session).chapterText(here.spineIndex)!!
        val page = layoutOf(session).page(here.spineIndex, here.pageIndex)!!
        assertTrue(text.startsWith(snippet.take(10), page.startChar), "엉뚱한 자리의 글이다: $snippet")
    }

    @Test
    fun `pressing the bookmark button twice does not make two bookmarks`() = runTest {
        val session = newSession()
        val here = session.forward(2, session.restore())

        val first = session.addBookmark(here)
        val second = session.addBookmark(here)
        assertEquals(first, second)
        assertEquals(1, session.bookmarks().size)
    }

    @Test
    fun `a bookmark can be removed from the page it is on`() = runTest {
        val session = newSession()
        val here = session.forward(2, session.restore())

        session.addBookmark(here)
        assertTrue(session.isBookmarked(here))
        assertTrue(session.removeBookmarkAt(here))
        assertFalse(session.isBookmarked(here))
        assertTrue(session.bookmarks().isEmpty())

        // 없는 것을 빼려 해도 조용히 아무 일도 없다.
        assertFalse(session.removeBookmarkAt(here))
    }

    @Test
    fun `other pages are not bookmarked`() = runTest {
        val session = newSession()
        val start = session.restore()
        session.addBookmark(session.forward(3, start))
        assertFalse(session.isBookmarked(start))
    }

    @Test
    fun `tapping a bookmark goes back to its page`() = runTest {
        val session = newSession()
        val here = session.forward(4, session.restore())
        val bookmark = session.addBookmark(here)

        val gone = session.goTo(bookmark)!!
        assertEquals(here.spineIndex, gone.spineIndex)
        assertEquals(here.pageIndex, gone.pageIndex)
    }

    @Test
    fun `bookmarks come back in reading order`() = runTest {
        val session = newSession()
        val start = session.restore()
        val later = session.forward(5, start)
        val middle = session.forward(2, start)

        session.addBookmark(later)
        session.addBookmark(start)
        session.addBookmark(middle)

        val offsets = session.bookmarks().map { (it.locator as Locator.Reflow).charOffset }
        assertEquals(offsets.sorted(), offsets, "목록이 읽는 순서가 아니다")
        assertEquals(3, offsets.size)
    }

    @Test
    fun `a bookmark on a fixed-page document is not resolved as reflow`() = runTest {
        // PDF 책갈피가 섞여 들어오면 null 을 주고 넘어간다 — 엉뚱한 자리로 보내는 것보다 낫다.
        val session = newSession()
        val pdfBookmark = Bookmark(
            id = 1,
            bookId = BookId("txt-1"),
            locator = Locator.FixedPage(page = 3, xPermille = 0, yPermille = 0),
            createdAtEpochMs = now,
        )
        assertNull(session.goTo(pdfBookmark))
    }

    @Test
    fun `the session and the layout always mean the same book`() = runTest {
        val session = newSession()
        session.addBookmark(session.restore())
        assertEquals(1, session.bookmarks().size)
        assertTrue(bookmarks.forBook(BookId("다른-책")).isEmpty())
    }
}
