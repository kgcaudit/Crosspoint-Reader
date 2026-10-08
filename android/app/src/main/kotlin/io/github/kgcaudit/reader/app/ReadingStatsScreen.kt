package io.github.kgcaudit.reader.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kgcaudit.reader.ui.design.CONTENT_MAX_WIDTH
import io.github.kgcaudit.reader.ui.design.CpDivider
import io.github.kgcaudit.reader.ui.design.CpEmptyMessage
import io.github.kgcaudit.reader.ui.design.CpHeader
import io.github.kgcaudit.reader.ui.design.CpIcon
import io.github.kgcaudit.reader.ui.design.CpIcons
import io.github.kgcaudit.reader.ui.design.CpListRow
import io.github.kgcaudit.reader.ui.design.CpSectionLabel
import io.github.kgcaudit.reader.ui.design.CpText
import io.github.kgcaudit.reader.ui.design.CpTextButton
import io.github.kgcaudit.reader.ui.design.CpTheme
import io.github.kgcaudit.reader.ui.design.TWO_PANE_LEFT_WIDTH
import io.github.kgcaudit.reader.ui.design.cpTablet
import io.github.kgcaudit.reader.ui.design.cpTwoPane
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.YearMonth
import java.time.ZoneId

/**
 * 독서 기록(0.51.0, 사용자 결정 1-3). 서재의 더 보기에서 연다. 이 휴대폰에서 손으로 넘기며 읽은 시간만 보인다 — 듣기 · 자동
 * 넘김은 맨 아래에 한 줄로 따로.
 *
 * 배치(docs/RESPONSIVE_PLAN.md): 좁음은 한 줄로 내려 읽고, 중간은 설정 · 앱 정보처럼 600dp 가운데, 넓음은 두 판 — 왼쪽에 타일 ·
 * 달력(고정 정보), 오른쪽에 다 읽은 것 · 가장 오래 읽은 것(굴러가는 목록).
 */
@Composable
internal fun ReadingStatsScreen(onBack: () -> Unit, onOpenBook: (String) -> Unit = {}, onOpenComic: (String) -> Unit = {}) {
    BackHandler(onBack = onBack)
    val c = CpTheme.colors
    val container = LocalContext.current.container
    val data = container.data
    // 읽은 시간이 바뀌면(화면을 끄고 듣는 중 1분마다) 다시 센다. 나머지(책 · 만화)는 그때마다 함께 읽는다.
    val stats by produceState<ReadingStats?>(null) {
        data.readingTime.rows().collect { rows ->
            value = withContext(Dispatchers.IO) {
                val works = data.comics.works().first()
                val progress = data.comics.progress().first()
                val shown = works.flatMap { w -> w.entries.flatMap { e -> listOf(e.unit.id) + e.copies.map { it.id } } }.toHashSet()
                val hidden = (progress.keys + rows.filter { it.item == io.github.kgcaudit.reader.data.TimeItem.COMIC }.map { it.id }) - shown
                ReadingStats.of(
                    rows = rows,
                    books = data.library.everyBook(),
                    works = works,
                    comicProgress = progress,
                    hiddenUnits = data.comics.unitsOf(hidden).associateBy { it.id },
                    today = container.today(),
                    zone = ZoneId.systemDefault(),
                )
            }
        }
    }
    Box(Modifier.fillMaxSize().background(c.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        val s = stats
        val twoPane = cpTwoPane()
        Column(
            Modifier.align(Alignment.TopCenter).fillMaxSize()
                .then(if (!twoPane && cpTablet()) Modifier.widthIn(max = CONTENT_MAX_WIDTH) else Modifier),
        ) {
            CpHeader("독서 기록", subtitle = "이 휴대폰에서 읽은 시간", onBack = onBack)
            when {
                s == null -> Unit
                s.isEmpty -> CpEmptyMessage("아직 읽은 기록이 없습니다. 책장을 넘기며 읽으면 여기에 쌓입니다.")
                twoPane -> Row(Modifier.fillMaxSize()) {
                    Column(Modifier.width(TWO_PANE_LEFT_WIDTH).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                        Overview(s, narrow = true)
                    }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(c.outline))
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                        Lists(s, onOpenBook, onOpenComic, first = true)
                    }
                }
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                    Overview(s)
                    Lists(s, onOpenBook, onOpenComic, first = false)
                }
            }
        }
    }
}

/**
 * 타일 셋과 달력. [narrow] 는 두 판의 왼쪽(320dp) — 타일 하나가 90dp 남짓이라 휴대폰 글자 크기로는 "139 시간" 이 "139 시…" 로
 * 잘렸다. 숫자를 한 단 줄인다.
 */
