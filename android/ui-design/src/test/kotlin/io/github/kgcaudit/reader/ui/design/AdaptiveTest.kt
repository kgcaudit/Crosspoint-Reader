package io.github.kgcaudit.reader.ui.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 창 폭 등급 · 두 판(0.46.0, docs/RESPONSIVE_PLAN.md). */
class AdaptiveTest {

    @Test
    fun `width classes follow material 3 breakpoints`() {
        assertEquals(CpWidthClass.Compact, widthClassOf(393))
        assertEquals(CpWidthClass.Compact, widthClassOf(599))
        assertEquals(CpWidthClass.Medium, widthClassOf(600))
        assertEquals(CpWidthClass.Medium, widthClassOf(839))
        assertEquals(CpWidthClass.Expanded, widthClassOf(840))
        // 깨진 값(0 · 음수)은 좁음 — 두 판이 펴져 왼쪽 판만 남는 것보다 낫다.
        assertEquals(CpWidthClass.Compact, widthClassOf(-1))
    }

    @Test
    fun `two panes open only on a wide and tall window`() {
        // 펼친 폴더블 가로(약 900×680) · 태블릿: 두 판.
        assertTrue(twoPaneOf(900, 680))
        // 휴대폰 · 바깥 화면을 가로로 돌린 것(폭 851, 높이 393): 폭은 넓어도 두 판이 아니다.
        assertFalse(twoPaneOf(851, 393))
        // 펼친 폴더블 세로(약 680×900): 중간 — 한 판.
        assertFalse(twoPaneOf(680, 900))
    }

    @Test
    fun `a short window keeps the phone list even when it is wide`() {
        // 휴대폰 가로(851×393): 격자 · 두 판 모두 아님 — 줄 목록.
        assertFalse(roomyOf(851, 393))
        assertTrue(roomyOf(680, 900))
        assertTrue(roomyOf(900, 680))
        assertFalse(roomyOf(393, 851))
    }
}
