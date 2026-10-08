package io.github.kgcaudit.reader.ui.design

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 독서 기록(0.51.0)의 시계. 읽는 속도와 달리 시간을 버리지 않고 자른다 — 한 쪽에 오래 머문 사람도 읽은 사람이다. 대신 건너뛰기 ·
 * 기계가 넘긴 시간 · 화면을 끈 시간은 "읽은 시간" 에 들어가면 안 된다.
 */
class PageClockTest {

    private fun pages() = PageClock<Int> { before, after -> after == before + 1 }

    @Test
    fun `a page turned by hand counts its whole time and a long stay is cut to five minutes`() {
        val c = pages()
        assertNull(c.shown(0, TurnBy.HAND, now = 0))
        // 3초 만에 넘긴 쪽도 센다(속도는 버리는 짧은 쪽이다).
        assertEquals(PageTime(TurnBy.HAND, 3_000), c.shown(1, TurnBy.HAND, now = 3_000))
        // 펴 둔 채 20분 — 5분까지만. 버리지 않는다.
        assertEquals(PageTime(TurnBy.HAND, PageClock.CAP_MS), c.shown(2, TurnBy.HAND, now = 3_000 + 20 * 60_000))
    }

    @Test
    fun `turning back by hand counts but a jump does not`() {
        val c = pages()
        c.shown(5, TurnBy.HAND, now = 0)
        assertEquals(PageTime(TurnBy.HAND, 10_000), c.shown(4, TurnBy.HAND, now = 10_000), "앞 쪽으로 넘긴 것을 세지 않았다")
        // 목차로 40쪽에 갔다 — 앞 쪽에 머문 시간은 목차를 고르던 시간이다.
        assertNull(c.shown(40, TurnBy.HAND, now = 70_000))
        assertEquals(PageTime(TurnBy.HAND, 20_000), c.shown(41, TurnBy.HAND, now = 90_000))
    }

    @Test
    fun `auto turning is counted apart and listening is not counted here`() {
        val c = pages()
        c.shown(0, TurnBy.AUTO, now = 0)
        assertEquals(PageTime(TurnBy.AUTO, 15_000), c.shown(1, TurnBy.AUTO, now = 15_000))
        // 자동 넘김을 껐다 — 쪽은 그대로, 여기까지가 자동 넘김의 시간이다.
        assertEquals(PageTime(TurnBy.AUTO, 5_000), c.shown(1, TurnBy.HAND, now = 20_000))
        assertEquals(PageTime(TurnBy.HAND, 30_000), c.shown(2, TurnBy.HAND, now = 50_000))
        // 듣기가 넘긴 쪽은 쪽 시계가 세지 않는다(앱이 듣기 상태로 센다 — 두 번 세지 않게).
        assertEquals(PageTime(TurnBy.HAND, 1_000), c.shown(2, TurnBy.LISTEN, now = 51_000))
        assertNull(c.shown(3, TurnBy.LISTEN, now = 60_000))
    }

    @Test
    fun `the clock stops while the screen is off and closing the book counts the last page`() {
        val c = pages()
        c.shown(0, TurnBy.HAND, now = 0)
        // 30초 읽고 화면을 껐다.
        assertEquals(PageTime(TurnBy.HAND, 30_000), c.pause(now = 30_000))
        // 8시간 뒤 켜서 10초 읽고 넘겼다 — 꺼져 있던 8시간은 들어가지 않는다.
        c.resume(now = 8 * 3_600_000L)
        assertEquals(PageTime(TurnBy.HAND, 10_000), c.shown(1, TurnBy.HAND, now = 8 * 3_600_000L + 10_000))
        // 다시 멈춤이 두 번 와도(화면 끔 · 닫음) 한 번만 센다.
        assertEquals(PageTime(TurnBy.HAND, 5_000), c.pause(now = 8 * 3_600_000L + 15_000))
        assertNull(c.pause(now = 9 * 3_600_000L))
    }

    @Test
    fun `a page changed while the screen is off is timed from when the screen comes back`() {
        val c = pages()
        c.shown(0, TurnBy.HAND, now = 0)
        c.pause(now = 1_000)
        assertNull(c.shown(1, TurnBy.HAND, now = 50_000))
        c.resume(now = 100_000)
        assertEquals(PageTime(TurnBy.HAND, 4_000), c.shown(2, TurnBy.HAND, now = 104_000))
    }

    @Test
    fun `a clock running backwards adds nothing`() {
        val c = pages()
        c.shown(0, TurnBy.HAND, now = 50_000)
        assertNull(c.shown(1, TurnBy.HAND, now = 10_000))
    }

    @Test
    fun `a webtoon counts the time between movements up to one minute`() {
        val c = ScrollClock<String>()
        assertNull(c.moved("1화", now = 0))
        assertEquals("1화" to 20_000L, c.moved("1화", now = 20_000))
        // 다음 화로 내려왔다 — 앞 움직임 때 보던 화(1화)에 붙는다.
        assertEquals("1화" to 10_000L, c.moved("2화", now = 30_000))
        // 10분 동안 손을 대지 않았다: 1분만.
        assertEquals("2화" to ScrollClock.WINDOW_MS, c.moved("2화", now = 630_000))
        assertEquals("2화" to 5_000L, c.pause(now = 635_000))
        c.resume(now = 9_000_000)
        assertEquals("2화" to 2_000L, c.moved("2화", now = 9_002_000))
    }
}
