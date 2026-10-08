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
        boxes: Map<Int, InlineBox>,
    ): List<LaidLine> {
        val tokens = tokenize(text, runs, measurer, boxes)
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
                // 서식 경계로만 나뉜 조각들(`<i>Hamlet</i>,` · `word<sup>1</sup>`)은 줄바꿈 기회가 아니다 — 한 덩어리로
                // 넣는다. 조각마다 끊던 때는 다음 줄이 쉼표로 시작하거나 낱말이 두 줄로 쪼개졌다.
                var end = index
                var chain = tokens[index].advance
                while (tokens[end].glueNext && end + 1 < tokens.size) {
                    chain += tokens[end].trailingSpaceAdvance
                    end++
                    chain += tokens[end].advance
                }
                // 줄 끝의 공백은 폭에 넣지 않는다(CSS 와 같은 동작). 넣으면 마지막
                // 어절이 들어갈 수 있는데도 다음 줄로 밀린다.
                if (current.isNotEmpty() && width + chain > available + EPSILON) break
                // 덩어리 하나가 줄보다 넓으면 조각 단위로 넣는다(넘치는 조각은 아래에서 쪼갠다).
                if (current.isEmpty() && width + chain > available + EPSILON) end = index
                for (k in index..end) {
                    current.add(tokens[k])
                    width += tokens[k].advance + tokens[k].trailingSpaceAdvance
                }
                index = end + 1
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
        /** 다음 조각과의 경계가 줄바꿈 기회가 아니다(서식만 바뀌는 자리). 둘을 다른 줄에 두지 않는다. */
        val glueNext: Boolean = false,
        /** 문장 속 그림. 글자 대신 이 상자를 둔다. */
        val box: InlineBox? = null,
    )

    private fun tokenize(
        text: CharSequence,
        runs: List<InlineRun>,
        measurer: TextMeasurer,
        boxes: Map<Int, InlineBox>,
    ): ArrayList<Token> {
        val tokens = ArrayList<Token>()
        for (run in runs) {
            if (run.isEmpty) continue
            var breaks = LineBreakRules.opportunities(text, run.start, run.endExclusive, breakBetweenCjk)
            if (boxes.isNotEmpty()) {
                // 그림은 언제나 혼자 한 조각이다. 금칙으로 붙어야 하는 자리(`(` 뒤 · `.` 앞)는 아래 glueNext 가 이어 준다 —
                // 조각 하나에 글자와 그림을 섞으면 그 그림 글자가 글꼴로 재지고 그려진다.
                val edges = boxes.keys.filter { it >= run.start && it < run.endExclusive }.flatMap { listOf(it, it + 1) }
                if (edges.isNotEmpty()) breaks = (breaks + edges).filter { it > run.start && it < run.endExclusive }.distinct().sorted()
            }
            var from = run.start
            for (at in breaks + listOf(run.endExclusive)) {
                if (at <= from) continue
                val box = boxes[from]?.takeIf { at == from + 1 }
                tokens.add(if (box != null) boxToken(from, box, run.style, measurer) else makeToken(text, from, at, run.style, measurer))
                from = at
            }
        }
        // 런 안의 경계는 모두 줄바꿈 기회다. 기회가 아닌 경계는 런(서식)이 바뀌는 자리뿐 — 거기에 금칙을 건다.
        for (i in 0 until tokens.size - 1) {
            val at = tokens[i].endExclusive
            if (at == tokens[i + 1].start && at in 1 until text.length &&
                !LineBreakRules.canBreakBetween(text[at - 1], text[at], breakBetweenCjk)
            ) {
                tokens[i] = tokens[i].copy(glueNext = true)
            }
        }
        // 그림 앞뒤의 원문 공백(정규화 텍스트에는 없다)을 실제 공백처럼 다룬다: 앞 조각의 꼬리 공백이 되고, 뒤 조각에는
        // 양쪽정렬이 벌릴 수 있는 틈이 된다. 줄 끝에 오면 실제 공백처럼 폭이 사라진다.
        for (i in 0 until tokens.size - 1) {
            val here = tokens[i]
            val next = tokens[i + 1]
            if (here.endExclusive != next.start) continue
            if (next.box?.spaceBefore == true && here.trailingSpaceAdvance == 0f) {
                tokens[i] = here.copy(trailingSpaceAdvance = measurer.spaceAdvance(here.style))
            }
            if (here.box?.spaceAfter == true) tokens[i + 1] = next.copy(breakableGapBefore = true)
        }
        return tokens
    }

    private fun boxToken(at: Int, box: InlineBox, style: TextStyle, measurer: TextMeasurer): Token = Token(
        start = at,
        endExclusive = at + 1,
        contentEnd = at + 1,
        style = style,
        advance = box.widthPx,
        trailingSpaceAdvance = if (box.spaceAfter) measurer.spaceAdvance(style) else 0f,
        breakableGapBefore = box.spaceBefore,
        box = box,
    )

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
        // 그림은 쪼갤 수 없다. 조판기가 줄보다 넓은 그림을 미리 블록으로 돌려 여기 오지 않는다.
        if (token.box != null) return null
        var cut = token.start + 1
        while (cut < token.contentEnd) {
            if (measurer.advance(text, token.start, cut + 1, token.style) > available) break
            cut++
        }
        // 이모지·확장 한자는 두 칸(서로게이트 쌍)이다. 그 사이에서 자르면 두 줄 모두 깨진 글자가 그려진다.
        if (cut < token.contentEnd && Character.isLowSurrogate(text[cut]) && cut - 1 > token.start) cut--
        if (cut >= token.contentEnd) return null
        return Split(
            head = makeToken(text, token.start, cut, token.style, measurer),
            tail = makeToken(text, cut, token.endExclusive, token.style, measurer).copy(glueNext = token.glueNext),
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
        // 좌표가 실수라 나눠 떨어지지 않는 나머지가 없다. 간격마다 같은 몫을 얹으면 줄 끝이 정확히 맞는다.
        val extraPerGap = if (justify) slack / gapCount else 0f

        // 이어지는 토큰을 한 조각으로 합친다.
        //
        // 글자 단위 줄바꿈에서는 토큰이 글자마다 하나씩 생긴다. 그대로 두면 한 줄이
        // 수십 개 조각이 되어 캐시가 부풀고 그리기도 글자마다 호출이 된다. 서식이
        // 같고 글자가 이어지며 사이에 벌어진 틈이 없으면(양쪽정렬이 끼워 넣은 여유가
        // 없으면) 한 조각으로 묶어도 그림이 같다.
        val pieces = ArrayList<PlacedPiece>()
        val placedBoxes = ArrayList<PlacedBox>()
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
                gapInserted = extraPerGap > EPSILON
            }

            val continues = pendingStart >= 0 &&
                !gapInserted &&
                token.style == pendingStyle &&
                token.start == pendingTokenEnd

            if (token.box != null) {
                // 그림 글자는 조각에 넣지 않는다 — 넣으면 U+FFFC 가 글꼴의 빈 네모로 찍힌다. 앞 조각도 여기서 닫는다.
                flushPending()
                placedBoxes.add(PlacedBox(token.start, x, token.box.widthPx, token.box.heightPx))
            } else if (continues) {
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

        // 그림 조각의 서식도 센다 — 그림만 든 줄도 그 문단 글자의 줄 높이(CSS 의 strut)를 갖는다.
        val tallest = tokens.maxOf { measurer.lineHeight(it.style) }
        val deepest = tokens.maxOf { measurer.ascent(it.style) }
        // 그림은 아래 끝을 베이스라인에 세운다(브라우저의 vertical-align: baseline). 글자 윗선보다 높으면 그만큼 줄을
        // 위로 늘린다 — 베이스라인을 그대로 두고 그림만 키우면 윗줄 글자를 덮었다.
        val rise = (placedBoxes.maxOfOrNull { it.heightPx } ?: 0f) - deepest
        val grow = rise.coerceAtLeast(0f)
        return LaidLine(
            pieces = pieces,
            startChar = tokens.first().start,
            endCharExclusive = tokens.last().endExclusive,
            heightPx = tallest * lineHeightMultiplier + grow,
            ascentPx = deepest + grow,
            isLastLine = isLastLine,
            boxes = placedBoxes,
        )
    }

    private companion object {
        /** 부동소수 비교 여유. 이게 없으면 폭이 딱 맞는 줄이 넘친 것으로 판정된다. */
        const val EPSILON = 0.01f

        /** 들여쓰기가 지면을 다 먹어도 최소한 이만큼은 남긴다(무한 루프 방지). */
        const val MIN_LINE_WIDTH = 1f
    }
}
