package io.github.kgcaudit.reader.reflow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kgcaudit.reader.document.TocEntry
import io.github.kgcaudit.reader.layout.book.SearchHit
import io.github.kgcaudit.reader.ui.design.CpBottomSheet
import io.github.kgcaudit.reader.ui.design.CpButton
import io.github.kgcaudit.reader.ui.design.CpSearchRow
import io.github.kgcaudit.reader.ui.design.CpSearchScreen
import io.github.kgcaudit.reader.ui.design.CpIcon
import io.github.kgcaudit.reader.ui.design.CpIcons
import io.github.kgcaudit.reader.ui.design.CpPopup
import io.github.kgcaudit.reader.ui.design.CpPopupButtons
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
    var searched by mutableIntStateOf(0)
        private set
    var chapters by mutableIntStateOf(0)
        private set
    var running by mutableStateOf(false)
        private set

    /** 지금 보고 있는 결과(목록에서 누른 것). -1 이면 결과 막대를 띄우지 않는다. */
    var current by mutableIntStateOf(-1)

    private var job: Job? = null

    /** 장마다 찾아 나오는 대로 목록에 더한다(E2). 새로 찾으면 앞의 찾기는 멈춘다. */
    fun start(reader: BookReader, scope: CoroutineScope) =
        start(scope, reader::chapterCount) { i, q -> reader.searchChapter(i, q).map { Found(it, reader.percentOf(it)) } }

    /** [start] 의 본체. 책 없이 시험하려고 장 수 · 장 하나 찾기를 함수로 받는다. */
    internal fun start(scope: CoroutineScope, chapterCount: suspend () -> Int, searchChapter: suspend (Int, String) -> List<Found>) {
        val q = query.trim()
        job?.cancel()
        results = emptyList()
        current = -1
        searched = 0
        if (q.isEmpty()) return
        running = true
        job = scope.launch {
            try {
                chapters = chapterCount()
                for (i in 0 until chapters) {
                    ensureActive()
                    results = results + searchChapter(i, q)
                    searched = i + 1
                }
            } finally {
                // 새 찾기가 이 일을 취소하고 시작했으면 "찾는 중" 은 그쪽 것이다 — 옛 일이 끄면 새 찾기가 도는데 막대가 사라졌다.
                if (job === coroutineContext[kotlinx.coroutines.Job]) running = false
            }
        }
    }

    /**
     * 찾기를 지운다(검색 칸의 ×). 결과 · 요약 · 결과 막대를 모두 비운다 — 멈추기만 하던 때는 칸은 비었는데 옛 결과
     * 목록과 "12곳 · 3장에서" 가 그대로 남아, 지운 말의 결과처럼 보였다(PDF 는 비운다). 쪽의 칠은 화면이 지운다.
     */
    fun stop() {
        job?.cancel()
        running = false
        results = emptyList()
        searched = 0
        chapters = 0
        current = -1
    }
}

/** 찾기 화면(모양은 ui-design 의 CpSearchScreen). 결과 자리는 책의 %. */
@Composable
internal fun SearchScreen(
    session: SearchSession,
    toc: List<TocEntry>,
    onSearch: () -> Unit,
    onOpen: (Int) -> Unit,
    onBack: () -> Unit,
    /** 찾기를 지웠다(×). 쪽에 칠해 둔 찾은 자리도 지운다. */
    onCleared: () -> Unit = {},
) {
    val summary = when {
        session.running -> "찾는 중… ${session.searched} / ${session.chapters}장 · 지금까지 ${session.results.size}곳"
        session.searched > 0 && session.results.isEmpty() -> "찾지 못했습니다"
        session.searched > 0 -> "${session.results.size}곳 · ${session.results.map { it.hit.spine }.distinct().size}장에서"
        else -> ""
    }
    val rows = session.results.map { found ->
        val hit = found.hit
        CpSearchRow(
            section = toc.getOrNull(currentTocIndex(toc, hit.spine))?.label ?: "${hit.spine + 1}장",
            context = hit.context,
            matchStart = hit.contextMatchStart,
            matchLength = (hit.endExclusive - hit.start).coerceAtMost(hit.context.length - hit.contextMatchStart),
            where = "${found.percent.roundToInt()}%",
        )
    }
    CpSearchScreen(
        query = session.query,
        onQuery = { session.query = it },
        onClear = { session.query = ""; session.stop(); onCleared() },
        summary = summary,
        progress = if (session.running && session.chapters > 0) session.searched / session.chapters.toFloat() else null,
        rows = rows,
        onSearch = onSearch,
        onOpen = onOpen,
        onBack = onBack,
    )
}

/**
 * 각주 판(F3): 아래에서 올라온다. 짧으면 내용만큼, 길면 화면의 60% 까지 올라오고 판 안에서 스크롤한다. 읽던
 * 쪽은 뒤에 흐리게 그대로 — 바깥을 누르거나 "닫기" 면 그대로 이어 읽는다.
 */
@Composable
internal fun NoteSheet(title: String, text: String, onGoTo: () -> Unit, onClose: () -> Unit) {
    val c = CpTheme.colors
    CpBottomSheet(onClose) { maxHeight ->
        val maxText = maxHeight * 0.6f - 120.dp
        CpText(title, CpTheme.type.label, c.accentText, Modifier.padding(top = 14.dp, bottom = 8.dp))
        Box(Modifier.heightIn(max = maxText.coerceAtLeast(80.dp)).verticalScroll(rememberScrollState())) {
            BasicText(text, style = CpTheme.type.body.copy(color = c.text, lineHeight = 26.sp))
        }
        CpPopupButtons {
            CpButton("각주 자리로 가기", onGoTo, primary = false)
            CpButton("닫기", onClose)
        }
    }
}

/** 링크로 건너간 뒤 떠 있는 "읽던 곳으로"(F4 · F5). */
@Composable
internal fun ReturnChip(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = CpTheme.colors
    // 찾기 결과 막대와 같은 14dp(0.32.2) — 24dp 는 48dp 높이에서 알약이었다.
    val shape = RoundedCornerShape(CpTheme.metrics.cornerMedium)
    Row(
        // 누르는 곳 48dp(0.29.0 — 44dp 였다).
        modifier.heightIn(min = CpTheme.metrics.touchTarget).shadow(8.dp, shape).clip(shape).background(c.surface)
            .border(1.dp, c.divider, shape).clickable(onClick = onClick)
            .padding(start = 8.dp, end = 18.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CpIcon(CpIcons.Back, c.accent, Modifier.padding(8.dp), size = 20.dp)
        CpText("읽던 곳으로", CpTheme.type.label, c.accentText)
    }
}

/** 책 밖 주소(F6): 묻고 연다. 앱은 인터넷을 쓰지 않는다 — 여는 것은 휴대폰의 브라우저다. */
@Composable
internal fun ExternalLinkPopup(url: String, onOpen: () -> Unit, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    CpPopup(title = "브라우저로 열까요?", message = url, onDismiss = onDismiss) {
        CpPopupButtons {
            CpButton("취소", onDismiss, primary = false)
            CpButton("열기", onOpen)
        }
    }
}
