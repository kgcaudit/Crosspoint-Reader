package io.github.kgcaudit.reader.reflow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.kgcaudit.reader.document.Annotation
import io.github.kgcaudit.reader.document.HighlightColor
import io.github.kgcaudit.reader.layout.Page
import io.github.kgcaudit.reader.text.AndroidTextMeasurer
import io.github.kgcaudit.reader.ui.design.CpText
import io.github.kgcaudit.reader.ui.design.CpPill
import io.github.kgcaudit.reader.ui.design.CpTheme
import io.github.kgcaudit.reader.ui.design.Pen
import io.github.kgcaudit.reader.ui.design.bounds

/** `HighlightColor` ↔ 디자인의 [Pen]. 둘은 같은 순서다. */
internal val HighlightColor.pen: Pen get() = Pen.entries[ordinal]
internal val Pen.color: HighlightColor get() = HighlightColor.entries[ordinal]

/** 고른 구간(장 텍스트의 글자 구간, 끝은 다음 자리). 한 번에 보이는 쪽 안에서만 고른다(N9). */
internal data class Selection(val start: Int, val endExclusive: Int) {
    val range: IntRange get() = start until endExclusive
}

/**
 * 보이는 쪽(두쪽이면 두 쪽) 위에서 글자 구간이 차지하는 네모들, 화면 좌표로. 오른쪽 쪽의 네모는 반 폭만큼 민다.
 */
internal fun screenBoxes(
    page: Page?,
    right: Page?,
    text: String,
    measurer: AndroidTextMeasurer,
    start: Int,
    endExclusive: Int,
    halfWidth: Float,
): List<Rect> {
    val boxes = ArrayList<Rect>()
    page?.let { boxes += rangeBoxes(it, text, measurer, start, endExclusive) }
    right?.let { boxes += rangeBoxes(it, text, measurer, start, endExclusive).map { r -> r.translate(halfWidth, 0f) } }
    return boxes
}

/** 누른 화면 자리의 글자. 두쪽이면 오른쪽 반은 오른쪽 쪽에서 찾는다. */
internal fun screenCharAt(
    page: Page?,
    right: Page?,
    text: String,
    measurer: AndroidTextMeasurer,
    x: Float,
    y: Float,
    halfWidth: Float,
    after: Boolean = false,
): Int? {
    val onRight = right != null && x > halfWidth
    val target = (if (onRight) right else page) ?: return null
    return charAt(target, text, measurer, if (onRight) x - halfWidth else x, y, after)
}

/** 누른 자리의 형광펜. 겹쳐 있으면 나중에 칠한 것(위에 보이는 것). */
internal fun annotationAt(boxesOf: (Annotation) -> List<Rect>, annotations: List<Annotation>, x: Float, y: Float): Annotation? =
    annotations.lastOrNull { a -> boxesOf(a).any { it.contains(Offset(x, y)) } }

// ── 떠 있는 메뉴 · 손잡이(N4) ── PDF 와 같은 공용 부품(CpFloatingMenu · CpSelectionHandles)을 쓴다(0.29.0). 한 줄씩 같은
// 사본을 따로 두어, 한쪽만 고치면 EPUB 과 PDF 의 메뉴가 달라질 참이었다.

/**
 * 손잡이를 끈 자리 → 새 구간. 시작 손잡이는 끝을 넘지 못하고 끝 손잡이는 시작을 넘지 못한다(최소 한 글자) —
 * 넘기면 구간이 뒤집혀 메뉴가 엉뚱한 곳에 뜬다. 고를 글자가 없는 자리(그림 위)면 그대로 둔다.
 */
internal fun dragHandle(selection: Selection, isStart: Boolean, offset: Int?): Selection {
    offset ?: return selection
    return if (isStart) {
        Selection(offset.coerceAtMost(selection.endExclusive - 1), selection.endExclusive)
    } else {
        Selection(selection.start, offset.coerceAtLeast(selection.start + 1))
    }
}

// ── 메모 판(N4) ────────────────────────────────────────────────────

/** 메모를 쓰는 중인 것: 새로 칠할 구간이거나 이미 칠한 곳. */
internal data class MemoDraft(
    val annotation: Annotation?,
    val selection: Selection?,
    val quote: String,
    val pen: Pen,
    /** [selection] 을 고른 장. 판이 떠 있는 사이 장이 바뀌어도 이 장에 칠한다. */
    val spineIndex: Int? = null,
)

/** 메뉴의 "이어서 ›"(쪽을 넘어 이어서 고르기). */
internal const val CONTINUE = "이어서 ›"

/** 보이는 쪽의 마지막 글자 뒤 자리(끝의 공백은 뺀다). 고른 끝이 여기에 닿아야 "이어서" 가 뜬다. */
internal fun lastVisible(text: String, shownStart: Int, shownEnd: Int): Int {
    var e = shownEnd.coerceAtMost(text.length)
    while (e > shownStart && text[e - 1].isWhitespace()) e--
    return e
}

/**
 * 이어 고를 때 새 쪽에서 처음 잡아 줄 끝: 쪽 첫머리부터 시작하는 문장의 끝. 쪽 안에 문장 끝이 없으면 쪽 끝.
 * 쪽 첫머리의 문장 조각(앞 쪽에서 넘어온 문장의 나머지)이 대개 이어 고르려던 곳이다.
 */
internal fun continueEnd(text: String, paragraphStarts: Set<Int>, pageStart: Int, shownEnd: Int): Int {
    val end = shownEnd.coerceAtMost(text.length)
    if (pageStart >= end) return end
    val sentences = io.github.kgcaudit.reader.layout.book.splitSentences(text.substring(pageStart, end), paragraphStarts.map { it - pageStart }.filter { it > 0 }.toSet())
    return sentences.firstOrNull()?.let { pageStart + it.endExclusive } ?: lastVisible(text, pageStart, end)
}

/** 새 쪽 위의 띠: "앞 쪽에서 이어 고르는 중 · 64자". 시작 손잡이가 보이지 않는 까닭을 알린다. */
@Composable
internal fun ContinueBanner(charsBefore: Int, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(CpTheme.metrics.cornerMedium)).background(CpPill.translucent).padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        io.github.kgcaudit.reader.ui.design.CpIcon(io.github.kgcaudit.reader.ui.design.CpIcons.Back, Color.White, size = 14.dp)
        CpText("앞 쪽에서 이어 고르는 중 · ${charsBefore}자", CpTheme.type.caption, Color.White, Modifier.padding(start = 4.dp))
    }
}
