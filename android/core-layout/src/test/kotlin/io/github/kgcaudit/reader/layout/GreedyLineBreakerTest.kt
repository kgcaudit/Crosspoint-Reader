package io.github.kgcaudit.reader.layout

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GreedyLineBreakerTest {

    private val measurer = FakeMeasurer(baseSizePx = 10f)

    /** 기본 폭 기준: 라틴 글자 10px, 전각 20px, 공백 5px. */
    private fun breakLines(
        text: String,
        widthPx: Float,
        align: TextAlign = TextAlign.Start,
        indentPx: Float = 0f,
        breakCjk: Boolean = true,
        style: TextStyle = TextStyle.Default,
    ): List<LaidLine> = GreedyLineBreaker(breakBetweenCjk = breakCjk).breakLines(
        text = text,
        runs = listOf(InlineRun(0, text.length, style)),
        style = BlockStyle(align = align),
        constraints = LineConstraints(widthPx, indentPx),
        measurer = measurer,
    )

    /**
     * 각 줄이 덮는 텍스트. 줄바꿈 위치를 눈으로 확인하는 데 쓴다.
     *
     * 그려지는 조각이 아니라 줄의 **구간**을 쓴다 — 조각은 줄 끝 공백을 일부러
     * 제외하므로(그리지 않으니까) 조각을 이어 붙이면 "aa bb" 가 "aabb" 로 보인다.
     */
    private fun rendered(text: String, lines: List<LaidLine>): List<String> =
        lines.map { text.substring(it.startChar, it.endCharExclusive).trimEnd() }

    /** 줄의 오른쪽 끝 위치(px). */
    private fun rightEdge(text: String, line: LaidLine): Float {
        val last = line.pieces.lastOrNull() ?: return 0f
        return last.xPx + measurer.advance(text, last.start, last.endExclusive, last.style)
    }

    // ── 기본 채우기 ─────────────────────────────────────────────────

    @Test
    fun `words fill a line and wrap at a space`() {
        // "aa bb cc" : 각 낱말 20px, 공백 5px → 폭 50 이면 "aa bb"(45) 까지
        val text = "aa bb cc"
        assertEquals(listOf("aa bb", "cc"), rendered(text, breakLines(text, 50f)))
    }

    @Test
    fun `a trailing space does not push the last word to the next line`() {
        // CSS 와 같은 동작. 넣어 계산하면 들어갈 수 있는 낱말이 밀려난다.
        val text = "aa bb"
        assertEquals(listOf("aa bb"), rendered(text, breakLines(text, 45f)))
    }

    @Test
    fun `every character is covered exactly once across the lines`() {
        // 진도와 책갈피가 줄 구간으로 기록되므로, 빠뜨리거나 겹치면 이어읽기가 어긋난다.
        val text = "한글 문장이 여러 줄로 나뉘는 상황을 확인한다"
        val lines = breakLines(text, 120f)
        assertEquals(0, lines.first().startChar)
        assertEquals(text.length, lines.last().endCharExclusive)
        lines.zipWithNext { a, b ->
            assertEquals(a.endCharExclusive, b.startChar, "줄 사이에 구멍이나 겹침이 있다")
        }
    }

    @Test
    fun `an empty paragraph produces no lines`() {
        assertTrue(breakLines("", 100f).isEmpty())
    }

    // ── 한국어 ──────────────────────────────────────────────────────

    @Test
    fun `korean text breaks between characters so lines fill the width`() {
        // 전각 20px, 폭 100 → 한 줄에 5자.
        val text = "한국어문장이길게이어진다"
        val lines = breakLines(text, 100f)
        assertEquals(listOf("한국어문장", "이길게이어", "진다"), rendered(text, lines))
    }

    @Test
    fun `no line ever exceeds the available width`() {
        // 넘치면 지면 밖에서 글자가 잘린다. 어떤 조합에서도 깨지면 안 되는 불변식이라
        // 여러 텍스트·폭·모드를 훑는다.
        val texts = listOf(
            "한국어문장이길게이어진다",
            "한국어 문장과 English words 가 섞인다",
            "aa bb cc dd ee ff gg",
            "verylongunbreakabletokenwithoutspaces",
            "괄호（여는것）과 마침표。가 섞인 문장이다",
        )
        for (text in texts) {
            for (width in listOf(15f, 37f, 60f, 100f, 155f)) {
                for (cjk in listOf(true, false)) {
                    for (align in TextAlign.entries) {
                        val lines = breakLines(text, width, align, breakCjk = cjk)
                        lines.forEach { line ->
                            val right = rightEdge(text, line)
                            if (right > width + 0.5f) {
                                // 유일하게 허용되는 넘침: 쪼갤 수 없는 한 글자가
                                // 지면보다 넓을 때(좁은 지면 + 전각). 글자를 버리는
                                // 것보다 넘치는 게 낫다.
                                // 그려지는 글자 수로 센다. 줄 끝 공백은 구간에 들어
                                // 있지만 그리지 않으므로 넘침의 원인이 될 수 없다.
                                val drawn = line.pieces.sumOf { it.endExclusive - it.start }
                                assertEquals(
                                    1,
                                    drawn,
                                    "넘침: text='$text' width=$width cjk=$cjk align=$align " +
                                        "right=$right drawn=$drawn",
                                )
                            }
                        }
                        // 전체 구간이 빠짐없이 덮여야 한다.
                        if (lines.isNotEmpty()) {
                            assertEquals(text.length, lines.last().endCharExclusive)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `word only breaking still splits an oversized korean word rather than overflowing`() {
        // 어절 경계만 쓰는 모드에서도 어절 하나가 폭보다 넓으면 쪼갠다. 넘치게 두면
        // 글자가 잘리는데, 그건 어떤 조판 정책으로도 정당화되지 않는다.
        val text = "한국어문장이길게이어진다"
        val lines = breakLines(text, 100f, breakCjk = false)
        assertTrue(lines.size > 1, "쪼개지지 않았다")
        lines.forEach { assertTrue(rightEdge(text, it) <= 100.5f) }
        assertEquals(text.length, lines.last().endCharExclusive)
    }

    @Test
    fun `justification stretches only real word gaps, not gaps between cjk characters`() {
        // 이게 이 엔진의 핵심 판단이다. CJK 글자 사이까지 늘리면 문단 전체가 균일
        // 자간으로 렌더되고, 그건 한국어 조판에서 틀린 결과다.
        val text = "한글 두 어절이 있다"
        val lines = breakLines(text, 200f, align = TextAlign.Justify)
        val first = lines.first()

        // 마지막 줄이 아닌 줄만 늘어난다. 한 줄에 다 들어가면 늘리지 않는다.
        if (!first.isLastLine) {
            // 늘어난 자리는 공백이 있던 경계뿐이어야 한다.
            first.pieces.zipWithNext { a, b ->
                val gap = b.xPx - (a.xPx + measurer.advance(text, a.start, a.endExclusive, a.style))
                val hadSpace = text.substring(a.endExclusive, b.start).any { it == ' ' }
                if (!hadSpace) {
                    assertTrue(abs(gap) < 0.5f, "공백이 없던 자리가 ${gap}px 늘어났다")
                }
            }
        }
    }

    @Test
    fun `a justified line ends exactly at the right edge`() {
        // 나머지 픽셀을 버리면 오른쪽 끝이 들쭉날쭉해진다.
        val text = "aa bb cc dd ee ff gg hh"
        val width = 103f
        val lines = breakLines(text, width, align = TextAlign.Justify)
        lines.filterNot { it.isLastLine }.forEach { line ->
            val last = line.pieces.last()
            val right = last.xPx + measurer.advance(text, last.start, last.endExclusive, last.style)
            assertTrue(abs(right - width) < 1.5f, "오른쪽 끝이 ${right}px, 폭은 ${width}px")
        }
    }

    @Test
    fun `the last line of a justified block is not stretched`() {
        val text = "aa bb cc dd"
        val lines = breakLines(text, 50f, align = TextAlign.Justify)
        val last = lines.last()
        assertTrue(last.isLastLine)
        // 간격이 기본 공백 폭(5px)이어야 한다.
        last.pieces.zipWithNext { a, b ->
            val gap = b.xPx - (a.xPx + measurer.advance(text, a.start, a.endExclusive, a.style))
            assertTrue(abs(gap - 5f) < 0.5f, "마지막 줄 간격이 ${gap}px 로 늘어났다")
        }
    }

    // ── 정렬 ────────────────────────────────────────────────────────

    @Test
    fun `alignment positions the line within the available width`() {
        val text = "aa" // 20px
        assertEquals(0f, breakLines(text, 100f, TextAlign.Start).single().pieces.single().xPx)
        assertEquals(40f, breakLines(text, 100f, TextAlign.Center).single().pieces.single().xPx)
        assertEquals(80f, breakLines(text, 100f, TextAlign.End).single().pieces.single().xPx)
    }

    @Test
    fun `the first line indent shifts only the first line`() {
        val text = "aa bb cc dd"
        val lines = breakLines(text, 50f, indentPx = 20f)
        assertEquals(20f, lines.first().pieces.first().xPx)
        assertEquals(0f, lines[1].pieces.first().xPx)
        // 들여쓰기만큼 첫 줄에 들어가는 글자가 줄어든다.
        assertEquals("aa", rendered(text, lines).first())
    }

    // ── 넘치는 내용 ─────────────────────────────────────────────────

    @Test
    fun `a single token wider than the line is split instead of overflowing`() {
        // 긴 URL 이나 공백 없는 라틴 단어. 그대로 두면 지면 밖으로 넘쳐 글자가 잘린다.
        val text = "abcdefghij"
        val lines = breakLines(text, 30f)
        assertEquals(listOf("abc", "def", "ghi", "j"), rendered(text, lines))
        assertEquals(text.length, lines.last().endCharExclusive)
    }

    @Test
    fun `a very narrow line still makes progress and terminates`() {
        // 폭이 한 글자보다 좁아도 무한 루프에 빠지지 않아야 한다.
        val text = "abcd"
        val lines = breakLines(text, 5f)
        assertEquals(4, lines.size)
        assertEquals(text.length, lines.last().endCharExclusive)
    }

    // ── 혼합 서식 ───────────────────────────────────────────────────

    @Test
    fun `line height follows the tallest style on the line`() {
        // 한 줄에 크기가 섞이면 큰 쪽에 맞춰야 글자가 겹치지 않는다.
        val text = "aa bb"
        val lines = GreedyLineBreaker().breakLines(
            text = text,
            runs = listOf(
                InlineRun(0, 3, TextStyle.Default),
                InlineRun(3, 5, TextStyle(sizeScale = 2f)),
            ),
            style = BlockStyle.Default,
            constraints = LineConstraints(200f),
            measurer = measurer,
        )
        val line = lines.single()
        assertEquals(measurer.lineHeight(TextStyle(sizeScale = 2f)), line.heightPx)
        assertEquals(measurer.ascent(TextStyle(sizeScale = 2f)), line.ascentPx)
    }

    @Test
    fun `adjacent characters of the same style merge into one piece`() {
        // 글자 단위 줄바꿈은 토큰을 글자마다 만든다. 그대로 조각이 되면 캐시가 부풀고
        // 그리기도 글자마다 호출이 된다. 서식이 같고 글자가 이어지면 한 조각이어야 한다.
        val text = "가나다라"
        val lines = breakLines(text, 200f)
        val piece = lines.single().pieces.single()
        assertEquals(0, piece.start)
        assertEquals(4, piece.endExclusive)
    }

    @Test
    fun `a style change splits the pieces but keeps each style`() {
        val text = "가나다라"
        val bold = TextStyle(bold = true)
        val lines = GreedyLineBreaker().breakLines(
            text = text,
            runs = listOf(InlineRun(0, 2, TextStyle.Default), InlineRun(2, 4, bold)),
            style = BlockStyle.Default,
            constraints = LineConstraints(200f),
            measurer = measurer,
        )
        val pieces = lines.flatMap { it.pieces }
        assertEquals(listOf(false, true), pieces.map { it.style.bold })
        assertEquals(listOf(0 to 2, 2 to 4), pieces.map { it.start to it.endExclusive })
    }

    @Test
    fun `merging spans word spaces when nothing is inserted between`() {
        // 공백도 함께 그려지므로, 벌어진 틈이 없으면 어절을 넘어 묶어도 그림이 같다.
        val text = "aa bb cc"
        val piece = breakLines(text, 200f).single().pieces.single()
        assertEquals(0, piece.start)
        assertEquals(text.length, piece.endExclusive)
    }

    @Test
    fun `justification gaps stop the merge so the spacing is not lost`() {
        // 양쪽정렬이 끼워 넣은 여유를 무시하고 묶으면 그 간격이 사라진다.
        val text = "aa bb cc dd ee"
        val lines = breakLines(text, 103f, align = TextAlign.Justify)
        val stretched = lines.first { !it.isLastLine }
        assertTrue(stretched.pieces.size > 1, "늘어난 줄이 한 조각으로 묶였다")
    }

    @Test
    fun `line height multiplier scales the line box`() {
        val text = "aa"
        val tight = GreedyLineBreaker(lineHeightMultiplier = 1f)
        val wide = GreedyLineBreaker(lineHeightMultiplier = 1.5f)
        val args = Triple(listOf(InlineRun(0, 2)), BlockStyle.Default, LineConstraints(100f))
        val a = tight.breakLines(text, args.first, args.second, args.third, measurer).single()
        val b = wide.breakLines(text, args.first, args.second, args.third, measurer).single()
        assertEquals(a.heightPx * 1.5f, b.heightPx, absoluteTolerance = 0.01f)
    }

    // ── 금칙 ────────────────────────────────────────────────────────

    @Test
    fun `a closing mark is not left alone at the start of a line`() {
        // 폭이 딱 맞아떨어져 마침표가 다음 줄로 밀릴 상황을 만든다.
        val text = "한국어문장。이어진다"
        val lines = breakLines(text, 100f)
        rendered(text, lines).drop(1).forEach { line ->
            assertTrue(line.firstOrNull() != '。', "줄이 마침표로 시작한다: $line")
        }
    }
}
