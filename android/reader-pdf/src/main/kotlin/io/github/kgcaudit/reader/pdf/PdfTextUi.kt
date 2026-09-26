package io.github.kgcaudit.reader.pdf

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import io.github.kgcaudit.reader.document.Annotation
import io.github.kgcaudit.reader.document.HighlightColor
import io.github.kgcaudit.reader.layout.book.SearchHit
import io.github.kgcaudit.reader.ui.design.CpFloatingMenu
import io.github.kgcaudit.reader.ui.design.CpSelectionHandles
import io.github.kgcaudit.reader.ui.design.HANDLE_RADIUS
import io.github.kgcaudit.reader.ui.design.Pen
import io.github.kgcaudit.reader.ui.design.drawMemoGlyph
import io.github.kgcaudit.reader.ui.design.handleCentres
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.min

/** `HighlightColor` ↔ 디자인의 [Pen]. 둘은 같은 순서다(EPUB 과 같다). */
internal val HighlightColor.pen: Pen get() = Pen.entries[ordinal]
internal val Pen.color: HighlightColor get() = HighlightColor.entries[ordinal]

/** 화면에 놓인 쪽 하나: 쪽 번호와 그 쪽이 그려진 화면 네모(px). 확대 · 폭 맞춤 · 두쪽이면 네모가 달라진다. */
internal data class Placed(val page: Int, val rect: Rect) {
    fun toPage(at: Offset): Offset = Offset((at.x - rect.left) / rect.width, (at.y - rect.top) / rect.height)

    fun toScreen(r: PageRegion): Rect =
        Rect(rect.left + r.left * rect.width, rect.top + r.top * rect.height, rect.left + r.right * rect.width, rect.top + r.bottom * rect.height)
}

/** 고른 구간. 한 쪽 안에서만 고른다(결정 2) — 그래서 쪽 번호 하나와 그 쪽 글자 층의 구간이다. */
internal data class PdfSelection(val page: Int, val start: Int, val endExclusive: Int)

/** 메모를 쓰는 중인 것: 새로 칠할 구간이거나 이미 칠한 곳. */
internal data class PdfMemo(val annotation: Annotation?, val selection: PdfSelection?, val quote: String, val pen: Pen)

/**
 * PDF 화면 위 글자 층의 상태: 보이는 쪽들의 자리 · 고른 구간 · 누른 칠 · 찾은 곳. 쪽 그림(PageView · SpreadView)과
 * 그 위에 얹는 층([PdfTextOverlay])이 이것 하나를 본다 — 확대하거나 폭 맞춤으로 내리면 쪽 그림이 자리를 알리고,
 * 층은 그 자리에 칠을 다시 놓는다.
 */
@Stable
internal class PdfTextState {
    var placed by mutableStateOf<List<Placed>>(emptyList())
    var selection by mutableStateOf<PdfSelection?>(null)
    var tapped by mutableStateOf<Annotation?>(null)
    var memo by mutableStateOf<PdfMemo?>(null)

    /** 찾은 곳들(쪽 → 구간)과 지금 보고 있는 결과. */
    var found by mutableStateOf<Map<Int, List<IntRange>>>(emptyMap())
    var current by mutableStateOf<Pair<Int, IntRange>?>(null)

    /** 글자 층을 새로 꺼낼 때마다 는다(층은 리더가 쥐고 있어 그리기가 다시 불리게 하려고). */
    var layers by mutableIntStateOf(0)

    fun placedAt(at: Offset): Placed? = placed.firstOrNull { it.rect.contains(at) }
}

/**
 * 찾기(4-1). EPUB 의 SearchSession 과 같은 모양이다: 쪽을 차례로 찾아 찾는 대로 [results] 에 쌓는다. 결과의
 * `spine` 자리가 쪽 번호다.
 */
@Stable
internal class PdfSearch {
    var query by mutableStateOf("")
    var results by mutableStateOf<List<SearchHit>>(emptyList())
        private set
    var searched by mutableIntStateOf(0)
        private set
    var running by mutableStateOf(false)
        private set
    var current by mutableIntStateOf(-1)
    private var job: Job? = null