@Composable
private fun Overview(s: ReadingStats, narrow: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = CpTheme.metrics.gutter), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tile("오늘", s.todayMs, narrow, Modifier.weight(1f))
        Tile("이번 주", s.weekMs, narrow, Modifier.weight(1f))
        Tile("올해", s.yearMs, narrow, Modifier.weight(1f))
    }
    MonthCalendar(s)
}

@Composable
private fun Tile(label: String, ms: Long, narrow: Boolean, modifier: Modifier) {
    val c = CpTheme.colors
    val (value, unit) = tileValue(ms)
    Column(
        modifier.clip(RoundedCornerShape(CpTheme.metrics.cornerMedium)).background(c.surface).padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$label $value $unit" },
    ) {
        CpText(label, CpTheme.type.caption, c.textMuted)
        Row(verticalAlignment = Alignment.Bottom) {
            CpText(value, CpTheme.type.title.copy(fontSize = if (narrow) 19.sp else 24.sp, lineHeight = if (narrow) 25.sp else 30.sp), c.text)
            Spacer(Modifier.width(2.dp))
            CpText(unit, if (narrow) CpTheme.type.caption else CpTheme.type.subtitle, c.textMuted, Modifier.padding(bottom = 3.dp))
        }
    }
}

/**
 * 한 달 달력. 칸은 그날 읽은 시간만큼 짙다(4단, [dayLevel]). 오늘은 테두리, 아직 오지 않은 날은 흐리게. 일요일부터 늘어놓는다 —
 * 한국 달력의 차례다.
 */
