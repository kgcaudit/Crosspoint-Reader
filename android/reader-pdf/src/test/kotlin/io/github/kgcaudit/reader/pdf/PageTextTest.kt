package io.github.kgcaudit.reader.pdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
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
    fun `a line from a font that cannot be recovered is skipped instead of spelled out`() {
        // 씨네21 15쪽: 모양 번호를 새로 매긴 글꼴이라 엔진이 제어 문자와 기호를 내준다. 그대로 읽으면 알파벳 · 기호를
        // 하나씩 읽는다. 그 줄은 건너뛰고, 멀쩡한 줄은 읽는다.
        val garbled = "E1)-@ \u001eBA >JC \u001d\u0012F< :\u0011A \u000e\u001a! \u0014\u0019\u000e( \u0010\u000b\u0005 \u00126H\u000e\u0001"
        val p = page(listOf("멀쩡한 첫 문장이다.", garbled, "멀쩡한 끝 문장이다."), tops = listOf(0.2f, 0.3f, 0.4f))
        assertEquals(listOf("멀쩡한 첫 문장이다.", "멀쩡한 끝 문장이다."), spokenOf(p))
    }

    @Test
    fun `letters stored out of order in a line are read in the order they are seen`() {
        // 좋은생각 109쪽 글귀: 자간을 맞추는 글꼴이 글자를 쓴 순서가 보이는 순서와 달라 "번역은" 이 "번은역" 으로 왔다.
        // 글자와 네모를 함께 화면 순서로 옮긴다. 위치를 모르는 글자(쉼표가 "\r" 로 온 것)는 앞 글자를 따라간다.
        val shown = "번역은 단순히,"
        val stored = intArrayOf(0, 2, 1, 3, 4, 5, 6, 7) // "번은역 단순히,"
        val boxes = FloatArray(shown.length * 4) { Float.NaN }
        val text = StringBuilder()
        stored.forEachIndexed { k, i ->
            text.append(shown[i])
            if (shown[i] != ',') listOf(0.1f + i * 0.02f, 0.2f, 0.1f + (i + 1) * 0.02f, 0.22f).forEachIndexed { j, v -> boxes[k * 4 + j] = v }
        }
        val fixed = PageText(text.toString(), boxes).inVisualOrder()
        assertEquals(shown, fixed.text)
        // 네모도 함께 옮겼다 — "역" 자리를 누르면 "역" 이다.
        assertEquals('역', fixed.text[fixed.charAt(0.1f + 1.5f * 0.02f, 0.21f)!!])
        // 순서가 맞는 줄은 그대로(같은 것) — 보통 쪽의 저장한 형광펜 자리가 바뀌지 않는다.
        val plain = page(listOf("이미 순서가 맞는 줄이다."))
        assertSame(plain, plain.inVisualOrder())
    }

    @Test
    fun `a box low on the left is read after a title high on the right`() {
        // 씨네21 18쪽: 오른쪽 위 제목 · 왼쪽 아래 ITEM 상자. 세로 틈이 있어도 높이가 전혀 겹치지 않으면 단이 아니다.
        val lines = listOf("갱신하는 질문, 확장하는 춤.", "장혜림 안무감독.", "괄사와 아로마 오일.", "해외 출장에도 함께하는 동반자다.")
        val p = page(lines, tops = listOf(0.1f, 0.14f, 0.7f, 0.74f), lefts = listOf(0.6f, 0.6f, 0.1f, 0.1f), charW = 0.012f)
        assertEquals(lines, spokenOf(p))
        val swapped = page(lines.drop(2) + lines.take(2), tops = listOf(0.7f, 0.74f, 0.1f, 0.14f), lefts = listOf(0.1f, 0.1f, 0.6f, 0.6f), charW = 0.012f)
        assertEquals(lines, spokenOf(swapped))
    }

    @Test
    fun `a quote set in a bigger font than the body is still one sentence across its lines`() {
        // 좋은생각 109쪽: 본문보다 큰 글귀 두 줄이 줄마다 새 덩이가 되어 "대담한 여정" · "이다." 로 따로 읽혔다.
        val p = page(
            listOf("본문 첫 줄이다.", "본문 둘째 줄이다.", "본문 셋째 줄이다.", "번역은 한 문화를 옮기는 대담한 여정", "이다."),
            heights = listOf(0.02f, 0.02f, 0.02f, 0.03f, 0.03f),
            tops = listOf(0.2f, 0.23f, 0.26f, 0.4f, 0.445f),
            justified = setOf(3),
        )
        assertEquals("번역은 한 문화를 옮기는 대담한 여정이다.", spokenOf(p).last())
    }

    @Test
    fun `print marks left outside the page are not read however long they are`() {
        // 씨네21 1569호 18쪽: 조판 프로그램의 인쇄용 표시("…016.indd 16 2026-08-07 오후…")가 쪽 아래 밖에 남아, 화면에는
        // 없는 글을 듣기가 알파벳 하나씩 읽었다. 가장자리의 짧은 줄만 건너뛰던 규칙으로는 긴 표시가 걸리지 않았다.
        val slug = "Cine21 016-017 STAFF indd 16 2026-08-07 PM 12:18:32 proof"
        val p = page(listOf("괄사와 아로마 오일.", "해외 출장에도 함께하는 동반자다.", slug), tops = listOf(0.7f, 0.74f, 1.04f))
        assertEquals(listOf("괄사와 아로마 오일.", "해외 출장에도 함께하는 동반자다."), spokenOf(p))
        // 쪽 안의 같은 줄은 읽는다(쪽 밖이라서 뺀 것이지 길어서 뺀 것이 아니다).
        val inside = page(listOf("괄사와 아로마 오일.", slug), tops = listOf(0.5f, 0.6f))
        assertTrue(spokenOf(inside).any { it.startsWith("Cine21") }, spokenOf(inside).toString())
    }

    @Test
    fun `a magazine page is read from the top down whatever order the text was placed in`() {
        // 좋은생각 77쪽: 조판 프로그램이 시 → 이름 → 맨 아래 안내문 → 가운데 심사평 상자 순으로 넣었다. 글자 층의
        // 순서대로 읽으면 안내문이 심사평보다 먼저 나온다.
        val lines = listOf(
            "떠나오는 산길은 가장자리로 걷기만 했다.",
            "강철주 님.",
            "좋은님의 자작시를 보내 주세요.",
            "이번 호에는 좋은 시가 여럿 들어왔다.",
            "매우 특별하고 보기 드문 작품이다.",
        )
        val p = page(lines, tops = listOf(0.2f, 0.3f, 0.8f, 0.5f, 0.53f), lefts = listOf(0.15f, 0.15f, 0.19f, 0.19f, 0.19f))
        assertEquals(
            listOf(lines[0], lines[1], lines[3], lines[4], lines[2]),
            spokenOf(p),
        )
        // 목록 순서만 바뀐다 — 문장 자리는 글 그대로(문장 칠이 그 글자를 가리킨다).
        val sentences = p.speech().sentences
        assertEquals(lines[2], p.text.substring(sentences.last().start, sentences.last().endExclusive))
    }

    @Test
    fun `two columns are read column by column even when their paragraphs end at the same height`() {
        // 두 단 기사, 단마다 두 줄짜리 문단 둘. 두 단의 문단이 같은 높이에서 끝나 가로 틈이 생겨도, 왼쪽 단을 다 읽고
        // 오른쪽 단으로 간다(가로부터 자르면 왼 · 오른 문단을 번갈아 읽는다).
        val left = listOf("왼쪽 단 첫 문단 첫 줄.", "왼쪽 단 첫 문단 끝 줄.", "왼쪽 단 둘째 문단 첫 줄.", "왼쪽 단 둘째 문단 끝 줄.")
        val right = left.map { it.replace("왼쪽", "오른쪽") }
        val tops = listOf(0.2f, 0.23f, 0.33f, 0.36f)
        fun twoColumns(first: List<String>, firstLeft: Float, second: List<String>, secondLeft: Float) =
            page(first + second, tops = tops + tops, lefts = List(4) { firstLeft } + List(4) { secondLeft }, charW = 0.015f)
        assertEquals(left + right, spokenOf(twoColumns(left, 0.1f, right, 0.55f)))
        // 글자 층이 오른쪽 단을 먼저 넣었어도 같다.
        assertEquals(left + right, spokenOf(twoColumns(right, 0.55f, left, 0.1f)))
    }

    @Test
    fun `a text box set further in than the body is read in sentences, not line by line`() {
        // 잡지 쪽(좋은생각 "좋은님 시 마당"): 위에 시, 아래에 본문보다 안쪽에 놓인 심사평 상자. 쪽 전체의 왼쪽 끝과 견주면
        // 상자의 줄이 모두 들여 쓴 줄이라 "시 안에는 세 개" 에서 끊겨 읽혔다.
        val lines = listOf(
            "떠나오는 산길은 가장자리로 걷기만 했다",
            "밟히며 피운 질경이가 웅크리고 하얗게 올려보고 있었다",
            "이번 호에는 좋은 시가 여럿 들어와 선뜻 하나를 고르기 쉽지 않았다. 끝내는 문",
            "학성이 강한 작품 쪽으로 마음을 정했다.",
            "〈질경이 꽃〉은 서사적이며 동심원적 구성을 가진 시다. 이런 특이성이 이 작품을",
            "손에서 내려놓지 못하게 했다. 매우 특별하고 보기 드문 작품이다. 시 안에는 세 개",
            "의 동심원이 있다. 가장 중심의 원에는 ‘어머니’, 그다음 원에는 ‘누에고치’, 표면의",
            "원에는 ‘질경이 꽃’이 자리한다.",
        )
        val p = page(
            lines,
            tops = listOf(0.25f, 0.28f) + (0..5).map { 0.5f + it * 0.03f },
            lefts = listOf(0.15f, 0.15f) + List(6) { 0.19f },
            charW = 0.01f,
            justified = setOf(2, 4, 5, 6),
        )
        val spoken = spokenOf(p)
        assertTrue("매우 특별하고 보기 드문 작품이다." in spoken, spoken.toString())
        assertTrue("시 안에는 세 개의 동심원이 있다." in spoken, spoken.toString())
        assertTrue("끝내는 문학성이 강한 작품 쪽으로 마음을 정했다." in spoken, spoken.toString())
        // 짧게 마침표로 끝난 줄 다음은 여전히 새 문단이다(상자 안에서도).
        assertTrue(spoken.any { it.startsWith("〈질경이 꽃〉은") }, spoken.toString())
        // 시와 상자는 다른 덩이 — 시의 끝 줄이 상자의 첫 문장에 붙지 않는다.
        assertTrue(spoken.none { "있었다 이번" in it || "있었다이번" in it }, spoken.toString())
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

class LayerAssemblyTest {

    /**
     * 안드로이드 PDF 엔진(pdfClient)을 그대로 흉내 낸다: 번호 [first]..[last] 만 글이 있고(앞뒤 공백 · 하이픈은 잘림),
     * 번호 2 는 "-\r\n" 으로 늘어나며, 고르기는 [시작, 끝) 이다.
     */
    private class FakeClient(val codes: IntArray) {
        val first = codes.indexOfFirst { it !in listOf(0x20, 0x2D, 2, 13, 10) }
        val last = codes.indexOfLast { it !in listOf(0x20, 0x2D, 2, 13, 10) }
        fun textOf(i: Int) = if (codes[i] == 2) "-\r\n" else codes[i].toChar().toString()
        /** 쪽 전체 글(textContents): 잘리고 늘어난 것. */
        val whole = (first..last).joinToString("") { textOf(it) }
        fun select(from: Int, to: Int): Pair<String, FloatArray?> {
            val a = from.coerceIn(first, last + 1)
            val b = to.coerceIn(first, last + 1)
            val s = (a until b).joinToString("") { textOf(it) }
            // 글자 i 의 네모: x = i × 0.01 — 어느 번호의 네모인지 알아볼 수 있게.
            return s to (a until b).firstOrNull { codes[it] != 0x20 }?.let { floatArrayOf(it * 0.01f, 0.1f, it * 0.01f + 0.01f, 0.12f) }
        }
    }

    @Test
    fun `every letter keeps its own box when the engine trims and expands the page text`() {
        // 앞머리 공백 둘(잘림) · 가운데 번호 2 둘(세 글자로 늘어남). 쪽 전체 글의 번호로 고르면 뒤 글자의 네모가 밀렸다.
        val codes = intArrayOf(0x20, 0x20, '가'.code, '나'.code, 2, '다'.code, 2, '라'.code, '마'.code)
        val engine = FakeClient(codes)
        val layer = assembleLayer(engine.whole.length + 64) { a, b -> engine.select(a, b) }
        assertEquals("가나\u0002다\u0002라마", layer.text)
        // "마" 는 번호 8 의 네모(x = 0.08)를 가진다 — 밀렸다면 다른 글자의 네모다.
        val ma = layer.text.indexOf('마')
        assertEquals(0.08f, layer.box(ma)!!.left, 1e-4f)
        assertEquals(0.02f, layer.box(0)!!.left, 1e-4f)
    }

    @Test
    fun `the broken word mark becomes a hyphen outside broken words and a letter inside them`() {
        // 멀쩡한 글에서는 줄 끝 하이픈("exam-" · "ple"), 대응표 없는 글꼴 낱말 안에서는 번호 2 = "!".
        assertEquals("exam-ple", BrokenHangul.repair("exam\u0002ple"))
    }
}
