package io.github.kgcaudit.reader.reflow

import org.junit.Test
import kotlin.test.assertEquals

class ReaderPrefsTest {

    @Test
    fun `with publisher fonts on, text the book leaves unstyled uses the phone font`() {
        // 글꼴 목록의 약속("책이 정하지 않은 곳은 휴대폰 글꼴")을 조판이 지킨다. 사용자 글꼴을 골라 둔
        // 사람이 출판사 글꼴을 켜도, 책이 정하지 않은 문단이 그 사용자 글꼴로 나오면 안 된다.
        val prefs = ReaderPrefs(font = "user:gyeonggi", publisherFonts = true)
        assertEquals(null, prefs.bodyFont(bookFontsInUse = true))
    }

    @Test
    fun `turning publisher fonts off brings back the chosen font`() {
        // 고른 글꼴은 지우지 않는다. 출판사 글꼴이 없는 책이거나 끄면 그 글꼴로 조판한다.
        val prefs = ReaderPrefs(font = "user:gyeonggi", publisherFonts = true)
        assertEquals("user:gyeonggi", prefs.bodyFont(bookFontsInUse = false))
        assertEquals("user:gyeonggi", prefs.copy(publisherFonts = false).bodyFont(bookFontsInUse = false))
    }
}
