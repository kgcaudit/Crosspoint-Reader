package io.github.kgcaudit.reader.ui.design

import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals

/** 다른 기기에서 더 읽었다는 띠의 글(결정 8-2): 언제 읽었는지를 사람이 바로 알아보는 말로. */
class SyncPromptTextTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int) = ZonedDateTime.of(y, m, d, h, min, 0, 0, seoul).toInstant().toEpochMilli()

    private val now = at(2026, 10, 8, 9, 5)

    @Test
    fun `the time reads as today, yesterday, a date this year or a date with its year`() {
        assertEquals("오늘 8:30", syncWhenText(at(2026, 10, 8, 8, 30), now, seoul))
        // 자정을 넘긴 것은 "어제" 다 — 시각만 보면 몇 시간 전과 같아도 사람은 날로 기억한다.
        assertEquals("어제 21:40", syncWhenText(at(2026, 10, 7, 21, 40), now, seoul))
        assertEquals("10월 3일 7:02", syncWhenText(at(2026, 10, 3, 7, 2), now, seoul))
        assertEquals("2025년 12월 31일", syncWhenText(at(2025, 12, 31, 23, 0), now, seoul))
    }

    @Test
    fun `the title keeps the device name apart from the particle`() {
        assertEquals("갤럭시 탭 S9 에서 더 읽었습니다", syncPromptTitle("갤럭시 탭 S9"))
    }
}
