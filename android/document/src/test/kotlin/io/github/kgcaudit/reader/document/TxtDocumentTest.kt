package io.github.kgcaudit.reader.document

import io.github.kgcaudit.reader.document.text.TextEncoding
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TxtDocumentTest {

    private fun open(name: String, bytes: ByteArray) =
        TxtDocument.open(BookId("test"), name, ByteSource.of(bytes))

    @Test
    fun `title comes from the file name without its extension`() {
        assertEquals("어린 왕자", open("어린 왕자.txt", "본문".toByteArray()).meta.title)
        assertEquals("notes.v2", open("notes.v2.txt", "x".toByteArray()).meta.title)
        // 확장자만 있는 이름은 그대로 둔다(제목을 비워 두면 목록에서 빈 줄이 된다).
        assertEquals(".txt", open(".txt", "x".toByteArray()).meta.title)
    }

    @Test
    fun `spine has exactly one chapter and no outline`() = runTest {
        val doc = open("book.txt", "본문".toByteArray())
        assertEquals(listOf(SpineItem(0, TxtDocument.SINGLE_HREF)), doc.spine())
        assertTrue(doc.outline().isEmpty())
        assertNull(doc.openResource("anything"))
    }

    @Test
    fun `utf8 content reads back unchanged`() = runTest {
        val text = "첫 줄\n두 번째 줄\n"
        val doc = open("book.txt", text.toByteArray())
        assertEquals(text, doc.openChapter(0).use { it.readText() })
    }

    @Test
    fun `cp949 content reads back as correct hangul`() = runTest {
        val text = "긴 문장을 여러 줄에 걸쳐 적는다.\n한글이 충분히 많아야 판정이 안정적이다.\n"
        val doc = open("book.txt", text.toByteArray(TextEncoding.EUC_KR.charset))
        assertEquals(TextEncoding.EUC_KR, doc.encoding.encoding)
        assertEquals(text, doc.openChapter(0).use { it.readText() })
    }

    @Test
    fun `a bom is skipped so the text does not start with an invisible character`() = runTest {
        val text = "본문 첫 글자"
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray()
        val doc = open("book.txt", withBom)
        val read = doc.openChapter(0).use { it.readText() }
        assertEquals(text, read)
        assertTrue(read.first() != '﻿', "BOM leaked into the text")
    }

    @Test
    fun `utf16 with a bom reads back unchanged`() = runTest {
        val text = "유니코드 본문\n둘째 줄\n"
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        assertEquals(text, open("book.txt", bytes).openChapter(0).use { it.readText() })
    }

    @Test
    fun `the chapter can be reopened, since re-pagination reads it again`() = runTest {
        val text = "다시 읽어도 같아야 한다"
        val doc = open("book.txt", text.toByteArray())
        repeat(3) { assertEquals(text, doc.openChapter(0).use { r -> r.readText() }) }
    }

    @Test
    fun `asking for a chapter other than the first fails loudly`() = runTest {
        val doc = open("book.txt", "본문".toByteArray())
        val error = runCatching { doc.openChapter(1) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException, "expected IllegalArgumentException, got $error")
    }

    @Test
    fun `an empty file opens without throwing`() = runTest {
        val doc = open("empty.txt", ByteArray(0))
        assertEquals("", doc.openChapter(0).use { it.readText() })
    }
}
