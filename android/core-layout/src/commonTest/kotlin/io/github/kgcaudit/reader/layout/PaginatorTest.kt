package io.github.kgcaudit.reader.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaginatorTest {

    // lineHeightRatio 를 1.0 으로 둔다. 측정기는 '글꼴 고유 줄 높이' 를 보고하고
    // 조판기가 사용자 줄 간격 배수를 곱하므로, 둘 다 1.2 면 14.4 가 되어 기대값
    // 계산이 불투명해진다. 배수가 곱해지는지는 GreedyLineBreakerTest 가 따로 본다.
    private val measurer = FakeMeasurer(baseSizePx = 10f, lineHeightRatio = 1f)

    /** 라틴 10px/글자, 전각 20px, 공백 5px. 줄 높이 = 10 * 1.2(배수) = 12px. */
    private fun spec(
        widthPx: Float = 100f,
        heightPx: Float = 60f,
        margin: Insets = Insets(),
        align: TextAlign = TextAlign.Start,
        indentEm: Float = 0f,
        spacingEm: Float = 0f,
        images: Boolean = true,
    ) = LayoutSpec(
        viewportWidthPx = widthPx,
        viewportHeightPx = heightPx,
        margin = margin,
        baseSizePx = 10f,
        lineHeightMultiplier = 1.2f,
        align = align,
        paragraphIndentEm = indentEm,
        paragraphSpacingEm = spacingEm,
        imagesEnabled = images,
    )

    private fun paginate(text: String, blocks: List<Block>, spec: LayoutSpec = spec()): List<Page> =
        Paginator(spec, measurer).paginate(text, blocks).toList()

    private fun paragraph(text: String, style: BlockStyle = BlockStyle.Default) =
        Block.Paragraph(listOf(InlineRun(0, text.length)), style)

    /**
     * 페이지의 줄들. 베이스라인이 같은 조각이 한 줄이다.
     *
     * `runs` 를 줄 수로 세면 안 된다 — 글자 단위 줄바꿈에서는 한 줄이 글자마다 하나씩
     * 조각을 내므로 줄 수보다 훨씬 많다.
     */
    private fun lines(page: Page): List<List<PlacedRun>> =
        page.runs.groupBy { it.baselineYPx }.entries.sortedBy { it.key }.map { it.value }

    private fun lineCount(page: Page): Int = lines(page).size

    /** 각 페이지가 덮는 텍스트. 페이지 경계를 눈으로 확인하는 데 쓴다. */
    private fun pageTexts(text: String, pages: List<Page>): List<String> =
        pages.map { text.substring(it.startChar, it.endCharExclusive).trimEnd() }

    // ── 기본 분할 ───────────────────────────────────────────────────

    @Test
    fun `lines fill a page then continue on the next`() {
        // 지면 높이 60, 줄 높이 12 → 페이지당 5줄. 폭 100 → 한 줄에 전각 5자.
        val text = "가".repeat(30) // 6줄 분량
        val pages = paginate(text, listOf(paragraph(text)))
        assertEquals(2, pages.size)
        assertEquals(5, lineCount(pages[0]))
        assertEquals(1, lineCount(pages[1]))
    }

    @Test
    fun `page indices are sequential from zero`() {
        val text = "가".repeat(60)
        val pages = paginate(text, listOf(paragraph(text)))
        assertEquals(pages.indices.toList(), pages.map { it.index })
    }

    @Test
    fun `every character appears on exactly one page in order`() {
        // 진도와 책갈피가 이 구간으로 기록되므로, 빠뜨리거나 겹치면 이어읽기가 어긋난다.
        val text = "한국어 문장이 길게 이어지며 여러 페이지에 걸쳐 조판된다 ".repeat(4)
        val pages = paginate(text, listOf(paragraph(text)))
        assertEquals(0, pages.first().startChar)
        assertEquals(text.length, pages.last().endCharExclusive)
        pages.zipWithNext { a, b ->
            assertEquals(a.endCharExclusive, b.startChar, "페이지 사이에 구멍이나 겹침이 있다")
        }
    }

    @Test
    fun `no page is ever empty`() {
        // 빈 페이지가 섞이면 사용자가 아무것도 없는 화면을 넘겨야 한다.
        val text = "가".repeat(100)
        paginate(text, listOf(paragraph(text))).forEach {
            assertTrue(!it.isEmpty, "빈 페이지가 나왔다: $it")
        }
    }

    @Test
    fun `nothing is placed below the content area`() {
        val margin = Insets.all(10f)
        val s = spec(widthPx = 120f, heightPx = 80f, margin = margin)
        val text = "가".repeat(80)
        val bottom = s.margin.top + s.contentHeightPx
        paginate(text, listOf(paragraph(text)), s).forEach { page ->
            page.runs.forEach { run ->
                assertTrue(
                    run.baselineYPx <= bottom + 0.5f,
                    "베이스라인 ${run.baselineYPx} 이 본문 영역(${bottom}) 아래다",
                )
                assertTrue(run.xPx >= margin.left - 0.5f, "글자가 왼쪽 여백을 침범했다")
            }
        }
    }

    @Test
    fun `margins offset the content`() {
        val s = spec(widthPx = 120f, heightPx = 80f, margin = Insets(left = 15f, top = 7f))
        val text = "가나"
        val page = paginate(text, listOf(paragraph(text)), s).single()
        assertEquals(15f, page.runs.first().xPx)
        // 첫 줄 베이스라인 = 위 여백 + ascent
        assertEquals(7f + measurer.ascent(TextStyle.Default), page.runs.first().baselineYPx)
    }

    // ── 문단 사이 ───────────────────────────────────────────────────

    @Test
    fun `paragraph spacing separates paragraphs`() {
        val text = "가나다라"
        val blocks = listOf(
            Block.Paragraph(listOf(InlineRun(0, 2))),
            Block.Paragraph(listOf(InlineRun(2, 4))),
        )
        val withoutGap = paginate(text, blocks, spec(heightPx = 200f)).single()
        val withGap = paginate(text, blocks, spec(heightPx = 200f, spacingEm = 1f)).single()

        fun baselineGap(page: Page): Float {
            val baselines = lines(page).map { it.first().baselineYPx }
            return baselines[1] - baselines[0]
        }
        assertEquals(10f, baselineGap(withGap) - baselineGap(withoutGap), absoluteTolerance = 0.01f)
    }

    @Test
    fun `adjacent margins collapse instead of adding up`() {
        // CSS 여백 상쇄. 더하면 문단 사이가 두 배로 벌어진다.
        val text = "가나다라"
        val blocks = listOf(
            Block.Paragraph(listOf(InlineRun(0, 2)), BlockStyle(marginBottomEm = 2f)),
            Block.Paragraph(listOf(InlineRun(2, 4)), BlockStyle(marginTopEm = 2f)),
        )
        val page = paginate(text, blocks, spec(heightPx = 200f)).single()
        val baselines = lines(page).map { it.first().baselineYPx }
        val gap = baselines[1] - baselines[0]
        // 줄 높이 12 + 상쇄된 여백 20 = 32 (더해졌다면 52)
        assertEquals(32f, gap, absoluteTolerance = 0.01f)
    }

    @Test
    fun `a page never starts with a blank margin band`() {
        // 페이지 맨 위의 여백을 남기면 빈 띠로 시작하는 페이지가 된다.
        val text = "가".repeat(10) + "나".repeat(10)
        val blocks = listOf(
            Block.Paragraph(listOf(InlineRun(0, 10)), BlockStyle(marginBottomEm = 3f)),
            Block.Paragraph(listOf(InlineRun(10, 20)), BlockStyle(marginTopEm = 3f)),
        )
        val pages = paginate(text, blocks, spec(heightPx = 36f)) // 페이지당 3줄
        // 두 번째 문단이 새 페이지에서 시작하면 그 페이지 첫 줄은 맨 위에 있어야 한다.
        pages.drop(1).forEach { page ->
            val firstBaseline = page.runs.minOf { it.baselineYPx }
            assertEquals(
                measurer.ascent(TextStyle.Default),
                firstBaseline,
                absoluteTolerance = 0.01f,
                message = "페이지가 빈 여백으로 시작한다",
            )
        }
    }

    // ── 들여쓰기 ────────────────────────────────────────────────────

    @Test
    fun `the paragraph indent setting shifts only the first line of each paragraph`() {
        val text = "가".repeat(12)
        val s = spec(heightPx = 200f, indentEm = 2f)
        val page = paginate(text, listOf(paragraph(text)), s).single()
        val byLine = lines(page)
        assertEquals(20f, byLine.first().first().xPx) // 2em = 20px
        assertEquals(0f, byLine[1].first().xPx)
    }

    @Test
    fun `a css text indent overrides the user setting instead of adding to it`() {
        // 둘을 더하면 들여쓰기가 두 배가 된다.
        val text = "가".repeat(6)
        val s = spec(heightPx = 200f, indentEm = 2f)
        val block = paragraph(text, BlockStyle(firstLineIndentEm = 1f))
        val page = paginate(text, listOf(block), s).single()
        assertEquals(10f, page.runs.first().xPx)
    }

    @Test
    fun `turning indents off removes even the book's own first line indent`() {
        // "첫 줄 들여쓰기: 끔" 은 책이 text-indent 로 정한 곳까지 끈다. 사용자 설정(2em)만 끄고 책의 1em 을
        // 남기면 끔을 골랐는데 들여쓰기가 그대로 보인다.
        val text = "가".repeat(6)
        val s = spec(heightPx = 200f, indentEm = 2f).copy(indentOff = true)
        assertEquals(0f, paginate(text, listOf(paragraph(text)), s).single().runs.first().xPx)
        val css = paragraph(text, BlockStyle(firstLineIndentEm = 1f))
        assertEquals(0f, paginate(text, listOf(css), s).single().runs.first().xPx)
    }

    @Test
    fun `turning indents off keeps a hanging indent`() {
        // 내어쓰기(음수)는 목록·대화문의 모양이다. 0 으로 펴면 둘째 줄부터 글자가 번호 밑으로 파고든다.
        val text = "가".repeat(12)
        val s = spec(heightPx = 200f).copy(indentOff = true)
        val block = paragraph(text, BlockStyle(firstLineIndentEm = -1f, indentStartEm = 1f))
        val byLine = lines(paginate(text, listOf(block), s).single())
        assertEquals(0f, byLine[0].first().xPx)
        assertEquals(10f, byLine[1].first().xPx)
    }

    // ── 정렬 덮어쓰기 ───────────────────────────────────────────────

    /** 한 줄이 폭을 다 채우지 않는 두 줄짜리 문단. 첫 줄 끝이 오른쪽 끝에 붙었는지로 양쪽정렬을 본다. */
    private fun firstLineEnd(style: BlockStyle, s: LayoutSpec): Float {
        // 라틴 낱말 3글자(30px) + 공백(5px). 폭 90 에 두 낱말(65px)씩 → 첫 줄 오른쪽에 25px 이 남는다.
        // (폭 100 이면 세 낱말이 꼭 차서 정렬과 상관없이 줄 끝이 100 이다 — 시험이 아무것도 못 본다.)
        val text = "abc def ghi jkl"
        val page = paginate(text, listOf(paragraph(text, style)), s).single()
        val first = lines(page).first()
        return first.maxOf { it.xPx + measurer.advance(text, it.start, it.endExclusive, it.style) }
    }

    @Test
    fun `a chosen alignment replaces the book's justified and left aligned paragraphs`() {
        val s = spec(widthPx = 90f, heightPx = 200f)
        val justified = BlockStyle(align = TextAlign.Justify)
        // 원본(덮어쓰기 없음): 책이 정한 양쪽정렬 그대로 — 첫 줄이 오른쪽 끝(90)까지 벌어진다.
        assertEquals(90f, firstLineEnd(justified, s), 0.5f)
        // "왼쪽" 을 고르면 책의 양쪽정렬도 왼쪽으로 붙는다.
        assertEquals(65f, firstLineEnd(justified, s.copy(alignOverride = TextAlign.Start)), 0.5f)
        // "양쪽" 을 고르면 책의 왼쪽 정렬도 양쪽으로 벌어진다.
        assertEquals(90f, firstLineEnd(BlockStyle(align = TextAlign.Start), s.copy(alignOverride = TextAlign.Justify)), 0.5f)
    }

    @Test
    fun `a chosen alignment leaves centred and right aligned text alone`() {
        // 시 · 표제지 · 서명. "왼쪽" 을 골랐다고 가운데 제목이 왼쪽으로 쏠리면 안 된다.
        val text = "abc"
        val s = spec(heightPx = 200f).copy(alignOverride = TextAlign.Start)
        val centred = paginate(text, listOf(paragraph(text, BlockStyle(align = TextAlign.Center))), s).single()
        assertEquals(35f, centred.runs.first().xPx, 0.5f)
        val right = paginate(text, listOf(paragraph(text, BlockStyle(align = TextAlign.End))), s).single()
        assertEquals(70f, right.runs.first().xPx, 0.5f)
    }

    @Test
    fun `the new settings keep the old cache key when left at their defaults`() {
        // 판을 올렸다고 모든 책이 처음부터 다시 조판되면(느린 첫 열기) 안 된다. 기본값이면 키가 예전 그대로다.
        val base = spec()
        assertEquals(KEY_BEFORE_0_12, base.cacheKey)
    }

    @Test
    fun `block indents narrow the text column`() {
        // 인용문. 폭 100 에서 좌우 1em 씩 들여쓰면 본문 폭 80 → 한 줄에 전각 4자.
        val text = "가".repeat(8)
        val block = paragraph(text, BlockStyle(indentStartEm = 1f, indentEndEm = 1f))
        val page = paginate(text, listOf(block), spec(heightPx = 200f)).single()
        assertEquals(10f, page.runs.first().xPx)
        assertEquals(2, lineCount(page))
    }

    // ── 그림 ────────────────────────────────────────────────────────

    @Test
    fun `an image is scaled to the content width and centred`() {
        val text = "￼"
        val block = Block.Image("a.png", 0, 1, intrinsicWidth = 200, intrinsicHeight = 100)
        val page = paginate(text, listOf(block), spec(widthPx = 100f, heightPx = 200f)).single()
        val image = page.images.single()
        assertEquals(100f, image.widthPx) // 폭에 맞춤
        assertEquals(50f, image.heightPx) // 비율 유지
        assertEquals(0f, image.xPx)
    }

    @Test
    fun `a small image is not enlarged`() {
        // 작은 아이콘을 지면 가득 늘리면 흐릿하게 확대돼 보기 나쁘다.
        val text = "￼"
        val block = Block.Image("i.png", 0, 1, intrinsicWidth = 20, intrinsicHeight = 20)
        val page = paginate(text, listOf(block), spec(widthPx = 100f, heightPx = 200f)).single()
        assertEquals(20f, page.images.single().widthPx)
        // 가운데 정렬
        assertEquals(40f, page.images.single().xPx)
    }

    @Test
    fun `an image taller than the page is scaled down to fit`() {
        val text = "￼"
        val block = Block.Image("t.png", 0, 1, intrinsicWidth = 100, intrinsicHeight = 1000)
        val s = spec(widthPx = 100f, heightPx = 60f)
        val image = paginate(text, listOf(block), s).single().images.single()
        assertTrue(image.heightPx <= s.contentHeightPx + 0.5f, "높이 ${image.heightPx} 가 지면을 넘는다")
    }

    @Test
    fun `an image with unknown dimensions still gets a place`() {
        // 파서가 크기를 못 채운 경우. 넘쳐서 잘리는 것보다 자리를 잡는 게 낫다.
        val text = "￼"
        val block = Block.Image("u.png", 0, 1)
        val image = paginate(text, listOf(block), spec(widthPx = 100f, heightPx = 200f))
            .single().images.single()
        assertEquals(100f, image.widthPx)
        assertEquals(75f, image.heightPx)
    }

    @Test
    fun `an image that does not fit moves to the next page`() {
        val text = "가".repeat(10) + "￼"
        val blocks = listOf(
            Block.Paragraph(listOf(InlineRun(0, 10))),
            Block.Image("a.png", 10, 11, intrinsicWidth = 100, intrinsicHeight = 100),
        )
        val pages = paginate(text, blocks, spec(widthPx = 100f, heightPx = 60f))
        assertEquals(2, pages.size)
        assertTrue(pages[0].images.isEmpty())
        assertEquals(1, pages[1].images.size)
    }

    @Test
    fun `images are dropped when disabled`() {
        val text = "가나￼"
        val blocks = listOf(
            Block.Paragraph(listOf(InlineRun(0, 2))),
            Block.Image("a.png", 2, 3, intrinsicWidth = 100, intrinsicHeight = 100),
        )
        val page = paginate(text, blocks, spec(heightPx = 200f, images = false)).single()
        assertTrue(page.images.isEmpty())
        assertTrue(page.runs.isNotEmpty())
    }

    // ── 구분선 ──────────────────────────────────────────────────────

    @Test
    fun `a rule spans the content width`() {
        val text = "가￼나"
        val blocks = listOf(
            Block.Paragraph(listOf(InlineRun(0, 1))),
            Block.Rule(1, 2),
            Block.Paragraph(listOf(InlineRun(2, 3))),
        )
        val page = paginate(text, blocks, spec(widthPx = 100f, heightPx = 200f)).single()
        val rule = page.rules.single()
        assertEquals(100f, rule.widthPx)
        assertTrue(rule.thicknessPx >= 1f)
        // 구분선이 두 문단 사이에 놓인다.
        assertTrue(page.runs.first().baselineYPx < rule.yPx)
        assertTrue(page.runs.last().baselineYPx > rule.yPx)
    }

    // ── 경계 ────────────────────────────────────────────────────────

    @Test
    fun `deep indents at a large font keep at least half the width for text`() {
        // 큰 글자에서 겹친 인용 · 깊은 목록: 들여쓰기 합(120px)이 본문 폭(100px)을 넘어 한 줄에 한 글자만 남았다.
        val text = "abcdefghij"
        val style = BlockStyle.Default.copy(indentStartEm = 8f, indentEndEm = 4f)
        val page = paginate(text, listOf(paragraph(text, style))).single()
        // 폭의 절반(50px = 다섯 글자)은 남는다. 들여쓰기의 비율(왼 2 : 오른 1)은 그대로.
        assertEquals(2, lineCount(page))
        assertEquals(100f / 3f, page.runs.first().xPx, 0.01f)
    }

    @Test
    fun `a line narrower than one emoji keeps the emoji whole`() {
        // 줄 폭(5px)이 이모지 하나보다 좁다. 쪼개는 자리가 서로게이트 쌍 가운데면 두 줄에 깨진 글자가 그려졌다.
        val text = "😀😀😀"
        val pages = paginate(text, listOf(paragraph(text)), spec(widthPx = 5f, heightPx = 1000f))
        val cuts = pages.flatMap { it.runs }.flatMap { listOf(it.start, it.endExclusive) }
        assertTrue(cuts.none { it in 1 until text.length && text[it].isLowSurrogate() }, "이모지 가운데서 잘렸다: $cuts")
    }

    @Test
    fun `a page never holds more pieces than the page cache can count`() {
        // 아주 작은 글자의 긴 블록: 한 쪽에 6만 조각을 넘기면 캐시에 쓰다 예외가 나 장을 끝내 열 수 없었다.
        // 글자마다 서식이 바뀌어(밑줄 · 보통) 한 글자가 한 조각이다.
        val text = "가".repeat(80_000)
        val runs = List(text.length) { InlineRun(it, it + 1, TextStyle(underline = it % 2 == 0)) }
        val pages = paginate(text, listOf(Block.Paragraph(runs, BlockStyle.Default)), spec(widthPx = 100f, heightPx = 2_000_000f))
        assertTrue(pages.sumOf { it.runs.size } > 0xFFFF, "시험 전제: 조각이 캐시 한도보다 많아야 한다")
        assertTrue(pages.all { it.runs.size <= 0xFFFF }, "한 쪽의 조각: ${pages.map { it.runs.size }}")
        pages.forEach { PageCodec.encode(listOf(it), textLength = text.length) }
    }

    @Test
    fun `a very long line without break points is split without measuring its tail again and again`() {
        // 띄어쓰기 없는 10만 자 한 줄(TXT 의 base64 · 깨진 글). 쪼갤 때마다 남은 꼬리 전체를 다시 재 길이의 제곱으로 느려졌다.
        var measured = 0L
        val counting = object : TextMeasurer by measurer {
            override fun advance(text: CharSequence, start: Int, endExclusive: Int, style: TextStyle): Float {
                measured += endExclusive - start
                return measurer.advance(text, start, endExclusive, style)
            }
        }
        val text = "a".repeat(100_000)
        val pages = Paginator(spec(heightPx = 1_000_000f), counting).paginate(text, listOf(paragraph(text))).toList()
        assertEquals(text.length, pages.last().endCharExclusive)
        // 줄마다 열 글자 남짓을 재면 된다 — 꼬리를 다시 재면 수억 글자가 된다.
        assertTrue(measured < 20_000_000L, "잰 글자 수: $measured")
    }

    @Test
    fun `an empty chapter still has one blank page to land on`() {
        // CSS 배경만 있는 표지 · 모두 숨긴 본문. 0 쪽이면 넘기기 · 목차가 그 장의 "0쪽" 으로 가 쪽이 없는(null) 화면이 떴다.
        listOf(paginate("", emptyList()), paginate("", listOf(Block.Paragraph(listOf(InlineRun(0, 0)))))).forEach { pages ->
            assertEquals(1, pages.size)
            assertTrue(pages.single().isEmpty)
            assertEquals(0, pages.single().startChar)
        }
    }

    @Test
    fun `a line taller than the page is still placed rather than looping forever`() {
        // 아주 큰 제목 + 낮은 지면. 넘겨도 영원히 안 들어가므로 그냥 놓는다.
        val text = "제목"
        val block = Block.Paragraph(listOf(InlineRun(0, 2, TextStyle(sizeScale = 8f))))
        val pages = paginate(text, listOf(block), spec(widthPx = 400f, heightPx = 30f))
        assertEquals(1, pages.size)
        assertEquals(1, lineCount(pages.single()))
    }

    @Test
    fun `the sequence is lazy so the first page arrives without laying out the rest`() {
        // 점진적 조판의 전제다. 목록으로 냈다면 큰 챕터의 첫 페이지가 늦게 나온다.
        val text = "가".repeat(100_000)
        var produced = 0
        val first = Paginator(spec(), measurer)
            .paginate(text, listOf(paragraph(text)))
            .onEach { produced++ }
            .first()
        assertEquals(1, produced, "첫 페이지를 받는데 ${produced}개가 조판됐다")
        assertEquals(0, first.startChar)
    }

    // ── 레코드 수 ───────────────────────────────────────────────────

    @Test
    fun `korean pages produce about one run per line - not one per character`() {
        // 글자 단위 줄바꿈은 토큰을 글자마다 만든다. 병합이 없으면 880자 페이지가
        // 런 880개가 되어 캐시가 20배로 부풀고 그리기도 글자마다 호출이 된다.
        // 페이지 넘김 16ms 예산에 직접 걸리는 성질이라 테스트로 고정한다.
        val text = "한국어 본문이 여러 줄에 걸쳐 이어지며 조판된다. 어절 사이에는 공백이 있다. ".repeat(20)
        val s = LayoutSpec(
            viewportWidthPx = 720f,
            viewportHeightPx = 1280f,
            margin = Insets.all(40f),
            baseSizePx = 18f,
            align = TextAlign.Justify,
        )
        val pages = Paginator(s, FakeMeasurer(baseSizePx = 18f, lineHeightRatio = 1.2f))
            .paginate(text, listOf(Block.Paragraph(listOf(InlineRun(0, text.length))))).toList()

        val lineTotal = pages.sumOf { page -> page.runs.map { it.baselineYPx }.distinct().size }
        val runTotal = pages.sumOf { it.runs.size }
        assertTrue(lineTotal > 20, "검사가 의미 있으려면 줄이 충분히 많아야 한다 (lineTotal=$lineTotal)")
        assertTrue(
            runTotal <= lineTotal * 3,
            "런이 줄당 3개를 넘는다: runs=$runTotal lines=$lineTotal (병합이 깨졌다)",
        )
    }

    // ── 캐시 키 ─────────────────────────────────────────────────────

    @Test
    fun `the cache key changes when any layout affecting setting changes`() {
        // 여기 빠진 설정이 있으면 설정을 바꿨는데 옛 페이지가 그대로 보인다.
        val base = spec()
        val variants = listOf(
            "폭" to base.copy(viewportWidthPx = 101f),
            "높이" to base.copy(viewportHeightPx = 61f),
            "여백" to base.copy(margin = Insets.all(1f)),
            "글자 크기" to base.copy(baseSizePx = 11f),
            "줄 간격" to base.copy(lineHeightMultiplier = 1.5f),
            "정렬" to base.copy(align = TextAlign.Justify),
            "들여쓰기" to base.copy(paragraphIndentEm = 1f),
            "문단 간격" to base.copy(paragraphSpacingEm = 1f),
            "글자 단위 줄바꿈" to base.copy(breakBetweenCjk = false),
            "그림 표시" to base.copy(imagesEnabled = false),
            "글꼴" to base.copy(fontId = "pretendard"),
            "화면 밀도" to base.copy(cssPxScale = 2.6f),
            "정렬 덮어쓰기" to base.copy(alignOverride = TextAlign.Start),
            "들여쓰기 끄기" to base.copy(indentOff = true),
        )
        variants.forEach { (label, variant) ->
            assertTrue(variant.cacheKey != base.cacheKey, "$label 를 바꿨는데 캐시 키가 같다")
        }
    }

    @Test
    fun `the cache key tells apart which book fonts could be read`() {
        // 같은 책 · 같은 설정이라도 꺼낸 책 글꼴 가족이 다르면 글자 폭이 다르다 — 키가 같으면 옛 쪽을 다른 글꼴로 그린다.
        val on = spec().copy(useBookFonts = true)
        assertTrue(on.copy(bookFontsKey = "all").cacheKey != on.copy(bookFontsKey = "some").cacheKey)
        assertTrue(on.copy(bookFontsKey = "all").cacheKey != on.cacheKey)
        // 책 글꼴을 끄면 지문은 키에 들지 않는다(쓰이지 않으니 다시 조판할 이유가 없다).
        assertEquals(spec().cacheKey, spec().copy(bookFontsKey = "all").cacheKey)
    }

    @Test
    fun `the cache key is stable for the same settings`() {
        assertEquals(spec().cacheKey, spec().cacheKey)
        assertEquals(16, spec().cacheKey.length)
    }

    private companion object {
        /** 0.11.0 에서 [spec] 기본값의 캐시 키. 새 칸이 기본값일 때 키를 바꾸면 이 값과 달라진다. */
        const val KEY_BEFORE_0_12 = "4e6d9fb6e923a4b6"
    }

    @Test
    fun `lines split by br are not spread apart by the paragraph spacing`() {
        // 시 · 편지 · 주소를 <br> 로 적은 책. 줄마다 문단 간격이 끼면 사용자가 없앨 방법이 없다.
        val text = "첫줄둘째줄"
        val style = BlockStyle(marginBottomEm = 1f)
        fun gap(continues: Boolean): Float {
            val blocks = listOf(
                Block.Paragraph(listOf(InlineRun(0, 2)), style),
                Block.Paragraph(listOf(InlineRun(2, 5)), style, continuesLine = continues),
            )
            val baselines = lines(paginate(text, blocks, spec(spacingEm = 2f)).single()).map { it.first().baselineYPx }
            return baselines[1] - baselines[0]
        }
        val lineHeight = 10f * 1.2f
        assertEquals(lineHeight, gap(continues = true), 0.5f)
        assertTrue(gap(continues = false) > lineHeight + 5f, "문단 사이는 여전히 벌어져야 한다")
    }
}
