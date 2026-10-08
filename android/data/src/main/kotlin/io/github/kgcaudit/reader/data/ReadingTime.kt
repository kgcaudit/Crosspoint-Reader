package io.github.kgcaudit.reader.data

import io.github.kgcaudit.reader.data.db.ReaderDatabase
import io.github.kgcaudit.reader.data.db.ReadingTimeEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 무엇을 읽었나. 책(만화 뷰어로 본 책도)은 책 id, 만화는 만화 단위 id 로 적는다. */
enum class TimeItem { BOOK, COMIC }

/**
 * 읽은 방식(사용자 결정 1-2). 손으로 넘기며 읽은 것만 "읽은 시간" 이다 — 듣기 · 자동 넘김은 따로 적어, 책을 틀어 놓고 잠든
 * 밤이 "8시간 읽음" 이 되지 않게 한다.
 */
enum class TimeMode { READ, LISTEN, AUTO }

/** 하루 합 한 칸. [mode] 를 모르면(다음 판이 더한 방식을 옛 판이 읽음) null — 행은 지우지 않고 셈에서만 뺀다. */
data class TimeRow(val item: TimeItem, val id: String, val day: LocalDate, val mode: TimeMode?, val millis: Long)

/**
 * 독서 기록의 저장소(0.51.0). 리더가 잰 시간을 하루 합으로 더한다.
 *
 * 날은 **적는 때의** 이 휴대폰 시간대로 정한다. 자정을 넘겨 읽은 한 쪽은 넘긴 때의 날에 들어간다 — 쪽 하나(최대 5분)를 두 날로
 * 쪼갤 만큼 정확할 까닭이 없다.
 */
class ReadingTime(
    private val db: ReaderDatabase,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val dao = db.readingTime()

    /**
     * [millis] 를 더한다. 0 이하(시계가 뒤로 감)는 버리고, 하루([DAY_MS])보다 큰 값은 하루로 자른다 — 시계를 앞으로 돌리거나
     * 잠든 사이 시각이 뛰어 생긴 값이 "올해 900시간" 을 만들지 않게(규칙 6).
     */
    suspend fun record(item: TimeItem, id: String, mode: TimeMode, millis: Long, atEpochMs: Long) {
        if (millis <= 0 || id.isEmpty()) return
        dao.add(item.name, id, dayOf(atEpochMs).toEpochDay(), mode.name, millis.coerceAtMost(DAY_MS), DAY_MS)
    }

    fun rows(): Flow<List<TimeRow>> = dao.observeAll().map { rows -> rows.mapNotNull(::toRow) }

    suspend fun all(): List<TimeRow> = dao.all().mapNotNull(::toRow)

    fun dayOf(epochMs: Long): LocalDate = Instant.ofEpochMilli(epochMs).atZone(zone()).toLocalDate()

    private fun toRow(e: ReadingTimeEntity): TimeRow? {
        // 모르는 갈래는 가리킬 데가 없다(무엇을 읽었는지 모름). 모르는 방식은 행을 두고 셈에서만 뺀다.
        val item = TimeItem.entries.firstOrNull { it.name == e.itemKind } ?: return null
        val day = runCatching { LocalDate.ofEpochDay(e.day) }.getOrNull() ?: return null
        return TimeRow(item, e.itemId, day, TimeMode.entries.firstOrNull { it.name == e.mode }, e.millis.coerceIn(0, DAY_MS))
    }

    companion object {
        const val DAY_MS: Long = 24 * 60 * 60_000L
    }
}