    fun start(reader: PdfReader, scope: CoroutineScope) {
        job?.cancel()
        results = emptyList()
        searched = 0
        current = -1
        val q = query.trim()
        if (q.isEmpty()) return
        running = true
        job = scope.launch {
            try {
                reader.search(q) { page, hits ->
                    if (hits.isNotEmpty()) results = results + hits
                    searched = page + 1
                }
            } finally {
                running = false
            }
        }
    }

    fun stop() {
        job?.cancel()
        running = false
        results = emptyList()
        searched = 0
        current = -1
    }

    fun summary(pageCount: Int): String = when {
        running -> "찾는 중… $searched / $pageCount 쪽 · 지금까지 ${results.size}곳"
        searched > 0 && results.isEmpty() -> "찾지 못했습니다"
        searched > 0 -> "${results.size}곳 · ${results.map { it.spine }.distinct().size}쪽에서"
        else -> ""
    }
}

/**
 * 쪽 위에 얹는 층: 형광펜 · 메모 쪽지 · 찾은 곳 · 듣는 문장 · 고른 구간과 그 손잡이 · 떠 있는 메뉴.
 *
 * 누름은 받지 않는다(메뉴 알약만 받는다) — 넘기기 · 확대 · 길게 누르기는 아래의 쪽 그림이 받는다.
 * PDF 쪽은 어떤 배경에서도 흰 종이로 그리므로 칠 색은 늘 밝은 지면의 것이다.
 */
@Composable
internal fun PdfTextOverlay(
    reader: PdfReader,
    text: PdfTextState,
    notes: List<Annotation>,
    showNotes: Boolean,
    /** 듣는 문장(쪽, 구간). */
    sentence: Pair<Int, IntRange>?,
    accent: Color,
    onPen: (Pen) -> Unit,
    onWord: (String) -> Unit,
) {
    val shownPages = text.placed.map { it.page }
    val visibleNotes = if (showNotes) notes.filter { it.start.spine in shownPages } else emptyList()
    // 칠할 것이 있는 쪽만 글자 층을 꺼낸다 — 층은 쪽마다 엔진을 수천 번 부르므로, 넘길 때마다 꺼내면 쪽 그리기가 밀린다.
    val needed = (visibleNotes.map { it.start.spine } + text.found.keys.filter { it in shownPages } +
        listOfNotNull(text.selection?.page, text.tapped?.start?.spine, sentence?.first)).distinct()
    LaunchedEffect(needed) {
        for (page in needed) {
            if (reader.cachedLayer(page) == null) {
                reader.textLayer(page)
                text.layers++
            }
        }
    }
    val version = text.layers
    fun rects(page: Int, range: IntRange): List<Rect> {
        val placed = text.placed.firstOrNull { it.page == page } ?: return emptyList()
        val layer = reader.cachedLayer(page) ?: return emptyList()
        return layer.rects(range.first, range.last + 1).map(placed::toScreen)
    }
    Canvas(Modifier.fillMaxSize()) {
        version.hashCode()
        for (note in visibleNotes) {
            val boxes = rects(note.start.spine, note.start.charOffset until note.end.charOffset)
            val pen = note.color.pen
            boxes.forEach { drawRect(pen.fill(false), it.topLeft, it.size) }
            if (note.note != null) boxes.lastOrNull()?.let { box -> drawMemoGlyph(Offset(box.right, box.top), (box.height * 0.55f).coerceAtLeast(6f), pen.mark(false)) }
        }
        for ((page, ranges) in text.found) {
            if (page !in shownPages) continue
            for (range in ranges) {
                val strong = text.current == page to range
                rects(page, range).forEach { drawRect(accent.copy(alpha = if (strong) 0.55f else 0.25f), it.topLeft, it.size) }
            }
        }
        sentence?.let { (page, range) -> rects(page, range).forEach { drawRect(accent.copy(alpha = 0.18f), it.topLeft, it.size) } }
        text.selection?.let { s -> rects(s.page, s.start until s.endExclusive).forEach { drawRect(accent.copy(alpha = 0.28f), it.topLeft, it.size) } }
    }
    text.selection?.let { s ->
        val boxes = rects(s.page, s.start until s.endExclusive)
        if (boxes.isNotEmpty()) {
            CpSelectionHandles(boxes.first(), boxes.last(), accent)
            if (text.memo == null) CpFloatingMenu(boxes.bounds(), null, listOf("메모", "복사", "공유", "사전"), onPen, onWord)
        }
    }
    text.tapped?.let { note ->
        val boxes = rects(note.start.spine, note.start.charOffset until note.end.charOffset)
        if (boxes.isNotEmpty() && text.memo == null) CpFloatingMenu(boxes.bounds(), note.color.pen, listOf("메모", "복사", "공유", "지우기"), onPen, onWord)
    }
}

