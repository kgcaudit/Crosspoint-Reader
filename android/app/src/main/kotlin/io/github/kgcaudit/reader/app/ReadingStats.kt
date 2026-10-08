package io.github.kgcaudit.reader.app

import io.github.kgcaudit.reader.data.TimeItem
import io.github.kgcaudit.reader.data.TimeMode
import io.github.kgcaudit.reader.data.TimeRow
import io.github.kgcaudit.reader.data.library.ShelfBook
import io.github.kgcaudit.reader.document.comic.ComicProgress
import io.github.kgcaudit.reader.document.comic.ComicUnit
import io.github.kgcaudit.reader.document.comic.ShelfMark
import io.github.kgcaudit.reader.document.comic.Work
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * "올해 다 읽은 것" 한 줄. [open] 은 누르면 여는 것(책 id 또는 만화 단위 id) — 서재에서 사라진 것 · 작품 통째(손으로 다 읽음)는
 * null 이라 누를 데가 없다.
 */
internal data class FinishedRow(
    val title: String,
    val subtitle: String,
    val date: LocalDate,
    val openBook: String? = null,
    val openComic: String? = null,
)

/** "가장 오래 읽은 책" 막대 하나. 만화는 작품으로 묶는다 — 권마다 줄을 세우면 한 작품이 목록을 다 차지한다. */
internal data class LongestRow(val title: String, val millis: Long)

/**
 * 독서 기록 화면이 보이는 것(0.51.0). 모든 시간은 **손으로 넘기며 읽은 시간(READ)** 이다 — 듣기 · 자동 넘김은 [listenYearMs] ·
 * [autoYearMs] 로 따로 둔다(사용자 결정 1-2).
 *
 * 주는 일요일에 시작한다. 한국 달력이 일요일부터 늘어놓아, 월요일로 시작하면 달력의 한 줄과 "이번 주" 가 어긋난다.
 */
