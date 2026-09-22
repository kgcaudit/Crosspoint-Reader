package io.github.kgcaudit.reader.document

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocatorTest {

    @Test
    fun `reflow locator survives an encode decode round trip`() {
        val original = Locator.Reflow(spine = 12, charOffset = 34_567)
        assertEquals(original, Locator.decodeOrNull(Locator.encode(original)))
    }

    @Test
    fun `fixed page locator survives an encode decode round trip`() {
        val original = Locator.FixedPage(page = 7, xPermille = 250, yPermille = 1000)
        assertEquals(original, Locator.decodeOrNull(Locator.encode(original)))
    }

    @Test
    fun `encoding is stable so stored bookmarks keep resolving across releases`() {
        // 이 형식이 바뀌면 사용자의 저장된 책갈피와 진도가 전부 해석 불가가 된다.
        // 바꿔야 한다면 마이그레이션을 함께 넣어야 하므로, 형식을 테스트로 고정한다.
        assertEquals("r:3:900", Locator.encode(Locator.Reflow(3, 900)))
        assertEquals("p:4:10:20", Locator.encode(Locator.FixedPage(4, 10, 20)))
    }

    @Test
    fun `start is the first character for reflow formats and the first page for pdf`() {
        assertEquals(Locator.Reflow(0, 0), Locator.start(BookFormat.EPUB))
        assertEquals(Locator.Reflow(0, 0), Locator.start(BookFormat.TXT))
        assertEquals(Locator.FixedPage(0), Locator.start(BookFormat.PDF))
    }

    @Test
    fun `malformed input decodes to null rather than throwing`() {
        // 저장된 값이 상해도 라이브러리 전체가 못 열리는 일은 없어야 한다.
        val malformed = listOf(
            "", "r", "r:1", "r:1:2:3", "p:1", "p:1:2", "p:1:2:3:4",
            "x:1:2", "r:a:b", "r:-1:0", "p:0:2000:0", "p:0:0:-5", "r:1:2 ",
        )
        malformed.forEach { assertNull(Locator.decodeOrNull(it)) }
    }

    @Test
    fun `negative and out of range components are rejected at construction`() {
        assertFailsWith<IllegalArgumentException> { Locator.Reflow(spine = -1, charOffset = 0) }
        assertFailsWith<IllegalArgumentException> { Locator.Reflow(spine = 0, charOffset = -1) }
        assertFailsWith<IllegalArgumentException> { Locator.FixedPage(page = -1) }
        assertFailsWith<IllegalArgumentException> { Locator.FixedPage(page = 0, xPermille = 1001) }
        assertFailsWith<IllegalArgumentException> { Locator.FixedPage(page = 0, yPermille = -1) }
    }

    @Test
    fun `boundary values round trip`() {
        listOf(
            Locator.Reflow(0, 0),
            Locator.Reflow(Int.MAX_VALUE, Int.MAX_VALUE),
            Locator.FixedPage(0, 0, 0),
            Locator.FixedPage(Int.MAX_VALUE, 1000, 1000),
        ).forEach { assertEquals(it, Locator.decodeOrNull(Locator.encode(it))) }
    }
}
