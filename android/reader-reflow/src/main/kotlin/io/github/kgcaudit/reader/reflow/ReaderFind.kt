package io.github.kgcaudit.reader.reflow

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
import io.github.kgcaudit.reader.document.TocEntry
import io.github.kgcaudit.reader.layout.book.SearchHit
import io.github.kgcaudit.reader.ui.design.CpButton
import io.github.kgcaudit.reader.ui.design.CpDivider
import io.github.kgcaudit.reader.ui.design.CpFullScreen
import io.github.kgcaudit.reader.ui.design.CpIcon
import io.github.kgcaudit.reader.ui.design.CpIconButton
import io.github.kgcaudit.reader.ui.design.CpIcons
import io.github.kgcaudit.reader.ui.design.CpPopup
import io.github.kgcaudit.reader.ui.design.CpProgressBar
import io.github.kgcaudit.reader.ui.design.CpSectionLabel
import io.github.kgcaudit.reader.ui.design.CpText
import io.github.kgcaudit.reader.ui.design.CpTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 찾은 자리와 그 자리의 진도(%). */
data class Found(val hit: SearchHit, val percent: Float)

/**
 * 한 권에서의 찾기. 화면(목록 · 결과 막대)이 닫혀도 남는다 — "목록" 을 누르면 같은 결과로 돌아온다.
 */
class SearchSession {
    var query by mutableStateOf("")
    var results by mutableStateOf<List<Found>>(emptyList())
        private set
    var searched by mutableStateOf(0)
        private set
    var chapters by mutableStateOf(0)
        private set
    var running by mutableStateOf(false)
        private set

    /** 지금 보고 있는 결과(목록에서 누른 것). -1 이면 결과 막대를 띄우지 않는다. */
    var current by mutableStateOf(-1)

    private var job: Job? = null

    /** 장마다 찾아 나오는 대로 목록에 더한다(E2). 새로 찾으면 앞의 찾기는 멈춘다. */
    fun start(reader: BookReader, scope: CoroutineScope) {
        val q = query.trim()
        job?.cancel()
        results = emptyList()
        current = -1
        searched = 0
        if (q.isEmpty()) return
        running = true
        job = scope.launch {
            try {
                chapters = reader.chapterCount()
                for (i in 0 until chapters) {
                    ensureActive()
                    val hits = reader.searchChapter(i, q)
                    results = results + hits.map { Found(it, reader.percentOf(it)) }
                    searched = i + 1
                }
            } finally {
                running = false
            }
        }
    }

    fun stop() {
        job?.cancel()
        current = -1
    }
}

/** 찾기 화면: 찾을 말 · 결과 수 · 장별 결과(앞뒤 문맥, 찾은 말 칠함). */
@Composable
internal fun SearchScreen(
    session: SearchSession,
    toc: List<TocEntry>,
    onSearch: () -> Unit,
    onOpen: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val c = CpTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (session.results.isEmpty()) runCatching { focus.requestFocus() } }
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
                    if (session.query.isEmpty()) CpText("책에서 찾기", CpTheme.type.body, c.textMuted)
                    BasicTextField(
                        value = session.query,
                        onValueChange = { session.query = it },
                        singleLine = true,
                        textStyle = CpTheme.type.body.copy(color = c.text),
                        cursorBrush = SolidColor(c.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "찾을 말" },
                    )
                }
                if (session.query.isNotEmpty()) {
                    CpIconButton(CpIcons.Close, "지우기", { session.query = ""; session.stop() }, tint = c.textMuted)
                }
            }
            Spacer(Modifier.width(12.dp))
        }
        val summary = when {
            session.running -> "찾는 중… ${session.searched} / ${session.chapters} 장 · 지금까지 ${session.results.size}곳"
            session.searched > 0 && session.results.isEmpty() -> "찾지 못했습니다"
            session.searched > 0 -> "${session.results.size}곳 · ${session.results.map { it.hit.spine }.distinct().size}장에서"
            else -> ""
        }
        if (summary.isNotEmpty()) {
            CpText(summary, CpTheme.type.caption, c.textMuted, Modifier.padding(horizontal = CpTheme.metrics.gutter, vertical = 6.dp))
        }
        if (session.running && session.chapters > 0) {
            CpProgressBar(session.searched / session.chapters.toFloat(), Modifier.padding(horizontal = CpTheme.metrics.gutter))
        }
        CpDivider(Modifier.padding(top = 8.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(session.results) { i, found ->
                val spine = found.hit.spine
                if (i == 0 || session.results[i - 1].hit.spine != spine) {
                    CpSectionLabel(toc.getOrNull(currentTocIndex(toc, spine))?.label ?: "${spine + 1}장")
                }
                HitRow(found, onClick = { onOpen(i) })
            }
        }
    }
}

