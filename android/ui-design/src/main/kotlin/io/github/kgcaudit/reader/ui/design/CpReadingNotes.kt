package io.github.kgcaudit.reader.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 형광펜 4색(N2). 글자 **뒤에** 반투명으로 깐다 — 글자 위에 덮으면 검정 지면에서 글자가 묻힌다.
 * 순서는 `HighlightColor` 와 같다(리더가 순번으로 옮긴다).
 */
enum class Pen(val label: String, val base: Color, private val deep: Color) {
    Yellow("노랑", Color(0xFFF2C94C), Color(0xFFB88A10)),
    Green("초록", Color(0xFF6FCF97), Color(0xFF2E9B5E)),
    Blue("파랑", Color(0xFF56B4F2), Color(0xFF2A7FC0)),
    Pink("분홍", Color(0xFFF28DB2), Color(0xFFD2537F)),
    ;

    /** 칠하는 색. 어두운 지면에서는 더 옅게 — 밝은 글자가 칠에 묻히지 않게. */
    fun fill(dark: Boolean): Color = base.copy(alpha = if (dark) 0.38f else 0.45f)

    /**
     * 메모 쪽지(N3) 색. 밝은 지면에서는 진하게 — 칠한 색 그대로면 노랑 쪽지가 노랑 칠 위에서 보이지 않는다.
     * 어두운 지면에서는 원래 색이 이미 도드라진다.
     */
    fun mark(dark: Boolean): Color = if (dark) base else deep
}

/** 지금 지면이 어두운가(검정 테마). 칠의 옅기를 고른다. */
val CpColors.darkPaper: Boolean get() = paper.luminance() < 0.4f

/** 떠 있는 메뉴 · 쪽지 판의 4색 동그라미. [current] 에 고리를 두른다. */
@Composable
fun CpPenDots(current: Pen?, onPick: (Pen) -> Unit, ring: Color, modifier: Modifier = Modifier, dotSize: androidx.compose.ui.unit.Dp = 26.dp) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Pen.entries.forEach { pen ->
            val on = pen == current
            Box(
                Modifier
                    // 동그라미는 26dp 여도 누르는 자리는 44dp — 손가락이 옆 색을 누르지 않게.
                    .size(dotSize + 18.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable(role = Role.Button) { onPick(pen) }
                    .semantics { contentDescription = pen.label + (if (on) " (지금 색)" else "") },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(dotSize)
                        .then(if (on) Modifier.border(2.dp, ring, RoundedCornerShape(50)) else Modifier)
                        .padding(if (on) 4.dp else 1.dp)
                        .clip(RoundedCornerShape(50)).background(pen.base),
                )
            }
        }
    }
}

// ── 독서노트(N6 · N7) ──────────────────────────────────────────────

/**
 * 독서노트 한 항목. 책갈피([pen] 이 null)이거나 형광펜(메모가 있을 수 있다).
 *
 * [where] 는 "3% · 2026.09.24." — 쪽 번호가 아니라 %(N7). 쪽 번호는 글자 크기를 바꾸면 달라져
 * 적어 둔 번호로 다시 찾아갈 수 없다.
 */
data class NoteItem(
    val key: String,
    val section: String,
    val text: String,
    val pen: Pen?,
    val memo: String?,
    val where: String,
) {
    val isBookmark: Boolean get() = pen == null
}

enum class NoteFilter(val label: String) { All("전체"), Bookmarks("책갈피"), Highlights("형광펜"), Memos("메모") }

/** 거르개가 이 항목을 보이는가. 형광펜 = 메모 없는 칠, 메모 = 메모 붙은 칠(둘이 겹치지 않아 합이 전체가 된다). */
fun NoteFilter.shows(item: NoteItem): Boolean = when (this) {
    NoteFilter.All -> true
    NoteFilter.Bookmarks -> item.isBookmark
    NoteFilter.Highlights -> !item.isBookmark && item.memo == null
    NoteFilter.Memos -> !item.isBookmark && item.memo != null
}

/**
 * "3% · 2026.09.24.". [percent] 가 null 이면(PDF) 날짜만 — PDF 는 쪽 번호가 곧 글이다.
 * [pages] 는 쪽을 넘어 이어 고른 칠이 걸친 쪽들 — "31% · 5–6쪽에 걸침 · 2026.09.25.".
 */
