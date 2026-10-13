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

    @Test
    fun `a wide first picture keeps only the front cover, right for left-to-right and left for right-to-left`() {
        // 앞 · 뒤표지를 한 장에 스캔한 것(1400×1000). 왼→오 책은 오른쪽 반이 앞표지, 책등 쪽 2%(28px)를 더 깎는다.
        assertEquals(728 until 1400, CoverCrop.front(1400, 1000, rightToLeft = false))
        // 오→왼(일본 만화)은 왼쪽 반이 앞표지다. 같은 쪽을 쓰면 뒤표지(바코드 · 줄거리)가 서재에 선다.
        assertEquals(0 until 672, CoverCrop.front(1400, 1000, rightToLeft = true))
    }

    @Test
    fun `a picture at or below the spread threshold stays whole`() {
        // 펼침면 나누기와 같은 문턱(1.15). 딱 1.15 는 펼침면이 아니다 — 조금 넓은 표지 · 정사각 그림을 반으로 자르면 제목이 잘린다.
        assertEquals(0 until 1150, CoverCrop.front(1150, 1000, rightToLeft = false))
        assertEquals(0 until 1150, CoverCrop.front(1150, 1000, rightToLeft = true))
        // 보통 세로 표지 · 웹툰 띠도 그대로.
        assertEquals(0 until 700, CoverCrop.front(700, 1000, rightToLeft = false))
        assertEquals(0 until 800, CoverCrop.front(800, 9000, rightToLeft = true))
        // 문턱을 막 넘으면 자른다 — 문턱 비교가 뒤집혀 있으면 여기서 걸린다.
        assertTrue(CoverCrop.front(1160, 1000, rightToLeft = false).first > 0)
    }

    @Test
    fun `a jacket with flaps keeps a cover-shaped piece at the front edge`() {
        // 날개까지 펼친 겉싸개(2500×1000). 반으로만 자르면 1250×1000 — 여전히 가로로 길어 표지 칸에서 납작하다.
        val ltr = CoverCrop.front(2500, 1000, rightToLeft = false)
        val rtl = CoverCrop.front(2500, 1000, rightToLeft = true)
        // 표지 칸 비율(세로 ÷ 가로 1.45)이라 세로로 길다.
        assertEquals(690, ltr.last - ltr.first + 1)
        assertEquals(690, rtl.last - rtl.first + 1)
        // 앞 가장자리에 붙되 가장 바깥 2%(50px, 날개 끝)는 뺀다.
        assertEquals(2500 - 50 - 1, ltr.last)
        assertEquals(50, rtl.first)
    }

    @Test
    fun `broken sizes give the whole picture instead of a crash`() {
        // 머리가 깨져 크기가 0 · 음수면 자르지 않는다 — 빈 범위면 부르는 쪽이 통째로 쓴다.
        assertEquals(0 until 0, CoverCrop.front(0, 1000, rightToLeft = false))
        assertTrue(CoverCrop.front(-5, 100, rightToLeft = true).isEmpty())
        assertEquals(0 until 1400, CoverCrop.front(1400, 0, rightToLeft = false))
        assertEquals(0 until 1400, CoverCrop.front(1400, -3, rightToLeft = true))
    }
}
