package io.github.kgcaudit.reader.ui.design

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 읽는 속도는 사람이 읽고 넘긴 쪽으로만 잰다. 자동 넘김 · 듣기 · 건너뛰기가 섞이면 하단의 "남은 시간" 이 사람의 속도가
 * 아니라 기계의 속도가 된다.
 */
class TurnSamplerTest {

    /** 쪽 번호가 열쇠인 리더(PDF 한 쪽 보기와 같다). */
    private fun pages() = TurnSampler<Int> { before, after -> after == before + 1 }

    @Test
    fun `a page read and turned forward by hand counts with its own length and time`() {
        val s = pages()
        assertNull(s.shown(0, 300.0, driven = false, now = 0), "처음 연 쪽은 앞 쪽이 없다")
        val dwell = s.shown(1, 280.0, driven = false, now = 40_000)
        // 앞 쪽(300자)을 40초 동안 읽었다 — 새 쪽의 분량(280자)이 아니다.
        assertEquals(ReadDwell(300.0, 40_000), dwell)
        assertEquals(ReadDwell(280.0, 30_000), s.shown(2, 100.0, driven = false, now = 70_000))
    }

    @Test
    fun `pages turned by auto turn or by listening are not counted`() {
        val s = pages()
        s.shown(0, 300.0, driven = true, now = 0)
        // 자동 넘김 15초마다 — 15초는 사람이 읽은 시간이 아니라 고른 초다.
        assertNull(s.shown(1, 300.0, driven = true, now = 15_000))
        assertNull(s.shown(2, 300.0, driven = true, now = 30_000))
    }

    @Test
    fun `a page the machine turned away is not counted even if a person was reading it`() {
        // 사람이 읽던 쪽에서 듣기를 켜자 듣기가 곧바로 다음 쪽으로 따라 넘겼다(같은 프레임). 사람이 다 읽고 넘긴 것이 아니다.
        val s = pages()
        s.shown(0, 300.0, driven = false, now = 0)
        assertNull(s.shown(1, 300.0, driven = true, now = 40_000))
    }

    @Test
    fun `after the machine stops the page is timed again from that moment`() {
        val s = pages()
        s.shown(0, 300.0, driven = true, now = 0)
        // 60초 동안 듣다가 듣기를 끄고(같은 쪽), 20초 더 읽고 넘겼다 — 센 것은 사람이 읽은 20초뿐이다.
        assertNull(s.shown(0, 300.0, driven = false, now = 60_000))
        assertEquals(ReadDwell(300.0, 20_000), s.shown(1, 300.0, driven = false, now = 80_000))
    }

    @Test
    fun `a page that arrived while the machine was turning is not counted even if a person turns it on`() {
        // 듣기가 넘겨 준 쪽에서 듣기를 끄는 그 순간과 넘김이 겹치면(같은 프레임) 머문 시간의 대부분이 들은 시간이다.
        val s = pages()
        s.shown(0, 300.0, driven = true, now = 0)
        assertNull(s.shown(1, 300.0, driven = false, now = 40_000))
    }

    @Test
    fun `jumps by contents, search or the progress bar are not counted`() {
        val s = pages()
        s.shown(3, 300.0, driven = false, now = 0)
        // 40초 뒤 목차로 9쪽에 간다(앞으로지만 바로 다음 쪽이 아니다). 뒤로 간 것도 세지 않는다.
        assertNull(s.shown(9, 300.0, driven = false, now = 40_000))
        assertNull(s.shown(2, 300.0, driven = false, now = 80_000))
        // 건너뛴 곳에서 다시 손으로 넘긴 것은 센다.
        assertEquals(ReadDwell(300.0, 30_000), s.shown(3, 300.0, driven = false, now = 110_000))
    }

    @Test
    fun `skimmed pages and pages left open are dropped`() {
        val s = pages()
        s.shown(0, 300.0, driven = false, now = 0)
        assertNull(s.shown(1, 300.0, driven = false, now = ReadingSpeed.MIN_MILLIS - 1), "훑어 넘긴 쪽")
        assertNull(s.shown(2, 300.0, driven = false, now = ReadingSpeed.MIN_MILLIS - 1 + ReadingSpeed.MAX_MILLIS + 1), "펴 둔 채 자리를 뜬 쪽")
        // 글이 없는 쪽(그림만 · 빈 장)은 분량이 0 — 넘겨도 속도에 넣을 것이 없다.
        s.shown(3, 0.0, driven = false, now = ReadingSpeed.MAX_MILLIS + 60_000)
        assertNull(s.shown(4, 300.0, driven = false, now = ReadingSpeed.MAX_MILLIS + 90_000), "빈 쪽")
    }

    @Test
    fun `a spread counts both pages once when the next spread is shown`() {
        // 두쪽보기: 열쇠는 보이는 쪽들, 분량은 두 쪽을 합친 것. 다음 펼침은 오른쪽 쪽 바로 뒤에서 시작한다.
        val s = TurnSampler<List<Int>> { before, after -> after.first() == before.last() + 1 }
        s.shown(listOf(0), 1.0, driven = false, now = 0)
        assertEquals(ReadDwell(1.0, 10_000), s.shown(listOf(1, 2), 2.0, driven = false, now = 10_000))
        assertEquals(ReadDwell(2.0, 50_000), s.shown(listOf(3, 4), 2.0, driven = false, now = 60_000))
        // 같은 쪽이 다시 묶이면(두쪽보기를 켬) 넘긴 것이 아니다.
        assertNull(s.shown(listOf(4, 5), 2.0, driven = false, now = 100_000))
    }

    @Test
    fun `nothing changes when the same page is reported again`() {
        val s = pages()
        s.shown(0, 300.0, driven = false, now = 0)
        assertNull(s.shown(0, 300.0, driven = false, now = 5_000))
        // 다시 알린 것이 시계를 되감지 않았다 — 처음 보인 때부터 센다.
        assertEquals(ReadDwell(300.0, 30_000), s.shown(1, 300.0, driven = false, now = 30_000))
    }
}