fun noteWhere(percent: Float?, createdAtEpochMs: Long, pages: IntRange? = null): String {
    val date = SimpleDateFormat("yyyy.MM.dd.", Locale.KOREA).format(Date(createdAtEpochMs))
    val span = pages?.takeIf { it.last > it.first }?.let { "${it.first}–${it.last}쪽에 걸침 · " } ?: ""
    return if (percent == null) "$span$date" else "${percent.roundToInt()}% · $span$date"
}

/**
 * 내보내기(N8): 공유 시트로 보낼 글. 파일을 만들지 않는다 — 받는 앱(메모장 · 메일 · 메신저)이 글로 받는다.
 * 책 순서 · 장별 묶음은 화면과 같다.
 */
fun exportNotes(title: String, author: String?, items: List<NoteItem>): String = buildString {
    append(title)
    if (!author.isNullOrBlank()) append(" — ").append(author)
    append("\n독서노트 ").append(items.size).append("개\n")
    var section: String? = null
    for (item in items) {
        if (item.section != section) {
            section = item.section
            append("\n[").append(item.section).append("]\n")
        }
        append(if (item.isBookmark) "■ 책갈피" else "● ${item.pen!!.label}").append(" · ").append(item.where).append('\n')
        append(item.text).append('\n')
        item.memo?.let { append("  └ 메모: ").append(it.replace("\n", "\n    ")).append('\n') }
    }
}

/**
 * 독서노트 목록: 거르개 칩 · 장별 묶음 · 항목 · ⋮ 메뉴.
 *
 * 위계 규칙: 장 이름(글자만) 아래 항목은 한 단([CpMetrics.levelIndent]) 안쪽, 칠한 글 아래 메모는 그 칠한 글의
 * **글자가 시작하는 자리**([CpMetrics.childIndent])에서 시작한다 — 메모가 따로 된 항목으로 읽히지 않게.
 *
 * @param chips 거르개 칩을 보이는가. PDF 는 책갈피뿐이라 칩 대신 [caption] 한 줄.
 */
