package io.github.kgcaudit.reader.ios

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 창 폭 등급(구상안 48, 안드로이드 `AdaptiveTest` 와 같은 경계). */
class AdaptiveTest {
    @Test
    fun `phones are compact and tablets are medium in portrait and expanded in landscape`() {
        assertEquals(IosAdaptive.COMPACT, IosAdaptive.widthClass(402.0))
        assertEquals(IosAdaptive.COMPACT, IosAdaptive.widthClass(599.9))
        assertEquals(IosAdaptive.MEDIUM, IosAdaptive.widthClass(600.0))
        // iPad 11인치 세로 834 · 가로 1194.
        assertEquals(IosAdaptive.MEDIUM, IosAdaptive.widthClass(834.0))
        assertEquals(IosAdaptive.EXPANDED, IosAdaptive.widthClass(840.0))
        assertEquals(IosAdaptive.EXPANDED, IosAdaptive.widthClass(1194.0))
    }

    @Test
    fun `a phone turned sideways is wide but too low for a grid or two panes`() {
        // iPhone 가로 874 × 402: 폭만 보면 넓음이지만 줄 목록이어야 한다.
        assertFalse(IosAdaptive.roomy(874.0, 402.0))
        assertFalse(IosAdaptive.twoPane(874.0, 402.0))
        assertTrue(IosAdaptive.roomy(834.0, 1194.0))
        assertFalse(IosAdaptive.twoPane(834.0, 1194.0))
        assertTrue(IosAdaptive.twoPane(1194.0, 834.0))
        assertTrue(IosAdaptive.twoPane(840.0, 480.0))
        assertFalse(IosAdaptive.twoPane(840.0, 479.0))
    }

    @Test
    fun `the grid always has at least one column`() {
        assertEquals(1, IosAdaptive.columns(50.0, 16.0))
        assertEquals(5, IosAdaptive.columns(785.0, 16.0))
        assertEquals(6, IosAdaptive.columns(802.0, 16.0))
        assertEquals(1, IosAdaptive.columns(0.0, 16.0))
    }
}
