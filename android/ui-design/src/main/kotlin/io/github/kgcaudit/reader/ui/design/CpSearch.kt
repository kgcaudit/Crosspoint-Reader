package io.github.kgcaudit.reader.ui.design

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// 찾기 화면 · 결과 막대(E1–E3). EPUB 과 PDF 가 같은 화면을 쓴다(0.18.0 에 EPUB 모듈에서 옮겼다) — 찾는 일과
// 결과를 어떻게 적을지(EPUB 은 %, PDF 는 쪽)는 각 리더가 정하고, 여기는 그리기만 한다.

/** 찾은 곳 한 줄. [context] 안에서 [matchStart] 부터 [matchLength] 글자를 칠한다. [where] 는 "12%" · "18쪽". */
data class CpSearchRow(val section: String, val context: String, val matchStart: Int, val matchLength: Int, val where: String)

/** 찾기 화면: 찾을 말 · 결과 수 · 목차 묶음별 결과(앞뒤 문맥, 찾은 말 칠함). */
@Composable
fun CpSearchScreen(
    query: String,
    onQuery: (String) -> Unit,
    onClear: () -> Unit,
    /** "찾는 중… 38 / 64 쪽 · 지금까지 9곳" 같은 한 줄. 빈 문자열이면 줄을 두지 않는다. */
    summary: String,
    /** 찾는 동안의 진행(0..1). null 이면 막대가 없다. */
    progress: Float?,
    rows: List<CpSearchRow>,
    onSearch: () -> Unit,
    onOpen: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val c = CpTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (rows.isEmpty()) runCatching { focus.requestFocus() } }
    CpFullScreen {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CpIconButton(CpIcons.Back, "뒤로", onBack)
            Row(
                Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(CpTheme.metrics.cornerMedium))
                    .border(2.dp, c.accent, RoundedCornerShape(CpTheme.metrics.cornerMedium)).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CpIcon(CpIcons.Search, c.textMuted, size = 20.dp)
                Box(Modifier.weight(1f).padding(start = 10.dp)) {
                    if (query.isEmpty()) CpText("책에서 찾기", CpTheme.type.body, c.textMuted)
                    BasicTextField(
                        value = query,
                        onValueChange = onQuery,
                        singleLine = true,
                        textStyle = CpTheme.type.body.copy(color = c.text),
                        cursorBrush = SolidColor(c.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "찾을 말" },
                    )
                }
                if (query.isNotEmpty()) {
                    CpIconButton(CpIcons.Close, "지우기", onClear, tint = c.textMuted)
                }
            }
            Spacer(Modifier.width(12.dp))
        }
        if (summary.isNotEmpty()) {
            CpText(summary, CpTheme.type.caption, c.textMuted, Modifier.padding(horizontal = CpTheme.metrics.gutter, vertical = 6.dp))
        }
        if (progress != null) {
            CpProgressBar(progress, Modifier.padding(horizontal = CpTheme.metrics.gutter))
        }
        CpDivider(Modifier.padding(top = 8.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(rows) { i, row ->
                if (i == 0 || rows[i - 1].section != row.section) CpSectionLabel(row.section)
                HitRow(row, onClick = { onOpen(i) })
            }
        }
    }
}

/** 결과 한 줄. 위계 규칙: 목차 이름(글자만) 아래 한 단(16dp) 안쪽. */
@Composable
private fun HitRow(row: CpSearchRow, onClick: () -> Unit) {
    val c = CpTheme.colors
    val m = CpTheme.metrics
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(start = m.gutter + m.levelIndent, end = m.gutter, top = 10.dp, bottom = 10.dp),
    ) {
        val start = row.matchStart.coerceIn(0, row.context.length)
        val end = (start + row.matchLength).coerceIn(start, row.context.length)
        val text = buildAnnotatedString {
            append(row.context.substring(0, start))
            withStyle(SpanStyle(background = c.accent.copy(alpha = 0.25f), fontWeight = FontWeight.Bold)) {
                append(row.context.substring(start, end))
            }
            append(row.context.substring(end))
        }
        BasicText(text, style = CpTheme.type.body.copy(color = c.text, lineHeight = 24.sp), maxLines = 2)
        CpText(row.where, CpTheme.type.caption, c.textMuted, Modifier.padding(top = 2.dp))
    }
}

/** 결과를 보고 있는 동안 아래에 뜨는 막대: 앞 결과 · n / 전체 · 다음 결과 · 목록 · 끝내기(E3). */
@Composable
fun CpSearchResultBar(
    index: Int,
    total: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onList: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = CpTheme.colors
    Row(
        modifier.shadow(8.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(c.surface)
            .border(1.dp, c.divider, RoundedCornerShape(24.dp)).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CpIconButton(CpIcons.Back, "앞 결과", onPrevious, tint = if (index > 0) c.text else c.outline)
        CpText("${index + 1} / $total", CpTheme.type.label, c.text, Modifier.padding(horizontal = 8.dp))
        CpIconButton(CpIcons.Forward, "다음 결과", onNext, tint = if (index < total - 1) c.text else c.outline)
        Box(Modifier.width(1.dp).height(24.dp).background(c.divider))
        CpText(
            "목록", CpTheme.type.label, c.accent,
            Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onList).padding(horizontal = 14.dp, vertical = 12.dp),
        )
        CpIconButton(CpIcons.Close, "찾기 끝내기", onClose)
    }
}

