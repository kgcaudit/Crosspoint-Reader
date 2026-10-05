package io.github.kgcaudit.reader.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.kgcaudit.reader.document.comic.ComicProgress
import io.github.kgcaudit.reader.document.comic.ComicReading
import io.github.kgcaudit.reader.document.comic.ComicShelf
import io.github.kgcaudit.reader.document.comic.ComicUnit
import io.github.kgcaudit.reader.document.comic.NaturalOrder
import io.github.kgcaudit.reader.document.comic.Work
import io.github.kgcaudit.reader.document.comic.WorkEntry
import io.github.kgcaudit.reader.document.comic.WorkShelf
import io.github.kgcaudit.reader.document.comic.WorkStatuses
import androidx.compose.foundation.combinedClickable
import io.github.kgcaudit.reader.ui.design.CpButton
import io.github.kgcaudit.reader.ui.design.CpCover
import io.github.kgcaudit.reader.ui.design.CpFullScreen
import io.github.kgcaudit.reader.ui.design.CpHeader
import io.github.kgcaudit.reader.ui.design.CpIcon
import io.github.kgcaudit.reader.ui.design.CpIconButton
import io.github.kgcaudit.reader.ui.design.CpIcons
import io.github.kgcaudit.reader.ui.design.CpListRow
import io.github.kgcaudit.reader.ui.design.CpPopup
import io.github.kgcaudit.reader.ui.design.CpPopupButtons
import io.github.kgcaudit.reader.ui.design.CpRadioRow
import io.github.kgcaudit.reader.ui.design.CpSectionLabel
import io.github.kgcaudit.reader.ui.design.CpText
import io.github.kgcaudit.reader.ui.design.CpTheme
import io.github.kgcaudit.reader.ui.design.cpComicGlyph
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Arrangement
import io.github.kgcaudit.reader.ui.design.cpRoomy
import io.github.kgcaudit.reader.ui.design.cpTwoPane
import io.github.kgcaudit.reader.ui.design.TWO_PANE_LEFT_WIDTH

/*
 * 서재의 만화 탭 · 작품 화면 · 작품 정리(0.33.0, docs/LIBRARY_COMIC_PLAN.md 확정 구상안 ①가 · ②).
 *
 * 작품은 저장하지 않는다 — 훑은 단위와 손 고침에서 그때그때 묶는다(ComicShelf.group). 그래서 화면은 작품을 이름 열쇠로만
 * 기억한다: 합치기 · 빼기로 작품이 바뀌면 같은 열쇠로 새로 묶인 작품이 보인다.
 */

/** 만화 단위 하나의 표지. 메모리에 있으면 바로, 없으면 꺼내는 동안 대신 표지. */
@Composable
internal fun rememberComicCover(unit: ComicUnit): ImageBitmap? {
    val covers = LocalContext.current.container.covers
    var image by remember(unit) { mutableStateOf(covers.cachedComic(unit)) }
    LaunchedEffect(unit) { image = covers.comic(unit) }
    return image
}

/**
 * 사람이 고른 작품 표지(0.47.0, 사용자 결정 ⑪). 없으면 null. 표지를 고르거나 되돌리면 다시 읽는다 — 서재로 돌아왔을 때
 * 옛 표지가 남아 있으면 바꾸기가 안 된 줄 안다.
 */
@Composable
internal fun rememberWorkCover(work: Work): ImageBitmap? {
    val covers = LocalContext.current.container.covers
    val version by covers.version.collectAsState()
    var image by remember(work.key) { mutableStateOf(covers.cachedWork(work.key)?.image) }
    LaunchedEffect(work.key, version) { image = covers.work(work.key).image }
    return image
}

/**
 * 작품 · 권의 표지. 그림이 없으면 계열 COMIC 타일(보관 황토 + 칸 줄 그린 펼친 책). [work] 를 주면 그 작품의 얼굴이다 — 사람이
 * 고른 표지가 있으면 그것을 먼저 쓴다(서재 · 작품 화면 머리). 권 · 화 줄에는 주지 않는다 — 줄마다 같은 그림이면 어느 화인지
 * 알 수 없다.
 */
@Composable
internal fun ComicCover(unit: ComicUnit, title: String, subtitle: String?, modifier: Modifier = Modifier, small: Boolean = false, work: Work? = null) {
    val chosen = work?.let { rememberWorkCover(it) }
    val own = rememberComicCover(unit)
    CpCover(
        image = chosen ?: own,
        title = title,
        subtitle = subtitle,
        fallback = CpTheme.colors.tiles.archive,
        icon = CpIcons.Book,
        modifier = modifier,
        small = small,
        glyph = cpComicGlyph(),
    )
}

/** 한 줄을 세는 말: 만화 "권", 웹툰 "화"(0.43.0 — 웹툰에 "같은 권 2곳" · "마지막 권" 이 나왔다). */
internal fun unitWord(work: Work?) = if (work?.webtoon == true) "화" else "권"