/** 결과 한 줄. 위계 규칙: 장 이름(글자만) 아래 한 단(16dp) 안쪽. */
@Composable
private fun HitRow(found: Found, onClick: () -> Unit) {
    val c = CpTheme.colors
    val m = CpTheme.metrics
    val hit = found.hit
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(start = m.gutter + m.levelIndent, end = m.gutter, top = 10.dp, bottom = 10.dp),
    ) {
        val word = (hit.endExclusive - hit.start).coerceAtMost(hit.context.length - hit.contextMatchStart)
        val text = buildAnnotatedString {
            append(hit.context.substring(0, hit.contextMatchStart))
            withStyle(SpanStyle(background = c.accent.copy(alpha = 0.25f), fontWeight = FontWeight.Bold)) {
                append(hit.context.substring(hit.contextMatchStart, hit.contextMatchStart + word))
            }
            append(hit.context.substring(hit.contextMatchStart + word))
        }
        BasicText(text, style = CpTheme.type.body.copy(color = c.text, lineHeight = 24.sp), maxLines = 2)
        CpText("${found.percent.roundToInt()}%", CpTheme.type.caption, c.textMuted, Modifier.padding(top = 2.dp))
    }
}

/** 결과를 보고 있는 동안 아래에 뜨는 막대: 앞 결과 · n / 전체 · 다음 결과 · 목록 · 끝내기(E3). */
@Composable
internal fun SearchResultBar(
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

/**
 * 각주 판(F3): 아래에서 올라온다. 짧으면 내용만큼, 길면 화면의 60% 까지 올라오고 판 안에서 스크롤한다. 읽던
 * 쪽은 뒤에 흐리게 그대로 — 바깥을 누르거나 "닫기" 면 그대로 이어 읽는다.
 */
@Composable
internal fun NoteSheet(title: String, text: String, onGoTo: () -> Unit, onClose: () -> Unit) {
    val c = CpTheme.colors
    BackHandler(onBack = onClose)
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color(0x44000000))
            .clickable(indication = null, interactionSource = null, onClick = onClose),
    ) {
        val maxText = maxHeight * 0.6f - 120.dp
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(c.surface)
                // 판 안을 눌러도 닫히지 않게.
                .clickable(indication = null, interactionSource = null) {}
                .padding(horizontal = CpTheme.metrics.gutter).padding(top = 10.dp, bottom = 24.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(50)).background(c.divider))
            CpText(title, CpTheme.type.label, c.accent, Modifier.padding(top = 14.dp, bottom = 8.dp))
            Box(Modifier.heightIn(max = maxText.coerceAtLeast(80.dp)).verticalScroll(rememberScrollState())) {
                BasicText(text, style = CpTheme.type.body.copy(color = c.text, lineHeight = 26.sp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                CpButton("각주 자리로 가기", onGoTo, primary = false)
                Spacer(Modifier.width(10.dp))
                CpButton("닫기", onClose)
            }
        }
    }
}

/** 링크로 건너간 뒤 떠 있는 "읽던 곳으로"(F4 · F5). */
@Composable
internal fun ReturnChip(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = CpTheme.colors
    Row(
        modifier.shadow(8.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(c.surface)
            .border(1.dp, c.divider, RoundedCornerShape(24.dp)).clickable(onClick = onClick)
            .padding(start = 8.dp, end = 18.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CpIcon(CpIcons.Back, c.accent, Modifier.padding(8.dp), size = 20.dp)
        CpText("읽던 곳으로", CpTheme.type.label, c.accent)
    }
}

/** 책 밖 주소(F6): 묻고 연다. 앱은 인터넷을 쓰지 않는다 — 여는 것은 휴대폰의 브라우저다. */
@Composable
internal fun ExternalLinkPopup(url: String, onOpen: () -> Unit, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    CpPopup(title = "브라우저로 열까요?", message = url, onDismiss = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
            CpButton("취소", onDismiss, primary = false)
            Spacer(Modifier.width(10.dp))
            CpButton("열기", onOpen)
        }
    }
}
