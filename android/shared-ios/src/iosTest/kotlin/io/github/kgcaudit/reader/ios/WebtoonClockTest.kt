package io.github.kgcaudit.reader.ios

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 웹툰의 읽은 시간(안드로이드 `ScrollClock` 과 같은 규칙): 화면이 움직인 사이를 1분까지, 보던 화에. */
class WebtoonClockTest {
    private fun WebtoonTime?.pair() = this?.let { it.unitId to it.millis }

    @Test
    fun `time between two scrolls goes to the episode that was on screen`() {
        val c = WebtoonClock()
        // 처음 움직임은 시작점일 뿐 — 연 순간까지의 시간은 없다.
        assertNull(c.moved("1화", 1_000))
        assertEquals("1화" to 4_000L, c.moved("1화", 5_000).pair())
        // 2화로 넘어가며 움직였다: 그 사이는 앞에 보던 1화에.
        assertEquals("1화" to 2_000L, c.moved("2화", 7_000).pair())
        assertEquals("2화" to 3_000L, c.moved("2화", 10_000).pair())
    }

    @Test
    fun `a long stare counts only the last minute`() {
        val c = WebtoonClock()
        c.moved("1화", 0)
        // 펴 둔 채 10분 자리를 비웠다 — 1분만.
        assertEquals("1화" to 60_000L, c.moved("1화", 600_000).pair())
    }

    @Test
    fun `time in the background is not counted and closing counts up to the close`() {
        val c = WebtoonClock()
        c.moved("1화", 0)
        assertEquals("1화" to 20_000L, c.pause(20_000).pair())
        // 두 번 멈춰도(뒤로 감 다음 닫기) 한 번만 센다.
        assertNull(c.pause(25_000))
        // 뒤에 있던 30분은 다음 움직임에 붙지 않는다 — 돌아온 때부터.
        c.resume(1_800_000)
        assertEquals("1화" to 5_000L, c.moved("1화", 1_805_000).pair())
        // 멈춘 동안 움직임(자동 스크롤이 남아 돈 것)은 세지 않는다.
        c.pause(1_806_000)
        assertNull(c.moved("1화", 1_807_000))
    }

    @Test
    fun `a clock that goes backwards adds nothing`() {
        val c = WebtoonClock()
        c.moved("1화", 50_000)
        assertNull(c.moved("1화", 40_000))
        assertNull(c.moved("1화", 40_000))
        assertNull(WebtoonClock().pause(10_000), "never moved: nothing to count")
    }
}
