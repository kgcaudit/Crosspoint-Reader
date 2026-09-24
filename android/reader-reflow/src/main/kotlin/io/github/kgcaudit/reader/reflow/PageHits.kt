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