/** 네모들을 모두 담는 네모(메뉴를 띄울 기준). */
internal fun List<Rect>.bounds(): Rect = Rect(minOf { it.left }, minOf { it.top }, maxOf { it.right }, maxOf { it.bottom })

/** 누른 자리의 칠. 겹치면 나중에 칠한 것(위에 보이는 것). */
internal fun annotationAt(reader: PdfReader, text: PdfTextState, notes: List<Annotation>, at: Offset): Annotation? {
    val placed = text.placedAt(at) ?: return null
    val layer = reader.cachedLayer(placed.page) ?: return null
    return notes.filter { it.start.spine == placed.page }.lastOrNull { note ->
        layer.rects(note.start.charOffset, note.end.charOffset).any { placed.toScreen(it).contains(at) }
    }
}

/**
 * 손잡이 끌기. 쪽 그림의 넘기기 · 확대보다 **먼저**(Initial 단계) 받아 먹는다 — 그래야 손잡이를 옆으로 끌 때 쪽이
 * 넘어가지 않는다(EPUB 에서 실제로 걸렸던 것). 손잡이에서 먼 곳의 누름은 건드리지 않고 아래로 흘려보낸다.
 */
internal fun Modifier.selectionHandles(reader: PdfReader, text: PdfTextState): Modifier = pointerInput(reader, text) {
    val grab = 28.dp.toPx()
    val r = HANDLE_RADIUS.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val sel = text.selection ?: return@awaitEachGesture
        val placed = text.placed.firstOrNull { it.page == sel.page } ?: return@awaitEachGesture
        val layer = reader.cachedLayer(sel.page) ?: return@awaitEachGesture
        val boxes = layer.rects(sel.start, sel.endExclusive).map(placed::toScreen)
        if (boxes.isEmpty()) return@awaitEachGesture
        val (a, b) = handleCentres(boxes.first(), boxes.last(), r)
        val da = hypot(down.position.x - a.x, down.position.y - a.y)
        val db = hypot(down.position.x - b.x, down.position.y - b.y)
        if (min(da, db) > grab) return@awaitEachGesture
        val isStart = da < db
        // 손가락이 가리지 않게 누른 높이와 글줄 높이의 차이를 유지한다(EPUB 과 같다).
        val lift = down.position.y - (if (isStart) boxes.first() else boxes.last()).center.y
        down.consume()
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            change.consume()
            // 손을 뗀 자리도 반영한다 — 마지막 움직임만 보고 멈추면 끝에 닿은 글자 하나가 빠진다(시험에서 마침표가 빠졌다).
            val cur = text.selection ?: break
            val p = placed.toPage(Offset(change.position.x, change.position.y - lift))
            layer.charAt(p.x, p.y, after = !isStart)?.let { at ->
                text.selection = if (isStart) {
                    cur.copy(start = at.coerceAtMost(cur.endExclusive - 1))
                } else {
                    cur.copy(endExclusive = at.coerceAtLeast(cur.start + 1))
                }
            }
            if (!change.pressed) break
        }
    }
}
