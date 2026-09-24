package io.github.kgcaudit.reader.ui.design

import org.junit.Test
import kotlin.test.assertEquals

/** 지면을 누른 자리 → 동작. 터치 영역 설정과 오른쪽 위 모서리(책갈피)를 본다. */
class TouchZonesTest {

    // 폭 1000px, 모서리 네모 150px.
    private fun TouchZones.at(x: Float, y: Float = 500f) = actionAt(x, y, width = 1000f, cornerPx = 150f)

    @Test
    fun `the default zones turn back on the left and forward on the right`() {
        assertEquals(TapAction.Previous, TouchZones.Default.at(100f))
        assertEquals(TapAction.Menu, TouchZones.Default.at(500f))
        assertEquals(TapAction.Next, TouchZones.Default.at(900f))
    }

    @Test
    fun `reversed zones swap the two sides but keep the menu in the middle`() {
        assertEquals(TapAction.Next, TouchZones.Reversed.at(100f))
        assertEquals(TapAction.Menu, TouchZones.Reversed.at(500f))
        assertEquals(TapAction.Previous, TouchZones.Reversed.at(900f))
    }

    @Test
    fun `one hand mode turns forward on either side`() {
        // 엄지가 닿는 쪽이 어디든 다음 쪽. 앞 쪽은 밀어서 간다(누르기로는 없다).
        assertEquals(TapAction.Next, TouchZones.OneHand.at(100f))
        assertEquals(TapAction.Next, TouchZones.OneHand.at(900f))
        assertEquals(TapAction.Menu, TouchZones.OneHand.at(500f))
    }

    @Test
    fun `the top right corner toggles the bookmark in every mode`() {
        for (zones in TouchZones.entries) assertEquals(TapAction.Bookmark, zones.at(950f, 50f), zones.name)
    }

    @Test
    fun `only the small corner square takes the bookmark, the rest of the right edge still turns the page`() {
        // 네모를 조금이라도 벗어나면 다음 쪽이다. 넓히면 넘기려던 손가락이 책갈피를 꽂는다.
        assertEquals(TapAction.Next, TouchZones.Default.at(950f, 151f))
        assertEquals(TapAction.Next, TouchZones.Default.at(849f, 50f))
    }

    @Test
    fun `the footer summary names the chosen items and the default matches the old status line`() {
        assertEquals("책 제목 · 쪽 · %", Footer().summary)
        assertEquals("시계 · 배터리", Footer(FooterItem.Clock, FooterItem.None, FooterItem.Battery).summary)
        assertEquals("없음", Footer(FooterItem.None, FooterItem.None, FooterItem.None).summary)
    }

    @Test
    fun `two pages open in landscape by default and in portrait only on a wide screen`() {
        val prefs = ScreenPrefs()
        // 휴대폰 가로(851×393): 기본 켬. 휴대폰 세로: 끔.
        assertEquals(true, prefs.twoPages(851f, 393f, smallestWidthDp = 393))
        assertEquals(false, prefs.twoPages(393f, 851f, smallestWidthDp = 393))
        // 세로 두쪽을 켜도 휴대폰에서는 한 쪽이다(한 쪽 170dp 남짓 — 한 줄에 여덟 글자).
        val portrait = prefs.copy(twoPagesPortrait = true)
        assertEquals(false, portrait.twoPages(393f, 851f, smallestWidthDp = 393))
        // 태블릿 세로에서는 두 쪽.
        assertEquals(true, portrait.twoPages(820f, 1180f, smallestWidthDp = 820))
        // 가로 두쪽을 끄면 가로도 한 쪽.
        assertEquals(false, prefs.copy(twoPagesLandscape = false).twoPages(851f, 393f, smallestWidthDp = 393))
    }
}
