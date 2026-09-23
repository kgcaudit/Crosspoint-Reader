package io.github.kgcaudit.reader.layout.conformance

import io.github.kgcaudit.reader.layout.FakeMeasurer
import io.github.kgcaudit.reader.layout.TextMeasurer
import io.github.kgcaudit.reader.layout.TextStyle
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 검사 자체를 검사한다.
 *
 * 없으면 안드로이드 쪽에서 "문제 없음" 이라는 말만 듣고 넘어갈 수 있다. 일부러 망가진
 * 구현들이 실제로 걸리는지 여기서 확인해 둔다 — 통과만 확인하는 검사는 검사가 아니다.
 */
class MeasurerConformanceTest {

    private fun problems(measurer: TextMeasurer) = MeasurerConformance.check(measurer)

    /** 온전한 구현을 감싸고 한 가지만 망가뜨린다. */
    private open class Wrapper(val inner: TextMeasurer) : TextMeasurer {
        override val baseSizePx: Float get() = inner.baseSizePx
        override fun advance(text: CharSequence, start: Int, endExclusive: Int, style: TextStyle) =
            inner.advance(text, start, endExclusive, style)
        override fun lineHeight(style: TextStyle) = inner.lineHeight(style)
        override fun ascent(style: TextStyle) = inner.ascent(style)
        override fun spaceAdvance(style: TextStyle) = inner.spaceAdvance(style)
    }

    @Test
    fun `the deterministic fake measurer conforms`() {
        val found = problems(FakeMeasurer())
        assertTrue(found.isEmpty(), "가짜 측정기가 자기 계약을 못 지킨다:\n" + found.joinToString("\n"))
    }

    @Test
    fun `it conforms at other base sizes too`() {
        listOf(8f, 10f, 16f, 24f, 48f).forEach { size ->
            val found = problems(FakeMeasurer(baseSizePx = size))
            assertTrue(found.isEmpty(), "기준 크기 $size 에서 실패:\n" + found.joinToString("\n"))
        }
    }

    @Test
    fun `a font with no hangul is caught`() {
        // 기기에서만 드러나는 실패다. 조판은 멀쩡하고 화면만 깨진다.
        val noHangul = object : Wrapper(FakeMeasurer()) {
            override fun advance(text: CharSequence, start: Int, endExclusive: Int, style: TextStyle): Float {
                var total = 0f
                for (i in start until endExclusive) {
                    if (text[i] !in '가'..'힣') total += inner.advance(text, i, i + 1, style)
                }
                return total
            }
        }
        assertTrue(problems(noHangul).any { "한글" in it }, "한글 없는 폰트를 못 잡았다")
    }

    @Test
    fun `widths that do not add up are caught`() {
        val notAdditive = object : Wrapper(FakeMeasurer()) {
            override fun advance(text: CharSequence, start: Int, endExclusive: Int, style: TextStyle): Float =
                inner.advance(text, start, endExclusive, style) + if (endExclusive > start) 7f else 0f
        }
        assertTrue(problems(notAdditive).any { "쪼개" in it }, "더해지지 않는 폭을 못 잡았다")
    }

    @Test
    fun `a zero width is caught`() {
        val zero = object : Wrapper(FakeMeasurer()) {
            override fun advance(text: CharSequence, start: Int, endExclusive: Int, style: TextStyle) = 0f
        }
        assertTrue(problems(zero).isNotEmpty(), "폭 0 을 못 잡았다 — 조판기가 같은 자리를 맴돈다")
    }

    @Test
    fun `a non-monotonic width is caught`() {
        val wobbly = object : Wrapper(FakeMeasurer()) {
            override fun advance(text: CharSequence, start: Int, endExclusive: Int, style: TextStyle): Float {
                val base = inner.advance(text, start, endExclusive, style)
                return if (endExclusive - start == 5) base / 4f else base
            }
        }
        assertTrue(problems(wobbly).isNotEmpty(), "줄어드는 폭을 못 잡았다")
    }

    @Test
    fun `a non-deterministic measurer is caught`() {
        var calls = 0
        val drifting = object : Wrapper(FakeMeasurer()) {
            override fun advance(text: CharSequence, start: Int, endExclusive: Int, style: TextStyle) =
                inner.advance(text, start, endExclusive, style) + (calls++ % 2) * 0.5f
        }
        assertTrue(problems(drifting).any { "두 번 재니" in it }, "값이 흔들리는 측정기를 못 잡았다")
    }

    @Test
    fun `broken vertical metrics are caught`() {
        val tallAscent = object : Wrapper(FakeMeasurer()) {
            override fun ascent(style: TextStyle) = inner.lineHeight(style) * 2f
        }
        assertTrue(problems(tallAscent).any { "ascent" in it }, "줄 높이보다 큰 ascent 를 못 잡았다")

        val flat = object : Wrapper(FakeMeasurer()) {
            override fun lineHeight(style: TextStyle) = 0f
        }
        assertTrue(problems(flat).isNotEmpty(), "줄 높이 0 을 못 잡았다")
    }

    @Test
    fun `a size scale that is ignored is caught`() {
        val fixedSize = object : Wrapper(FakeMeasurer()) {
            override fun advance(text: CharSequence, start: Int, endExclusive: Int, style: TextStyle) =
                inner.advance(text, start, endExclusive, TextStyle.Default)
            override fun lineHeight(style: TextStyle) = inner.lineHeight(TextStyle.Default)
        }
        assertTrue(problems(fixedSize).any { "sizeScale" in it }, "크기 배율을 무시하는 구현을 못 잡았다")
    }

    @Test
    fun `a space width that disagrees with the measured space is caught`() {
        val lyingSpace = object : Wrapper(FakeMeasurer()) {
            override fun spaceAdvance(style: TextStyle) = inner.spaceAdvance(style) * 3f
        }
        assertTrue(problems(lyingSpace).any { "spaceAdvance" in it }, "어긋난 공백 폭을 못 잡았다")
    }

    @Test
    fun `an empty range that is not zero is caught`() {
        val nonZeroEmpty = object : Wrapper(FakeMeasurer()) {
            override fun advance(text: CharSequence, start: Int, endExclusive: Int, style: TextStyle) =
                inner.advance(text, start, endExclusive, style) + 1f
        }
        assertTrue(problems(nonZeroEmpty).any { "빈 구간" in it }, "빈 구간의 폭을 못 잡았다")
    }

    @Test
    fun `every problem reads as a sentence a person can act on`() {
        val broken = object : Wrapper(FakeMeasurer()) {
            override fun advance(text: CharSequence, start: Int, endExclusive: Int, style: TextStyle) = 0f
            override fun lineHeight(style: TextStyle) = 0f
        }
        val found = problems(broken)
        assertTrue(found.isNotEmpty())
        found.forEach {
            assertTrue(it.length > 25, "설명이 너무 짧다: $it")
            // 기기 로그에 남는 한 줄이 곧 원인이어야 한다 — 증상만 적혀 있으면 다시 파야 한다.
            assertTrue(it.contains('.') || it.contains('—'), "무슨 일이 생기는지가 빠졌다: $it")
        }
    }
}
