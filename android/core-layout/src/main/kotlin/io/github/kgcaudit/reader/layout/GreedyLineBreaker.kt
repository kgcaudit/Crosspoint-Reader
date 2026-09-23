package io.github.kgcaudit.reader.layout

/**
 * 탐욕적 줄바꿈. 들어가는 만큼 채우고 넘치면 끊는다.
 *
 * ### 왜 StaticLayout 에 맡기지 않는가
 *
 * 안드로이드의 `StaticLayout` 은 잘 만들어져 있지만 JVM 에 없다. 조판을 그쪽에
 * 맡기면 페이지 분할을 기기 없이 테스트할 수 없고, 그건 이 프로젝트가 코어를 순수
 * Kotlin 으로 유지하는 이유를 무너뜨린다. 셰이핑·커닝 같은 어려운 일은 [TextMeasurer]
 * 뒤의 `Paint` 가 여전히 해 주므로, 여기서 잃는 것은 정책뿐이고 그 정책은 우리가
 * 통제하고 싶은 바로 그것이다.
 *
 * ### 정렬
 *
 * 남는 폭을 [TextAlign] 에 따라 처리한다. 양쪽정렬은 **실제 어절 경계**에만 여유를
 * 나눈다 — CJK 글자 사이에도 나누면 문단 전체가 균일 자간으로 렌더되고, 그건 한국어
 * 조판에서 틀린 결과다. 마지막 줄은 늘리지 않는다(관례).
 */