/** 줄 하나를 세는 말: 화 번호만 있으면 "화". 작품을 모르는 곳(같은 권 고르기 판)에서 쓴다. */
internal fun unitWord(entry: WorkEntry) = if (entry.name.chapter != null && entry.name.volume == null) "화" else "권"

/** "만화" · "웹툰". */
private fun kindLabel(work: Work) = if (work.webtoon) "웹툰" else "만화"

/** "12권" · "48화". 합본 · 외전도 한 줄로 센다 — 작품 화면에서 보이는 줄 수와 같아야 한다. */
private fun countLabel(work: Work) = "${work.volumeCount}${if (work.webtoon) "화" else "권"}"

/** 작품의 얼굴이 되는 단위: 첫 줄(1권 · 1화). */
private fun Work.face(): ComicUnit = entries.first().unit

/**
 * 만화 탭 내용(0.38.0, 2026-10-03 사용자 결정 1): 책과 같은 세 갈래 — 읽는 중 · 다 읽은 작품 · 읽을 작품. 작품 단위로 나눈다
 * (권마다 나누면 한 작품이 세 갈래에 흩어진다). 갈래는 권 진도를 모아 정하고 사람이 옮긴 표시가 앞선다([WorkStatuses]).
 */
internal fun LazyListScope.comicShelf(
    works: List<Work>,
    progress: Map<String, ComicProgress>,
    sort: LibrarySort,
    layout: LibraryLayout,
    columns: Int,
    onOpen: (Work) -> Unit,
    onResume: (WorkEntry) -> Unit,
    onMenu: (Work) -> Unit,
) {
    val status = works.associate { it.key to WorkStatuses.of(it, progress) }
    fun shelf(s: WorkShelf) = works.filter { status.getValue(it.key).shelf == s }
    fun arrange(list: List<Work>, recent: ((Work) -> Long?)?) = sort.arrange(
        list, { it.title },
        added = { w -> w.entries.flatMap { listOf(it.unit) + it.copies }.mapNotNull { it.addedAtEpochMs }.maxOrNull() },
        size = { w -> w.entries.sumOf { it.unit.sizeBytes ?: 0L }.takeIf { it > 0 } },
        recent = recent,
    )
    val reading = arrange(shelf(WorkShelf.READING)) { status.getValue(it.key).lastReadAtEpochMs }
    val finished = arrange(shelf(WorkShelf.FINISHED)) { status.getValue(it.key).finishedAtEpochMs }
    val toRead = arrange(shelf(WorkShelf.TO_READ), null)

    shelfSection(
        "comic-reading", "읽는 책 · ${reading.size}작품", reading, { it.key }, layout, columns,
        grid = { work, width -> val (e, p) = resumeOf(work, progress); ResumeItem(work, e, p, status.getValue(work.key).newVolumes, width, onResume, onMenu) },
        row = { work ->
            val (e, p) = resumeOf(work, progress)
            WorkRow(work, onClick = { onResume(e) }, onMenu = onMenu, description = "${work.title} ${e.label} 이어 읽기") {
                ResumeLine(work, e, p, status.getValue(work.key).newVolumes)
            }
        },
        // 읽는 중 칸은 이어 볼 권의 표지를 세운다 — 누르면 그 자리부터(격자 칸과 같다).
        shelf = { work, width ->
            val e = resumeOf(work, progress).first
            ShelfWork(work, e.unit, e.label, width, "${work.title} ${e.label} 이어 읽기", { onResume(e) }, onMenu)
        },
    )
    shelfSection(
        "comic-finished", "읽은 책 · ${finished.size}작품", finished, { it.key }, layout, columns,
        grid = { work, width -> WorkItem(work, width, onOpen, onMenu) { DoneLine(status.getValue(work.key).finishedAtEpochMs) } },
        row = { work -> WorkRow(work, onClick = { onOpen(work) }, onMenu = onMenu) { DoneLine(status.getValue(work.key).finishedAtEpochMs) } },
        shelf = { work, width -> ShelfWork(work, work.face(), kindLabel(work), width, "${work.title} 작품", { onOpen(work) }, onMenu) },
    )
    shelfSection(
        "comic-to-read", "읽을 책 · ${toRead.size}작품", toRead, { it.key }, layout, columns,
        divider = reading.isNotEmpty() || finished.isNotEmpty(),
        grid = { work, width -> WorkItem(work, width, onOpen, onMenu) },
        row = { work -> WorkRow(work, onClick = { onOpen(work) }, onMenu = onMenu) },
        shelf = { work, width -> ShelfWork(work, work.face(), kindLabel(work), width, "${work.title} 작품", { onOpen(work) }, onMenu) },
    )
}

/**
 * 읽는 중 작품의 이어 볼 줄과 그 진도. 펼친 적이 없는데 읽는 중인 작품(다 읽었다고 옮겼다가 되돌림 · 새 권만 남음)은 처음
 * 펼치지 않은 권부터 — 이어 볼 자리가 없다고 칸을 비우면 누를 곳이 없다.
 */
