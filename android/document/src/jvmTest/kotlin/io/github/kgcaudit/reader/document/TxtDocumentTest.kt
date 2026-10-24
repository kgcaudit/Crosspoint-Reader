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
        val doc = open("book.txt", text.toByteArray(java.nio.charset.Charset.forName(TextEncoding.koreanCharset)))
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

    // ── 큰 TXT 의 조각(0.60.0) ──────────────────────────────────────

    private fun scenes(count: Int) = (1..count).joinToString("\n\n") { scene ->
        (1..30).joinToString("\n") { line -> "$scene 장면 $line 번째 줄, 후치는 마차 바퀴에 등을 기대고 별을 세었다." }
    }

    @Test
    fun `the pieces of a big txt join back into exactly the whole text`() = runTest {
        val text = scenes(80)
        val doc = open("big.txt", text.toByteArray())
        val spine = doc.spine()
        assertTrue(spine.size >= 4, "조각이 여럿이어야 하는 크기: ${spine.size}")
        val joined = buildString { for (i in spine.indices) append(doc.openChapter(i).use { r -> r.readText() }) }
        // 한 글자라도 다르면 저장된 자리(r:0:N)가 다른 글자를 가리킨다.
        assertEquals(text, joined)
        assertEquals(text.length, doc.textLength())
    }

    @Test
    fun `a big txt is cut where a scene changes`() = runTest {
        val text = scenes(80)
        val starts = open("big.txt", text.toByteArray()).pieceStarts()
        for (start in starts.drop(1)) {
            // 조각 경계에서 쪽이 일찍 끝나므로 문단 한가운데가 아니라 빈 줄 뒤 첫 줄에서 자른다.
            assertTrue(text.startsWith("\n\n", start - 2), "빈 줄 뒤가 아닌 곳에서 잘랐다: ${text.substring(start - 10, start + 10)}")
        }
        for (i in 1 until starts.size) {
            val size = starts[i] - starts[i - 1]
            assertTrue(size in TxtDocument.PIECE_CHARS / 2..TxtDocument.PIECE_CHARS * 3 / 2, "조각 $i 크기 $size")
        }
    }

    @Test
    fun `a small txt stays one chapter as before`() = runTest {
        val doc = open("small.txt", scenes(3).toByteArray())
        assertEquals(1, doc.spine().size)
        assertEquals(listOf(0), doc.pieceStarts().toList())
    }

    @Test
    fun `windows line endings are counted the way the reader counts them`() = runTest {
        // 저장된 자리는 줄바꿈을 \n 하나로 맞춘 글의 번호다. 맞추기 전 글에서 자르면 조각마다 자리가 밀렸다.
        val text = scenes(80)
        val doc = open("crlf.txt", text.replace("\n", "\r\n").toByteArray())
        val joined = buildString { for (i in doc.spine().indices) append(doc.openChapter(i).use { r -> r.readText() }) }
        assertEquals(text, joined)
        assertEquals(text.length, doc.textLength())
    }

    @Test
    fun `a txt without blank lines or line breaks is still cut into pieces`() {
        val lines = (1..4000).joinToString("\n") { "줄 $it 번째, 빈 줄 없이 이어지는 글이다." }
        val byLine = TxtDocument.pieceStartsOf(lines)
        assertTrue(byLine.size > 1)
        // 빈 줄이 없으면 줄의 처음에서.
        byLine.drop(1).forEach { assertEquals('\n', lines[it - 1]) }

        // 줄바꿈조차 없는 파일 — 목표 자리에서 자르되 이모지를 반으로 나누지 않는다.
        val emoji = "😀"
        // 앞 한 글자로 무늬(5칸)를 밀어, 목표 자리가 이모지 한가운데에 떨어지게 한다.
        val flat = buildString { append("앞"); while (length < TxtDocument.PIECE_CHARS * 3) append("가나다").append(emoji) }
        assertTrue(flat[TxtDocument.PIECE_CHARS].isLowSurrogate(), "시험 전제: 목표 자리가 이모지 뒤 반쪽")
        val cuts = TxtDocument.pieceStartsOf(flat)
        assertTrue(cuts.size > 1)
        cuts.forEach { assertTrue(!flat[it].isLowSurrogate(), "이모지를 반으로 잘랐다: $it") }
    }
}
