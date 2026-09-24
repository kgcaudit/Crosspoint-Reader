package io.github.kgcaudit.reader.reflow

import io.github.kgcaudit.reader.layout.Page
import io.github.kgcaudit.reader.layout.PlacedRun
import io.github.kgcaudit.reader.layout.TextMeasurer
import io.github.kgcaudit.reader.layout.TextStyle
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 글자 고르기(N4)의 셈: 누른 자리 → 글자, 길게 누른 낱말, 손잡이 끌기. 글자마다 폭 10 · 줄 높이 20 인 가짜
 * 측정기로 좌표를 손으로 셀 수 있게 한다.
 */
class SelectionTest {

    private object Mono : TextMeasurer {
        override val baseSizePx = 10f
        override fun advance(text: CharSequence, start: Int, endExclusive: Int, style: TextStyle) = (endExclusive - start) * 10f
        override fun lineHeight(style: TextStyle) = 20f
        override fun ascent(style: TextStyle) = 16f
        override fun spaceAdvance(style: TextStyle) = 10f
    }

    // 두 줄: "보아 구렁이는" (0..7) 이 x=0 에서, "모자" (7..9) 가 둘째 줄. 둘째 줄은 양쪽정렬로 벌어진 조각 둘.
    private val text = "보아 구렁이는모자 뱀"
    private val page = Page(
        index = 0, startChar = 0, endCharExclusive = text.length,
        runs = listOf(
            PlacedRun(0, 7, TextStyle(), xPx = 0f, baselineYPx = 16f),
            PlacedRun(7, 9, TextStyle(), xPx = 0f, baselineYPx = 46f),
            PlacedRun(9, 11, TextStyle(), xPx = 60f, baselineYPx = 46f),
        ),
    )

    @Test
    fun `a touch picks the character under the finger, line by line`() {
        assertEquals(0, charAt(page, text, Mono, 3f, 10f))
        assertEquals(3, charAt(page, text, Mono, 35f, 10f))
        // 둘째 줄의 벌어진 조각: x 60 부터가 9번 글자다. 벌림을 모르면 x 60 을 6번째 글자로 센다.
        assertEquals(9, charAt(page, text, Mono, 62f, 40f))
        // 줄 사이(y 25)는 가까운 줄로 — 행간을 눌렀다고 아무것도 안 고르면 손가락이 헛돈다.
        assertEquals(1, charAt(page, text, Mono, 12f, 23f))
    }

    @Test
    fun `touching past the end of a line picks its last character, and the end handle lands after it`() {
        // 여백까지 끌어도 고르기가 사라지지 않는다.
        assertEquals(6, charAt(page, text, Mono, 300f, 10f))
        assertEquals(7, charAt(page, text, Mono, 300f, 10f, after = true))
        // 끝 손잡이: 글자 오른쪽 반이면 그 글자까지 고른다.
        assertEquals(4, charAt(page, text, Mono, 37f, 10f, after = true))
        assertEquals(3, charAt(page, text, Mono, 32f, 10f, after = true))
    }

    @Test
    fun `an empty page has nothing to pick`() {
        assertNull(charAt(Page(0, 0, 0), text, Mono, 10f, 10f))
    }

    @Test
    fun `a long press picks the whole word around the finger`() {
        // 한국어는 어절 하나. 띄어쓰기 · 문장부호에서 끊는다.
        assertEquals(3..6, wordAt("보아 구렁이는, 모자", 4))
        assertEquals(0..1, wordAt("보아 구렁이는", 0))
        // 공백을 누르면 그 한 칸 — 빈 구간이면 메뉴가 뜰 자리가 없다.
        assertEquals(2..2, wordAt("보아 구렁이는", 2))
        assertEquals(IntRange.EMPTY, wordAt("", 0))
    }

    @Test
    fun `a handle cannot cross the other one`() {
        val sel = Selection(3, 7)
        // 시작 손잡이를 끝 너머로 끌어도 한 글자는 남는다. 뒤집힌 구간은 메뉴를 엉뚱한 곳에 띄운다.
        assertEquals(Selection(6, 7), dragHandle(sel, isStart = true, offset = 9))
        assertEquals(Selection(3, 4), dragHandle(sel, isStart = false, offset = 1))
        assertEquals(Selection(1, 7), dragHandle(sel, isStart = true, offset = 1))
        // 고를 글자가 없는 자리(그림 위)면 그대로.
        assertEquals(sel, dragHandle(sel, isStart = false, offset = null))
    }

    @Test
    fun `a snippet is one tidy line and a long one is cut`() {
        assertEquals("보아 구렁이는 모자", snippetOf("  보아\n 구렁이는\t\t모자 ", 0, 16))
        // 장 텍스트는 문단을 구분자 없이 잇는다 — 문단이 시작하는 자리에 한 칸.
        assertEquals("삼킨다. 어른들은", snippetOf("삼킨다.어른들은", 0, 9, paragraphStarts = setOf(4)))
        val long = "가".repeat(1000)
        assertEquals(401, snippetOf(long, 0, 1000).length)
        // 범위가 텍스트 밖이어도(옛 책 · 상한 자리) 죽지 않는다.
        assertEquals("", snippetOf("짧다", 10, 20))
    }
}
