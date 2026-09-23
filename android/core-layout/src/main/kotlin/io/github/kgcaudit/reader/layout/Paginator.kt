package io.github.kgcaudit.reader.layout

import io.github.kgcaudit.reader.layout.css.CssLength
import io.github.kgcaudit.reader.layout.css.CssUnit
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
     * 지면에 맞춘 그림 크기. Readium 의 방어 규칙과 같은 순서다:
     *
     * 1. 책이 지정한 크기(HTML 속성·CSS)를 쓴다. 퍼센트는 본문 폭 기준.
     * 2. 지정이 없으면 파일의 원래 크기를 dp 로 옮긴다([LayoutSpec.cssPxScale]).
     * 3. **비율은 언제나 지킨다.** 한쪽만 정해졌으면 다른 쪽은 비율로 구한다.
     * 4. 본문 폭·높이(와 max-width·max-height)를 넘으면 비율대로 **줄이기만** 한다.
     *
     * 지정이 없는 작은 그림은 키우지 않는다 — 118px 로고를 폭 가득 늘리면 흐려진다(실제로
     * 그렇게 나와 "깨진 체스 기호" 처럼 보였다). 책이 `width:100%` 라고 **적었으면** 키운다.
     */
    private fun imageSize(block: Block.Image): Pair<Float, Float> {
        val contentW = spec.contentWidthPx
        val contentH = spec.contentHeightPx
        val sizing = block.sizing

        val aspect: Float? = if (block.hasIntrinsicSize) {
            block.intrinsicHeight.toFloat() / block.intrinsicWidth
        } else {
            null
        }

        var width = sizing.width?.let { length(it, contentW) }
        // 높이 퍼센트는 기준이 없다 — 최대 높이로 다룬다(ImageSizing 참고).
        var height = sizing.height?.takeIf { it.unit != CssUnit.Percent }?.let { length(it, contentH) }

        var maxW = contentW
        sizing.maxWidth?.let { maxW = min(maxW, length(it, contentW)) }
        var maxH = contentH
        sizing.maxHeight?.let { maxH = min(maxH, length(it, contentH)) }
        sizing.height?.takeIf { it.unit == CssUnit.Percent }?.let { maxH = min(maxH, length(it, contentH)) }

        // 비율: 파일에서, 없으면 책이 두 변을 다 적었을 때 그 값에서, 그것도 없으면 3:4.
        val ratio = aspect
            ?: if (width != null && height != null) height / width else UNKNOWN_IMAGE_ASPECT

        when {
            width != null && height != null -> {
                // 두 변을 다 적었어도 파일 비율과 다르면 상자 안에 비율대로 넣는다
                // (object-fit: contain). 늘려 채우면 지금 고치는 증상이 그대로 남는다.
                val fit = min(width, height / ratio)
                width = fit
                height = fit * ratio
            }
            width != null -> height = width * ratio
            height != null -> width = height / ratio
            aspect != null -> {
                width = block.intrinsicWidth * spec.cssPxScale
                height = block.intrinsicHeight * spec.cssPxScale
            }
            else -> {
                // 파일 크기를 끝내 모른다(머리가 깨졌거나 모르는 형식). 폭에 맞춰 자리를 잡는다.
                width = contentW
                height = contentW * UNKNOWN_IMAGE_ASPECT
            }
        }

        val shrink = min(1f, min(maxW / width, maxH / height))
        return max(1f, width * shrink) to max(1f, height * shrink)
    }

    /** CSS 길이를 화면 px 로. 퍼센트는 [percentBase] 기준. */
    private fun length(value: CssLength, percentBase: Float): Float = when (value.unit) {
        CssUnit.Percent -> percentBase * value.value / 100f
        CssUnit.Px -> value.value * spec.cssPxScale
        CssUnit.Pt -> value.value * CssLength.PT_TO_PX * spec.cssPxScale
        CssUnit.Em, CssUnit.Rem -> value.value * spec.baseSizePx
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