@Composable
private fun MonthCalendar(s: ReadingStats) {
    val c = CpTheme.colors
    val current = YearMonth.from(s.today)
    // 달은 저장해 둔다 — 화면을 돌려도 보던 달에 남는다.
    var monthIndex by rememberSaveable { mutableLongStateOf(current.year * 12L + current.monthValue - 1) }
    val month = YearMonth.of((monthIndex / 12).toInt(), (monthIndex % 12).toInt() + 1)
    val (readDays, total) = s.monthSummary(month)
    Row(
        Modifier.fillMaxWidth().padding(start = CpTheme.metrics.gutter, end = 4.dp, top = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CpText("${month.year}년 ${month.monthValue}월", CpTheme.type.label, c.text, Modifier.weight(1f))
        CpText(if (readDays == 0) "읽은 날 없음" else "${readDays}일 읽음 · ${durationText(total)}", CpTheme.type.caption, c.textMuted)
        MonthArrow(CpIcons.Back, "앞 달", enabled = month > s.firstMonth) { monthIndex-- }
        MonthArrow(CpIcons.Forward, "다음 달", enabled = month < current) { monthIndex++ }
    }
    Column(Modifier.padding(horizontal = CpTheme.metrics.gutter)) {
        Row(Modifier.fillMaxWidth()) {
            listOf("일", "월", "화", "수", "목", "금", "토").forEach {
                CpText(it, CpTheme.type.caption, c.textMuted, Modifier.weight(1f), align = TextAlign.Center)
            }
        }
        Spacer(Modifier.height(4.dp))
        val lead = monthLead(month)
        val length = month.lengthOfMonth()
        for (week in 0 until (lead + length + 6) / 7) {
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                for (d in 0 until 7) {
                    val n = week * 7 + d - lead + 1
                    Box(Modifier.weight(1f).aspectRatio(1.25f).padding(2.dp), contentAlignment = Alignment.Center) {
                        if (n in 1..length) DayCell(month.atDay(n), s)
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(day: java.time.LocalDate, s: ReadingStats) {
    val c = CpTheme.colors
    val ms = s.days[day] ?: 0L
    val level = dayLevel(ms)
    val shape = RoundedCornerShape(6.dp)
    val future = day > s.today
    Box(
        Modifier.fillMaxSize().clip(shape)
            .background(if (level == 0) c.surface else c.accent.copy(alpha = DAY_ALPHA[level]))
            .then(if (day == s.today) Modifier.border(1.5.dp, c.text, shape) else Modifier)
            .semantics { contentDescription = "${day.monthValue}월 ${day.dayOfMonth}일 " + if (ms > 0) durationText(ms) else "안 읽음" },
        contentAlignment = Alignment.Center,
    ) {
        CpText(
            "${day.dayOfMonth}",
            CpTheme.type.caption,
            when {
                level == 3 -> c.onAccent
                future -> c.textMuted.copy(alpha = 0.5f)
                else -> c.text
            },
        )
    }
}

/** 달 넘기기 화살표. 넘길 달이 없으면 흐리게 두고 누르지 않는다(지나간 기록 앞 · 이번 달 뒤). */
@Composable
private fun MonthArrow(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    val c = CpTheme.colors
    Box(
        Modifier.size(CpTheme.metrics.touchTarget * 0.75f).clip(RoundedCornerShape(50))
            .then(if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { CpIcon(icon, if (enabled) c.textMuted else c.textMuted.copy(alpha = 0.3f), size = 20.dp) }
}

/** 올해 다 읽은 것 · 가장 오래 읽은 것 · 세는 규칙. */
@Composable
private fun Lists(s: ReadingStats, onOpenBook: (String) -> Unit, onOpenComic: (String) -> Unit, first: Boolean) {
    val c = CpTheme.colors
    var all by rememberSaveable { mutableStateOf(false) }
    val counts = listOfNotNull("책 ${s.finishedBooks}".takeIf { s.finishedBooks > 0 }, "만화 ${s.finishedComics}권".takeIf { s.finishedComics > 0 })
    if (!first) Spacer(Modifier.height(4.dp))
    CpSectionLabel(if (counts.isEmpty()) "올해 다 읽은 것" else "올해 다 읽은 것 · " + counts.joinToString(" · "))
    if (s.finished.isEmpty()) {
        CpText("올해 다 읽은 책이 아직 없습니다", CpTheme.type.subtitle, c.textMuted, Modifier.padding(horizontal = CpTheme.metrics.gutter, vertical = 8.dp))
    }
    val rows = if (all) s.finished else s.finished.take(FINISHED_PREVIEW)
    rows.forEach { r ->
        CpListRow(
            r.title,
            { r.openBook?.let(onOpenBook) ?: r.openComic?.let(onOpenComic) },
            icon = CpIcons.Book,
            subtitle = r.subtitle,
            value = monthDay(r.date),
        )
    }
    if (s.finished.size > FINISHED_PREVIEW) {
        CpTextButton(if (all) "접기" else "모두 보기", { all = !all }, Modifier.padding(start = CpTheme.metrics.gutter - 10.dp, top = 4.dp))
    }
    if (s.longest.isNotEmpty()) {
        CpDivider(Modifier.padding(top = 10.dp))
        CpSectionLabel("가장 오래 읽은 책 · 올해")
        val top = s.longest.first().millis.coerceAtLeast(1)
        s.longest.forEach { r ->
            Column(Modifier.fillMaxWidth().padding(horizontal = CpTheme.metrics.gutter, vertical = 8.dp).semantics(mergeDescendants = true) {}) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CpText(r.title, CpTheme.type.body, c.text, Modifier.weight(1f))
                    CpText(durationText(r.millis), CpTheme.type.caption, c.textMuted)
                }
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.progressTrack)) {
                    Box(Modifier.fillMaxWidth((r.millis.toFloat() / top).coerceIn(0.02f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.accent))
                }
            }
        }
    }
    // 듣기 · 자동 넘김은 읽은 시간에 넣지 않고 여기 한 줄로만(사용자 결정 1-2: 드러내되 섞지 않는다).
    val apart = listOfNotNull(
        s.listenYearMs.takeIf { it > 0 }?.let { "들은 시간 ${durationText(it)}" },
        s.autoYearMs.takeIf { it > 0 }?.let { "자동 넘김 ${durationText(it)}" },
    )
    if (apart.isNotEmpty()) {
        CpText("올해 따로 센 시간 · " + apart.joinToString(" · "), CpTheme.type.caption, c.textMuted, Modifier.padding(start = CpTheme.metrics.gutter, end = CpTheme.metrics.gutter, top = 12.dp), maxLines = 3)
    }
    CpText(
        "책장을 넘기며 읽은 시간만 셉니다. 한 쪽에 5분 넘게 머물면 5분까지만, 웹툰은 손을 멈춘 뒤 1분까지만 셉니다. 듣기 · 자동 넘김은 따로 셉니다.",
        CpTheme.type.caption, c.textMuted,
        Modifier.padding(start = CpTheme.metrics.gutter, end = CpTheme.metrics.gutter, top = if (apart.isEmpty()) 12.dp else 6.dp),
        maxLines = 5,
    )
}

/** 달력 칸의 짙기(단마다). 0 은 칸 바탕(surface) 이라 쓰지 않는다. */
private val DAY_ALPHA = floatArrayOf(0f, 0.25f, 0.5f, 0.85f)

/** "올해 다 읽은 것" 을 처음에 몇 줄 보이나. 넘으면 "모두 보기". */
private const val FINISHED_PREVIEW = 3
