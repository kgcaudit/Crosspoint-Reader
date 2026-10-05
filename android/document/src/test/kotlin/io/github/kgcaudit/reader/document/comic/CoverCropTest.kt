package io.github.kgcaudit.reader.document.comic

import io.github.kgcaudit.reader.document.image.ImageSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoverCropTest {

    private val white = 0xFFFFFFFF.toInt()
    private val ink = 0xFF202020.toInt()

    private fun row(color: Int, width: Int = 8) = IntArray(width) { color }

    @Test
    fun `only a picture much taller than the cover slot is cropped`() {
        assertTrue(CoverCrop.isTall(ImageSize(800, 9000)), "웹툰 띠를 자르지 않았다")
        // 보통 만화 표지(1:1.45 ~ 1:1.6)는 그대로 — 자르면 제목이 날아간다.
        assertFalse(CoverCrop.isTall(ImageSize(1000, 1450)))
        assertFalse(CoverCrop.isTall(ImageSize(1000, 1600)))
        assertFalse(CoverCrop.isTall(null), "크기를 모르는데 잘랐다")
    }

    @Test
    fun `the cover starts at the first panel, not at the blank top`() {
        // 웹툰 첫 그림은 흰 바탕 몇백 줄 뒤에 첫 칸이 나온다 — 그 흰 바탕을 표지로 쓰면 빈 표지다.
        val rows = List(10) { row(white) } + listOf(row(ink)) + List(5) { row(white) }
        assertEquals(10, CoverCrop.firstContentRow(rows))
    }

    @Test
    fun `a dark background is the background, and a near white JPEG row is still blank`() {
        val black = 0xFF000000.toInt()
        val rows = List(4) { row(black) } + listOf(IntArray(8) { if (it == 3) white else black })
        assertEquals(4, CoverCrop.firstContentRow(rows), "검은 바탕 작품에서 바탕을 내용으로 봤다")
        // JPEG 의 흰 바탕은 250 언저리로 흔들린다 — 그것까지 내용으로 보면 늘 0 줄에서 시작한다.
        val jpeg = listOf(row(white), row(0xFFF6F8F5.toInt()), row(ink))
        assertEquals(2, CoverCrop.firstContentRow(jpeg))
    }

    @Test
    fun `a picture that is all background starts at the top, and no rows means the top`() {
        assertEquals(0, CoverCrop.firstContentRow(List(6) { row(white) }))
        assertEquals(0, CoverCrop.firstContentRow(emptyList()))
    }

    @Test
    fun `the window has the cover shape, a small margin above, and stays inside the picture`() {
        val size = ImageSize(800, 9000)
        val w = CoverCrop.window(size, contentTop = 400)
        assertEquals(1160, w.last - w.first + 1, "표지 비율이 아니다")
        assertEquals(400 - 24, w.first, "첫 칸 위 여백")
        // 내용이 맨 아래에 붙어도 그림 밖으로 나가지 않는다.
        val bottom = CoverCrop.window(size, contentTop = 8990)
        assertEquals(8999, bottom.last)
        assertEquals(1160, bottom.last - bottom.first + 1)
        // 표지 비율보다 짧은 그림(잘못 불렀을 때)은 그림 전체.
        assertEquals(0..99, CoverCrop.window(ImageSize(800, 100), 50))
    }
}