private fun resumeOf(work: Work, progress: Map<String, ComicProgress>): Pair<WorkEntry, ComicProgress?> =
    ComicReading.resume(work, progress)
        ?: (work.entries.firstOrNull { e -> (listOf(e.unit) + e.copies).none { progress[it.id] != null } } ?: work.entries.first()).let { it to null }

/** 읽는 중 한 칸: 이어 볼 권의 표지 · 작품 이름 · 진도 막대 · "3권 · 45쪽". 누르면 곧바로 그 자리부터, 길게 누르면 갈래 판. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ResumeItem(work: Work, entry: WorkEntry, progress: ComicProgress?, newVolumes: Boolean, width: Dp, onResume: (WorkEntry) -> Unit, onMenu: (Work) -> Unit) {
    val c = CpTheme.colors
    Column(
        Modifier.width(width).combinedClickable(role = Role.Button, onLongClick = { onMenu(work) }) { onResume(entry) }
            .semantics(mergeDescendants = true) { contentDescription = "${work.title} ${entry.label} 이어 읽기" },
    ) {
        Box {
            ComicCover(entry.unit, work.title, entry.label, Modifier.fillMaxWidth(), work = work)
            if (work.volumeCount > 1) CountBadge(countLabel(work), Modifier.align(Alignment.BottomEnd))
        }
        Spacer(Modifier.height(8.dp))
        CpText(work.title, CpTheme.type.label, c.text)
        ResumeLine(work, entry, progress, newVolumes)
    }
}

/** 진도 막대 + "3권 · 45쪽". 새 권이 들어와 돌아온 작품이면 "새 권 · 4권부터" 를 강조색으로. */
@Composable
private fun ResumeLine(work: Work, entry: WorkEntry, progress: ComicProgress?, newVolumes: Boolean) {
    val c = CpTheme.colors
    Spacer(Modifier.height(6.dp))
    io.github.kgcaudit.reader.ui.design.CpProgressBar(progress?.fraction ?: 0f, Modifier.fillMaxWidth(), io.github.kgcaudit.reader.ui.design.CpBarWeight.Thin)
    Spacer(Modifier.height(4.dp))
    if (newVolumes && progress == null) {
        CpText("새 ${if (work.webtoon) "화" else "권"} · ${entry.label}부터", CpTheme.type.caption, c.accentText)
    } else {
        CpText(resumeLabel(entry, progress), CpTheme.type.caption, c.textMuted)
    }
}

/** "3권 · 45쪽" · 아직 펼치지 않은 다음 권이면 "4권 · 처음부터". */
internal fun resumeLabel(entry: WorkEntry, progress: ComicProgress?): String =
    "${entry.label} · ${progress?.let { "${it.page + 1}쪽" } ?: "처음부터"}"

@Composable
private fun CountBadge(text: String, modifier: Modifier) {
    Box(
        modifier.padding(5.dp).clip(RoundedCornerShape(CpTheme.metrics.cornerChip))
            .background(BADGE).padding(horizontal = 6.dp, vertical = 2.dp),
    ) { CpText(text, CpTheme.type.caption, Color.White) }
}

