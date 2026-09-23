package io.github.kgcaudit.reader.layout

import kotlin.math.max
import kotlin.math.min

/**
 * 블록들을 페이지로 나눈다.
 *
 * 챕터당 한 번만 돌고 결과는 디스크에 캐시된다. 그래서 페이지 넘김은 "이미 계산된
 * 위치에 글자를 찍는" 일로 줄어들고, 그게 16ms 예산의 근거다.
 *
 * 결과를 [Sequence] 로 내는 이유: 점진적 조판이 앞쪽 페이지부터 받아 즉시 화면에
 * 띄우고 나머지를 배경에서 이어가야 한다. 목록으로 내면 큰 챕터에서 첫 페이지가
 * 나오기까지 전부 기다려야 한다.
 */
class Paginator(
    private val spec: LayoutSpec,
    private val measurer: TextMeasurer,
    private val lineBreaker: LineBreaker = GreedyLineBreaker(
        breakBetweenCjk = spec.breakBetweenCjk,
        lineHeightMultiplier = spec.lineHeightMultiplier,
    ),
) {

    fun paginate(text: CharSequence, blocks: List<Block>): Sequence<Page> = sequence {
        val builder = PageBuilder()

        // 앞 블록의 아래 여백. 다음 블록의 위 여백과 **큰 쪽을 취해** 합친다(CSS 여백
        // 상쇄). 더하면 문단 사이가 두 배로 벌어진다.
        var pendingMarginPx = 0f

        for (block in blocks) {
            if (block.style.pageBreakBefore && !builder.isAtPageTop) {
                builder.finish()?.let { yield(it) }
                pendingMarginPx = 0f
            }

            val em = spec.baseSizePx
            val marginTop = max(block.style.marginTopEm * em, paragraphSpacing(block))
            val gap = max(pendingMarginPx, marginTop)

            // 페이지 맨 위에서는 여백을 버린다. 남기면 빈 띠로 시작하는 페이지가 된다.
            if (!builder.isAtPageTop) builder.advance(gap)

            when (block) {
                is Block.Paragraph -> placeParagraph(text, block, builder)
                is Block.Image -> placeImage(block, builder)
                is Block.Rule -> placeRule(block, builder)
            }

            pendingMarginPx = max(block.style.marginBottomEm * em, paragraphSpacing(block))
        }

        builder.finish()?.let { yield(it) }
    }

    /** 문단 사이 간격 설정. 문단끼리에만 적용한다(그림 앞뒤는 블록 여백이 맡는다). */
    private fun paragraphSpacing(block: Block): Float =
        if (block is Block.Paragraph) spec.paragraphSpacingEm * spec.baseSizePx else 0f

    // ── 문단 ────────────────────────────────────────────────────────

    private suspend fun SequenceScope<Page>.placeParagraph(
        text: CharSequence,
        block: Block.Paragraph,
        builder: PageBuilder,
    ) {
        if (block.isBlank) return

        val em = spec.baseSizePx
        val indentStart = block.style.indentStartEm * em
        val indentEnd = block.style.indentEndEm * em
        val width = (spec.contentWidthPx - indentStart - indentEnd).coerceAtLeast(MIN_WIDTH)

        // CSS text-indent 가 정해졌으면 그걸 쓰고, 없으면 사용자 설정을 쓴다. 둘을
        // 더하면 들여쓰기가 두 배가 된다.
        val firstLineIndent = (block.style.firstLineIndentEm ?: spec.paragraphIndentEm) * em

        val lines = lineBreaker.breakLines(
            text = text,
            runs = block.runs,
            style = block.style,
            constraints = LineConstraints(width, firstLineIndent),
            measurer = measurer,
        )

        for (line in lines) {
            // 줄이 남은 높이에 안 들어가면 페이지를 넘긴다. 단 빈 페이지에서는 그냥
            // 놓는다 — 한 줄이 지면보다 높으면(아주 큰 제목) 넘겨도 영원히 안 들어간다.
            if (!builder.fits(line.heightPx) && !builder.isAtPageTop) {
                builder.finish()?.let { yield(it) }
            }
            builder.addLine(line, xOffset = spec.margin.left + indentStart)
        }
    }

    // ── 그림 ────────────────────────────────────────────────────────

    private suspend fun SequenceScope<Page>.placeImage(block: Block.Image, builder: PageBuilder) {
        if (!spec.imagesEnabled) return

        val (width, height) = imageSize(block)

        if (!builder.fits(height) && !builder.isAtPageTop) {
            builder.finish()?.let { yield(it) }
        }
        builder.addImage(block, width, height, spec.margin.left)
    }

    /**
     * 지면에 맞춘 그림 크기.
     *
     * 폭에 맞추고, 그래도 지면 높이를 넘으면 높이에 맞춘다. 원본보다 **크게 늘리지는
     * 않는다** — 작은 아이콘이 지면을 가득 채우면 흐릿하게 확대돼 보기 나쁘다.
     */
    private fun imageSize(block: Block.Image): Pair<Float, Float> {
        val maxWidth = spec.contentWidthPx
        val maxHeight = spec.contentHeightPx

        if (!block.hasIntrinsicSize) {
            // 크기를 모른다. 폭에 맞추고 3:4 로 자리를 잡는다(넘쳐서 잘리는 것 방지).
            val height = min(maxWidth * UNKNOWN_IMAGE_ASPECT, maxHeight)
            return maxWidth to height
        }

        val intrinsicW = block.intrinsicWidth.toFloat()
        val intrinsicH = block.intrinsicHeight.toFloat()
        val scale = min(min(maxWidth / intrinsicW, maxHeight / intrinsicH), 1f)
        return intrinsicW * scale to intrinsicH * scale
    }

    // ── 구분선 ──────────────────────────────────────────────────────

    private suspend fun SequenceScope<Page>.placeRule(block: Block.Rule, builder: PageBuilder) {
        val thickness = max(1f, spec.baseSizePx * RULE_THICKNESS_EM)
        val height = thickness + spec.baseSizePx * RULE_PADDING_EM * 2f

        if (!builder.fits(height) && !builder.isAtPageTop) {
            builder.finish()?.let { yield(it) }
        }
        builder.addRule(block, spec.contentWidthPx, thickness, height, spec.margin.left)
    }

    // ── 페이지 조립 ─────────────────────────────────────────────────

    /**
     * 한 페이지를 쌓는다.
     *
     * 세로 위치를 여백을 포함한 절대값으로 유지하므로, 그리는 쪽은 산술 없이 찍기만
     * 하면 된다.
     */
    private inner class PageBuilder {
        private var pageIndex = 0
        private var y = spec.margin.top
        private val runs = ArrayList<PlacedRun>()
        private val images = ArrayList<PlacedImage>()
        private val rules = ArrayList<PlacedRule>()
        private var startChar = -1
        private var endChar = -1

        val isAtPageTop: Boolean get() = runs.isEmpty() && images.isEmpty() && rules.isEmpty()

        private val bottom: Float get() = spec.margin.top + spec.contentHeightPx

        fun fits(heightPx: Float): Boolean = y + heightPx <= bottom + EPSILON

        fun advance(heightPx: Float) {
            // 여백이 페이지를 넘기지는 않는다. 넘길 만큼 크면 남은 높이까지만 쓴다 —
            // 그러면 다음 요소가 fits() 에서 걸려 정상적으로 페이지를 넘긴다.
            y = min(y + heightPx, bottom)
        }

        fun addLine(line: LaidLine, xOffset: Float) {
            val baseline = y + line.ascentPx
            line.pieces.forEach { piece ->
                runs.add(
                    PlacedRun(
                        start = piece.start,
                        endExclusive = piece.endExclusive,
                        style = piece.style,
                        xPx = xOffset + piece.xPx,
                        baselineYPx = baseline,
                    ),
                )
            }
            cover(line.startChar, line.endCharExclusive)
            y += line.heightPx
        }

        fun addImage(block: Block.Image, width: Float, height: Float, xOffset: Float) {
            // 그림은 가로 가운데 정렬. 본문 폭보다 좁은 그림이 왼쪽에 붙으면 어색하다.
            val x = xOffset + (spec.contentWidthPx - width) / 2f
            images.add(PlacedImage(block.href, x, y, width, height))
            cover(block.charStart, block.charEndExclusive)
            y += height
        }

        fun addRule(
            block: Block.Rule,
            width: Float,
            thickness: Float,
            height: Float,
            xOffset: Float,
        ) {
            rules.add(PlacedRule(xOffset, y + (height - thickness) / 2f, width, thickness))
            cover(block.charStart, block.charEndExclusive)
            y += height
        }

        private fun cover(from: Int, to: Int) {
            if (startChar < 0) startChar = from
            endChar = max(endChar, to)
        }

        /** 지금까지 쌓인 것을 페이지로 내고 다음 페이지를 시작한다. 빈 페이지는 내지 않는다. */
        fun finish(): Page? {
            if (isAtPageTop) return null
            val page = Page(
                index = pageIndex,
                startChar = startChar.coerceAtLeast(0),
                endCharExclusive = max(endChar, startChar.coerceAtLeast(0)),
                runs = runs.toList(),
                images = images.toList(),
                rules = rules.toList(),
            )
            pageIndex++
            y = spec.margin.top
            runs.clear()
            images.clear()
            rules.clear()
            startChar = -1
            endChar = -1
            return page
        }
    }

    private companion object {
        const val EPSILON = 0.01f
        const val MIN_WIDTH = 1f

        /** 크기를 모르는 그림의 가로:세로 = 1:0.75. */
        const val UNKNOWN_IMAGE_ASPECT = 0.75f

        const val RULE_THICKNESS_EM = 0.07f
        const val RULE_PADDING_EM = 0.6f
    }
}
