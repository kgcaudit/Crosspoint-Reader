package io.github.kgcaudit.reader.ui.design

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 남은 시간(E5): 읽는 속도를 기기에서 재고, 믿을 만할 때만 보인다. */
class ReadingSpeedTest {

    @Test
    fun `the time left stays blank until a few pages have been read`() {
        // 첫 쪽 하나로 센 남은 시간은 거의 틀린다.
        val speed = ReadingSpeed()
        speed.record(600.0, 60_000)
        speed.record(600.0, 60_000)
        assertNull(speed.minutesFor(6_000.0))
        speed.record(600.0, 60_000)
        assertEquals(10, speed.minutesFor(6_000.0), "분당 600자 → 6000자에 10분")
    }

    @Test
    fun `skimmed pages and pages left open are ignored`() {
        // 훑어 넘긴 쪽(1초) · 펴 둔 채 자리를 뜬 쪽(30분)을 넣으면 남은 시간이 몇 분에서 몇 시간으로 튄다.
        val speed = ReadingSpeed(unitsPerMinute = 600.0, samples = 3)
        assertFalse(speed.record(600.0, 1_000))
        assertFalse(speed.record(600.0, 30 * 60_000L))
        assertEquals(600.0, speed.unitsPerMinute)
        assertTrue(speed.record(600.0, 120_000))
        assertTrue(speed.unitsPerMinute!! < 600.0, "천천히 읽은 쪽이 속도를 조금 낮춘다")
    }

    @Test
    fun `minutes read as a person would say them`() {
        assertEquals("1분 미만", minutesText(0))
        assertEquals("4분", minutesText(4))
        assertEquals("2시간", minutesText(120))
        assertEquals("2시간 10분", minutesText(130))
    }
}
