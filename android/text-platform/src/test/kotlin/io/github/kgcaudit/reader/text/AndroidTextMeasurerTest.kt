package io.github.kgcaudit.reader.text

import android.content.Context
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.layout.Block
import io.github.kgcaudit.reader.layout.BlockStyle
import io.github.kgcaudit.reader.layout.InlineRun
import io.github.kgcaudit.reader.layout.Insets
import io.github.kgcaudit.reader.layout.LayoutSpec
import io.github.kgcaudit.reader.layout.Page
import io.github.kgcaudit.reader.layout.Paginator
import io.github.kgcaudit.reader.layout.TextAlign
import io.github.kgcaudit.reader.layout.TextStyle
import io.github.kgcaudit.reader.layout.conformance.MeasurerConformance
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 번들 글꼴로 실제 `Paint` 를 돌려 보는 테스트.
 *
 * Robolectric 의 네이티브 그래픽스는 기기와 같은 minikin·FreeType 으로 글자를 잰다.
 * 그래서 기기 없이도 "폰트가 한글을 못 그린다", "글꼴을 바꾸니 줄이 무너진다" 같은
 * 기기에서만 드러나던 실패를 여기서 잡는다. 레거시 그래픽스(기본값)는 글자 수를 폭으로
 * 돌려주는 가짜라 이 테스트의 의미가 없어진다 — [ConformanceHarnessTest] 가 그걸 지킨다.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AndroidTextMeasurerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun spec(font: ReaderFont, baseSizePx: Float = 42f) = LayoutSpec(
        viewportWidthPx = 1080f,
        viewportHeightPx = 1600f,
        margin = Insets.all(48f),
        baseSizePx = baseSizePx,
        lineHeightMultiplier = 1.5f,
        align = TextAlign.Justify,
        paragraphIndentEm = 1f,
        fontId = font.layoutFontId,
    )

    private fun measurer(font: ReaderFont, baseSizePx: Float = 42f) =
        AndroidTextMeasurer.forSpec(context, spec(font, baseSizePx))

    // ── 계약 ────────────────────────────────────────────────────────

    @Test
    fun `every bundled font passes the measurer conformance suite`() {
        // 한글 글리프 검사가 B2(번들 글꼴) 결정을 검증한다. 휴대폰 본문 크기(xhdpi 기준
        // 16sp ≈ 42px)와 작은 크기 둘 다 본다 — 힌팅 반올림은 작은 크기에서 커진다.
        for (font in ReaderFont.entries) {
            for (size in listOf(16f, 42f)) {
                val problems = MeasurerConformance.check(measurer(font, size))
                assertTrue(problems.isEmpty(), "$font ${size}px:\n" + problems.joinToString("\n"))
            }
        }
    }

    // ── 세로 지표 ───────────────────────────────────────────────────

    @Test
    fun `switching fonts does not change how many lines fit on a page`() {
        // 폰트의 ascent/descent 를 그대로 쓰면 KoPubWorld 는 1.54em, Pretendard 는
        // 1.19em 이 줄 높이가 된다. 글꼴만 바꿨는데 페이지 수가 30% 늘어나면 안 된다.
        val styles = listOf(TextStyle.Default, TextStyle(bold = true), TextStyle(sizeScale = 1.5f))
        for (style in styles) {
            val heights = ReaderFont.entries.map { measurer(it).lineHeight(style) }
            assertEquals(1, heights.distinct().size, "$style 의 줄 높이가 글꼴마다 다르다: $heights")
        }

        val pitches = ReaderFont.entries.map { font ->
            val pages = paginate(font)
            val baselines = pages.first().runs.map { it.baselineYPx }.distinct().sorted()
            baselines.zipWithNext { a, b -> b - a }.distinct()
        }
        assertEquals(pitches[0], pitches[1], "줄 간격이 글꼴마다 다르다")
    }

    @Test
    fun `the first line's glyphs do not rise above the top margin`() {
        // 베이스라인이 글리프 윗변보다 위에 있으면 첫 줄 머리가 여백을 넘어 잘린다.
        for (font in ReaderFont.entries) {
            val m = measurer(font)
            val bounds = Rect()
            m.paintFor(TextStyle.Default).getTextBounds("가漢H", 0, 3, bounds)
            val ascent = m.ascent(TextStyle.Default)
            assertTrue(
                ascent >= -bounds.top - 1f,
                "$font: ascent $ascent 인데 글리프가 베이스라인 위로 ${-bounds.top}px 솟는다",
            )
            assertTrue(ascent <= m.lineHeight(TextStyle.Default), "$font: ascent 가 줄 높이를 넘는다")
        }
    }

    @Test
    fun `bold words do not shift the baseline of their line`() {
        // 굵은 단어가 섞인 줄만 베이스라인이 내려가면 줄 간격이 들쭉날쭉해 보인다.
        for (font in ReaderFont.entries) {
            val m = measurer(font)
            assertEquals(m.ascent(TextStyle.Default), m.ascent(TextStyle(bold = true)), "$font")
            assertEquals(m.ascent(TextStyle.Default), m.ascent(TextStyle(italic = true)), "$font")
        }
    }

    // ── 캐시 ────────────────────────────────────────────────────────

    @Test
    fun `measuring a heading first does not change body widths`() {
        // 챕터는 대개 제목으로 시작한다. 서식별 Paint 캐시가 크기를 키에서 빠뜨리거나
        // 한 Paint 를 돌려 쓰며 크기를 되돌리지 않으면, 먼저 잰 제목 크기로 본문이
        // 전부 재진다 — 증상은 "첫 챕터만 글자가 성기게 놓인다".
        val m = measurer(ReaderFont.Batang)
        m.advance(SENTENCE, 0, SENTENCE.length, TextStyle(sizeScale = 2f, bold = true))
        m.advance(SENTENCE, 0, SENTENCE.length, TextStyle(sizeScale = 0.75f, italic = true))
        val body = m.advance(SENTENCE, 0, SENTENCE.length, TextStyle.Default)
        val italicBody = m.advance(SENTENCE, 0, SENTENCE.length, TextStyle(italic = true))

        val fresh = measurer(ReaderFont.Batang)
        assertEquals(fresh.advance(SENTENCE, 0, SENTENCE.length, TextStyle.Default), body)
        assertEquals(fresh.advance(SENTENCE, 0, SENTENCE.length, TextStyle(italic = true)), italicBody)
        assertEquals(fresh.lineHeight(TextStyle.Default), m.lineHeight(TextStyle.Default))
    }

    @Test
    fun `underline and strikethrough share the plain paint`() {
        // 폭을 바꾸지 않는 서식이 Paint 를 복제하면 서식 조합 수만큼 글리프 캐시가 생긴다.
        val m = measurer(ReaderFont.Batang)
        val plain = m.paintFor(TextStyle.Default)
        assertTrue(plain === m.paintFor(TextStyle(underline = true, strikethrough = true)))
    }

    // ── 설정 → 측정기 ───────────────────────────────────────────────

    @Test
    fun `the font chosen in the layout spec is the font that gets measured`() {
        // forSpec 이 fontId 를 무시하면, 캐시 키는 고딕인데 바탕으로 잰 페이지가 들어간다.
        val batang = measurer(ReaderFont.Batang).advance(SENTENCE, 0, SENTENCE.length, TextStyle.Default)
        val gothic = measurer(ReaderFont.Gothic).advance(SENTENCE, 0, SENTENCE.length, TextStyle.Default)
        assertNotEquals(batang, gothic)
    }

    @Test
    fun `an unknown font id still opens the book with the default font`() {
        // 폰트를 빼거나 이름을 바꾼 뒤 옛 설정이 남아 있어도 책은 열려야 한다.
        val stale = AndroidTextMeasurer.forSpec(context, spec(ReaderFont.Batang).copy(fontId = "removed-font@0.1"))
        val empty = AndroidTextMeasurer.forSpec(context, spec(ReaderFont.Batang).copy(fontId = ""))
        val expected = measurer(ReaderFont.Default).advance(SENTENCE, 0, SENTENCE.length, TextStyle.Default)

        assertEquals(expected, stale.advance(SENTENCE, 0, SENTENCE.length, TextStyle.Default))
        assertEquals(expected, empty.advance(SENTENCE, 0, SENTENCE.length, TextStyle.Default))
    }

    @Test
    fun `an old font revision reaches the same font but a different cache`() {
        // 폰트 파일을 교체한 뒤에도 사용자의 글꼴 선택은 유지되고, 옛 판으로 잰 페이지는
        // 다른 캐시 키라 섞이지 않는다.
        val old = "batang@kopubworld-0.9"
        assertEquals(ReaderFont.Batang, ReaderFont.of(old))
        assertNotEquals(
            spec(ReaderFont.Batang).copy(fontId = old).cacheKey,
            spec(ReaderFont.Batang).cacheKey,
        )
    }

    // ── 실제 조판 ───────────────────────────────────────────────────

    @Test
    fun `pages laid out with the real fonts stay inside the text area`() {
        // FakeMeasurer 기준 골든과 값이 다른 건 당연하다. 여기서 보는 것은 실제 글꼴로
        // 조판해도 페이지가 무너지지 않는가 — 글자가 지면 밖으로 나가지 않고, 빈 페이지
        // 없이 본문 전체를 빈틈없이 덮는가 — 뿐이다.
        for (font in ReaderFont.entries) {
            val spec = spec(font)
            val m = AndroidTextMeasurer.forSpec(context, spec)
            val pages = paginate(font)
            val left = spec.margin.left
            val right = spec.viewportWidthPx - spec.margin.right
            val bottom = spec.viewportHeightPx - spec.margin.bottom

            assertTrue(pages.size > 1, "$font: 여러 페이지가 나와야 하는 분량이다")
            assertEquals(0, pages.first().startChar)
            assertEquals(CHAPTER.length, pages.last().endCharExclusive)
            // 문단 사이 개행은 어느 블록에도 속하지 않아 페이지 경계에 틈으로 남을 수 있다.
            // PageStore.pageOf 가 앞 페이지로 돌려주므로 무해하다. 틈에 **글자**가 있으면
            // 그 글자는 어느 페이지에도 그려지지 않는 것이다.
            pages.zipWithNext { a, b ->
                val gap = CHAPTER.substring(a.endCharExclusive, b.startChar)
                assertTrue(gap.isBlank(), "$font p${a.index} 와 p${b.index} 사이에서 \"$gap\" 가 사라졌다")
            }

            for (page in pages) {
                assertTrue(page.runs.isNotEmpty(), "$font p${page.index} 가 비었다")
                for (run in page.runs) {
                    val width = m.advance(CHAPTER, run.start, run.endExclusive, run.style)
                    val label = "$font p${page.index} \"${CHAPTER.substring(run.start, run.endExclusive)}\""
                    assertTrue(run.xPx >= left - EPS, "$label 가 왼쪽 여백을 넘는다(x=${run.xPx})")
                    assertTrue(run.xPx + width <= right + EPS, "$label 가 오른쪽 여백을 넘는다(${run.xPx + width} > $right)")
                    assertTrue(run.baselineYPx - m.ascent(run.style) >= spec.margin.top - EPS, "$label 가 위 여백을 넘는다")
                    assertTrue(run.baselineYPx <= bottom + EPS, "$label 가 아래 여백을 넘는다")
                }
            }
        }
    }

    @Test
    fun `justified lines end flush with the right margin`() {
        // 잰 폭과 조판기가 합한 폭이 어긋나면 양쪽정렬된 줄 끝이 들쭉날쭉해진다.
        for (font in ReaderFont.entries) {
            val spec = spec(font)
            val m = AndroidTextMeasurer.forSpec(context, spec)
            val right = spec.viewportWidthPx - spec.margin.right
            val lines = paginate(font).flatMap { page -> page.runs.groupBy { page.index to it.baselineYPx }.values }

            var checked = 0
            for (line in lines) {
                val end = line.maxOf { it.endExclusive }
                // 문단의 마지막 줄은 왼쪽 정렬이다. 줄 끝 뒤로 공백을 건너뛰어 개행이나
                // 본문 끝이 나오면 마지막 줄이다.
                var next = end
                while (next < CHAPTER.length && CHAPTER[next] == ' ') next++
                if (next >= CHAPTER.length || CHAPTER[next] == '\n') continue

                val last = line.maxBy { it.xPx }
                val edge = last.xPx + m.advance(CHAPTER, last.start, last.endExclusive, last.style)
                assertTrue(abs(edge - right) <= 1.5f, "$font: 양쪽정렬된 줄이 ${right}가 아니라 ${edge}에서 끝난다")
                checked++
            }
            assertTrue(checked > 10, "$font: 양쪽정렬된 줄이 검사되지 않았다($checked)")
        }
    }

    private fun paginate(font: ReaderFont): List<Page> {
        val spec = spec(font)
        return Paginator(spec, AndroidTextMeasurer.forSpec(context, spec)).paginate(CHAPTER, blocks()).toList()
    }

    private fun blocks(): List<Block> {
        val result = ArrayList<Block>()
        var offset = 0
        CHAPTER.split('\n').forEach { para ->
            // 정렬은 블록 서식에서 온다. EPUB·TXT 경로에서는 StyleResolver 가 spec.align 을
            // 채우지만, 여기서는 블록을 직접 만드므로 직접 넣는다.
            result.add(Block.Paragraph(listOf(InlineRun(offset, offset + para.length)), JUSTIFIED))
            offset += para.length + 1
        }
        return result
    }

    private companion object {
        const val EPS = 0.5f

        val JUSTIFIED = BlockStyle(align = TextAlign.Justify)

        const val SENTENCE = "어린 왕자는 사막에서 조종사를 만났다. Chapter 1 — 1943년."

        /** 한글·라틴·숫자·문장부호·한자가 섞인 여러 문단. 몇 페이지에 걸친다. */
        val CHAPTER: String = buildString {
            val paragraphs = listOf(
                "어린 왕자는 사막에 떨어진 조종사를 만났다. 그는 양 한 마리를 그려 달라고 부탁했고, 조종사는 세 번을 다시 그렸다.",
                "별에서 온 아이는 장미 한 송이를 두고 왔다고 했다. 그 장미는 까다롭고 허영심이 많았지만 그에게는 하나뿐인 꽃이었다.",
                "여우는 말했다. \"가장 중요한 것은 눈에 보이지 않아.\" 길들인다는 것은 關係를 만드는 일이라고, 1943년에 쓰인 그 문장은 말한다.",
                "The little prince crossed the desert and met a fox, who asked to be tamed — because tamed things become unique in all the world.",
            )
            repeat(8) { round ->
                paragraphs.forEach { p ->
                    if (isNotEmpty()) append('\n')
                    append(p).append(' ').append(round + 1).append('.')
                }
            }
        }
    }
}
