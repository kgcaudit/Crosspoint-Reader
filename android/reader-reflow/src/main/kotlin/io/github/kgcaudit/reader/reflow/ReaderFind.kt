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
import io.github.kgcaudit.reader.ui.design.ReaderSearch
import kotlinx.coroutines.CoroutineScope
import kotlin.math.roundToInt

/** 찾은 자리와 그 자리의 진도(%). */
data class Found(val hit: SearchHit, val percent: Float)

/** 한 권에서의 찾기. 단위는 장이다 — 결과의 `spine` 이 장 번호다. */
internal fun chapterSearch(): ReaderSearch<Found> = ReaderSearch("장") { it.hit.spine }

/** 장마다 찾아 나오는 대로 목록에 더한다(E2). 새로 찾으면 앞의 찾기는 멈춘다. */
internal fun ReaderSearch<Found>.start(reader: BookReader, scope: CoroutineScope) =
    start(scope, reader::chapterCount) { i, q -> reader.searchChapter(i, q).map { Found(it, reader.percentOf(it)) } }

/** 찾기 화면(모양은 ui-design 의 CpSearchScreen). 결과 자리는 책의 %. */
@Composable
internal fun SearchScreen(
    session: ReaderSearch<Found>,
    toc: List<TocEntry>,
    onSearch: () -> Unit,
    onOpen: (Int) -> Unit,
    onBack: () -> Unit,
    /** 찾기를 지웠다(×). 쪽에 칠해 둔 찾은 자리도 지운다. */
    onCleared: () -> Unit = {},
) {
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
        summary = session.summary,
        progress = session.progress,
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
