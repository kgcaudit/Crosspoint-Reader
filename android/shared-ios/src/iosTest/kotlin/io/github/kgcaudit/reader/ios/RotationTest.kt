package io.github.kgcaudit.reader.ios

import kotlin.test.Test
import kotlin.test.assertEquals

/** 화면 회전(구상안 45). */
class RotationTest {
    @Test
    fun `the buttons are auto then portrait then landscape like Android`() {
        assertEquals(listOf("자동", "세로", "가로"), IosRotation.LABELS)
        assertEquals("세로", IosRotation.LABELS[IosRotation.PORTRAIT])
        assertEquals("가로", IosRotation.LABELS[IosRotation.LANDSCAPE])
    }

    @Test
    fun `a phone keeps the chosen direction`() {
        assertEquals(IosRotation.PORTRAIT, IosRotation.applied(IosRotation.PORTRAIT, phone = true))
        assertEquals(IosRotation.LANDSCAPE, IosRotation.applied(IosRotation.LANDSCAPE, phone = true))
    }

    @Test
    fun `an unknown saved value turns with the phone instead of trapping it in one direction`() {
        assertEquals(IosRotation.AUTO, IosRotation.applied(7, phone = true))
        assertEquals(IosRotation.AUTO, IosRotation.applied(-1, phone = true))
    }

    @Test
    fun `an iPad never locks even when the value came from a phone`() {
        // 창 크기를 바꾸는 iPad 앱은 방향을 고정할 수 없다 — 걸어 두면 나란히 보기에서 화면이 어긋난다.
        assertEquals(IosRotation.AUTO, IosRotation.applied(IosRotation.LANDSCAPE, phone = false))
    }
}
