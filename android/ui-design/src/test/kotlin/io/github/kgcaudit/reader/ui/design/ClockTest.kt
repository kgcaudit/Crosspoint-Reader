package io.github.kgcaudit.reader.ui.design

import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals

class ClockTest {
    private val seoul = TimeZone.getTimeZone("Asia/Seoul")
    private val saved = Locale.getDefault()

    @After
    fun restore() = Locale.setDefault(saved)

    @Test
    fun `the footer clock says 오전 and 오후 even on a phone set to English`() {
        // 휴대폰 언어가 영어여도 한국어 화면 한가운데 "AM" 이 나오지 않는다.
        Locale.setDefault(Locale.US)
        val morning = 1_700_000_000_000L - 1_700_000_000_000L % 86_400_000L // 09:00 서울
        assertEquals("오전 9:00", formatClock(morning, twentyFour = false, zone = seoul))
        assertEquals("오후 9:00", formatClock(morning + 12 * 3_600_000L, twentyFour = false, zone = seoul))
    }

    @Test
    fun `a 24 hour phone shows the hour without 오전 or 오후`() {
        val morning = 1_700_000_000_000L - 1_700_000_000_000L % 86_400_000L
        assertEquals("21:00", formatClock(morning + 12 * 3_600_000L, twentyFour = true, zone = seoul))
    }
}