@Composable
fun CpReadingNotesList(
    items: List<NoteItem>?,
    filter: NoteFilter,
    onFilter: (NoteFilter) -> Unit,
    onOpen: (NoteItem) -> Unit,
    onRemove: (NoteItem) -> Unit,
    modifier: Modifier = Modifier,
    onMemo: ((NoteItem) -> Unit)? = null,
    onRecolor: ((NoteItem, Pen) -> Unit)? = null,
    onShare: ((NoteItem) -> Unit)? = null,
    chips: Boolean = true,
    caption: String? = null,
    empty: String = "독서노트가 비어 있습니다. 글자를 길게 눌러 칠하거나 쪽 오른쪽 위를 눌러 책갈피를 꽂으면 여기에 모입니다",
) {
    val c = CpTheme.colors
    val m = CpTheme.metrics
    var menu by remember { mutableStateOf<Pair<NoteItem, Offset>?>(null) }
    var recolor by remember { mutableStateOf(false) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        Column(Modifier.fillMaxSize()) {
            if (chips && items != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = m.gutter, vertical = 10.dp)) {
                    NoteFilter.entries.forEach { f ->
                        val on = f == filter
                        Box(
                            Modifier.padding(end = 8.dp).heightIn(min = 36.dp).clip(RoundedCornerShape(50))
                                .background(if (on) c.accent else Color.Transparent)
                                .border(1.dp, if (on) c.accent else c.outline, RoundedCornerShape(50))
                                .clickable(role = Role.Tab) { onFilter(f) }
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) { CpText("${f.label} ${items.count { f.shows(it) }}", CpTheme.type.label, if (on) c.onAccent else c.text) }
                    }
                }
            }
            if (caption != null) CpText(caption, CpTheme.type.caption, c.textMuted, Modifier.padding(horizontal = m.gutter, vertical = 12.dp), maxLines = 2)
            CpDivider()
            val shown = items?.filter { filter.shows(it) }
            when {
                shown == null -> Unit
                shown.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    CpText(if (items.isEmpty()) empty else "${filter.label} 항목이 없습니다", CpTheme.type.subtitle, c.textMuted, maxLines = 4)
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(shown, key = { _, it -> it.key }) { i, item ->
                        if (i == 0 || shown[i - 1].section != item.section) CpSectionLabel(item.section)
                        NoteRow(item, onOpen = { onOpen(item) }, onMenu = { at -> recolor = false; menu = item to (at - origin) })
                    }
                }
            }
        }
        menu?.let { (item, at) ->
            // 바깥을 누르면 닫힌다(투명한 막).
            Box(Modifier.fillMaxSize().clickable(indication = null, interactionSource = null) { menu = null })
            val width = 168.dp
            Column(
                Modifier
                    .offset { IntOffset((at.x - width.toPx()).roundToInt().coerceAtLeast(8.dp.roundToPx()), at.y.roundToInt()) }
                    .width(if (recolor) 224.dp else width)
                    .shadow(8.dp, RoundedCornerShape(m.cornerMedium))
                    .clip(RoundedCornerShape(m.cornerMedium)).background(c.dialog)
                    .padding(vertical = 6.dp),
            ) {
                fun close() { menu = null }
                if (recolor && onRecolor != null) {
                    CpPenDots(item.pen, { onRecolor(item, it); close() }, ring = c.text, Modifier.padding(horizontal = 6.dp), dotSize = 24.dp)
                } else {
                    @Composable
                    fun Entry(label: String, danger: Boolean = false, action: () -> Unit) = CpText(
                        label, CpTheme.type.body, if (danger) c.error else c.text,
                        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = action).padding(horizontal = 18.dp, vertical = 12.dp),
                    )
                    if (!item.isBookmark) {
                        onMemo?.let { Entry(if (item.memo != null) "메모 고치기" else "메모 쓰기") { close(); it(item) } }
                        if (onRecolor != null) Entry("색 바꾸기") { recolor = true }
                        onShare?.let { Entry("공유") { close(); it(item) } }
                    }
                    Entry("지우기", danger = true) { close(); onRemove(item) }
                }
            }
        }
    }
}

@Composable
private fun NoteRow(item: NoteItem, onOpen: () -> Unit, onMenu: (Offset) -> Unit) {
    val c = CpTheme.colors
    val m = CpTheme.metrics
    var button by remember { mutableStateOf(Offset.Zero) }
    Column(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen)
            .padding(start = m.gutter + m.levelIndent, end = 4.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (item.pen == null) {
                    CpIcon(CpIcons.BookmarkFilled, c.accent, size = 20.dp)
                } else {
                    Box(Modifier.size(12.dp).clip(RoundedCornerShape(50)).background(item.pen.base))
                }
            }
            // 글자가 childIndent 에서 시작한다 — 아래 메모 상자가 같은 선에서 시작해 "이 칠의 메모" 로 읽힌다.
            Spacer(Modifier.width(m.childIndent - 24.dp))
            Column(Modifier.weight(1f)) {
                val body = if (item.pen == null) {
                    AnnotatedString(item.text)
                } else {
                    buildAnnotatedString { withStyle(SpanStyle(background = item.pen.fill(c.background.luminance() < 0.4f))) { append(item.text) } }
                }
                BasicText(body, style = CpTheme.type.body.copy(color = c.text, lineHeight = 24.sp), maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                CpText(item.where, CpTheme.type.caption, c.textMuted, Modifier.padding(top = 4.dp))
            }
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(50))
                    .onGloballyPositioned { val p = it.positionInRoot(); button = Offset(p.x + it.size.width, p.y + it.size.height) }
                    .clickable(role = Role.Button) { onMenu(button) }
                    .semantics { contentDescription = "더 보기" },
                contentAlignment = Alignment.Center,
            ) { CpText("⋮", CpTheme.type.title, c.textMuted) }
        }
        if (item.memo != null) {
            Box(
                Modifier.padding(start = m.childIndent, end = 40.dp, top = 8.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(m.cornerSmall)).background(c.dialog).padding(12.dp),
            ) { CpText(item.memo, CpTheme.type.subtitle, c.text, maxLines = 6) }
        }
    }
}
