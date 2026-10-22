package io.github.kgcaudit.reader.pdf

import kotlin.test.Test
import kotlin.test.assertEquals

/** 폭 맞춤의 화면 이름표. 이름표는 안드로이드 화면 코드라 PageViewportTest(공통)에서 떼었다. */
class ScreenLabelTest {
    @Test
    fun `the label names the first and the last screen and counts the ones between`() {
        assertEquals("18쪽 · 첫 화면 (1/3)", screenLabel(17, 1, 3))
        assertEquals("18쪽 · 2번째 화면 (2/3)", screenLabel(17, 2, 3))
        assertEquals("18쪽 · 끝 화면 (3/3)", screenLabel(17, 3, 3))
    }
}