class GreedyLineBreaker(
    /**
     * CJK 글자 사이에서도 끊을지.
     *
     * 기본이 true 인 이유: 한국어 어절은 길어서 어절 경계만 쓰면 한 줄에 두어 개만
     * 들어가고, 양쪽정렬이 그 사이를 벌려 간격이 폭발한다. 글자 단위로 끊으면 줄이
     * 폭까지 차서 간격이 고르다.
     */
    private val breakBetweenCjk: Boolean = true,
    /** 줄 높이 배수(사용자 설정의 줄 간격). */
    private val lineHeightMultiplier: Float = 1f,
) : LineBreaker {

    override fun breakLines(
        text: CharSequence,
        runs: List<InlineRun>,
        style: BlockStyle,
        constraints: LineConstraints,
        measurer: TextMeasurer,
    ): List<LaidLine> {
        val tokens = tokenize(text, runs, measurer)
        if (tokens.isEmpty()) return emptyList()

        val lines = ArrayList<LaidLine>()
        val current = ArrayList<Token>()
        var index = 0

        while (index < tokens.size) {
            current.clear()
            val isFirstLine = lines.isEmpty()
            val indent = if (isFirstLine) constraints.firstLineIndentPx else 0f
            val available = (constraints.widthPx - indent).coerceAtLeast(MIN_LINE_WIDTH)

            var width = 0f
            while (index < tokens.size) {
                val token = tokens[index]
                // 줄 끝의 공백은 폭에 넣지 않는다(CSS 와 같은 동작). 넣으면 마지막
                // 어절이 들어갈 수 있는데도 다음 줄로 밀린다.
                val candidate = width + token.advance
                if (current.isNotEmpty() && candidate > available + EPSILON) break
                current.add(token)
                width = candidate + token.trailingSpaceAdvance
                index++
            }

            // 토큰 하나가 폭보다 넓다(긴 URL, 공백 없는 라틴 단어). 강제로 쪼갠다 —
            // 그대로 두면 지면 밖으로 넘쳐 글자가 잘린다.
            //
            // 글자 하나가 폭보다 넓으면(아주 좁은 지면 + 전각) 쪼갤 수 없어 그대로
            // 넘친다. 그게 유일하게 남는 선택이다 — 글자를 버리면 본문이 사라지고,
            // 크기를 줄이면 사용자 설정을 무시하는 것이다. 무한 루프는 아니다:
            // forceSplit 이 null 을 주면 토큰을 그대로 배치하고 다음으로 넘어간다.
            if (current.size == 1 && current[0].advance > available + EPSILON) {
                val split = forceSplit(text, current[0], available, measurer)
                if (split != null) {
                    current[0] = split.head
                    tokens.add(index, split.tail)
                    width = split.head.advance
                }
            }

            val isLast = index >= tokens.size
            lines.add(layOut(current, indent, available, style.align, isLast, measurer))
        }
        return lines
    }

    // ── 토큰화 ──────────────────────────────────────────────────────

    /**
     * 줄바꿈 기회로 잘린 조각.
     *
     * [trailingSpaceAdvance] 를 따로 두는 이유: 줄 끝에 온 공백은 폭을 차지하지 않아야
     * 하지만(CSS 동작), 줄 중간에 있으면 차지해야 한다. 한 값에 섞으면 둘 중 하나가
     * 틀린다.
     */
    private data class Token(
        val start: Int,
        val endExclusive: Int,
        /** 공백을 뺀 내용 끝. 그릴 때 이 위치까지만 그린다. */
        val contentEnd: Int,
        val style: TextStyle,
        val advance: Float,
        val trailingSpaceAdvance: Float,
        /** 앞에 공백이 있어 양쪽정렬이 늘릴 수 있는 경계인지. */
        val breakableGapBefore: Boolean,
    )

    private fun tokenize(
        text: CharSequence,
        runs: List<InlineRun>,
        measurer: TextMeasurer,
    ): ArrayList<Token> {
        val tokens = ArrayList<Token>()
        for (run in runs) {
            if (run.isEmpty) continue
            val breaks = LineBreakRules.opportunities(text, run.start, run.endExclusive, breakBetweenCjk)
            var from = run.start
            for (at in breaks + listOf(run.endExclusive)) {
                if (at <= from) continue
                tokens.add(makeToken(text, from, at, run.style, measurer))
                from = at
            }
        }
        return tokens
    }

    private fun makeToken(
        text: CharSequence,
        start: Int,
        endExclusive: Int,
        style: TextStyle,
        measurer: TextMeasurer,
    ): Token {
        var contentEnd = endExclusive
        while (contentEnd > start && LineBreakRules.isSpace(text[contentEnd - 1])) contentEnd--

        val hasGap = start > 0 && LineBreakRules.isSpace(text[start - 1])
        return Token(
            start = start,
            endExclusive = endExclusive,
            contentEnd = contentEnd,
            style = style,
            advance = measurer.advance(text, start, contentEnd, style),
            trailingSpaceAdvance = measurer.advance(text, contentEnd, endExclusive, style),
            breakableGapBefore = hasGap,
        )
    }

    private class Split(val head: Token, val tail: Token)

    /** 폭보다 넓은 토큰을 들어가는 만큼만 남기고 쪼갠다. */
    private fun forceSplit(
        text: CharSequence,
        token: Token,
        available: Float,
        measurer: TextMeasurer,
    ): Split? {
        var cut = token.start + 1
        while (cut < token.contentEnd) {
            if (measurer.advance(text, token.start, cut + 1, token.style) > available) break
            cut++
        }
        if (cut >= token.contentEnd) return null
        return Split(
            head = makeToken(text, token.start, cut, token.style, measurer),
            tail = makeToken(text, cut, token.endExclusive, token.style, measurer),
        )
    }

    // ── 배치 ────────────────────────────────────────────────────────

    private fun layOut(
        tokens: List<Token>,
        indent: Float,
        available: Float,
        align: TextAlign,
        isLastLine: Boolean,
        measurer: TextMeasurer,
    ): LaidLine {
        val contentWidth = tokens.sumOf { it.advance.toDouble() }.toFloat() +
            tokens.dropLast(1).sumOf { it.trailingSpaceAdvance.toDouble() }.toFloat()
        val slack = (available - contentWidth).coerceAtLeast(0f)

        // 양쪽정렬로 늘릴 수 있는 간격의 수. 첫 토큰 앞은 세지 않는다.
        val gapCount = tokens.drop(1).count { it.breakableGapBefore }
        val justify = align == TextAlign.Justify && !isLastLine && gapCount > 0

        var x = indent + when {
            justify -> 0f
            align == TextAlign.Center -> slack / 2f
            align == TextAlign.End -> slack
            else -> 0f
        }
        val extraPerGap = if (justify) slack / gapCount else 0f

        // 나머지 픽셀을 앞쪽 간격에 하나씩 얹어, 줄의 오른쪽 끝이 정확히 맞게 한다.
        var remainder = if (justify) (slack - extraPerGap * gapCount) else 0f

        // 이어지는 토큰을 한 조각으로 합친다.
        //
        // 글자 단위 줄바꿈에서는 토큰이 글자마다 하나씩 생긴다. 그대로 두면 한 줄이
        // 수십 개 조각이 되어 캐시가 부풀고 그리기도 글자마다 호출이 된다. 서식이
        // 같고 글자가 이어지며 사이에 벌어진 틈이 없으면(양쪽정렬이 끼워 넣은 여유가
        // 없으면) 한 조각으로 묶어도 그림이 같다.
        val pieces = ArrayList<PlacedPiece>()
        var pendingStart = -1
        var pendingDrawEnd = -1
        var pendingTokenEnd = -1
        var pendingStyle = TextStyle.Default
        var pendingX = 0f

        fun flushPending() {
            if (pendingStart >= 0 && pendingDrawEnd > pendingStart) {
                pieces.add(PlacedPiece(pendingStart, pendingDrawEnd, pendingStyle, pendingX))
            }
            pendingStart = -1
        }

        tokens.forEachIndexed { i, token ->
            var gapInserted = false
            if (i > 0 && justify && token.breakableGapBefore) {
                x += extraPerGap
                if (remainder > EPSILON) {
                    x += 1f
                    remainder -= 1f
                }
                gapInserted = extraPerGap > EPSILON || remainder > EPSILON
            }

            val continues = pendingStart >= 0 &&
                !gapInserted &&
                token.style == pendingStyle &&
                token.start == pendingTokenEnd

            if (continues) {
                pendingDrawEnd = token.contentEnd
                pendingTokenEnd = token.endExclusive
            } else {
                flushPending()
                if (token.contentEnd > token.start) {
                    pendingStart = token.start
                    pendingDrawEnd = token.contentEnd
                    pendingTokenEnd = token.endExclusive
                    pendingStyle = token.style
                    pendingX = x
                }
            }

            x += token.advance
            if (i < tokens.size - 1) x += token.trailingSpaceAdvance
        }
        flushPending()

        val tallest = tokens.maxOf { measurer.lineHeight(it.style) }
        val deepest = tokens.maxOf { measurer.ascent(it.style) }
        return LaidLine(
            pieces = pieces,
            startChar = tokens.first().start,
            endCharExclusive = tokens.last().endExclusive,
            heightPx = tallest * lineHeightMultiplier,
            ascentPx = deepest,
            isLastLine = isLastLine,
        )
    }

    private companion object {
        /** 부동소수 비교 여유. 이게 없으면 폭이 딱 맞는 줄이 넘친 것으로 판정된다. */
        const val EPSILON = 0.01f

        /** 들여쓰기가 지면을 다 먹어도 최소한 이만큼은 남긴다(무한 루프 방지). */
        const val MIN_LINE_WIDTH = 1f
    }
}
