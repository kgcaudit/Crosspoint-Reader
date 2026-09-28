package io.github.kgcaudit.reader.reflow

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.kgcaudit.reader.document.Annotation
import io.github.kgcaudit.reader.document.HighlightColor
import io.github.kgcaudit.reader.layout.Page
import io.github.kgcaudit.reader.text.AndroidTextMeasurer
import io.github.kgcaudit.reader.ui.design.CpButton
import io.github.kgcaudit.reader.ui.design.CpPenDots
import io.github.kgcaudit.reader.ui.design.CpText
import io.github.kgcaudit.reader.ui.design.CpTheme
import io.github.kgcaudit.reader.ui.design.Pen
import kotlin.math.roundToInt

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

// ── 떠 있는 메뉴(N4) ───────────────────────────────────────────────

private val PILL = Color(0xFF302A24)

/**
 * 고른 말 · 칠한 곳 위에 뜨는 어두운 알약: 4색 동그라미 / 줄 / 낱말 단추들. 구간 위에 자리가 있으면 위, 없으면
 * 아래(손잡이 밑)에 띄운다 — 손가락과 손잡이를 가리지 않게.
 *
 * @param anchor 구간의 네모들을 모두 담는 네모(화면 좌표).
 */
@Composable
internal fun FloatingMenu(
    anchor: Rect,
    current: Pen?,
    words: List<String>,
    onPen: (Pen) -> Unit,
    onWord: (String) -> Unit,
) {
    val density = LocalDensity.current
    Layout(
        content = {
            Column(
                Modifier.shadow(8.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(PILL)
                    // 알약 안을 눌러도 뒤의 "고르기 풀기" 가 불리지 않게.
                    .clickable(indication = null, interactionSource = null) {}
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    .semantics { contentDescription = "고른 글 메뉴" },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CpPenDots(current, onPen, ring = Color.White, Modifier.padding(top = 2.dp))
                Box(Modifier.width(280.dp).height(1.dp).background(Color(0x33FFFFFF)))
                Row {
                    words.forEach { word ->
                        CpText(
                            word, CpTheme.type.label,
                            // "이어서 ›" 는 쪽 끝에 닿았을 때만 새로 생기는 낱말이라 눈에 띄게 한다 — 흰색이면 늘 있던
                            // 메뉴로 보여 지나친다.
                            when (word) { "지우기" -> Color(0xFFFFB0A0); CONTINUE -> Color(0xFFFFC7A8); else -> Color.White },
                            Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { onWord(word) }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val placeable = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val gap = with(density) { 12.dp.toPx() }
        val below = with(density) { 30.dp.toPx() } // 손잡이 물방울 아래로
        val margin = with(density) { 8.dp.toPx() }
        val x = (anchor.center.x - placeable.width / 2f)
            .coerceIn(margin, (constraints.maxWidth - placeable.width - margin).coerceAtLeast(margin))
        val above = anchor.top - gap - placeable.height
        val y = if (above >= margin) above else (anchor.bottom + below).coerceAtMost(constraints.maxHeight - placeable.height - margin)
        layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(x.roundToInt(), y.roundToInt()) }
    }
}

/** 손잡이 물방울의 반지름. 그리기와 누르기 판정이 같은 값을 쓴다. */
internal val HANDLE_RADIUS = 9.dp

/** 시작 · 끝 손잡이 물방울의 가운데(화면 좌표). 줄 아래로 달리고, 시작은 왼쪽 · 끝은 오른쪽으로 기운다. */
internal fun handleCentres(first: Rect, last: Rect, r: Float): Pair<Offset, Offset> =
    Offset(first.left - r * 0.7f, first.bottom + r) to Offset(last.right + r * 0.7f, last.bottom + r)

/**
 * 고른 구간의 손잡이 둘(그리기만). 끄는 것은 지면의 제스처 층이 받는다 — 손잡이 자체에 제스처를 달면 끄는 동안
 * 손잡이가 새 자리로 옮겨 가며 손가락과의 거리가 매번 어긋난다.
 */
@Composable
internal fun SelectionHandles(first: Rect, last: Rect, color: Color, showStart: Boolean = true) {
    Canvas(Modifier.fillMaxSize()) {
        val r = HANDLE_RADIUS.toPx()
        val stroke = 2.dp.toPx()
        val (a, b) = handleCentres(first, last, r)
        if (showStart) {
            drawLine(color, Offset(first.left, first.top), Offset(first.left, first.bottom + 2), strokeWidth = stroke)
            drawCircle(color, r, a)
        }
        drawLine(color, Offset(last.right, last.top), Offset(last.right, last.bottom + 2), strokeWidth = stroke)
        drawCircle(color, r, b)
    }
    // 화면 읽기(TalkBack)와 시험이 손잡이 자리를 알 수 있게 이름만 단 빈 상자. 누름은 받지 않는다(지면이 받는다).
    val density = LocalDensity.current
    val (a, b) = handleCentres(first, last, with(density) { HANDLE_RADIUS.toPx() })
    for ((at, name) in listOfNotNull(if (showStart) a to "고르기 시작 손잡이" else null, b to "고르기 끝 손잡이")) {
        Box(
            Modifier.offset { IntOffset((at.x - HANDLE_RADIUS.toPx()).roundToInt(), (at.y - HANDLE_RADIUS.toPx()).roundToInt()) }
                .size(HANDLE_RADIUS * 2).semantics { contentDescription = name },
        )
    }
}

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
)

/**
 * 메모 판: 아래에서 올라온다. 칠한 글 인용(색 막대) · 입력 칸 · 색 · 취소/저장. 읽던 쪽은 뒤에 흐리게 그대로.
 * 새 구간이면 "저장" 할 때 비로소 칠한다 — 취소하면 아무것도 남지 않는다.
 */
@Composable
internal fun MemoSheet(draft: MemoDraft, onSave: (text: String, pen: Pen) -> Unit, onCancel: () -> Unit) {
    val c = CpTheme.colors
    val m = CpTheme.metrics
    var text by remember(draft) { mutableStateOf(draft.annotation?.note.orEmpty()) }
    var pen by remember(draft) { mutableStateOf(draft.pen) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(draft) { runCatching { focus.requestFocus() } }
    BackHandler(onBack = onCancel)
    Box(
        Modifier.fillMaxSize().background(Color(0x66000000))
            .clickable(indication = null, interactionSource = null, onClick = onCancel),
    ) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(c.surface)
                .clickable(indication = null, interactionSource = null) {}
                .padding(horizontal = m.gutter).padding(top = 10.dp, bottom = 24.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(50)).background(c.divider))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                CpText("메모", CpTheme.type.title, c.text, Modifier.weight(1f))
                CpPenDots(pen, { pen = it }, ring = c.text, dotSize = 24.dp)
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                Box(Modifier.width(4.dp).height(44.dp).clip(RoundedCornerShape(50)).background(pen.base))
                CpText(draft.quote, CpTheme.type.subtitle, c.textMuted, Modifier.padding(start = 12.dp), maxLines = 2)
            }
            Box(
                Modifier.padding(top = 14.dp).fillMaxWidth().height(120.dp).clip(RoundedCornerShape(m.cornerMedium))
                    .border(2.dp, c.accent, RoundedCornerShape(m.cornerMedium)).padding(14.dp),
            ) {
                if (text.isEmpty()) CpText("생각을 적어 두세요", CpTheme.type.body, c.textMuted)
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = CpTheme.type.body.copy(color = c.text),
                    cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.fillMaxSize().focusRequester(focus).semantics { contentDescription = "메모 입력" },
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                CpButton("취소", onCancel, primary = false)
                Spacer(Modifier.width(10.dp))
                CpButton("저장", { onSave(text, pen) })
            }
        }
    }
}

// ── 복사 · 공유 · 사전 ─────────────────────────────────────────────

/** 공유하는 글: 인용 + (메모) + 책 제목. 받는 쪽에서 어느 책의 글인지 알 수 있게. */
internal fun shareText(quote: String, memo: String?, title: String): String = buildString {
    append('“').append(quote).append('”')
    if (!memo.isNullOrBlank()) append("\n\n").append(memo.trim())
    append("\n— ").append(title)
}

/** 클립보드에 넣는다. 안드로이드 13 부터는 시스템이 "복사됨" 을 보이므로 true(알림 불필요)를 돌려준다. */
internal fun copyText(context: Context, text: String): Boolean {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("OLO eBook", text))
    return Build.VERSION.SDK_INT >= 33
}

