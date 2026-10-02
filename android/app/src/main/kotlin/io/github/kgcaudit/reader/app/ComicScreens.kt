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

/*
 * 서재의 만화 탭 · 작품 화면 · 작품 정리(0.33.0, docs/LIBRARY_COMIC_PLAN.md 확정 구상안 ①가 · ②).
 *
 * 작품은 저장하지 않는다 — 훑은 단위와 손 고침에서 그때그때 묶는다(ComicShelf.group). 그래서 화면은 작품을 이름 열쇠로만
 * 기억한다: 합치기 · 빼기로 작품이 바뀌면 같은 열쇠로 새로 묶인 작품이 보인다.
 */

/** 만화 단위 하나의 표지. 메모리에 있으면 바로, 없으면 꺼내는 동안 대신 표지. */
@Composable
private fun rememberComicCover(unit: ComicUnit): ImageBitmap? {
    val covers = LocalContext.current.container.covers
    var image by remember(unit) { mutableStateOf(covers.cachedComic(unit)) }
    LaunchedEffect(unit) { image = covers.comic(unit) }
    return image
}

/** 작품 · 권의 표지. 그림이 없으면 계열 COMIC 타일(보관 황토 + 칸 줄 그린 펼친 책). */
@Composable
internal fun ComicCover(unit: ComicUnit, title: String, subtitle: String?, modifier: Modifier = Modifier, small: Boolean = false) {
    CpCover(
        image = rememberComicCover(unit),
        title = title,
        subtitle = subtitle,
        fallback = CpTheme.colors.tiles.archive,
        icon = CpIcons.Book,
        modifier = modifier,
        small = small,
        glyph = cpComicGlyph(),
    )
}

/** "만화" · "웹툰". */
private fun kindLabel(work: Work) = if (work.webtoon) "웹툰" else "만화"

/** "12권" · "48화". 합본 · 외전도 한 줄로 센다 — 작품 화면에서 보이는 줄 수와 같아야 한다. */
private fun countLabel(work: Work) = "${work.volumeCount}${if (work.webtoon) "화" else "권"}"

/** 작품의 얼굴이 되는 단위: 첫 줄(1권 · 1화). */
private fun Work.face(): ComicUnit = entries.first().unit

/**
 * 만화 탭 내용(구상안 ①가): "만화 · N작품" 머리와 작품 격자. 읽는 중 책장은 뷰어가 붙은 뒤(진도가 생긴 뒤)에 선다.
 */
internal fun LazyListScope.comicShelf(
    works: List<Work>,
    progress: Map<String, ComicProgress>,
    columns: Int,
    onOpen: (Work) -> Unit,
    onResume: (WorkEntry) -> Unit,
) {
    // 읽는 중: 이어 볼 권이 있는 작품, 최근에 본 차례. 마지막 권까지 다 읽은 작품은 뺀다 — 이어 볼 것이 없다.
    val reading = works.mapNotNull { w -> ComicReading.resume(w, progress)?.let { (e, p) -> Triple(w, e, p) } }
        .filter { (_, _, p) -> p?.finished != true }
        .sortedByDescending { (w, _, _) -> w.entries.flatMap { e -> listOf(e.unit) + e.copies }.maxOf { u -> progress[u.id]?.updatedAtEpochMs ?: 0L } }
    if (reading.isNotEmpty()) {
        item(key = "comic-reading-label") { ShelfLabel("읽는 중 · ${reading.size}작품", more = reading.size > columns) }
        item(key = "comic-reading") {
            ShelfRow(reading, { it.first.key }) { (work, entry, p), width -> ResumeItem(work, entry, p, width, onResume) }
        }
        item { Spacer(Modifier.height(14.dp)); io.github.kgcaudit.reader.ui.design.CpDivider() }
    }
    item(key = "comic-label") { ShelfLabel("만화 · ${works.size}작품", more = false) }
    items(works.chunked(columns), key = { "c" + it.first().key }) { row ->
        GridRow(row) { work, width -> WorkItem(work, width, onOpen) }
    }
    item { Spacer(Modifier.height(24.dp)) }
}

