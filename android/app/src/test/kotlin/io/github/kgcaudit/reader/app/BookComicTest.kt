package io.github.kgcaudit.reader.app

import io.github.kgcaudit.reader.data.ComicPages
import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.document.comic.ComicUnit
import io.github.kgcaudit.reader.document.comic.ComicUnitKind
import io.github.kgcaudit.reader.document.epub.PictureBook
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 책을 만화로 볼 때(0.50.0) 쪽 번호와 책 자리의 짝 — 책으로 보기와 오가도 같은 곳이어야 한다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BookComicTest {

    private fun comic(n: Int) = ComicBook(ComicUnit("b", "책", emptyList(), ComicUnitKind.ARCHIVE), ComicPages(List(n) { "$it" }, { null }, null))

    // 장 0: 그림 둘 · 장 1: 판권(그림 없음) · 장 2: 그림 하나.
    private val book = PictureBook(listOf(PictureBook.Page(0, "a"), PictureBook.Page(0, "b"), PictureBook.Page(2, "c")), rightToLeft = null)

    @Test
    fun `each page keeps its own place, even two pictures in one chapter`() {
        val c = BookComic.ofPictures(comic(3), book)
        assertEquals(listOf(Locator.Reflow(0, 0), Locator.Reflow(0, 1), Locator.Reflow(2, 0)), (0..2).map(c::locatorOf))
        assertEquals(listOf(0, 1, 2), (0..2).map { c.pageOf(c.locatorOf(it)) })
    }

    @Test
    fun `a place from the book view lands on a picture of that chapter or the next one`() {
        val c = BookComic.ofPictures(comic(3), book)
        // 책으로 보기의 자리는 글자 자리다 — 장 0 의 500번째 글자를 그림 순번으로 읽으면 없는 쪽이다. 그 장의 첫 그림으로.
        assertEquals(0, c.pageOf(Locator.Reflow(0, 500)))
        // 그림 없는 장(판권)을 가리키면 그 뒤의 첫 쪽.
        assertEquals(2, c.pageOf(Locator.Reflow(1, 0)))
        // 책 끝을 넘어선 자리는 마지막 쪽.
        assertEquals(2, c.pageOf(Locator.Reflow(9, 0)))
        // 다른 종류의 자리는 나타낼 수 없다.
        assertNull(c.pageOf(Locator.FixedPage(1)))
    }

    @Test
    fun `a pdf page is its own place and the last page reads as finished`() {
        val c = BookComic.ofPdf(comic(4))
        assertEquals(Locator.FixedPage(2), c.locatorOf(2))
        assertEquals(3, c.pageOf(Locator.FixedPage(7)))
        assertEquals(100f, BookComic.percentAt(3, 4))
        assertEquals(50f, BookComic.percentAt(1, 3))
    }
}
