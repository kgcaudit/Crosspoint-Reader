package io.github.kgcaudit.reader.layout.book

import io.github.kgcaudit.reader.document.Annotation
import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.Bookmark
import io.github.kgcaudit.reader.document.BookmarkRepository
import io.github.kgcaudit.reader.document.ByteSource
import io.github.kgcaudit.reader.document.HighlightColor
import io.github.kgcaudit.reader.document.InMemoryAnnotations
import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.document.ProgressRepository
import io.github.kgcaudit.reader.document.ReadingProgress
import io.github.kgcaudit.reader.document.ReflowDocument
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 큰 TXT 를 조각으로 나눠 조판한다(0.60.0, iOS 요청). 저장하는 자리는 나누기 전 한 장의 글자 번호 그대로다.
 */
class BigTxtTest {

    private val root: File = File.createTempFile("bigtxt", "").let { it.delete(); it.mkdirs(); it }
    private val store = PageStore(root)

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private val spec = LayoutSpec(
        viewportWidthPx = 300f,
        viewportHeightPx = 400f,
        margin = Insets.all(20f),
        baseSizePx = 10f,
        align = TextAlign.Justify,
    )

    /** 장면 서른 개 남짓, 장면 사이는 빈 줄 — 약 10만 글자라 조각이 여럿 나온다. */
    private val content: String = (1..80).joinToString("\n\n") { scene ->
        (1..30).joinToString("\n") { line -> "$scene 장면 $line 번째 줄, 후치는 마차 바퀴에 등을 기대고 별을 세었다." }
    }

    private val id = BookId("big-txt")
    private fun document(): TxtDocument = TxtDocument.open(id, "드래곤라자.txt", ByteSource.of(content.toByteArray()))
    private fun layout(doc: ReflowDocument = document()) = BookLayout(doc, spec, store, FakeMeasurer(baseSizePx = spec.baseSizePx))

    @Test
    fun `a big txt shows its first page after laying out only the first piece`() = runTest {
        val l = layout()
        assertTrue(l.spine().size >= 4, "조각이 여럿이어야 하는 크기: ${l.spine().size}")
        val first = l.resolve(Locator.Reflow(0, 0))
        assertNotNull(l.page(first.spineIndex, first.pageIndex))
        // 첫 쪽을 보이려고 뒤 조각을 짜지 않았다 — 한 장이던 때는 여기서 파일 전체를 조판했다.
        for (i in 1 until l.spine().size) assertNull(store.chapter(id, spec, i).readIndex(), "조각 $i 를 미리 짰다")
    }

    @Test
    fun `a position saved before the split opens on the same character`() = runTest {
        // 0.59 까지 TXT 의 자리는 한 장의 글자 번호(r:0:N)였다. 같은 글자가 든 쪽이 열려야 한다.
        val target = content.indexOf("61 장면 12 번째 줄")
        val progress = MemoryProgress().apply { save(ReadingProgress(id, Locator.Reflow(0, target), 75f, 1L)) }
        val l = layout()
        val repos = LayoutRepositories(NoBookmarks, progress, InMemoryAnnotations(), l::space)
        val session = ReadingSession(l, repos.bookmarks, repos.progress) { 2L }
        val position = session.restore()
        assertTrue(position.spineIndex > 0, "뒤 조각의 자리인데 첫 조각에 열렸다")
        val page = assertNotNull(l.page(position.spineIndex, position.pageIndex))
        val text = assertNotNull(l.chapterText(position.spineIndex))
        val local = target - l.space().pieceStart(position.spineIndex)
        assertTrue(local in page.startChar until page.endCharExclusive, "열린 쪽에 그 글자가 없다")
        assertEquals(content[target], text[local])
        // 다시 저장할 때도 책 자리로 — 조각 자리(r:k:M)가 저장되면 옛 판 앱 · 다른 기기에서 엉뚱한 곳이 열린다.
        session.saveProgress(position, position)
        val saved = assertNotNull(progress.get(id)).locator as Locator.Reflow
        assertEquals(0, saved.spine)
        assertEquals(l.space().pieceStart(position.spineIndex) + page.startChar, saved.charOffset)
    }

    @Test
    fun `a cache written while the txt was one chapter is not shown as the first piece`() = runTest {
        // 0.59 까지의 캐시: 같은 책 · 같은 설정의 0 번 장에 파일 전체.
        val whole = object : ReflowDocument by document() {
            override suspend fun spine() = listOf(io.github.kgcaudit.reader.document.SpineItem(0, TxtDocument.SINGLE_HREF))
            override suspend fun openChapter(index: Int) = io.github.kgcaudit.reader.document.io.StringInput(content)
        }
        layout(whole).ensurePaginated(0)
        assertEquals(content.length, store.chapter(id, spec, 0).readIndex()?.textLength)

        val l = layout()
        val firstPiece = l.space().pieceStart(1)
        // 옛 캐시를 그대로 쓰면 첫 조각에 책 전체가 들어가, 다음 조각으로 넘어가면 글이 되풀이됐다.
        assertEquals(content.substring(0, firstPiece), l.chapterText(0))
    }