internal fun shareOut(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/**
 * 사전(N4): 고른 말을 휴대폰의 사전 · 번역 앱에 넘긴다(ACTION_PROCESS_TEXT). 앱이 없으면 false — 화면이 알린다.
 * 사전을 앱에 넣지 않는 이유: 사전 데이터는 책보다 크고, 이미 쓰는 사전 앱이 사용자에게 더 익숙하다.
 */
internal fun lookUp(context: Context, word: String): Boolean {
    val process = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
        .putExtra(Intent.EXTRA_PROCESS_TEXT, word)
        .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
    val apps = context.packageManager.queryIntentActivities(process, 0)
    if (apps.isEmpty()) return false
    val intent = if (apps.size == 1) {
        process.setClassName(apps[0].activityInfo.packageName, apps[0].activityInfo.name)
    } else {
        Intent.createChooser(process, "사전")
    }
    return runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
}

/** 네모들을 모두 담는 네모(메뉴를 띄울 기준). */
internal fun List<Rect>.bounds(): Rect =
    Rect(minOf { it.left }, minOf { it.top }, maxOf { it.right }, maxOf { it.bottom })

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
        modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xE6302A24)).padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        io.github.kgcaudit.reader.ui.design.CpIcon(io.github.kgcaudit.reader.ui.design.CpIcons.Back, Color.White, size = 14.dp)
        CpText("앞 쪽에서 이어 고르는 중 · ${charsBefore}자", CpTheme.type.caption, Color.White, Modifier.padding(start = 4.dp))
    }
}
