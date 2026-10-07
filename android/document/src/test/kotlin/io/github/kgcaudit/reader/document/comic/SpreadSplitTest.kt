package io.github.kgcaudit.reader.document.comic

import io.github.kgcaudit.reader.document.comic.SpreadSplit.Half
import io.github.kgcaudit.reader.document.comic.SpreadSplit.Part
import io.github.kgcaudit.reader.document.image.ImageSize
import kotlin.test.Test
import kotlin.test.assertEquals

class SpreadSplitTest {

    private val tall = ImageSize(1000, 1450)
    private val spread = ImageSize(2000, 1450)

    @Test
    fun `a spread becomes two parts, the reading side first`() {
        val sizes = listOf(tall, spread, tall)
        assertEquals(listOf(Part(0, Half.WHOLE), Part(1, Half.LEFT), Part(1, Half.RIGHT), Part(2, Half.WHOLE)), SpreadSplit.parts(sizes, rightToLeft = false))
        // 오→왼 책은 오른쪽 반이 먼저 — 일본 만화 펼침면의 앞 쪽은 오른쪽이다.
        assertEquals(listOf(Part(0, Half.WHOLE), Part(1, Half.RIGHT), Part(1, Half.LEFT), Part(2, Half.WHOLE)), SpreadSplit.parts(sizes, rightToLeft = true))
    }

    @Test
    fun `a slightly wide page, a square picture and an unknown size are not cut`() {
        // 망가뜨린 경우: 조금 넓은 표지(1.1) · 정사각 · 크기를 모르는 깨진 쪽.
        val sizes = listOf(ImageSize(1100, 1000), ImageSize(1000, 1000), null)
        assertEquals(SpreadSplit.whole(3), SpreadSplit.parts(sizes, rightToLeft = false))
    }

    @Test
    fun `a book page maps to the first part showing it, so the place never slides`() {
        val parts = SpreadSplit.parts(listOf(tall, spread, tall), rightToLeft = false)
        assertEquals(listOf(0, 1, 3), (0..2).map { SpreadSplit.partOf(parts, it) })
        // 책 끝을 넘어선 쪽은 마지막 조각 · 빈 책은 0.
        assertEquals(3, SpreadSplit.partOf(parts, 9))
        assertEquals(0, SpreadSplit.partOf(emptyList(), 2))
        assertEquals(ImageSize(1000, 1450), SpreadSplit.sizeOf(parts[1], spread))
        assertEquals(tall, SpreadSplit.sizeOf(parts[0], tall))
    }
}
