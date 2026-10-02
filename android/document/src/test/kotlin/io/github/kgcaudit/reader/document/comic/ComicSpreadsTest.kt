package io.github.kgcaudit.reader.document.comic

import io.github.kgcaudit.reader.document.image.ImageSize
import kotlin.test.Test
import kotlin.test.assertEquals

class ComicSpreadsTest {

    private val p = ImageSize(1200, 1800)
    private val w = ImageSize(2400, 1800)

    @Test
    fun `the cover stands alone and the rest face each other`() {
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3, 4), listOf(5)), ComicSpreads.of(List(6) { p }))
        assertEquals(listOf(listOf(0, 1), listOf(2, 3)), ComicSpreads.of(List(4) { p }, coverAlone = false))
    }

    @Test
    fun `a wide spread picture stands alone and pairing starts again after it`() {
        // 1–2 짝, 3 펼침면 혼자, 4–5 짝(3 다음부터 다시 센다 — 이어 세면 4 가 혼자 남고 5–6 이 짝이 되어 어긋난다).
        val sizes = listOf(p, p, p, w, p, p, p)
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3), listOf(4, 5), listOf(6)), ComicSpreads.of(sizes))
        // 펼침면 바로 앞 쪽은 짝이 없으면 혼자.
        assertEquals(listOf(listOf(0), listOf(1), listOf(2), listOf(3, 4)), ComicSpreads.of(listOf(p, p, w, p, p)))
    }

    @Test
    fun `unknown sizes pair like portrait pages and nothing is lost`() {
        val sizes = listOf(p, null, p, null, w)
        val spreads = ComicSpreads.of(sizes)
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3), listOf(4)), spreads)
        assertEquals((0..4).toList(), spreads.flatten())
        assertEquals(emptyList(), ComicSpreads.of(emptyList()))
    }

    @Test
    fun `finding the spread of a page`() {
        val spreads = ComicSpreads.of(List(6) { p })
        assertEquals(1, ComicSpreads.indexOf(spreads, 2))
        assertEquals(0, ComicSpreads.indexOf(spreads, -4))
        assertEquals(3, ComicSpreads.indexOf(spreads, 99))
        assertEquals(0, ComicSpreads.indexOf(emptyList(), 3))
    }
}
