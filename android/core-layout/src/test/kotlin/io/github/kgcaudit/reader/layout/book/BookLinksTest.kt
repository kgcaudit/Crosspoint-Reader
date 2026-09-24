package io.github.kgcaudit.reader.layout.book

import io.github.kgcaudit.reader.layout.Block
import io.github.kgcaudit.reader.layout.InlineRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 각주 내용 뽑기와 본문 검색(순수 함수). */
class BookLinksTest {

    private fun para(text: String, from: Int, to: Int) = Block.Paragraph(listOf(InlineRun(from, to)), io.github.kgcaudit.reader.layout.BlockStyle.Default)

    @Test
    fun `a note runs until the next note begins`() {
        // "주석" 장: 각주마다 id 가 붙은 문단. 1번을 누르면 1번만 — 2번까지 따라오면 판이 엉뚱하게 길다.
        val text = "주석1) 보아 구렁이: 큰 뱀.둘째 줄 설명.2) 체험한 이야기: 어린이 책."
        val a1 = text.indexOf("1)")
        val a2 = text.indexOf("2)")
        val blocks = listOf(
            para(text, 0, 2),
            para(text, a1, text.indexOf("둘째")),
            para(text, text.indexOf("둘째"), a2),
            para(text, a2, text.length),
        )
        val note = noteText(text, blocks, listOf(a1, a2), a1)
        assertEquals("1) 보아 구렁이: 큰 뱀.\n둘째 줄 설명.", note)
    }

    @Test
    fun `back links are removed and a note without an end stops early`() {
        val text = "각주 내용 ↩" + "긴 문단.".repeat(10)
        val blocks = (0 until 10).map { i -> if (i == 0) para(text, 0, 7) else para(text, 7 + (i - 1) * 5, 7 + i * 5) }
        val note = noteText(text, blocks, listOf(0), 0)
        assertTrue(!note.contains("↩"), note)
        // 끝 표시가 없는 책: 여섯 문단에서 끊는다(장 끝까지 끌려오지 않는다).
        assertEquals(6, note.lines().size)
    }

    @Test
    fun `search ignores case and spacing and gives context around each hit`() {
        val text = "The Boa  Constrictor swallowed it. 보아 구렁이는 씹지 않는다. 또 보아 구렁이."
        val latin = findAll(text, "boa constrictor", 4)
        assertEquals(1, latin.size)
        assertEquals(4, latin.single().spine)
        assertEquals("Boa  Constrictor", text.substring(latin.single().start, latin.single().endExclusive))
        val korean = findAll(text, "보아 구렁이", 0)
        assertEquals(2, korean.size)
        val hit = korean.first()
        // 목록은 문맥 안에서 찾은 말만 칠한다 — 그 자리가 맞아야 한다.
        assertEquals("보아 구렁이", hit.context.substring(hit.contextMatchStart, hit.contextMatchStart + 6))
    }

    @Test
    fun `paragraphs in the context are kept apart`() {
        // 챕터 텍스트는 문단을 이어 붙인다. 문맥에서 "링크.누리집" 처럼 붙으면 한 낱말로 읽힌다.
        val text = "장미 링크.누리집 링크.보아 구렁이"
        val hit = findAll(text, "보아", 0, paragraphStarts = listOf(text.indexOf("누리집"), text.indexOf("보아"))).single()
        assertTrue("링크. 누리집 링크. 보아" in hit.context, hit.context)
        assertEquals("보아", hit.context.substring(hit.contextMatchStart, hit.contextMatchStart + 2))
    }

    @Test
    fun `an empty or blank query finds nothing and odd characters are taken literally`() {
        assertTrue(findAll("아무 글", "   ", 0).isEmpty())
        // 정규식 글자를 그대로 찾는다 — "(" 하나로 앱이 죽으면 안 된다.
        assertEquals(1, findAll("괄호 (열림 만", "(열림", 0).size)
    }
}
