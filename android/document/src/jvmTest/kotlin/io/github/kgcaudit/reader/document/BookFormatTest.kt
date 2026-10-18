package io.github.kgcaudit.reader.document

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BookFormatTest {

    @Test
    fun `recognises the three supported extensions regardless of case`() {
        assertEquals(BookFormat.EPUB, BookFormat.fromFileName("책.epub"))
        assertEquals(BookFormat.TXT, BookFormat.fromFileName("NOTES.TXT"))
        assertEquals(BookFormat.PDF, BookFormat.fromFileName("Report.Pdf"))
    }

    @Test
    fun `ignores everything else so the library scan skips it`() {
        listOf("cover.jpg", "book", "archive.epub.bak", ".epub", "a.", "")
            .forEach { assertNull(BookFormat.fromFileName(it)) }
    }

    @Test
    fun `a name without a real extension has none, wherever it is read`() {
        // 곳마다 손으로 꺼내던 때 점 없는 "zip" 이 확장자 "zip" 으로 읽혀 압축 속 권이 됐다(0.48.0).
        assertNull(extensionOf("zip"))
        assertNull(extensionOf("폴더/zip"))
        assertNull(extensionOf(".zip"))
        assertNull(extensionOf("이름."))
        assertNull(extensionOf("폴더.v2/이름"))
        assertEquals("cbz", extensionOf("작품/1권.CBZ"))
        assertEquals("jpg", extensionOf("._001.jpg"))
    }

    @Test
    fun `dots in the name do not confuse the extension`() {
        assertEquals(BookFormat.EPUB, BookFormat.fromFileName("어린 왕자 (1943).v2.epub"))
    }

    @Test
    fun `only pdf takes the fixed page path`() {
        assertTrue(BookFormat.EPUB.isReflowable)
        assertTrue(BookFormat.TXT.isReflowable)
        assertFalse(BookFormat.PDF.isReflowable)
    }

    @Test
    fun `book id rejects blank values`() {
        assertFailsWith<IllegalArgumentException> { BookId("") }
        assertFailsWith<IllegalArgumentException> { BookId("   ") }
    }
}