    @Test
    fun `page numbers run on across pieces once every piece is counted`() = runTest {
        val l = layout()
        val inThird = l.resolve(Locator.Reflow(2, 0))
        // 앞 조각을 세기 전에는 모른다 — 화면은 "… / …".
        assertEquals(PageNumber(null, null), l.pageNumber(inThird))
        val counts = l.spine().indices.map { l.pageCount(it) }
        assertNull(l.nextUncounted())
        val number = l.pageNumber(inThird)
        assertEquals(counts[0] + counts[1] + 1, number.number)
        assertEquals(counts.sum(), number.total)
        // 머리 · 남은 시간이 말하는 장은 조각이 아니라 책 한 장이다.
        val span = l.chapterSpan(2)
        assertEquals(ChapterSpan(0, 1, l.space().pieceStart(2), content.length, 0), span)
    }

    @Test
    fun `a highlight across a piece boundary reaches from one piece into the next`() = runTest {
        val l = layout()
        val boundary = l.space().pieceStart(1)
        val store = InMemoryAnnotations()
        val saved = store.add(Annotation(Annotation.NO_ID, id, Locator.Reflow(0, boundary - 10), Locator.Reflow(0, boundary + 10), HighlightColor.Yellow, null, "칠", 1L))
        val repos = LayoutRepositories(NoBookmarks, MemoryProgress(), store, l::space)

        val seen = repos.annotations.forBook(id).single()
        assertEquals(Locator.Reflow(0, l.chapterLength(0) - 10), seen.start)
        assertEquals(Locator.Reflow(1, 10), seen.end)
        // 새로 칠한 것은 책 자리로 저장된다.
        val added = repos.annotations.add(seen.copy(id = Annotation.NO_ID))
        assertEquals(saved.start to saved.end, store.forBook(id).last().let { it.start to it.end })
        assertEquals(seen.start to seen.end, added.start to added.end)
    }

    @Test
    fun `a highlight ending exactly at a piece boundary stays in the piece before it`() = runTest {
        val l = layout()
        val boundary = l.space().pieceStart(1)
        // 경계에서 끝나는 칠을 뒤 조각 0 으로 두면 뒤 조각 첫 쪽에도 그 칠이 걸린 것으로 보였다.
        assertEquals(Locator.Reflow(0, boundary), l.space().toLayoutEnd(Locator.Reflow(0, boundary)))
        assertEquals(Locator.Reflow(1, 0), l.space().toLayout(Locator.Reflow(0, boundary)))
        assertEquals(Locator.Reflow(0, boundary + 3), l.space().toBook(Locator.Reflow(1, 3)))
    }

    @Test
    fun `a bookmark added on a later piece is saved as a place in the whole text`() = runTest {
        val l = layout()
        val bookmarks = MemoryBookmarks()
        val repos = LayoutRepositories(bookmarks, MemoryProgress(), InMemoryAnnotations(), l::space)
        val session = ReadingSession(l, repos.bookmarks, repos.progress) { 3L }
        val position = l.resolve(Locator.Reflow(3, 0))
        session.addBookmark(position)
        val stored = bookmarks.forBook(id).single().locator as Locator.Reflow
        assertEquals(Locator.Reflow(0, l.space().pieceStart(3)), stored)
        // 책 안에서는 조각 자리로 돌아와 같은 쪽을 책갈피로 알아본다.
        assertTrue(session.isBookmarked(position, position))
    }

    private object NoBookmarks : BookmarkRepository {
        override suspend fun forBook(bookId: BookId): List<Bookmark> = emptyList()
        override suspend fun add(bookmark: Bookmark) = bookmark
        override suspend fun remove(id: Long) = Unit
    }

    private class MemoryBookmarks : BookmarkRepository {
        private val rows = ArrayList<Bookmark>()
        override suspend fun forBook(bookId: BookId): List<Bookmark> = rows.filter { it.bookId == bookId }
        override suspend fun add(bookmark: Bookmark) = bookmark.copy(id = rows.size + 1L).also(rows::add)
        override suspend fun remove(id: Long) { rows.removeAll { it.id == id } }
    }

    private class MemoryProgress : ProgressRepository {
        private val rows = HashMap<BookId, ReadingProgress>()
        override suspend fun get(bookId: BookId) = rows[bookId]
        override suspend fun save(progress: ReadingProgress) { rows[progress.bookId] = progress }
        override suspend fun remove(bookId: BookId) { rows.remove(bookId) }
    }
}
