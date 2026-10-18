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
        var previous: Block? = null

        for (block in blocks.asSequence().flatMap { expand(it) }) {
            if (block.style.pageBreakBefore && !builder.isAtPageTop) {
                builder.finish()?.let { yield(it) }
                pendingMarginPx = 0f
            }

            val em = spec.baseSizePx
            val marginTop = max(block.style.marginTopEm * em, paragraphSpacing(block))
            val gap = when {
                // 줄바꿈으로 이어진 글: 문단 사이가 아니다. 앞이 그림이면 그림의 아래 여백만 둔다.
                block is Block.Paragraph && block.continuesLine -> if (previous is Block.Paragraph) 0f else previous?.style?.marginBottomEm?.times(em) ?: 0f
                else -> max(pendingMarginPx, marginTop)
            }

            // 페이지 맨 위에서는 여백을 버린다. 남기면 빈 띠로 시작하는 페이지가 된다.
            if (!builder.isAtPageTop) builder.advance(gap)

            when (block) {
                is Block.Paragraph -> placeParagraph(text, block, builder)
                is Block.Image -> placeImage(block, builder)
                is Block.Rule -> placeRule(block, builder)
            }

            pendingMarginPx = max(block.style.marginBottomEm * em, paragraphSpacing(block))
            previous = block
        }

        builder.finish()?.let { yield(it) }
    }

    /** 사용자가 정한 본문 정렬([LayoutSpec.alignOverride]). 양쪽·왼쪽(시작) 문단에만 — 가운데·끝은 책의 뜻이다. */
    private fun overridden(style: BlockStyle): BlockStyle {
        val forced = spec.alignOverride ?: return style
        return if (style.align == TextAlign.Justify || style.align == TextAlign.Start) style.copy(align = forced) else style
    }

    /** 문단 사이 간격 설정. 문단끼리에만 적용한다(그림 앞뒤는 블록 여백이 맡는다). */
    private fun paragraphSpacing(block: Block): Float =
        if (block is Block.Paragraph) spec.paragraphSpacingEm * spec.baseSizePx else 0f

    // ── 문단 ────────────────────────────────────────────────────────

    /** 문단이 놓일 폭과 첫 줄 들여쓰기(px). 줄바꿈과 "그림이 줄에 들어가는가" 판정이 같은 값을 봐야 한다. */
    private class ParagraphGeometry(val indentStart: Float, val width: Float, val firstLineIndent: Float) {
        /** 어느 줄에서든 쓸 수 있는 가장 좁은 폭. 첫 줄 들여쓰기만큼 좁은 줄에 놓여도 넘치지 않아야 한다. */
        val narrowestLine: Float get() = (width - firstLineIndent.coerceAtLeast(0f)).coerceAtLeast(MIN_WIDTH)
    }

    private fun geometry(block: Block.Paragraph): ParagraphGeometry {
        val em = spec.baseSizePx
        val indentStart = block.style.indentStartEm * em
        val indentEnd = block.style.indentEndEm * em
        val width = (spec.contentWidthPx - indentStart - indentEnd).coerceAtLeast(MIN_WIDTH)

        // CSS text-indent 가 정해졌으면 그걸 쓰고, 없으면 사용자 설정을 쓴다. 둘을
        // 더하면 들여쓰기가 두 배가 된다.
        val indentEm = (block.style.firstLineIndentEm ?: spec.paragraphIndentEm)
            .let { if (spec.indentOff) min(it, 0f) else it }
        return ParagraphGeometry(indentStart, width, indentEm * em)
    }

    private suspend fun SequenceScope<Page>.placeParagraph(
        text: CharSequence,
        block: Block.Paragraph,
        builder: PageBuilder,
    ) {
        if (block.isBlank) return

        val geometry = geometry(block)
        val boxes = HashMap<Int, InlineBox>()
        val hrefs = HashMap<Int, String>()
        for (picture in block.images) {
            val image = picture.image
            // 그림을 끈 설정: 상자는 0 이라 그려지지 않지만, 그 글자를 글꼴로 그리지 않도록 자리는 둔다. 공백은 한쪽만
            // 남긴다 — 둘 다 두면 그림이 빠진 자리가 두 칸으로 벌어진다.
            val size = if (spec.imagesEnabled) inlineSize(image, styleAt(block, image.charStart), geometry) else 0f to 0f
            // 줄에 못 들어가는 그림은 expand() 가 이미 떼어 냈다. 여기서 null 이면 그 판정과 어긋난 것이라 그냥 0 으로 둔다.
            val (w, h) = size ?: (0f to 0f)
            boxes[image.charStart] = InlineBox(w, h, picture.spaceBefore, picture.spaceAfter && (spec.imagesEnabled || !picture.spaceBefore))
            hrefs[image.charStart] = image.href
        }

        val lines = lineBreaker.breakLines(
            text = text,
            runs = block.runs,
            style = overridden(block.style),
            constraints = LineConstraints(geometry.width, geometry.firstLineIndent),
            measurer = measurer,
            boxes = boxes,
        )

        for (line in lines) {
            // 줄이 남은 높이에 안 들어가면 페이지를 넘긴다. 단 빈 페이지에서는 그냥
            // 놓는다 — 한 줄이 지면보다 높으면(아주 큰 제목) 넘겨도 영원히 안 들어간다.
            // 그림 글자로 넣은 이모지처럼 1px 그림이 빽빽한 깨진 책은 한 쪽에 그림이 캐시 칸(u16)보다 많아질 수 있다.
            // 그 전에 쪽을 넘긴다 — 넘치면 캐시가 그 쪽 그림 수를 잘못 적어 엉뚱한 그림을 그린다.
            val crowded = builder.imageCount + line.boxes.size > PageCodec.MAX_OBJECTS_PER_PAGE
            if ((!builder.fits(line.heightPx) || crowded) && !builder.isAtPageTop) {
                builder.finish()?.let { yield(it) }
            }
            builder.addLine(line, xOffset = spec.margin.left + geometry.indentStart, hrefs = hrefs)
        }
    }

    /** [offset] 글자에 걸린 서식. 문장 속 그림은 둘레 글자의 크기를 기준으로 잰다(`<sup>` 안이면 작은 글자). */
    private fun styleAt(block: Block.Paragraph, offset: Int): TextStyle =
        block.runs.firstOrNull { offset >= it.start && offset < it.endExclusive }?.style ?: TextStyle.Default

    // ── 문장 속 그림 ────────────────────────────────────────────────

    /**
     * 문장 속 그림 가운데 줄에 들어가지 못하는 것을 예전처럼 블록으로 떼어 낸다. 모두 들어가면 문단 그대로.
     *
     * 떼어 낸 모양은 파서가 문장 속 그림을 언제나 블록으로 만들던 판과 **똑같다**: 그림 앞 글 · 가운데 그림 · 들여쓰기
     * 없이 이어지는 뒤 글(`continuesLine`). 큰 삽화에 설명 한 줄이 붙은 책이 이 수정으로 달라 보이지 않게 한다.
     * 글자 위치는 문단이든 블록이든 같다 — 문단 시작(찾기 · 낭독의 경계)은 장의 블록 목록에서 세므로 그대로다.
     */
    private fun expand(block: Block): List<Block> {
        if (block !is Block.Paragraph || block.images.isEmpty() || !spec.imagesEnabled) return listOf(block)
        val geometry = geometry(block)
        val apart = block.images.filter { inlineSize(it.image, styleAt(block, it.image.charStart), geometry) == null }
        if (apart.isEmpty()) return listOf(block)

        val base = block.style
        val out = ArrayList<Block>()
        var cursor = block.charStart
        var afterImage = false
        var pageBreak = base.pageBreakBefore

        // 파서가 그림마다 문단을 끊던 때의 서식: 그림 뒤는 들여쓰기 · 위 여백 없이 이어지고, 쪽 넘김은 첫 블록만 갖는다.
        fun nextStyle(): BlockStyle {
            val style = if (afterImage) base.copy(firstLineIndentEm = 0f, marginTopEm = 0f) else base
            val withBreak = style.copy(pageBreakBefore = pageBreak)
            pageBreak = false
            return withBreak
        }

        fun emitText(until: Int) {
            val runs = block.runs.mapNotNull { run ->
                val from = max(run.start, cursor)
                val to = min(run.endExclusive, until)
                if (from < to) run.copy(start = from, endExclusive = to) else null
            }
            if (runs.isEmpty()) return
            out += Block.Paragraph(
                runs = runs,
                style = nextStyle(),
                continuesLine = if (afterImage) true else block.continuesLine,
                images = block.images.filter { it.image.charStart >= cursor && it.image.charStart < until },
            )
        }

        for (picture in apart) {
            emitText(picture.image.charStart)
            out += picture.image.copy(style = nextStyle())
            cursor = picture.image.charEndExclusive
            afterImage = true
        }
        emitText(block.charEndExclusive)
        return out
    }

    /**
     * 문장 속 그림이 줄에서 받는 상자. 줄에 둘 수 없으면 null(블록으로 뗀다).
     *
     * 1. 크기는 블록 그림과 같은 규칙([imageSize])으로 먼저 정한다 — 책이 적은 크기 · 파일 크기 · 줄이기만.
     * 2. 그 높이가 둘레 글의 줄 높이(줄 간격 포함)의 [INLINE_MAX_LINE_HEIGHTS] 배를 넘으면 글자처럼 둘 그림이 아니다(삽화에 설명이
     *    붙은 모양). 넘는 그림을 줄에 넣으면 한 줄이 쪽 절반으로 벌어진다.
     * 3. 책이 크기를 적지 않았고 글자 윗선(ascent)보다 높으면 그 높이로 줄인다. 로고 · 외자는 글자처럼 보여야 한다 —
     *    원래 크기로 두면 그 줄만 벌어져 문단의 줄 간격이 들쭉날쭉해진다. 줄 높이가 아니라 윗선에 맞추는 이유: 그림은
     *    베이스라인 위에 서므로 윗선까지면 줄 높이가 그대로이고, 줄 높이까지 키우면 그 줄만 아래로 밀렸다. 책이 크기를
     *    **적었으면** 따른다(그 줄이 늘어난다) — 출판사가 일부러 고른 크기다.
     * 4. 파일 크기도 지정도 없으면(머리가 깨졌거나 0×0) 윗선 높이의 정사각형으로 둔다. 블록 그림처럼 본문 폭 3:4 로
     *    잡으면 추측 하나로 문장이 쪼개지고, 문장 속 그림은 거의 늘 글자 크기의 기호다. 그리는 쪽이 비율대로 상자에
     *    넣으므로(object-fit: contain) 실제로 정사각이 아니어도 일그러지지 않는다.
     * 5. 줄의 가장 좁은 폭보다 넓으면 null — 줄을 넘쳐 글이 지면 밖으로 밀리는 것보다 블록이 낫다.
     */
    private fun inlineSize(image: Block.Image, style: TextStyle, geometry: ParagraphGeometry): Pair<Float, Float>? {
        // 줄 높이는 화면에 보이는 줄(글자 줄 높이 × 줄 간격 설정)이다. 측정기의 값만 쓰면 앱에서는 1em(줄 높이를 1em 으로
        // 정규화했다)이라, 2em 남짓한 40px 로고도 "줄의 두 배를 넘는다" 며 문장을 끊고 혼자 섰다.
        val lineHeight = measurer.lineHeight(style) * spec.lineHeightMultiplier
        val ascent = measurer.ascent(style)
        val specified = image.sizing.width != null || image.sizing.height != null
        var (width, height) = if (image.hasIntrinsicSize || specified) imageSize(image) else ascent to ascent
        if (height > lineHeight * INLINE_MAX_LINE_HEIGHTS + EPSILON) return null
        if (!specified && height > ascent) {
            width *= ascent / height
            height = ascent
        }
        if (width > geometry.narrowestLine + EPSILON) return null
        return max(1f, width) to max(1f, height)
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

        val imageCount: Int get() = images.size

        private val bottom: Float get() = spec.margin.top + spec.contentHeightPx

        fun fits(heightPx: Float): Boolean = y + heightPx <= bottom + EPSILON

        fun advance(heightPx: Float) {
            // 여백이 페이지를 넘기지는 않는다. 넘길 만큼 크면 남은 높이까지만 쓴다 —
            // 그러면 다음 요소가 fits() 에서 걸려 정상적으로 페이지를 넘긴다.
            y = min(y + heightPx, bottom)
        }

        fun addLine(line: LaidLine, xOffset: Float, hrefs: Map<Int, String> = emptyMap()) {
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
            // 문장 속 그림: 아래 끝이 베이스라인에 선다. 크기 0 은 그림을 끈 설정이 남긴 자리라 그리지 않는다.
            line.boxes.forEach { box ->
                val href = hrefs[box.charIndex] ?: return@forEach
                if (box.widthPx <= 0f || box.heightPx <= 0f) return@forEach
                images.add(PlacedImage(href, xOffset + box.xPx, baseline - box.heightPx, box.widthPx, box.heightPx))
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

        /** 문장 속 그림이 줄에 남을 수 있는 높이(둘레 글자 줄 높이의 배수). 넘으면 예전처럼 블록으로 선다. */
        const val INLINE_MAX_LINE_HEIGHTS = 2f

        const val RULE_THICKNESS_EM = 0.07f
        const val RULE_PADDING_EM = 0.6f
    }
}
