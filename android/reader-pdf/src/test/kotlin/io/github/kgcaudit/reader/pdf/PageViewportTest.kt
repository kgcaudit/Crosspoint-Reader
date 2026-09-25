package io.github.kgcaudit.reader.pdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PageViewportTest {

    // 휴대폰 세로 화면(1080×2000)에 A4 세로(0.707).
    private val a4 = PageViewport.fit(1080f, 2000f, 0.7071f)

    @Test
    fun `a whole portrait page fits the width and sits in the middle`() {
        // 폭에 맞추고 위아래로 남는 곳은 똑같이 나눈다. 한쪽에 붙으면 넘길 때마다 쪽이 흔들려 보인다.
        assertEquals(1080f, a4.width, 0.5f)
        assertEquals((2000f - a4.height) / 2, a4.top, 0.5f)
        assertEquals(0f, a4.left, 0.5f)
        assertFalse(a4.isZoomed)
    }

    @Test
    fun `a landscape slide fits the width and is not cut at the bottom`() {
        val slide = PageViewport.fit(1080f, 2000f, 16f / 9f)
        assertEquals(1080f, slide.width, 0.5f)
        assertTrue(slide.height <= 2000f)
        // 가로 화면에 세로 쪽이면 높이에 맞춘다.
        val tablet = PageViewport.fit(2000f, 1080f, 0.7071f)
        assertEquals(1080f, tablet.height, 0.5f)
        assertEquals((2000f - tablet.width) / 2, tablet.left, 0.5f)
    }

    @Test
    fun `zooming keeps the point under the fingers in place`() {
        // 벌린 손가락 사이의 글자가 달아나면 확대할 때마다 찾던 곳을 다시 찾아야 한다.
        val focusX = 700f
        val focusY = 1100f
        val pageX = (focusX - a4.left) / a4.width
        val pageY = (focusY - a4.top) / a4.height
        val zoomed = a4.zoom(2f, focusX, focusY)
        assertEquals(focusX, zoomed.left + pageX * zoomed.width, 1f)
        assertEquals(focusY, zoomed.top + pageY * zoomed.height, 1f)
    }

    @Test
    fun `the page can never be dragged off the screen`() {
        val zoomed = a4.zoom(3f, 540f, 1000f)
        val far = zoomed.pan(10_000f, 10_000f)
        assertEquals(0f, far.left, 0.5f)
        assertEquals(0f, far.top, 0.5f)
        val other = zoomed.pan(-10_000f, -10_000f)
        assertEquals(1080f - other.width, other.left, 0.5f)
        assertEquals(2000f - other.height, other.top, 0.5f)
        // 확대하지 않은 쪽은 끌어도 가운데 그대로다.
        assertEquals(a4, a4.pan(300f, -300f))
    }

    @Test
    fun `zoom stays between the whole page and the limit`() {
        assertEquals(1f, a4.zoom(0.2f, 0f, 0f).scale)
        assertEquals(PageViewport.MAX_SCALE, a4.zoom(100f, 0f, 0f).scale)
    }

    @Test
    fun `double tap zooms in and a second double tap shows the whole page again`() {
        val zoomed = a4.toggleZoom(540f, 1000f)
        assertEquals(PageViewport.DOUBLE_TAP_SCALE, zoomed.scale)
        assertEquals(a4, zoomed.toggleZoom(10f, 10f))
    }

    @Test
    fun `the visible region is the part of the page on screen`() {
        assertEquals(PageRegion.WHOLE, a4.visible())
        // 왼쪽 위 구석을 2배로: 가로는 절반, 세로는 화면 높이만큼만 보인다.
        val corner = a4.zoom(2f, 0f, 0f).pan(10_000f, 10_000f)
        val region = corner.visible()
        assertEquals(0f, region.left, 0.001f)
        assertEquals(0f, region.top, 0.001f)
        assertEquals(0.5f, region.right, 0.001f)
        assertEquals((2000f / corner.height).coerceAtMost(1f), region.bottom, 0.001f)
    }

    @Test
    fun `a page with an unreadable size is shown as a4 instead of failing`() {
        // 깨진 쪽 하나 때문에 화면 계산이 NaN 이 되면 그 뒤로 아무것도 그려지지 않는다.
        for (bad in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val fit = PageViewport.fit(1080f, 2000f, bad)
            assertEquals(PageViewport.DEFAULT_ASPECT, fit.pageAspect)
            assertTrue(fit.width.isFinite() && fit.height.isFinite())
        }
    }

    // ── 폭 맞춤(③) ─────────────────────────────────────────────────
    // 가로 휴대폰(2200×1000)에 A4 세로. 쪽 전체로는 폭이 707px 뿐이라 글자가 읽히지 않는다.
    private val landscape = PageViewport.fitWidth(2200f, 1000f, 0.7071f)

    @Test
    fun `fit width fills the screen width and starts at the top of the page`() {
        assertEquals(2200f, landscape.width, 0.5f)
        assertEquals(0f, landscape.left, 0.5f)
        assertEquals(0f, landscape.top, 0.5f)
        // 폭 맞춤이 곧 쉬는 크기다 — "확대 중" 이면 두 번 누르기가 쪽 전체로 줄여 버린다.
        assertFalse(landscape.isZoomed)
        assertTrue(landscape.atTop)
        assertFalse(landscape.atBottom)
    }

    @Test
    fun `stepping forward moves one screen less a little overlap and stops at the page end`() {
        val second = landscape.scroll(true)!!
        // 한 화면(1000) 에서 겹침(10%)을 뺀 만큼. 겹치지 않으면 화면 끝에 걸친 줄이 두 번 다 반만 보인다.
        assertEquals(-900f, second.top, 0.5f)
        var v = second
        var steps = 1
        while (true) { v = v.scroll(true) ?: break; steps++ }
        assertTrue(v.atBottom)
        assertEquals(1000f - v.height, v.top, 0.5f)
        // 쪽 높이 3111 → 900 씩 세 번이면 끝. 끝에서는 더 내려가지 않고 null — 그때 쪽을 넘긴다.
        assertEquals(3, steps)
        assertEquals(null, v.scroll(true))
        assertEquals(null, landscape.scroll(false))
    }

    @Test
    fun `the position counts screens and the last screen is always the last`() {
        assertEquals(1 to 4, landscape.screen())
        assertEquals(2 to 4, landscape.scroll(true)!!.screen())
        val bottom = PageViewport.fitWidth(2200f, 1000f, 0.7071f, fromBottom = true)
        assertTrue(bottom.atBottom)
        assertEquals(4 to 4, bottom.screen())
        // 한 화면에 다 들어가는 쪽(세로 휴대폰에 A4)은 옮길 곳이 없다.
        val portrait = PageViewport.fitWidth(1080f, 2000f, 0.7071f)
        assertEquals(1 to 1, portrait.screen())
        assertEquals(null, portrait.scroll(true))
        assertEquals("18쪽 · 첫 화면 (1/3)", screenLabel(17, 1, 3))
        assertEquals("18쪽 · 2번째 화면 (2/3)", screenLabel(17, 2, 3))
        assertEquals("18쪽 · 끝 화면 (3/3)", screenLabel(17, 3, 3))
    }

    @Test
    fun `double tap in fit width zooms from the fitted size and comes back to it, not to the whole page`() {
        val lower = landscape.scroll(true)!!
        val zoomed = lower.toggleZoom(1100f, 500f)
        assertTrue(zoomed.isZoomed)
        assertEquals(lower.base * PageViewport.DOUBLE_TAP_SCALE, zoomed.scale, 0.001f)
        val back = zoomed.toggleZoom(1100f, 500f)
        assertFalse(back.isZoomed)
        assertEquals(2200f, back.width, 0.5f)
        // 누른 점을 붙잡고 줄이므로 읽던 높이 근처에 남는다(쪽 머리로 튀지 않는다).
        assertEquals(lower.top, back.top, 1f)
        // 두 손가락으로 줄여도 폭 맞춤보다 작아지지 않는다.
        assertEquals(lower.base, lower.zoom(0.1f, 0f, 0f).scale, 0.001f)
    }

    @Test
    fun `a wide slide in fit width is the same as the whole page`() {
        // 가로 슬라이드는 폭에 맞추면 곧 쪽 전체다. 폭 맞춤으로 두었다고 슬라이드가 잘리면 안 된다.
        val slide = PageViewport.fitWidth(1080f, 2000f, 16f / 9f)
        assertEquals(PageViewport.fit(1080f, 2000f, 16f / 9f).width, slide.width, 0.5f)
        assertEquals(1f, slide.base)
        assertEquals(1 to 1, slide.screen())
    }
}
