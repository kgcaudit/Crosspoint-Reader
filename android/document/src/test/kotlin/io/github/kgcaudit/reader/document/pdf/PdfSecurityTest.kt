package io.github.kgcaudit.reader.document.pdf

import io.github.kgcaudit.reader.document.SeekableSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 소유자 암호만 걸린(빈 사용자 암호) PDF — 잡지 · 전자책이 흔히 이렇다. 시험 파일은
 * `resources/pdf/make_encrypted.py` 가 이 코드와 **따로** 만든다(같은 코드로 암호화·복호화하면 같이 틀려도 통과).
 */
class PdfSecurityTest {

    private fun structure(name: String): PdfStructure {
        val bytes = javaClass.getResourceAsStream("/pdf/$name")!!.use { it.readBytes() }
        return PdfStructureReader.read(SeekableSource.of(bytes))
    }

    private fun PdfStructure.summary() = outline.map { "${it.pageIndex}:${it.label}" }

    @Test
    fun `contents of owner-password pdfs read in every encryption the format has`() {
        // 인쇄·복사만 막은 파일은 누구나 열 수 있다(PdfRenderer 도 연다). 목차도 보여야 한다.
        for (name in listOf("encrypted-rc4-128.pdf", "encrypted-aes-128.pdf", "encrypted-aes-256.pdf")) {
            val book = structure(name)
            assertEquals(listOf("0:1장 어린 새", "2:2장 검은 숨"), book.summary(), name)
            assertEquals("소년이 온다", book.title, name)
            assertEquals("한강", book.author, name)
        }
    }

    @Test
    fun `an encrypted object stream is decrypted once, not twice`() {
        // 실제 잡지 PDF 의 모양: 목차 항목이 암호화된 객체 스트림 안에 있다. 스트림째 풀었으니 안의 문자열을
        // 한 번 더 풀면 깨진다(명세: 객체 스트림 속 문자열은 따로 암호화하지 않는다).
        val book = structure("encrypted-aes-256-objstm.pdf")
        assertEquals(listOf("0:1장 어린 새", "2:2장 검은 숨"), book.summary())
        assertEquals("소년이 온다", book.title)
    }

    @Test
    fun `a pdf locked with a real password gives nothing instead of garbled titles`() {
        // 빈 암호로 열리지 않는 파일은 풀 수 없다. 암호문을 그대로 제목으로 보이면 깨진 글자가 된다.
        val book = structure("encrypted-locked.pdf")
        assertTrue(book.outline.isEmpty())
        assertEquals(null, book.title)
    }
}
