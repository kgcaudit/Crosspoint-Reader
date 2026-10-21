@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.github.kgcaudit.reader.ios

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.writeToFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** PDF 기록 · 구조(구상안 32A · 33). 쪽 그리기는 PDFKit(화면)이라 여기서는 목차 · 쪽 이름표 · 자리 · 책갈피만. */
class PdfBookTest {
    private val root = NSTemporaryDirectory() + "olo-pdf-" + NSUUID().UUIDString

    init {
        NSFileManager.defaultManager.createDirectoryAtPath(root, true, null, null)
    }

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(root, null)
    }

    @Test
    fun `title author contents and printed page names come from the file`() {
        val book = open()
        assertEquals("OLO 사용 설명서", book.title, "the word processor prefix is dropped")
        assertEquals("올로", book.author)
        assertEquals(listOf("1장 시작하기" to 2, "2장 책 넣기" to 4), book.contents.map { it.label to it.page })
        // 앞의 두 쪽은 로마 숫자(i, ii), 본문은 1 부터.
        assertEquals("ii", book.pageName(1))
        assertEquals("1", book.pageName(2))
        assertEquals("1장 시작하기", book.section(3))
        assertEquals("", book.section(0), "before the first entry there is no chapter")
    }

    @Test
    fun `the library shows the cleaned title of a pdf`() {
        open()
        val info = IosBookReader.info(path(), "scan_0001.pdf")
        assertEquals("OLO 사용 설명서", info.title)
        assertEquals("올로", info.author)
    }

    @Test
    fun `pages left count to the next contents entry after what is shown`() {
        val book = open()
        assertEquals(1, book.pagesLeftInSection(2), "page 3 is left before chapter 2 starts on page 4")
        assertEquals(0, book.pagesLeftInSection(3))
        assertEquals(1, book.pagesLeftInSection(4), "the last chapter runs to the end of the book")
    }

    @Test
    fun `the place survives reopening and a shorter file opens at its last page`() = runTest {
        val store = ReadingStore("$root/r.tsv")
        val book = open(store)
        assertEquals(0, book.restore().page)
        val percent = book.save(4, 350, lastShown = 5)
        assertEquals(100f, percent, "a spread that shows the last page has read the book")
        val again = IosPdfBook.open(path(), "app:a.pdf", "a.pdf", 6, ReadingStore("$root/r.tsv")).restore()
        assertEquals(4, again.page)
        assertEquals(350, again.yPermille)
        val shorter = IosPdfBook.open(path(), "app:a.pdf", "a.pdf", 3, ReadingStore("$root/r.tsv")).restore()
        assertEquals(2, shorter.page)
        assertEquals(0, shorter.yPermille)
    }

    @Test
    fun `a bookmark on either page of a spread is taken out together`() = runTest {
        val store = ReadingStore("$root/r.tsv")
        val book = open(store)
        assertTrue(book.toggleBookmark(3, 3))
        assertEquals("2쪽", book.bookmarks().single().snippet, "the printed page name, not the file index")
        assertTrue(book.isBookmarked(2, 3))
        // 펼침(2–3쪽)에서 누르면 오른쪽 쪽의 책갈피도 빠진다.
        assertFalse(book.toggleBookmark(2, 3))
        assertTrue(book.bookmarks().isEmpty())
    }

    @Test
    fun `a broken or missing file still opens with the file name and no contents`() {
        val store = ReadingStore("$root/r.tsv")
        val bytes = "%PDF-1.4 깨진 파일".encodeToByteArray()
        val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        data.writeToFile("$root/b.pdf", true)
        val broken = IosPdfBook.open("$root/b.pdf", "app:b.pdf", "깨진 문서.pdf", 2, store)
        assertEquals("깨진 문서", broken.title)
        assertTrue(broken.contents.isEmpty())
        assertEquals("3", broken.pageName(2))
        assertEquals("없는 파일", IosPdfBook.open("$root/없음.pdf", "app:x.pdf", "없는 파일.pdf", 1, store).title)
    }

    private fun path() = "$root/a.pdf"

    private fun open(store: ReadingStore = ReadingStore("$root/r.tsv")): IosPdfBook {
        val bytes = TestPdf.sixPages()
        val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        assertTrue(data.writeToFile(path(), true))
        return IosPdfBook.open(path(), "app:a.pdf", "a.pdf", 6, store)
    }
}

/** 시험용 PDF: 6쪽, 문서 정보(제목 · 저자), 목차 둘, 쪽 이름표(i, ii, 1, 2 …). */
internal object TestPdf {
    private fun utf16(s: String): String = "<FEFF" + s.map { it.code.toString(16).padStart(4, '0').uppercase() }.joinToString("") + ">"

    fun sixPages(): ByteArray {
        val objects = linkedMapOf<Int, String>()
        val pages = (0 until 6).map { 3 + it }
        objects[1] = "<< /Type /Catalog /Pages 2 0 R /Outlines 10 0 R /PageLabels << /Nums [0 << /S /r >> 2 << /S /D >>] >> >>"
        objects[2] = "<< /Type /Pages /Kids [${pages.joinToString(" ") { "$it 0 R" }}] /Count 6 >>"
        for (p in pages) objects[p] = "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] >>"
        objects[10] = "<< /Type /Outlines /First 11 0 R /Last 12 0 R /Count 2 >>"
        objects[11] = "<< /Title ${utf16("1장 시작하기")} /Parent 10 0 R /Next 12 0 R /Dest [5 0 R /Fit] >>"
        objects[12] = "<< /Title ${utf16("2장 책 넣기")} /Parent 10 0 R /Prev 11 0 R /Dest [7 0 R /Fit] >>"
        objects[20] = "<< /Title ${utf16("Microsoft Word - OLO 사용 설명서.docx")} /Author ${utf16("올로")} >>"
        val out = StringBuilder("%PDF-1.4\n")
        val offsets = HashMap<Int, Int>()
        for ((n, body) in objects) {
            offsets[n] = out.length
            out.append("$n 0 obj\n$body\nendobj\n")
        }
        val size = objects.keys.max() + 1
        val xrefAt = out.length
        out.append("xref\n0 $size\n0000000000 65535 f \n")
        for (n in 1 until size) {
            val o = offsets[n]
            out.append(if (o != null) "${o.toString().padStart(10, '0')} 00000 n \n" else "0000000000 65535 f \n")
        }
        out.append("trailer\n<< /Size $size /Root 1 0 R /Info 20 0 R >>\nstartxref\n$xrefAt\n%%EOF\n")
        // 모두 ASCII 라 글자 수 = 바이트 수.
        return out.toString().encodeToByteArray()
    }
}