/** 작품 한 칸: 표지(두 권 이상이면 권 수 꼬리표) · 이름 · "만화 · 12권"(다 읽은 작품은 [status] 가 대신). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun WorkItem(work: Work, width: Dp, onOpen: (Work) -> Unit, onMenu: (Work) -> Unit, status: (@Composable () -> Unit)? = null) {
    val c = CpTheme.colors
    Column(
        Modifier.width(width).combinedClickable(role = Role.Button, onLongClick = { onMenu(work) }) { onOpen(work) }
            .semantics(mergeDescendants = true) { contentDescription = "${work.title} 작품" },
    ) {
        Box {
            ComicCover(work.face(), work.title, kindLabel(work), Modifier.fillMaxWidth(), work = work)
            // 한 권뿐이면 꼬리표가 할 말이 없다(구상안: 고양이 탐정).
            if (work.volumeCount > 1) CountBadge(countLabel(work), Modifier.align(Alignment.BottomEnd))
        }
        Spacer(Modifier.height(8.dp))
        CpText(work.title, CpTheme.type.label, c.text)
        if (status != null) status() else {
            Spacer(Modifier.height(4.dp))
            CpText("${kindLabel(work)} · ${countLabel(work)}", CpTheme.type.caption, c.textMuted)
        }
    }
}

/** 책장 보기의 작품 한 칸: 표지와 권 수 꼬리표만. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ShelfWork(work: Work, unit: ComicUnit, subtitle: String, width: Dp, description: String, onClick: () -> Unit, onMenu: (Work) -> Unit) {
    Box(
        Modifier.width(width).combinedClickable(role = Role.Button, onLongClick = { onMenu(work) }, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        ComicCover(unit, work.title, subtitle, Modifier.fillMaxWidth(), work = work)
        if (work.volumeCount > 1) CountBadge(countLabel(work), Modifier.align(Alignment.BottomEnd))
    }
}

/** 목록 보기의 작품 한 줄: 작은 표지 · 이름 · "만화 · 12권 · 오→왼" · 갈래 줄 · ⋮(갈래 판). 책 목록 줄과 같은 치수. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun WorkRow(
    work: Work,
    onClick: () -> Unit,
    onMenu: (Work) -> Unit,
    description: String = "${work.title} 작품",
    status: (@Composable () -> Unit)? = null,
) {
    val c = CpTheme.colors
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(role = Role.Button, onLongClick = { onMenu(work) }, onClick = onClick)
            .padding(start = CpTheme.metrics.gutter, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(56.dp).semantics(mergeDescendants = true) { contentDescription = description }) {
            ComicCover(work.face(), work.title, kindLabel(work), Modifier.fillMaxWidth(), small = true, work = work)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            CpText(work.title, CpTheme.type.body, c.text, maxLines = 2)
            CpText(workSubtitle(work), CpTheme.type.caption, c.textMuted)
            status?.invoke()
        }
        CpIconButton(CpIcons.More, "${work.title} 더 보기", { onMenu(work) }, tint = c.textMuted)
    }
}

/** 길게 누른 작품의 갈래 판(0.38.0). 책의 표지 판처럼 갈래를 옮기고, 읽는 중 칸에서 작품 화면으로 가는 길이기도 하다. */
@Composable
internal fun WorkMenu(
    work: Work,
    shelf: WorkShelf,
    onOpen: () -> Unit,
    onFinished: () -> Unit,
    onReading: () -> Unit,
    onToRead: () -> Unit,
    onDismiss: () -> Unit,
    /** 보던 자리를 열어 표지로 쓸 부분을 고른다(0.47.0, 사용자 결정 ⑪). */
    onScene: () -> Unit = {},
    /** 사진 · 파일에서 표지를 고른다. */
    onPhoto: () -> Unit = {},
    /** 고른 표지를 지운다. */
    onRevert: () -> Unit = {},
) {
    val custom = rememberWorkCover(work) != null
    CpPopup(title = work.title, message = workSubtitle(work), onDismiss = onDismiss) {
        Spacer(Modifier.height(8.dp))
        // 판 안의 행은 판 글자 시작선에서(inset 0) — 책 표지 판과 같다.
        CpListRow("작품 화면 열기", onOpen, icon = CpIcons.Book, compact = true, inset = 0.dp)
        when (shelf) {
            WorkShelf.FINISHED -> CpListRow("읽는 책으로 옮기기", onReading, icon = CpIcons.Book, compact = true, inset = 0.dp)
            else -> CpListRow("읽은 책으로 옮기기", onFinished, icon = CpIcons.Bookmark, compact = true, inset = 0.dp)
        }
        if (shelf != WorkShelf.TO_READ) CpListRow("읽을 책으로 옮기기", onToRead, icon = CpIcons.Back, compact = true, inset = 0.dp)
        // 표지(⑪): 웹툰은 1화 첫 칸보다 중간의 한 장면이 작품을 더 잘 보여 줄 때가 많다. 책의 표지 판과 같은 말 · 같은 그림.
        CpListRow("보던 장면에서 표지 고르기", onScene, icon = CpIcons.View, compact = true, inset = 0.dp)
        CpListRow("사진 · 파일에서 표지 고르기", onPhoto, icon = CpIcons.Folder, compact = true, inset = 0.dp)
        CpListRow(
            "원래 표지로 되돌리기",
            // 고른 표지가 없으면 되돌릴 것이 없다. 눌러도 판만 닫는다.
            { if (custom) onRevert() else onDismiss() },
            icon = CpIcons.Refresh,
            compact = true,
            enabled = custom,
            inset = 0.dp,
        )
        CpPopupButtons { CpButton("닫기", onDismiss, primary = false) }
    }
}

/** 표지 위 권 수 꼬리표 바탕. 80% 검정 — 밝은 표지 그림 위에서도 흰 글자가 읽힌다. */
private val BADGE = Color(0xCC000000)

/** 작품 화면 머리 아랫줄: "만화 · 12권 · 2곳에서 모음 · 완결 · 오→왼". 모르는 것은 뺀다. */
internal fun workSubtitle(work: Work): String = listOfNotNull(
    kindLabel(work),
    countLabel(work),
    work.places.size.takeIf { it > 1 }?.let { "${it}곳에서 모음" },
    "완결".takeIf { work.complete },
    when (work.rightToLeft) {
        true -> "오→왼"
        false -> "왼→오"
        null -> null
    },
).joinToString(" · ")

/**
 * 작품 화면(구상안 ②): 권 · 화 목록. 합본 안의 권은 합본 줄의 글자가 시작하는 자리에서 시작한다(UI 규칙 1 — 속하면
 * 들인다). 같은 권이 여러 곳이면 한 줄만 보이고 "같은 권 n곳" 꼬리표를 단다.
 */
