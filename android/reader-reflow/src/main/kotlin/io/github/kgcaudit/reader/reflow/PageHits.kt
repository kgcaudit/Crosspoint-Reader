package io.github.kgcaudit.reader.reflow

import androidx.compose.ui.geometry.Rect
import io.github.kgcaudit.reader.layout.Page
import io.github.kgcaudit.reader.layout.TextMeasurer
import io.github.kgcaudit.reader.layout.html.Link

/**
 * 쪽 위에서 글자 구간 [start]..[endExclusive] 가 차지하는 네모들(줄마다 하나).
 *
 * 조판은 양쪽정렬로 벌린 자리에서 조각(run)을 끊어 두므로, 한 조각 안에서는 글자 폭을 더해 가면 정확한 x 가
 * 나온다. 찾은 말 칠하기 · 각주 표시 누르기가 모두 이 네모를 쓴다.
 */
fun rangeBoxes(page: Page, text: CharSequence, measurer: TextMeasurer, start: Int, endExclusive: Int): List<Rect> {
    val boxes = ArrayList<Rect>()
    for (run in page.runs) {
        val from = maxOf(run.start, start)
        val to = minOf(run.endExclusive, endExclusive)
        if (from >= to || run.start >= text.length) continue
        val safeTo = to.coerceAtMost(text.length)
        val left = run.xPx + measurer.advance(text, run.start, from, run.style)
        val right = left + measurer.advance(text, from, safeTo, run.style)
        val top = run.baselineYPx - measurer.ascent(run.style)
        boxes.add(Rect(left, top, right, top + measurer.lineHeight(run.style)))
    }
    return boxes
}

/**
 * 누른 자리([x], [y])의 링크. 표시가 글자 하나(¹)여도 손가락이 닿는 네모([minTouchPx], 48dp)는 넉넉하게 — 그 안이면
 * 그 링크다(F2). 여럿이 겹치면 가장 가까운 것.
 */
fun linkAt(page: Page, text: CharSequence, measurer: TextMeasurer, links: List<Link>, x: Float, y: Float, minTouchPx: Float): Link? {
    var best: Link? = null
    var bestDistance = Float.MAX_VALUE
    for (link in links) {
        if (link.endExclusive <= page.startChar || link.start >= page.endCharExclusive) continue
        for (box in rangeBoxes(page, text, measurer, link.start, link.endExclusive)) {
            val w = maxOf(box.width, minTouchPx)
            val h = maxOf(box.height, minTouchPx)
            val target = Rect(box.center.x - w / 2, box.center.y - h / 2, box.center.x + w / 2, box.center.y + h / 2)
            if (!target.contains(androidx.compose.ui.geometry.Offset(x, y))) continue
            val d = (box.center - androidx.compose.ui.geometry.Offset(x, y)).getDistance()
            if (d < bestDistance) {
                best = link
                bestDistance = d
            }
        }
    }
    return best
}

/**
 * 누른 자리([x], [y])에 가장 가까운 글자의 오프셋(고르기 · 손잡이 끌기).
 *
 * 먼저 [y] 가 든 줄(없으면 세로로 가장 가까운 줄)을 고르고, 그 줄에서 [x] 가 든 글자를 찾는다. 줄 끝 바깥을
 * 누르면 그 줄 마지막 글자 — 손잡이를 여백까지 끌어도 선택이 사라지지 않게. [after] 면 글자 오른쪽 반을 누를
 * 때 다음 오프셋을 준다(끝 손잡이는 "이 글자까지" 이므로 끝 자리가 글자 뒤여야 한다).
 */
fun charAt(page: Page, text: CharSequence, measurer: TextMeasurer, x: Float, y: Float, after: Boolean = false): Int? {
    val lines = page.runs.filter { it.start < it.endExclusive && it.start < text.length }.groupBy { it.baselineYPx }
    if (lines.isEmpty()) return null
    fun top(r: io.github.kgcaudit.reader.layout.PlacedRun) = r.baselineYPx - measurer.ascent(r.style)
    fun bottom(r: io.github.kgcaudit.reader.layout.PlacedRun) = top(r) + measurer.lineHeight(r.style)
    val line = lines.values.minBy { runs ->
        val t = runs.minOf { top(it) }
        val b = runs.maxOf { bottom(it) }
        when {
            y < t -> t - y
            y > b -> y - b
            else -> 0f
        }
    }.sortedBy { it.xPx }
    val run = line.firstOrNull { x < it.xPx + measurer.advance(text, it.start, it.endExclusive.coerceAtMost(text.length), it.style) }
        ?: return line.last().endExclusive.coerceAtMost(text.length).let { if (after) it else it - 1 }
    if (x <= run.xPx) return run.start
    val end = run.endExclusive.coerceAtMost(text.length)
    var i = run.start
    var left = run.xPx
    while (i < end) {
        val next = if (i + 1 < end && Character.isHighSurrogate(text[i])) i + 2 else i + 1
        val right = run.xPx + measurer.advance(text, run.start, next, run.style)
        if (x < right) return if (after && x > (left + right) / 2) next else i
        left = right
        i = next
    }
    return if (after) end else end - 1
}

/**
 * [offset] 이 든 낱말(길게 눌렀을 때 처음 고르는 구간). 띄어쓰기 · 문장부호에서 끊는다 — 한국어는 어절 하나.
 * 공백을 눌렀으면 그 한 글자만(빈 구간을 돌려주면 메뉴가 뜰 자리가 없다).
 */
fun wordAt(text: CharSequence, offset: Int): IntRange {
    if (text.isEmpty()) return IntRange.EMPTY
    val at = offset.coerceIn(0, text.length - 1)
    fun inWord(c: Char) = c.isLetterOrDigit() || c == '\'' || c == '’' || c == '-'
    if (!inWord(text[at])) return at..at
    var s = at
    while (s > 0 && inWord(text[s - 1])) s--
    var e = at
    while (e < text.length - 1 && inWord(text[e + 1])) e++
    return s..e
}
