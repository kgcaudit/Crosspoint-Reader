package io.github.kgcaudit.reader.ui.design

import android.content.ClipData
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import kotlin.math.roundToInt

// 글자 고르기 · 형광펜의 공용 부품. EPUB 과 PDF 가 같은 모양 · 같은 동작이어야 해서 여기에 둔다(0.18.0 에 EPUB
// 모듈에서 옮겼다). 구간 계산(무엇을 골랐나)은 각 리더가 하고, 여기는 그리기와 판만 맡는다.

// ── 떠 있는 메뉴(N4) ───────────────────────────────────────────────

internal val PILL = Color(0xFF302A24)

/**
 * 고른 말 · 칠한 곳 위에 뜨는 어두운 알약: 4색 동그라미 / 줄 / 낱말 단추들. 구간 위에 자리가 있으면 위, 없으면
 * 아래(손잡이 밑)에 띄운다 — 손가락과 손잡이를 가리지 않게.
 *
 * @param anchor 구간의 네모들을 모두 담는 네모(화면 좌표).
 */
@Composable
fun CpFloatingMenu(
    anchor: Rect,
    current: Pen?,
    words: List<String>,
    onPen: (Pen) -> Unit,
    onWord: (String) -> Unit,
    /** 눈에 띄게 할 낱말(EPUB 의 "이어서 ›" — 쪽 끝에서만 새로 생기는 낱말이라 흰색이면 지나친다). */
    accentWord: String? = null,
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
                            when (word) { "지우기" -> Color(0xFFFFB0A0); accentWord -> Color(0xFFFFC7A8); else -> Color.White },
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
val HANDLE_RADIUS = 9.dp

/** 시작 · 끝 손잡이 물방울의 가운데(화면 좌표). 줄 아래로 달리고, 시작은 왼쪽 · 끝은 오른쪽으로 기운다. */
fun handleCentres(first: Rect, last: Rect, r: Float): Pair<Offset, Offset> =
    Offset(first.left - r * 0.7f, first.bottom + r) to Offset(last.right + r * 0.7f, last.bottom + r)

/**
 * 고른 구간의 손잡이 둘(그리기만). 끄는 것은 지면의 제스처 층이 받는다 — 손잡이 자체에 제스처를 달면 끄는 동안
 * 손잡이가 새 자리로 옮겨 가며 손가락과의 거리가 매번 어긋난다.
 */
@Composable
fun CpSelectionHandles(first: Rect, last: Rect, color: Color, showStart: Boolean = true) {
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


// ── 메모 판(N4) ────────────────────────────────────────────────────

/**
 * 메모 판: 아래에서 올라온다. 칠한 글 인용(색 막대) · 입력 칸 · 색 · 취소/저장. 읽던 쪽은 뒤에 흐리게 그대로.
 * 새 구간이면 "저장" 할 때 비로소 칠한다 — 취소하면 아무것도 남지 않는다.
 */
@Composable
fun CpMemoSheet(
    /** 판을 새로 여는 기준(바뀌면 입력 칸 · 색을 처음 값으로). */
    key: Any,
    quote: String,
    initialText: String,
    initialPen: Pen,
    onSave: (text: String, pen: Pen) -> Unit,
    onCancel: () -> Unit,
) {
    val c = CpTheme.colors
    val m = CpTheme.metrics
    var text by remember(key) { mutableStateOf(initialText) }
    var pen by remember(key) { mutableStateOf(initialPen) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(key) { runCatching { focus.requestFocus() } }
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
                CpText(quote, CpTheme.type.subtitle, c.textMuted, Modifier.padding(start = 12.dp), maxLines = 2)
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
fun shareText(quote: String, memo: String?, title: String): String = buildString {
    append('“').append(quote).append('”')
    if (!memo.isNullOrBlank()) append("\n\n").append(memo.trim())
    append("\n— ").append(title)
}

/** 클립보드에 넣는다. 안드로이드 13 부터는 시스템이 "복사됨" 을 보이므로 true(알림 불필요)를 돌려준다. */
fun copyText(context: Context, text: String): Boolean {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("OLO eBook", text))
    return Build.VERSION.SDK_INT >= 33
}

fun shareOut(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/**
 * 사전(N4): 고른 말을 휴대폰의 사전 · 번역 앱에 넘긴다(ACTION_PROCESS_TEXT). 앱이 없으면 false — 화면이 알린다.
 * 사전을 앱에 넣지 않는 이유: 사전 데이터는 책보다 크고, 이미 쓰는 사전 앱이 사용자에게 더 익숙하다.
 */
fun lookUp(context: Context, word: String): Boolean {
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


/**
 * 메모 쪽지: 접힌 귀가 있는 작은 종이에 흰 줄 둘. [at] 은 칠한 끝 글자의 오른쪽 위 — 쪽지는 그 자리에서
 * 4분의 3 쯤 위로 올라가 줄 사이(행간)에 걸친다. 줄 안에 두면 다음 글자의 머리를 덮는다.
 */
fun DrawScope.drawMemoGlyph(at: Offset, size: Float, color: Color) {
    val x = at.x + size * 0.1f
    val y = at.y - size * 0.75f
    val fold = size * 0.32f
    val body = androidx.compose.ui.graphics.Path().apply {
        moveTo(x, y); lineTo(x + size, y); lineTo(x + size, y + size - fold); lineTo(x + size - fold, y + size); lineTo(x, y + size); close()
    }
    drawPath(body, color)
    val stroke = (size * 0.1f).coerceAtLeast(1f)
    drawLine(Color.White, Offset(x + size * 0.2f, y + size * 0.32f), Offset(x + size * 0.8f, y + size * 0.32f), stroke)
    drawLine(Color.White, Offset(x + size * 0.2f, y + size * 0.56f), Offset(x + size * 0.6f, y + size * 0.56f), stroke)
}