@Composable
internal fun WorkScreen(
    work: Work,
    progress: Map<String, ComicProgress>,
    onBack: () -> Unit,
    onArrange: () -> Unit,
    onEntry: (WorkEntry, Int?) -> Unit,
    onCopies: (WorkEntry) -> Unit,
) {
    // 배치는 창 폭 등급으로(0.46.0, docs/RESPONSIVE_PLAN.md): 좁음 · 낮은 창(가로로 돌린 휴대폰)은 줄 목록, 넓고 높으면 두 판
    // (왼쪽 작품 · 오른쪽 권 표지 격자), 중간은 한 판(위 작품 · 아래 격자). 0.45 까지는 태블릿에서도 가운데 600dp 줄
    // 목록이라 양옆이 비고 표지가 엄지손톱만 했다(사용자 스크린샷).
    val layout = when {
        !cpRoomy() -> WorkLayout.List
        cpTwoPane() -> WorkLayout.TwoPane
        else -> WorkLayout.Grid
    }
    CpFullScreen(side = false, wide = layout != WorkLayout.List) {
        CpHeader(work.title, subtitle = workSubtitle(work), onBack = onBack) {
            CpIconButton(CpIcons.More, "작품 정리", onArrange)
        }
        if (layout != WorkLayout.List) {
            WorkWide(work, progress, twoPane = layout == WorkLayout.TwoPane, onEntry = onEntry, onCopies = onCopies)
            return@CpFullScreen
        }
        LazyColumn(Modifier.fillMaxSize()) {
            // "3권 이어 보기 · 45쪽"(구상안 ②). 아무것도 펼치지 않았으면 없다 — 처음부터는 1권 줄을 누르면 된다.
            ComicReading.resume(work, progress)?.let { (entry, p) ->
                item(key = "resume") {
                    Row(Modifier.padding(horizontal = CpTheme.metrics.gutter, vertical = 8.dp)) {
                        CpButton("${entry.label} 이어 읽기" + (p?.let { " · ${it.page + 1}쪽" } ?: ""), { onEntry(entry, null) })
                    }
                }
            }
            item { CpSectionLabel("${work.volumeCount}${unitWord(work)}") }
            for (entry in work.entries) {
                item(key = entry.slot) { EntryRow(entry, work.title, progress[entry.unit.id], { onEntry(it, null) }, onCopies) }
                val sections = entry.unit.contents?.sections.orEmpty()
                entry.sections.forEachIndexed { i, label ->
                    item(key = entry.slot + "#" + i) {
                        val section = sections.getOrNull(i)
                        val pages = section?.takeIf { it.pageCount > 0 }?.let {
                            // 한 쪽뿐이면 "5–5쪽" 이 아니라 "5쪽".
                            if (it.pageCount == 1) "${it.firstPage + 1}쪽" else "${it.firstPage + 1}–${it.firstPage + it.pageCount}쪽"
                        }
                        // 합본 안의 권을 누르면 그 권의 첫 쪽부터.
                        UnitRow(entry.unit, work.title, label, pages, indent = ENTRY_LEAD, onClick = { onEntry(entry, section?.firstPage) })
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private enum class WorkLayout { List, TwoPane, Grid }

/** 격자 칸 하나: 권 · 화, 또는 합본 안의 권(그 합본의 [firstPage] 부터). */
private class WorkCell(val entry: WorkEntry, val label: String, val sub: String?, val progress: Float?, val firstPage: Int?, val inside: String?)

/** 권 줄과 같은 글: "다 읽음 · 180쪽" · "45 / 182쪽" · "182쪽". */
private fun entrySub(entry: WorkEntry, progress: ComicProgress?): String? {
    val pages = progress?.pageCount?.takeIf { it > 0 } ?: entry.unit.pageCount
    return when {
        progress?.finished == true -> listOfNotNull("다 읽음", pages?.let { "${it}쪽" }).joinToString(" · ")
        progress != null && pages != null -> "${progress.page + 1} / ${pages}쪽"
        entry.omnibus && pages != null -> "한 파일 · ${pages}쪽"
        entry.omnibus -> "한 파일"
        pages != null -> "${pages}쪽"
        else -> null
    }
}

private fun workCells(work: Work, progress: Map<String, ComicProgress>): List<WorkCell> = work.entries.flatMap { entry ->
    val p = progress[entry.unit.id]
    val label = if (entry.omnibus && entry.name.isRange) "${entry.label} 합본" else entry.label
    val sections = entry.unit.contents?.sections.orEmpty()
    listOf(WorkCell(entry, label, entrySub(entry, p), p?.let { if (it.finished) 1f else it.fraction }, null, null)) +
        entry.sections.mapIndexed { i, section ->
            val s = sections.getOrNull(i)?.takeIf { it.pageCount > 0 }
            val pages = s?.let { if (it.pageCount == 1) "${it.firstPage + 1}쪽" else "${it.firstPage + 1}–${it.firstPage + it.pageCount}쪽" }
            // 합본 안의 권은 그 합본에 속한다는 것을 글로 보인다 — 격자에서는 들여쓰기로 보일 수 없다.
            WorkCell(entry, section, pages, null, s?.firstPage, "$label 안")
        }
}

/**
 * 넓은 화면의 작품 화면(0.46.0, 구상안 tablet 가안). 두 판이면 왼쪽에 큰 표지 · 이름 · 진도 · 이어 읽기, 오른쪽에 권 표지
 * 격자. 한 판이면 그 정보를 위에 가로로 두고 아래에 격자.
 */
@Composable
private fun WorkWide(
    work: Work,
    progress: Map<String, ComicProgress>,
    twoPane: Boolean,
    onEntry: (WorkEntry, Int?) -> Unit,
    onCopies: (WorkEntry) -> Unit,
) {
    val resume = ComicReading.resume(work, progress)
    val done = work.entries.count { e -> (listOf(e.unit) + e.copies).any { progress[it.id]?.finished == true } }
    val status = listOfNotNull(
        "$done${unitWord(work)} 다 읽음".takeIf { done > 0 },
        resume?.let { "${it.first.label} 읽는 중" },
    ).joinToString(" · ").ifEmpty { null }
    val cells = workCells(work, progress)
    val c = CpTheme.colors

    @Composable
    fun Info(coverWidth: Dp, fill: Boolean) {
        CpText(work.title, CpTheme.type.title, c.text, maxLines = 2)
        status?.let { CpText(it, CpTheme.type.caption, c.textMuted) }
        resume?.let { (entry, p) ->
            Spacer(Modifier.height(16.dp))
            CpButton(
                "${entry.label} 이어 읽기" + (p?.let { " · ${it.page + 1}쪽" } ?: ""), { onEntry(entry, null) },
                if (fill) Modifier.fillMaxWidth() else Modifier,
            )
        }
    }

    val grid: androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit = {
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { CpSectionLabel("${work.volumeCount}${unitWord(work)}") }
        items(cells.size, key = { cells[it].let { cell -> cell.entry.slot + "#" + (cell.firstPage ?: -1) + cell.label } }) { i ->
            WorkGridCell(cells[i], work.title, { onEntry(cells[i].entry, cells[i].firstPage) }, onCopies)
        }
    }
    if (twoPane) {
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.width(TWO_PANE_LEFT_WIDTH).fillMaxHeight().verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            ) {
                ComicCover(work.entries.first().unit, work.title, null, Modifier.width(200.dp), work = work)
                Spacer(Modifier.height(16.dp))
                Info(200.dp, fill = true)
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(c.outline))
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                androidx.compose.foundation.lazy.grid.GridCells.Adaptive(WORK_CELL_MIN),
                Modifier.weight(1f).fillMaxHeight(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = grid,
            )
        }
    } else {
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            androidx.compose.foundation.lazy.grid.GridCells.Adaptive(WORK_CELL_MIN),
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
                    ComicCover(work.entries.first().unit, work.title, null, Modifier.width(110.dp), work = work)
                    Spacer(Modifier.width(20.dp))
                    Column(Modifier.weight(1f)) { Info(110.dp, fill = false) }
                }
            }
            grid()
        }
    }
}

/** 격자 칸의 가장 작은 폭. 펼친 폴더블 두 판의 오른쪽(약 560dp)에 네 칸, 한 판(약 900dp)에 여섯 칸 — 구상안과 같다. */
private val WORK_CELL_MIN = 120.dp

@Composable
private fun WorkGridCell(cell: WorkCell, title: String, onClick: () -> Unit, onCopies: (WorkEntry) -> Unit) {
    val c = CpTheme.colors
    Column(Modifier.clickable(role = Role.Button, onClick = onClick).semantics(mergeDescendants = true) {}) {
        ComicCover(cell.entry.unit, title, null, Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        CpText(cell.label, CpTheme.type.label, c.text)
        cell.inside?.let { CpText(it, CpTheme.type.caption, c.textMuted) }
        cell.progress?.let { io.github.kgcaudit.reader.ui.design.CpProgressBar(it, Modifier.fillMaxWidth().padding(vertical = 4.dp), io.github.kgcaudit.reader.ui.design.CpBarWeight.Thin) }
        cell.sub?.let { CpText(it, CpTheme.type.caption, if (cell.progress == 1f) c.accentText else c.textMuted) }
    }
    if (cell.firstPage == null && cell.entry.copies.isNotEmpty()) {
        val entry = cell.entry
        Box(
            Modifier.heightIn(min = CpTheme.metrics.touchTarget).clickable(role = Role.Button) { onCopies(entry) }
                .semantics(mergeDescendants = true) { contentDescription = "${entry.label} 같은 ${unitWord(entry)} ${entry.copies.size + 1}곳" },
            contentAlignment = Alignment.CenterStart,
        ) {
            CpText("같은 ${unitWord(entry)} ${entry.copies.size + 1}곳", CpTheme.type.caption, c.textMuted)
        }
    }
}

/** 권 줄의 앞머리 폭(작은 표지 + 사이). 합본 안의 권은 이만큼 들여 부모 줄의 글자 시작선에 선다. */
internal val ENTRY_COVER = 40.dp
internal val ENTRY_LEAD = ENTRY_COVER + 14.dp

@Composable
private fun EntryRow(entry: WorkEntry, title: String, progress: ComicProgress?, onEntry: (WorkEntry) -> Unit, onCopies: (WorkEntry) -> Unit) {
    val pages = progress?.pageCount?.takeIf { it > 0 } ?: entry.unit.pageCount
    // 범위로 읽힌 합본은 "4–6권 합본", 목차로만 알아본 합본은 이름 그대로(이미 "합본" 이 붙은 경우가 많다).
    val label = if (entry.omnibus && entry.name.isRange) "${entry.label} 합본" else entry.label
    val sub = when {
        // 읽은 권은 진도를 먼저(구상안 ②: "180쪽 · 다 읽음" · "45 / 182쪽").
        // 다 읽은 줄은 서재와 같은 꼴 "다 읽음 · …"(0.43.0 — 여기만 "180쪽 · 다 읽음" 으로 거꾸로였다).
        progress?.finished == true -> listOfNotNull("다 읽음", pages?.let { "${it}쪽" }).joinToString(" · ")
        progress != null && pages != null -> "${progress.page + 1} / ${pages}쪽"
        entry.omnibus && pages != null -> "한 파일 · ${pages}쪽"
        entry.omnibus -> "한 파일"
        pages != null -> "${pages}쪽"
        else -> null
    }
    UnitRow(entry.unit, title, label, sub, indent = 0.dp, onClick = { onEntry(entry) }, progress = progress?.let { if (it.finished) 1f else it.fraction }) {
        if (entry.copies.isNotEmpty()) {
            val shape = RoundedCornerShape(CpTheme.metrics.cornerChip)
            Box(
                Modifier.heightIn(min = CpTheme.metrics.touchTarget).clickable(role = Role.Button) { onCopies(entry) }
                    .semantics(mergeDescendants = true) { contentDescription = "${entry.label} 같은 ${unitWord(entry)} ${entry.copies.size + 1}곳" },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.clip(shape).border(1.dp, CpTheme.colors.outline, shape).padding(horizontal = 8.dp, vertical = 2.dp)) {
                    CpText("같은 ${unitWord(entry)} ${entry.copies.size + 1}곳", CpTheme.type.caption, CpTheme.colors.textMuted)
                }
            }
        }
    }
}

@Composable
private fun UnitRow(
    unit: ComicUnit,
    title: String,
    label: String,
    sub: String?,
    indent: Dp,
    onClick: () -> Unit,
    /** 펼친 권의 진도(0..1). null 이면 막대가 없다. */
    progress: Float? = null,
    trailing: @Composable () -> Unit = {},
) {
    val c = CpTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(role = Role.Button, onClick = onClick)
            .padding(start = CpTheme.metrics.gutter + indent, end = CpTheme.metrics.gutter, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ComicCover(unit, title, null, Modifier.width(ENTRY_COVER), small = true)
        Spacer(Modifier.width(ENTRY_LEAD - ENTRY_COVER))
        Column(Modifier.weight(1f)) {
            CpText(label, CpTheme.type.body, c.text)
            sub?.let { CpText(it, CpTheme.type.caption, c.textMuted) }
            progress?.let { io.github.kgcaudit.reader.ui.design.CpProgressBar(it, Modifier.fillMaxWidth().padding(top = 4.dp), io.github.kgcaudit.reader.ui.design.CpBarWeight.Thin) }
        }
        trailing()
    }
}

/**
 * 작품 정리(구상안 ②): 모은 곳(폴더마다 어떤 권이 있는지)과 묶음 고치기 넷. 고친 것은 파일이 아니라 앱에 적는다 —
 * 파일 이름 · 자리는 그대로이고, 다시 훑어도 남는다.
 */
@Composable
internal fun WorkArrange(
    work: Work,
    others: List<Work>,
    onBack: () -> Unit,
    onMerge: (Work) -> Unit,
    onSplit: (WorkEntry) -> Unit,
    onRename: (String) -> Unit,
    onCopies: (WorkEntry) -> Unit,
) {
    var merging by remember { mutableStateOf(false) }
    var splitting by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    CpFullScreen(side = false) {
        CpHeader("작품 정리", subtitle = work.title, onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            CpSectionLabel("모은 곳")
            for (place in work.places) PlaceRow(place, placeSummary(work, place))
            CpSectionLabel("묶음")
            CpListRow(
                "다른 작품과 합치기", { merging = true }, icon = CpIcons.Plus, subtitle = "이름이 달라 따로 보이는 같은 작품",
                enabled = others.isNotEmpty(),
            )
            CpListRow(
                "이 작품에서 빼기", { splitting = true }, icon = CpIcons.Minus, subtitle = "잘못 묶인 ${unitWord(work)}${objectParticle(unitWord(work))} 따로",
                // 한 줄뿐인 작품에서 빼면 빈 작품과 같은 작품이 하나 더 생길 뿐이다.
                enabled = work.entries.size > 1,
            )
            CpListRow("작품 이름 고치기", { renaming = true }, icon = CpIcons.Note, subtitle = "파일 이름은 그대로")
            for (entry in work.entries.filter { it.copies.isNotEmpty() }) {
                val n = entry.copies.size + 1
                CpListRow(
                    "같은 ${unitWord(work)} ${n}곳 — ${entry.label}", { onCopies(entry) }, icon = CpIcons.Bookmark,
                    subtitle = "읽을 파일 고르기 · ${if (n == 2) "다른 하나는" else "나머지는"} 숨김",
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (merging) {
        PickPopup("‘${work.title}’ — 어느 작품에 합칠까요?", "합친 뒤에는 고른 작품의 이름으로 보입니다.", { merging = false }) {
            for (other in others) {
                CpListRow(other.title, { merging = false; onMerge(other) }, subtitle = "${kindLabel(other)} · ${countLabel(other)}", compact = true, inset = 0.dp)
            }
        }
    }
    if (splitting) {
        PickPopup("어느 ${unitWord(work)}${objectParticle(unitWord(work))} 뺄까요?", "뺀 ${unitWord(work)}${if (work.webtoon) "는" else "은"} 제 이름으로 따로 보입니다.", { splitting = false }) {
            for (entry in work.entries) {
                CpListRow(entry.label, { splitting = false; onSplit(entry) }, subtitle = entry.unit.name, compact = true, inset = 0.dp)
            }
        }
    }
    if (renaming) RenamePopup(work.title, onDismiss = { renaming = false }, onSave = { renaming = false; onRename(it) })
}

/** 폴더 한 줄(누르지 않는 정보 줄). 모은 곳은 고칠 것이 아니라 보는 것이라 누름 자리를 두지 않는다. */
@Composable
private fun PlaceRow(place: String, summary: String) {
    val c = CpTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = CpTheme.metrics.rowHeight).padding(horizontal = CpTheme.metrics.gutter, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CpIcon(CpIcons.Folder, c.textMuted)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            CpText(place.ifEmpty { "등록 폴더" }, CpTheme.type.body, c.text, maxLines = 2)
            CpText(summary, CpTheme.type.subtitle, c.textMuted, maxLines = 2)
        }
    }
}

/** 한 폴더에 든 것: 읽는 권은 이어 적고("1–6권 · 외전"), 다른 곳의 권을 고른 사본은 "(같은 권)" 을 붙인다. */
internal fun placeSummary(work: Work, place: String): String {
    val chosen = work.entries.filter { it.unit.place == place }
    val copies = work.entries.filter { e -> e.unit.place != place && e.copies.any { it.place == place } }
    return listOf(ComicShelf.summary(chosen), copies.joinToString(" · ") { "${it.label}(같은 ${unitWord(work)})" })
        .filter { it.isNotEmpty() }.joinToString(" · ")
}

/** 같은 권 여러 곳 중 읽을 것을 고르는 판. 자리와 크기로 가른다 — 이름은 같은 권이라 거의 같다. */
@Composable
internal fun CopiesPopup(entry: WorkEntry, onPick: (ComicUnit) -> Unit, onDismiss: () -> Unit) {
    val all = (listOf(entry.unit) + entry.copies).sortedWith { a, b -> NaturalOrder.compare(a.place + "/" + a.name, b.place + "/" + b.name) }
    PickPopup("같은 ${unitWord(entry)} ${all.size}곳 — ${entry.label}", "고른 파일로 읽습니다. 나머지는 목록에서 숨깁니다(파일은 지우지 않습니다).", onDismiss) {
        for (unit in all) {
            CpRadioRow(
                unit.name, unit.id == entry.unit.id, { onPick(unit) },
                subtitle = listOfNotNull(unit.place.ifEmpty { null }, unit.sizeBytes?.let(::sizeLabel)).joinToString(" · "),
                inset = 0.dp,
            )
        }
    }
}

/** 고를 것이 여럿인 판. 길면 판 안에서 넘긴다 — 작품이 백 개면 판이 화면을 넘어 닫기 단추가 사라진다. */
@Composable
private fun PickPopup(title: String, message: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    CpPopup(title = title, message = message, onDismiss = onDismiss) {
        Spacer(Modifier.height(8.dp))
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) { content() }
        CpPopupButtons { CpButton("닫기", onDismiss, primary = false) }
    }
}

@Composable
private fun RenamePopup(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val c = CpTheme.colors
    val m = CpTheme.metrics
    var text by remember { mutableStateOf(current) }
    CpPopup(title = "작품 이름 고치기", message = "비우고 저장하면 원래 이름으로 돌아갑니다.", onDismiss = onDismiss) {
        Box(
            Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(m.cornerMedium))
                .border(2.dp, c.accent, RoundedCornerShape(m.cornerMedium)).padding(14.dp),
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = CpTheme.type.body.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "작품 이름 입력" },
            )
        }
        CpPopupButtons {
            CpButton("취소", onDismiss, primary = false)
            CpButton("저장", { onSave(text) })
        }
    }
}

