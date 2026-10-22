package io.github.kgcaudit.reader.ios

import kotlin.test.Test
import kotlin.test.assertEquals

/** 터치 영역(구상안 37). 안드로이드 `TouchZonesTest` 와 같은 성질 — 두 앱에서 같은 자리를 누르면 같은 쪽으로 간다. */
class TouchZonesTest {
    private val left = 0.1
    private val middle = 0.5
    private val right = 0.9

    @Test
    fun `the default turns back on the left and forward on the right`() {
        assertEquals(-1, TouchZones.STANDARD.step(left, false))
        assertEquals(1, TouchZones.STANDARD.step(right, false))
        assertEquals(0, TouchZones.STANDARD.step(middle, false), "the middle opens the menu")
    }

    @Test
    fun `the middle forty percent is always the menu`() {
        for (z in TouchZones.all) for (rtl in listOf(false, true)) {
            assertEquals(0, z.step(0.31, rtl))
            assertEquals(0, z.step(0.69, rtl))
            assertEquals(if (z == TouchZones.ONE_HAND) 1 else if ((z == TouchZones.REVERSED) == rtl) -1 else 1, z.step(0.29, rtl))
        }
    }

    @Test
    fun `reversed swaps the sides`() {
        assertEquals(1, TouchZones.REVERSED.step(left, false))
        assertEquals(-1, TouchZones.REVERSED.step(right, false))
    }

    @Test
    fun `a right-to-left comic mirrors the sides`() {
        assertEquals(1, TouchZones.STANDARD.step(left, true), "the left is the next page in a right-to-left work")
        assertEquals(-1, TouchZones.STANDARD.step(right, true))
        assertEquals(-1, TouchZones.REVERSED.step(left, true))
        assertEquals(1, TouchZones.REVERSED.step(right, true))
    }

    @Test
    fun `one hand always goes forward even in a right-to-left comic`() {
        // 안드로이드 0.54.8 에서 고친 것: 오→왼 만화에서 한 손으로 누르면 앞 쪽으로 갔다.
        for (rtl in listOf(false, true)) {
            assertEquals(1, TouchZones.ONE_HAND.step(left, rtl))
            assertEquals(1, TouchZones.ONE_HAND.step(right, rtl))
        }
    }

    @Test
    fun `the words follow what the taps do`() {
        assertEquals("왼쪽 앞 쪽 · 오른쪽 다음 쪽", TouchZones.STANDARD.description(false))
        assertEquals("오른쪽 앞 쪽 · 왼쪽 다음 쪽", TouchZones.STANDARD.description(true))
        assertEquals("왼쪽 다음 쪽 · 오른쪽 앞 쪽", TouchZones.REVERSED.description(false))
        assertEquals("어느 쪽을 눌러도 다음 쪽 · 앞 쪽은 오른쪽으로 밀어서", TouchZones.ONE_HAND.description(false))
        assertEquals("어느 쪽을 눌러도 다음 쪽 · 앞 쪽은 왼쪽으로 밀어서", TouchZones.ONE_HAND.description(true))
        // 글과 동작이 어긋나지 않는다: 설명에서 "다음 쪽" 앞에 적힌 쪽을 누르면 다음 쪽이다.
        for (z in listOf(TouchZones.STANDARD, TouchZones.REVERSED)) for (rtl in listOf(false, true)) {
            val nextSide = z.description(rtl).split(" · ").first { it.endsWith("다음 쪽") }.substringBefore(' ')
            assertEquals(1, z.step(if (nextSide == "왼쪽") left else right, rtl), "$z rtl=$rtl")
        }
    }

    @Test
    fun `an unknown saved number falls back to the default`() {
        assertEquals(TouchZones.STANDARD, TouchZones.of(-1))
        assertEquals(TouchZones.STANDARD, TouchZones.of(7))
        assertEquals(TouchZones.ONE_HAND, TouchZones.of(2))
    }
}
