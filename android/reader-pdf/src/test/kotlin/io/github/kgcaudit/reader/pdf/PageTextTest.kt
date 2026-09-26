package io.github.kgcaudit.reader.pdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PageTextTest {

    private val JUSTIFIED_RIGHT = 0.9f

    /**
     * 줄들을 쪽에 놓는다: 줄마다 높이 [lineH], 글자 폭 [charW](고정폭), 줄 사이는 "\r\n"(PdfRenderer 가 내주는
     * 모양). [tops] 로 줄마다 세로 자리를 따로 줄 수 있다(머리말 · 쪽 번호).
     */
    private fun page(
        lines: List<String>,
        tops: List<Float>? = null,
        lefts: List<Float>? = null,
        lineH: Float = 0.02f,
        charW: Float = 0.02f,
        /** 줄마다 글자 높이(제목은 크다). */
        heights: List<Float>? = null,
        /** 줄 간격(글자 높이에 대한 배수). */
        leading: Float = 1.5f,
        /** 양쪽 맞춤으로 오른쪽 [JUSTIFIED_RIGHT] 까지 늘일 줄들(인쇄된 책처럼 글자 사이를 벌린다). */
        justified: Set<Int> = emptySet(),
    ): PageText {
        val text = StringBuilder()
        val boxes = ArrayList<Float>()
        lines.forEachIndexed { k, line ->
            if (k > 0) {
                text.append("\r\n")
                repeat(8) { boxes += Float.NaN }
            }
            val h = heights?.get(k) ?: lineH
            val top = tops?.get(k) ?: (0.15f + k * lineH * leading)
            val left = lefts?.get(k) ?: 0.1f
            val w = if (k in justified) (JUSTIFIED_RIGHT - left) / line.length else charW
            line.forEachIndexed { i, c ->
                text.append(c)
                boxes += listOf(left + i * w, top, left + (i + 1) * w, top + h)
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
            // 제목은 본문보다 글자가 크다.
            heights = listOf(0.02f, 0.03f, 0.02f, 0.02f),
        )
        val spoken = p.speech().sentences.map { p.text.substring(it.start, it.endExclusive) }
        assertEquals(listOf("2-1 확대하기", "PDF 는 쪽 모양 그대로 보입니다."), spoken)
    }

    @Test
    fun `a heading without a full stop is read on its own, and a paragraph ending short starts a new one`() {
        // 제목은 글자가 크다. 셋째 줄은 짧게, 마침표로 끝난다.
        val p = page(
            listOf("2-1 확대하기", "PDF 는 쪽 모양 그대로 보입니다 글자가 작으면 두 손가", "락으로 벌립니다.", "확대 배율은 다섯 배까지입니다"),
            heights = listOf(0.03f, 0.02f, 0.02f, 0.02f),
            tops = listOf(0.10f, 0.15f, 0.18f, 0.21f),
        )
        val spoken = p.speech().sentences.map { p.text.substring(it.start, it.endExclusive) }
        // 제목과 본문이 한 숨에 읽히지 않는다. 짧게 끝난 셋째 줄 다음 줄은 새 문단.
        assertEquals("2-1 확대하기", spoken.first())
        assertEquals("확대 배율은 다섯 배까지입니다", spoken.last())
        assertEquals(3, spoken.size)
    }

    /** 사용자 소설 PDF(현남 · 0.18.0 실기기 검토)와 같은 모양: 양쪽 맞춤, 글자 단위 줄바꿈, 줄 간격 = 글자 높이의 0.8배. */
    private val novel = page(
        listOf(
            "민철 엄마 말처럼 처음 몇 개월 동안은 자주 울었다. 눈물이 나면 흐",
            "르게 내버려뒀다. 눈물을 흘리다가 손님이 들어오면 아무렇지 않게 눈물",
            "을 닦고 손님을 맞았다. 손님들은 영주의 눈물을 모른 체했다. 왜 우느냐",
            "고 묻지 않았다.",
            "  눈물의 이유는 과거 그 자리에 그대로 있었지만, 영주는 어느 날 문",
        ),
        leading = 1.8f,
        charW = 0.0115f,
        justified = setOf(0, 1, 2, 4),
    )

    private fun spokenOf(p: PageText): List<String> {
        val speech = p.speech()
        return speech.sentences.map { io.github.kgcaudit.reader.layout.book.speakable(speech.text, it) }
    }

    @Test
    fun `a sentence running onto the next line is read as one, with a word broken at the line end joined`() {
        val spoken = spokenOf(novel)
        // 줄 간격이 넉넉해도 줄마다 새 문단이 아니다 — 문장이 줄을 넘어 이어 읽힌다.
        assertTrue("눈물이 나면 흐르게 내버려뒀다." in spoken, spoken.toString())
        // 줄 끝에서 끊긴 낱말은 공백 없이("흐르게", "눈물을", "우느냐고").
        assertTrue("눈물을 흘리다가 손님이 들어오면 아무렇지 않게 눈물을 닦고 손님을 맞았다." in spoken, spoken.toString())
        assertTrue("왜 우느냐고 묻지 않았다." in spoken, spoken.toString())
        // 짧게, 마침표로 끝난 줄 다음의 들여 쓴 줄은 새 문단.
        assertEquals("눈물의 이유는 과거 그 자리에 그대로 있었지만, 영주는 어느 날 문", spoken.last())
        // 듣기 글은 원문과 길이가 같다 — 문장 칠이 같은 글자를 가리킨다.
        assertEquals(novel.text.length, novel.speech().text.length)
    }

    @Test
    fun `a line break between words of a justified book stays a space, a particle on the next line joins`() {
        // 글자 단위로 줄을 바꾼 책도 어절 사이에서 끊기는 일이 많다(실제 소설 PDF: "그러는↵건데", "영주가↵커피").
        val p = page(
            listOf(
                "제가 원래는 너무 완벽한 사람이라 일부러 어리숙해 보이려 그러는",
                "건데, 그게 안 통하는 것 같아요. 하지만 당장 서점을 위해 뭔가",
                "를 할 마음은 나지 않았다.",
            ),
            justified = setOf(0, 1),
            leading = 1.8f,
            charW = 0.0115f,
        )
        val spoken = spokenOf(p)
        assertTrue(spoken.any { it.contains("그러는 건데") }, spoken.toString())
        // 다음 줄이 조사 하나("를")로 시작하면 앞 낱말에 붙는다.
        assertTrue(spoken.any { it.contains("뭔가를 할") }, spoken.toString())
    }

    @Test
    fun `a short line in a ragged book keeps its space even when the word could join`() {
        // "친구" 는 어절 끝 글자로 보지 않는 말이지만, 앞 줄이 오른쪽 끝까지 차지 않았으니 어절 단위 줄바꿈이다.
        val p = page(listOf("나는 어제 저녁에 오래된 친구", "집에 갔다. 가는 길에 비가 내리기 시작했고 우산이 없었다"))
        assertTrue(spokenOf(p).any { it.contains("친구 집에") }, spokenOf(p).toString())
    }

    @Test
    fun `an indented line starts a new paragraph even after a full line without a stop`() {
        // 시 · 인용처럼 마침표 없이 끝까지 찬 줄 다음의 들여 쓴 줄 — 붙이면 "생각했다그런데" 가 된다.
        val p = page(
            listOf("그날 밤 나는 오래도록 창밖을 보며 생각했다", "그런데 아침이 되자 모든 것이 달라 보였다."),
            lefts = listOf(0.1f, 0.14f),
            justified = setOf(0),
        )
        val spoken = spokenOf(p)
        assertEquals(listOf("그날 밤 나는 오래도록 창밖을 보며 생각했다", "그런데 아침이 되자 모든 것이 달라 보였다."), spoken)
    }

    @Test
    fun `a ragged line ends at a word boundary so its line break stays a space`() {
        // 오른쪽이 들쭉날쭉(어절 단위로 줄을 바꾼 책): 짧게 끝난 줄은 어절 끝이다 — 붙이면 "학교에갔다" 가 된다.
        val p = page(listOf("그날 아침 나는 오래된 가방을 메고 학교로", "갔다. 가는 길에 비가 내리기 시작했고 우산이 없었다 나는 뛰었다"))
        assertTrue("그날 아침 나는 오래된 가방을 메고 학교로 갔다." in spokenOf(p), spokenOf(p).toString())
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
        // 깨진 글자 사이에 로마자 몇 개가 섞여도(글꼴 표가 일부만 있는 PDF) 읽을 글이 아니다 — 찾거나 읽으면 헛돈다.
        assertFalse(PageText.isReadable("\uE001\uE002\uE003\uE004\uE005\uE006\uE007\uE008\uE009\uE00A ab"))
        // 깨진 글자가 조금 섞인 본문은 읽을 글이다.
        assertTrue(PageText.isReadable("책갈피는 쪽 번호로 저장된다 \uFFFD"))
    }

    @Test
    fun `a page whose engine gave no positions still reads and finds, but cannot be touched`() {
        val p = PageText.textOnly("첫 문장이다. 둘째 문장이다.")
        assertTrue(p.lines.isEmpty())
        assertNull(p.charAt(0.5f, 0.5f))
        assertTrue(p.rects(0, 5).isEmpty())
        assertEquals(2, p.speech().sentences.size)
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
