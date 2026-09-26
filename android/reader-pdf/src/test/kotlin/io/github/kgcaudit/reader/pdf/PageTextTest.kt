package io.github.kgcaudit.reader.pdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PageTextTest {

    /**
     * 줄들을 쪽에 놓는다: 줄마다 높이 [lineH], 글자 폭 [charW](고정폭), 줄 사이는 "\r\n"(PdfRenderer 가 내주는
     * 모양). [tops] 로 줄마다 세로 자리를 따로 줄 수 있다(머리말 · 쪽 번호).
     */
    private fun page(lines: List<String>, tops: List<Float>? = null, lefts: List<Float>? = null, lineH: Float = 0.02f, charW: Float = 0.02f): PageText {
        val text = StringBuilder()
        val boxes = ArrayList<Float>()
        lines.forEachIndexed { k, line ->
            if (k > 0) {
                text.append("\r\n")
                repeat(8) { boxes += Float.NaN }
            }
            val top = tops?.get(k) ?: (0.15f + k * lineH * 1.5f)
            val left = lefts?.get(k) ?: 0.1f
            line.forEachIndexed { i, c ->
                text.append(c)
                boxes += listOf(left + i * charW, top, left + (i + 1) * charW, top + lineH)
            }
        }
        return PageText(text.toString(), boxes.toFloatArray())
    }

    private val body = page(
        listOf(
            "PDF 는 쪽 모양 그대로 보입니다. 글자가 작",
            "으면 두 손가락으로 벌립니다.",
            "확대 배율은 다섯 배까지입니다.",
        ),
    )

    @Test
    fun `lines follow the positions, not the line break characters`() {
        assertEquals(3, body.lines.size)
        assertEquals("으면 두 손가락으로 벌립니다.", body.text.substring(body.lines[1].start, body.lines[1].endExclusive))
    }

    @Test
    fun `a long press picks the word under the finger and nothing in the margin`() {
        // "모양" 의 "모" 를 누른다: 첫 줄 8번째 글자(0부터).
        val at = body.charAt(0.1f + 8.5f * 0.02f, 0.16f)!!
        assertEquals("모양", body.text.substring(body.wordAt(at).first, body.wordAt(at).last + 1))
        // 줄보다 한참 아래(여백)는 아무것도 아니다 — 빈 곳을 길게 누르면 고르기가 시작되지 않아야 한다.
        assertNull(body.charAt(0.3f, 0.9f))
        // 줄 사이 살짝 아래는 봐준다(손가락은 글자보다 굵다).
        assertEquals(at, body.charAt(0.1f + 8.5f * 0.02f, 0.15f + 0.02f + 0.005f))
        // 어절 사이 공백을 눌러도 가까운 어절을 잡는다.
        assertEquals("쪽", body.text.substring(body.wordAt(body.charAt(0.1f + 7.1f * 0.02f, 0.16f)!!).first, body.wordAt(body.charAt(0.1f + 7.1f * 0.02f, 0.16f)!!).last + 1))
    }

    @Test
    fun `the end handle takes the character when the right half is under the finger`() {
        val x = 0.1f + 3 * 0.02f
        assertEquals(3, body.charAt(x + 0.004f, 0.16f, after = true))
        assertEquals(4, body.charAt(x + 0.016f, 0.16f, after = true))
    }

    @Test
    fun `a range across a line break paints one rectangle per line and skips the break`() {
        val start = body.text.indexOf("작")
        val end = body.text.indexOf("두") + 1
        val rects = body.rects(start, end)
        assertEquals(2, rects.size)
        assertEquals(0.1f + 25 * 0.02f, rects[0].left, 1e-4f)
        // 둘째 줄은 줄 머리부터 "두" 까지(공백 셋 포함 네 글자 폭).
        assertEquals(0.1f, rects[1].left, 1e-4f)
        assertEquals(0.1f + 4 * 0.02f, rects[1].right, 1e-4f)
        // 줄 높이 그대로 — 칠이 줄마다 들쭉날쭉하지 않다.
        assertEquals(0.02f, rects[0].height, 1e-4f)
    }

    @Test
    fun `a quote joins the lines with a space`() {
        assertEquals("작 으면", body.quote(body.text.indexOf("작"), body.text.indexOf("면") + 1))
    }

    @Test
    fun `listening skips a running head and page number but reads the body and a long last line`() {
        val p = page(
            listOf(
                "OLO 사용 설명서      2장 넘기기", // 머리말(쪽 위 가장자리)
                "2-1 확대하기",
                "PDF 는 쪽 모양 그대로 보입니다.",
                "18", // 쪽 번호(쪽 아래 가장자리)
            ),
            tops = listOf(0.03f, 0.12f, 0.16f, 0.95f),
        )
        val spoken = p.speech().map { p.text.substring(it.start, it.endExclusive) }
        assertEquals(listOf("2-1 확대하기", "PDF 는 쪽 모양 그대로 보입니다."), spoken)
    }

    @Test
    fun `a heading without a full stop is read on its own, and a paragraph ending short starts a new one`() {
        val p = page(listOf("2-1 확대하기", "PDF 는 쪽 모양 그대로 보입니다 글자가 작으면 두 손가", "락으로 벌립니다", "확대 배율은 다섯 배까지입니다"))
        val spoken = p.speech().map { p.text.substring(it.start, it.endExclusive) }
        // 제목과 본문이 한 숨에 읽히지 않는다. 짧게 끝난 셋째 줄 다음 줄은 새 문단.
        assertEquals("2-1 확대하기", spoken.first())
        assertEquals("확대 배율은 다섯 배까지입니다", spoken.last())
        assertEquals(3, spoken.size)
    }

    @Test
    fun `a column to the left at the same height is another line`() {
        // 두 단: 오른쪽 단 첫 줄 다음에 왼쪽 단 줄이 같은 높이로 나온다(파일 순서). 한 줄로 합치면 칠이 두 단을 가로지른다.
        val p = page(listOf("오른쪽 단", "왼쪽 단"), tops = listOf(0.2f, 0.2f), lefts = listOf(0.55f, 0.1f))
        assertEquals(2, p.lines.size)
    }

    @Test
    fun `scanned or broken text is not treated as readable`() {
        assertFalse(PageText.isReadable(""))
        assertFalse(PageText.isReadable("   \r\n  "))
        // 글자 모양을 문자로 되돌리는 표가 없는 PDF: 사용자 영역 글자뿐.
        assertFalse(PageText.isReadable(" "))
        assertTrue(PageText.isReadable("PDF 는 쪽 모양 그대로 보입니다."))
    }

    @Test
    fun `a page whose engine gave no positions still reads and finds, but cannot be touched`() {
        val p = PageText.textOnly("첫 문장이다. 둘째 문장이다.")
        assertTrue(p.lines.isEmpty())
        assertNull(p.charAt(0.5f, 0.5f))
        assertTrue(p.rects(0, 5).isEmpty())
        assertEquals(2, p.speech().size)
    }

    @Test
    fun `broken boxes from the engine are ignored instead of crashing`() {
        // 뒤집힌 네모 · NaN · 길이가 안 맞는 배열.
        val bad = PageText("가나", floatArrayOf(0.5f, 0.5f, 0.4f, 0.4f, Float.NaN, 0f, 1f, 1f))
        assertNull(bad.box(0))
        assertNull(bad.box(1))
        assertTrue(bad.lines.isEmpty())
        assertTrue(runCatching { PageText("가", FloatArray(3)) }.isFailure)
    }
}

class GlyphBoxesTest {
    private fun row(n: Int, width: Float) = FloatArray(n * 4).also { b ->
        for (i in 0 until n) { b[i * 4] = i * 0.02f; b[i * 4 + 1] = 0.1f; b[i * 4 + 2] = i * 0.02f + width; b[i * 4 + 3] = 0.12f }
    }

    @Test
    fun `boxes two characters wide are recognised so the engine is asked again one by one`() {
        // 엔진이 (i, i+1) 을 "두 글자까지" 로 읽었다면 네모가 이웃과 반씩 겹친다. 알아채지 못하면 칠이 한 글자씩 넘친다.
        assertTrue(mostlyDoubled("가나다라마바", row(6, 0.04f)))
        assertFalse(mostlyDoubled("가나다라마바", row(6, 0.02f)))
        // 글자가 너무 적으면 판단하지 않는다(짧은 쪽 번호 하나로 뒤집히지 않게).
        assertFalse(mostlyDoubled("가나", row(2, 0.04f)))
    }
}