internal class ReadingStats(
    val today: LocalDate,
    val todayMs: Long,
    val weekMs: Long,
    val yearMs: Long,
    val listenYearMs: Long,
    val autoYearMs: Long,
    /** 날마다 읽은 시간(READ). 달력이 쓴다. */
    val days: Map<LocalDate, Long>,
    /** 달력을 거슬러 갈 수 있는 첫 달(읽은 기록이 있는 가장 이른 달, 없으면 이번 달). */
    val firstMonth: YearMonth,
    val finished: List<FinishedRow>,
    val finishedBooks: Int,
    val finishedComics: Int,
    val longest: List<LongestRow>,
) {
    /** 보일 것이 하나도 없다 — 빈 화면 안내를 띄운다. */
    val isEmpty: Boolean get() = days.isEmpty() && listenYearMs == 0L && autoYearMs == 0L && finished.isEmpty()

    /** [month] 에 읽은 날 수와 합. */
    fun monthSummary(month: YearMonth): Pair<Int, Long> {
        val inMonth = days.filterKeys { YearMonth.from(it) == month }.values.filter { it > 0 }
        return inMonth.size to inMonth.sum()
    }

    companion object {
        /** 이번 주의 첫날(일요일). */
        fun weekStart(day: LocalDate): LocalDate = day.minusDays((day.dayOfWeek.value % 7).toLong())

        fun of(
            rows: List<TimeRow>,
            books: List<ShelfBook>,
            works: List<Work>,
            comicProgress: Map<String, ComicProgress>,
            /** 서재에서 사라진 권의 이름(작품 목록에 없다). */
            hiddenUnits: Map<String, ComicUnit>,
            today: LocalDate,
            zone: ZoneId,
        ): ReadingStats {
            val read = rows.filter { it.mode == TimeMode.READ && it.millis > 0 }
            val yearStart = today.withDayOfYear(1)
            fun sum(from: LocalDate, mode: TimeMode? = TimeMode.READ) =
                rows.filter { it.mode == mode && it.day in from..today }.sumOf { it.millis }
            val days = read.groupBy { it.day }.mapValues { (_, r) -> r.sumOf { it.millis } }

            // 만화 단위 → (작품, 줄). 사본으로 읽은 권도 같은 작품에 묶는다.
            val placeOf = HashMap<String, Pair<Work, io.github.kgcaudit.reader.document.comic.WorkEntry>>()
            for (w in works) for (e in w.entries) {
                placeOf[e.unit.id] = w to e
                e.copies.forEach { placeOf[it.id] = w to e }
            }
            val timeOf = read.groupBy { it.item to it.id }.mapValues { (_, r) -> r.sumOf { it.millis } }
            fun dayOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

            val finished = ArrayList<FinishedRow>()
            var finishedBooks = 0
            var finishedComics = 0
            for (shelf in books) {
                val at = shelf.finishedAtEpochMs?.let(::dayOf) ?: continue
                if (at.year != today.year) continue
                finishedBooks++
                val ms = timeOf[TimeItem.BOOK to shelf.book.id.value] ?: 0L
                finished += FinishedRow(
                    title = shelf.book.label,
                    subtitle = listOfNotNull(shelf.book.author?.takeIf { it.isNotBlank() }, ms.takeIf { it > 0 }?.let(::durationText))
                        .joinToString(" · ").ifEmpty { "책" },
                    date = at,
                    openBook = shelf.book.id.value,
                )
            }
            // 손으로 "다 읽음" 으로 옮긴 작품(종이책으로 읽음 등)은 작품 한 줄로 — 그 작품의 권 줄은 따로 세우지 않는다(두 번 세지 않게).
            val markedWorks = works.filter { w -> (w.shelfMark as? ShelfMark.Finished)?.let { dayOf(it.atEpochMs).year == today.year } == true }
            val markedKeys = markedWorks.mapTo(HashSet()) { it.key }
            for (w in markedWorks) {
                val mark = w.shelfMark as ShelfMark.Finished
                val ids = w.entries.flatMap { e -> listOf(e.unit.id) + e.copies.map { it.id } }
                val ms = ids.sumOf { timeOf[TimeItem.COMIC to it] ?: 0L }
                finishedComics += mark.volumes
                finished += FinishedRow(
                    title = w.title,
                    subtitle = listOfNotNull("만화 ${mark.volumes}권", ms.takeIf { it > 0 }?.let(::durationText)).joinToString(" · "),
                    date = dayOf(mark.atEpochMs),
                )
            }
            for ((unitId, p) in comicProgress) {
                val at = p.finishedAtEpochMs?.let(::dayOf) ?: continue
                if (at.year != today.year) continue
                val place = placeOf[unitId]
                if (place != null && place.first.key in markedKeys) continue
                val title = place?.let { (w, e) -> listOf(w.title, e.label).filter { it.isNotBlank() }.distinct().joinToString(" ") }
                    ?: hiddenUnits[unitId]?.name
                    ?: continue
                finishedComics++
                val ms = timeOf[TimeItem.COMIC to unitId] ?: 0L
                finished += FinishedRow(
                    title = title,
                    subtitle = listOfNotNull("만화", ms.takeIf { it > 0 }?.let(::durationText)).joinToString(" · "),
                    date = at,
                    openComic = unitId.takeIf { place != null },
                )
            }

            // 올해 가장 오래 읽은 것: 책은 권마다, 만화는 작품마다. 이름을 모르는 것(받은 파일)은 뺀다.
            val bookLabels = books.associate { it.book.id.value to it.book.label }
            val longest = HashMap<String, LongestRow>()
            for (r in read) {
                if (r.day < yearStart || r.day > today) continue
                val (key, title) = when (r.item) {
                    TimeItem.BOOK -> ("b:" + r.id) to (bookLabels[r.id] ?: continue)
                    TimeItem.COMIC -> placeOf[r.id]?.let { (w, _) -> ("w:" + w.key) to w.title }
                        ?: (("c:" + r.id) to (hiddenUnits[r.id]?.name ?: continue))
                }
                longest[key] = LongestRow(title, (longest[key]?.millis ?: 0L) + r.millis)
            }

            val first = days.keys.minOrNull()?.let(YearMonth::from)?.takeIf { it < YearMonth.from(today) } ?: YearMonth.from(today)
            return ReadingStats(
                today = today,
                todayMs = sum(today),
                weekMs = sum(weekStart(today)),
                yearMs = sum(yearStart),
                listenYearMs = sum(yearStart, TimeMode.LISTEN),
                autoYearMs = sum(yearStart, TimeMode.AUTO),
                days = days,
                firstMonth = first,
                finished = finished.sortedByDescending { it.date },
                finishedBooks = finishedBooks,
                finishedComics = finishedComics,
                longest = longest.values.sortedByDescending { it.millis }.take(LONGEST_ROWS),
            )
        }

        private const val LONGEST_ROWS = 5
    }
}

/** 타일 값: 1시간 아래는 분("42" 분), 위는 시간을 소수 한 자리("2.1" 시간), 100시간부터는 정수("128" 시간). */
internal fun tileValue(ms: Long): Pair<String, String> {
    val minutes = ms / 60_000
    if (minutes < 60) return minutes.toString() to "분"
    val hours = ms / 3_600_000.0
    return (if (hours >= 100) hours.toLong().toString() else String.format(java.util.Locale.ROOT, "%.1f", Math.floor(hours * 10) / 10)) to "시간"
}

/** "6시간 40분" · "38분" · "2시간". 1분이 안 되면 "1분 미만" — 0분이라 하면 읽은 것이 없는 것처럼 보인다. */
internal fun durationText(ms: Long): String {
    val minutes = ms / 60_000
    if (minutes == 0L) return "1분 미만"
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0L -> "${m}분"
        m == 0L -> "${h}시간"
        else -> "${h}시간 ${m}분"
    }
}

/** "10월 3일". */
internal fun monthDay(day: LocalDate): String = "${day.monthValue}월 ${day.dayOfMonth}일"

/** 달력의 짙기: 0(안 읽음) · 1(20분 아래) · 2(40분 아래) · 3. */
internal fun dayLevel(ms: Long): Int = when {
    ms <= 0 -> 0
    ms < 20 * 60_000L -> 1
    ms < 40 * 60_000L -> 2
    else -> 3
}

/** 달력 한 달의 칸: 앞에 비우는 칸 수(일요일 시작)와 날 수. */
internal fun monthLead(month: YearMonth): Int = month.atDay(1).dayOfWeek.let { if (it == DayOfWeek.SUNDAY) 0 else it.value }