/** 읽는 중 한 칸: 이어 볼 권의 표지 · 작품 이름 · 진도 막대 · "3권 · 45쪽". 누르면 곧바로 그 자리부터. */
@Composable
private fun ResumeItem(work: Work, entry: WorkEntry, progress: ComicProgress?, width: Dp, onResume: (WorkEntry) -> Unit) {
    val c = CpTheme.colors
    Column(
        Modifier.width(width).clickable(role = Role.Button) { onResume(entry) }
            .semantics(mergeDescendants = true) { contentDescription = "${work.title} ${entry.label} 이어 보기" },
    ) {
        Box {
            ComicCover(entry.unit, work.title, entry.label, Modifier.fillMaxWidth())
            if (work.volumeCount > 1) CountBadge(countLabel(work), Modifier.align(Alignment.BottomEnd))
        }
        Spacer(Modifier.height(8.dp))
        CpText(work.title, CpTheme.type.label, c.text)
        Spacer(Modifier.height(6.dp))
        io.github.kgcaudit.reader.ui.design.CpProgressBar(progress?.fraction ?: 0f, Modifier.fillMaxWidth(), io.github.kgcaudit.reader.ui.design.CpBarWeight.Thin)
        Spacer(Modifier.height(4.dp))
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

/** 작품 한 칸: 표지(두 권 이상이면 권 수 꼬리표) · 이름 · "만화 · 12권". */
@Composable
private fun WorkItem(work: Work, width: Dp, onOpen: (Work) -> Unit) {
    val c = CpTheme.colors
    Column(
        Modifier.width(width).clickable(role = Role.Button) { onOpen(work) }
            .semantics(mergeDescendants = true) { contentDescription = "${work.title} 작품" },
    ) {
        Box {
            ComicCover(work.face(), work.title, kindLabel(work), Modifier.fillMaxWidth())
            // 한 권뿐이면 꼬리표가 할 말이 없다(구상안: 고양이 탐정).
            if (work.volumeCount > 1) CountBadge(countLabel(work), Modifier.align(Alignment.BottomEnd))
        }
        Spacer(Modifier.height(8.dp))
        CpText(work.title, CpTheme.type.label, c.text)
        Spacer(Modifier.height(4.dp))
        CpText("${kindLabel(work)} · ${countLabel(work)}", CpTheme.type.caption, c.textMuted)
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
    CpFullScreen(side = false) {
        CpHeader(work.title, subtitle = workSubtitle(work), onBack = onBack) {
            CpIconButton(CpIcons.More, "작품 정리", onArrange)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            // "3권 이어 보기 · 45쪽"(구상안 ②). 아무것도 펼치지 않았으면 없다 — 처음부터는 1권 줄을 누르면 된다.
            ComicReading.resume(work, progress)?.let { (entry, p) ->
                item(key = "resume") {
                    Row(Modifier.padding(horizontal = CpTheme.metrics.gutter, vertical = 8.dp)) {
                        CpButton("${entry.label} 이어 보기" + (p?.let { " · ${it.page + 1}쪽" } ?: ""), { onEntry(entry, null) })
                    }
                }
            }
            item { CpSectionLabel("${if (work.webtoon) "화" else "권"} · ${work.volumeCount}") }
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
        progress?.finished == true -> listOfNotNull(pages?.let { "${it}쪽" }, "다 읽음").joinToString(" · ")
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
                    .semantics(mergeDescendants = true) { contentDescription = "${entry.label} 같은 권 ${entry.copies.size + 1}곳" },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.clip(shape).border(1.dp, CpTheme.colors.outline, shape).padding(horizontal = 8.dp, vertical = 2.dp)) {
                    CpText("같은 권 ${entry.copies.size + 1}곳", CpTheme.type.caption, CpTheme.colors.textMuted)
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
                "이 작품에서 빼기", { splitting = true }, icon = CpIcons.Minus, subtitle = "잘못 묶인 권을 따로",
                // 한 줄뿐인 작품에서 빼면 빈 작품과 같은 작품이 하나 더 생길 뿐이다.
                enabled = work.entries.size > 1,
            )
            CpListRow("작품 이름 고치기", { renaming = true }, icon = CpIcons.Note, subtitle = "파일 이름은 그대로")
            for (entry in work.entries.filter { it.copies.isNotEmpty() }) {
                val n = entry.copies.size + 1
                CpListRow(
                    "같은 권 ${n}곳 — ${entry.label}", { onCopies(entry) }, icon = CpIcons.Bookmark,
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
        PickPopup("어느 권을 뺄까요?", "뺀 권은 제 이름으로 따로 보입니다.", { splitting = false }) {
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
    return listOf(ComicShelf.summary(chosen), copies.joinToString(" · ") { "${it.label}(같은 권)" })
        .filter { it.isNotEmpty() }.joinToString(" · ")
}

/** 같은 권 여러 곳 중 읽을 것을 고르는 판. 자리와 크기로 가른다 — 이름은 같은 권이라 거의 같다. */
@Composable
internal fun CopiesPopup(entry: WorkEntry, onPick: (ComicUnit) -> Unit, onDismiss: () -> Unit) {
    val all = (listOf(entry.unit) + entry.copies).sortedWith { a, b -> NaturalOrder.compare(a.place + "/" + a.name, b.place + "/" + b.name) }
    PickPopup("같은 권 ${all.size}곳 — ${entry.label}", "고른 파일로 읽습니다. 나머지는 목록에서 숨깁니다(파일은 지우지 않습니다).", onDismiss) {
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

