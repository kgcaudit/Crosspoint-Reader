package io.github.kgcaudit.reader.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LineBreakRulesTest {

    private fun canBreak(pair: String, cjk: Boolean = true) =
        LineBreakRules.canBreakBetween(pair[0], pair[1], cjk)

    @Test
    fun `hangul hanja and kana are recognised as cjk`() {
        listOf('한', '글', '漢', 'あ', 'ア', '、', '。', '　').forEach {
            assertTrue(LineBreakRules.isCjk(it), "$it 이 CJK 로 인식되지 않았다")
        }
        listOf('a', 'Z', '1', ' ', '.', ' ').forEach {
            assertFalse(LineBreakRules.isCjk(it), "$it 이 CJK 로 잘못 인식됐다")
        }
    }

    @Test
    fun `a break is allowed after a space`() {
        assertTrue(canBreak(" a"))
        assertTrue(canBreak(" 한"))
    }

    @Test
    fun `a latin word is not broken in the middle`() {
        assertFalse(canBreak("ab"))
        assertFalse(canBreak("Zx"))
    }

    @Test
    fun `cjk characters can be broken between when enabled`() {
        // 이게 없으면 한국어 양쪽정렬에서 한 줄에 어절이 두어 개만 들어가고
        // 그 사이 간격이 폭발한다.
        assertTrue(canBreak("한글", cjk = true))
        assertFalse(canBreak("한글", cjk = false))
    }

    @Test
    fun `a boundary between latin and cjk is a break opportunity`() {
        assertTrue(canBreak("a한", cjk = true))
        assertTrue(canBreak("한a", cjk = true))
    }

    @Test
    fun `closing punctuation never starts a line`() {
        // 줄 첫 칸에 마침표가 혼자 오면 조판이 어색해진다.
        listOf("글。", "글，", "글、", "글）", "글」", "가.", "가,", "가!", "가?").forEach {
            assertFalse(canBreak(it), "'${it[1]}' 앞에서 끊겼다")
        }
    }

    @Test
    fun `opening punctuation never ends a line`() {
        listOf("「글", "（글", "［글").forEach {
            assertFalse(canBreak(it), "'${it[0]}' 뒤에서 끊겼다")
        }
    }

    @Test
    fun `a non breaking space holds words together`() {
        // "홍 길동" 이 두 줄로 갈라지면 안 된다.
        assertFalse(LineBreakRules.canBreakBetween(' ', '길', true))
        assertFalse(LineBreakRules.canBreakBetween('홍', ' ', true))
        // 줄 끝에서 지워지는 공백에도 포함되지 않는다(폭을 차지해야 한다).
        assertFalse(LineBreakRules.isSpace(' '))
        assertTrue(LineBreakRules.isSpace(' '))
    }

    @Test
    fun `a hyphen allows a break after it but not inside a number range`() {
        // 하이픈 '뒤'에서 끊는다. 앞에서 끊으면 하이픈이 다음 줄 첫 칸에 온다.
        assertTrue(LineBreakRules.canBreakBetween('-', 'm', true))
        assertFalse(LineBreakRules.canBreakBetween('e', '-', true))
        // 음수·범위 표기가 갈라지면 안 된다.
        assertFalse(LineBreakRules.canBreakBetween('-', '2', true))
    }

    @Test
    fun `a break before a space is never offered`() {
        // 끊으면 그 공백이 다음 줄 첫 칸에 와서 본문이 한 칸 밀려 보인다.
        assertFalse(LineBreakRules.canBreakBetween('나', ' ', true))
        assertFalse(LineBreakRules.canBreakBetween('a', ' ', true))
    }

    @Test
    fun `opportunities are reported as indices to break before`() {
        val text = "가나 다라"
        // 0:가 1:나 2:공백 3:다 4:라  → 한글 사이(1,4)와 공백 뒤(3)
        assertEquals(listOf(1, 3, 4), LineBreakRules.opportunities(text, 0, text.length, true))
        // 어절 경계만 쓰면 공백 뒤 하나뿐이다.
        assertEquals(listOf(3), LineBreakRules.opportunities(text, 0, text.length, false))
    }

    @Test
    fun `the start of the range is never an opportunity`() {
        // 구간 시작을 포함하면 빈 줄이 만들어진다.
        val text = " 가나"
        assertFalse(0 in LineBreakRules.opportunities(text, 0, text.length, true))
    }
}
