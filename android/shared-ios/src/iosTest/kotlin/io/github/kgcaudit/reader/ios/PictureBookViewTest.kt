@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.document.comic.SpreadSplit
import io.github.kgcaudit.reader.document.epub.PictureBook
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 그림 위주 책을 만화로 보기(구상안 46, 안드로이드 `BookComicTest` · `BookAsComicAppTest`). */
class PictureBookViewTest {
    private val root = NSTemporaryDirectory() + "olo-pictures-" + NSUUID().UUIDString
    private val id = "app:그림책.epub"

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(root, null)
    }

    // 장 0 에 그림 둘, 장 1 은 판권(그림 없음), 장 2 · 3 에 하나씩.
    private val pages = listOf(
        PictureBook.Page(0, "a.png"), PictureBook.Page(0, "b.png"), PictureBook.Page(2, "c.png"), PictureBook.Page(3, "d.png"),
    )

    private fun place(store: ReadingStore = ReadingStore("$root/r.tsv")) = BookComicPlace(store, id, pages)

    @Test
    fun `each picture is written as its chapter and its order in that chapter`() {
        val p = place()
        assertEquals(Locator.Reflow(0, 0), p.locatorOf(0))
        assertEquals(Locator.Reflow(0, 1), p.locatorOf(1))
        assertEquals(Locator.Reflow(2, 0), p.locatorOf(2))
        for (i in pages.indices) assertEquals(i, p.pageOf(p.locatorOf(i)))
    }

    @Test
    fun `a place read as a book lands on a sensible picture`() {
        val p = place()
        // 책으로 보기의 글자 자리(장 0 의 500번째 글자)를 그림 순번으로 읽으면 없는 그림이 된다 — 장의 첫 그림으로.
        assertEquals(0, p.pageOf(Locator.Reflow(0, 500)))
        // 그림 없는 장(판권)은 그 뒤의 첫 쪽, 끝 너머는 마지막.
        assertEquals(2, p.pageOf(Locator.Reflow(1, 0)))
        assertEquals(3, p.pageOf(Locator.Reflow(9, 0)))
        // PDF 쪽 자리는 그림책의 자리가 아니다.
        assertNull(p.pageOf(Locator.FixedPage(1)))
    }

    @Test
    fun `only the true end counts as finished`() {
        assertEquals(100f, BookPlace.percentAt(3, 4, atEnd = true))
        assertEquals(50f, BookPlace.percentAt(2, 5, atEnd = false))
        // 마지막 쪽이어도 펼침면의 첫 반이면 끝이 아니다 — 다 읽음 문턱(99.95)에 닿으면 반만 보고 다 읽은 책이 됐다.
        assertTrue(BookPlace.percentAt(3, 4, atEnd = false) < 99.95f)
    }

    @Test
    fun `the comic view saves the book place so the book view opens there`() {
        val store = ReadingStore("$root/r.tsv")
        // 두 쪽 판(1 · 2쪽): 자리는 앞 쪽 — 끝 쪽을 적으면 다시 열 때 한 쪽 건너뛴다. 진도는 끝 쪽까지.
        place(store).save(first = 1, last = 2, pageCount = 4, atEnd = false, half = SpreadSplit.Half.RIGHT)
        // 다시 켠 뒤에도 책의 자리로 남는다(만화 진도 표가 아니라).
        val again = ReadingStore("$root/r.tsv")
        assertEquals(Locator.Reflow(0, 1), again.progressOf(id)?.locator)
        assertEquals(BookPlace.percentAt(2, 4, atEnd = false), again.progressOf(id)?.percent)
        assertTrue(again.comicProgress().isEmpty())
        // 반은 버린다 — 그 쪽의 처음부터.
        assertEquals(ComicStart(1, SpreadSplit.Half.WHOLE), place(again).start(4))
    }

    @Test
    fun `a book finished at its last page starts over`() {
        val store = ReadingStore("$root/r.tsv")
        place(store).save(first = 3, last = 3, pageCount = 4, atEnd = true, half = SpreadSplit.Half.WHOLE)
        assertNull(place(store).start(4))
        assertTrue(store.shelf(listOf(id)).single().finishedAtEpochMs != null)
    }

    @Test
    fun `a bookmark set in the comic view is the book's bookmark`() {
        val store = ReadingStore("$root/r.tsv")
        val p = place(store)
        assertTrue(p.toggle(listOf(1, 2)))
        assertEquals(listOf(1), p.marks())
        val mark = store.bookmarksOf(id).single()
        assertEquals(Locator.Reflow(0, 1), mark.locator)
        assertEquals("2쪽", mark.snippet)
        // 같은 판에서 다시 누르면 지운다(펼침면의 다른 쪽에 꽂힌 것도).
        assertFalse(p.toggle(listOf(2, 1)))
        assertTrue(store.bookmarksOf(id).isEmpty())
    }

    @Test
    fun `the user's choice wins over detection and a PDF needs a choice`() {
        assertTrue(IosPictureBooks.opensAsComic(null, pdf = false, picture = true))
        assertFalse(IosPictureBooks.opensAsComic(null, pdf = true, picture = false))
        assertTrue(IosPictureBooks.opensAsComic("COMIC", pdf = true, picture = false))
        assertFalse(IosPictureBooks.opensAsComic("BOOK", pdf = false, picture = true))
        // 글 책은 만화로 고른 기록이 남아 있어도 만화로 열지 않는다 — 그림이 없어 빈 화면이 된다.
        assertFalse(IosPictureBooks.opensAsComic("COMIC", pdf = false, picture = false))
    }

    @Test
    fun `detection is redone when the file changes`() {
        val store = ReadingStore("$root/r.tsv")
        IosPictureBooks.remember(store, id, 2048, picture = true, rightToLeft = true)
        assertEquals(true, IosPictureBooks.cached(store, id, 2048))
        assertEquals(true, IosPictureBooks.cachedRightToLeft(store, id))
        IosPictureBooks.remember(store, id, 2048, picture = false)
        assertEquals(false, IosPictureBooks.cached(store, id, 2048))
        assertNull(IosPictureBooks.cachedRightToLeft(store, id))
        IosPictureBooks.remember(store, id, 2048, picture = true)
        assertNull(IosPictureBooks.cached(store, id, 4096))
        store.setComicSetting(IosPictureBooks.PICTURES, id, "깨진 값")
        assertNull(IosPictureBooks.cached(store, id, 2048))
    }

    @Test
    fun `the reading direction is the user's then the book's then left to right`() {
        assertTrue(IosPictureBooks.rightToLeft("1", book = false))
        assertFalse(IosPictureBooks.rightToLeft("0", book = true))
        assertTrue(IosPictureBooks.rightToLeft(null, book = true))
        assertFalse(IosPictureBooks.rightToLeft(null, book = null))
    }

    @Test
    fun `a comic volume keeps using its own page record`() {
        val store = ReadingStore("$root/r.tsv")
        val unit = UnitComicPlace(store, "app:만화/1권.cbz")
        unit.save(first = 4, last = 5, pageCount = 10, atEnd = false, half = SpreadSplit.Half.WHOLE)
        assertEquals(5, store.comicProgress()["app:만화/1권.cbz"]?.page)
        assertNull(store.progressOf("app:만화/1권.cbz"))
    }

    @Test
    fun `a PDF viewed as a comic keeps the PDF page as its place`() {
        val store = ReadingStore("$root/r.tsv")
        val pdf = PdfComicPlace(store, "app:스캔.pdf", 4)
        // PDF 보기가 적은 쪽(쪽 안 세로 자리까지)도 그 쪽으로, 쪽 수 밖은 끝 쪽으로.
        assertEquals(2, pdf.pageOf(Locator.FixedPage(2, 0, 640)))
        assertEquals(3, pdf.pageOf(Locator.FixedPage(7)))
        // 그림책의 자리는 PDF 의 자리가 아니다.
        assertNull(pdf.pageOf(Locator.Reflow(0, 0)))
        pdf.save(first = 1, last = 2, pageCount = 4, atEnd = false, half = SpreadSplit.Half.WHOLE)
        assertEquals(Locator.FixedPage(1), store.progressOf("app:스캔.pdf")?.locator)
        assertTrue(store.comicProgress().isEmpty())
        assertTrue(pdf.toggle(listOf(2)))
        assertEquals(Locator.FixedPage(2), store.bookmarksOf("app:스캔.pdf").single().locator)
    }
}
