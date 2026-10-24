package io.github.kgcaudit.reader.reflow

import org.junit.Test
import kotlin.test.assertEquals

/** 화면 아래 쪽 표시(0.60.0). */
class PageLabelTest {

    @Test
    fun `pages still being counted show an ellipsis instead of a wrong number`() {
        // 큰 TXT 를 뒤에서 세는 동안(사용자 결정: "…"). 0 이나 조각 안 번호를 보이면 몇 초 뒤 수가 튄다.
        assertEquals("12 / …", pageLabel(12, null, twoPages = false))
        assertEquals("… / …", pageLabel(null, null, twoPages = false))
        assertEquals("12 / 1,480", pageLabel(12, 1480, twoPages = false))
    }

    @Test
    fun `two pages side by side show both numbers`() {
        assertEquals("5–6 / 12", pageLabel(5, 12, twoPages = true))
        assertEquals("… / …", pageLabel(null, null, twoPages = true))
    }
}
