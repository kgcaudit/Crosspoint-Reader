package io.github.kgcaudit.reader.document.comic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarginTrimTest {

    private val paper = 0xFFF8F6F0.toInt()
    private val ink = 0xFF101010.toInt()

    /** 바탕 [background] 위에 (l, t)–(r, b) 칸을 [fill] 로 채운 쪽. */
    private fun page(w: Int, h: Int, l: Int, t: Int, r: Int, b: Int, background: Int = paper, fill: Int = ink): IntArray =
        IntArray(w * h) { i -> val x = i % w; val y = i / w; if (x in l until r && y in t until b) fill else background }

    @Test
    fun `a scanned page loses its paper margins but keeps a thin edge around the panels`() {
        // 200×300 쪽, 사방 20 · 30 픽셀 여백(10%).
        val box = assertNotNull(MarginTrim.find(200, 300, page(200, 300, 20, 30, 180, 270)))
        // 여유는 그 변 길이의 1% — 칸 테두리가 화면 끝에 붙지 않는다.
        assertEquals(MarginTrim.Box(18, 27, 182, 273), box)
    }

    @Test
    fun `no side is cut deeper than fifteen percent`() {
        // 위 여백이 40%인 쪽(제목 쪽처럼 그림이 아래에 몰렸다) — 흰 바탕의 그림까지 먹어 들어가지 않게 15%에서 멈춘다.
        val box = assertNotNull(MarginTrim.find(200, 300, page(200, 300, 10, 120, 190, 290)))
        assertTrue(box.top <= 45, "위를 ${box.top} 줄 걷었다(한도 45)")
        assertTrue(box.left >= 0 && box.left <= 30)
    }

    @Test
    fun `a full-bleed page is left alone`() {
        // 넷이 다 다른 펼침 그림: 바탕을 정할 수 없다.
        val w = 200; val h = 300
        val colors = intArrayOf(0xFF2060C0.toInt(), 0xFFC03020.toInt(), 0xFF20A040.toInt(), 0xFFE0C020.toInt())
        val pixels = IntArray(w * h) { i -> colors[(if (i % w < w / 2) 0 else 1) + (if (i / w < h / 2) 0 else 2)] }
        assertNull(MarginTrim.find(w, h, pixels))
    }

    @Test
    fun `a sky reaching the top edge is not taken for a margin`() {
        // 위 두 모서리가 같은 하늘색, 아래는 땅 — 하늘 띠를 여백으로 걷으면 그림 위가 잘린다.
        val sky = 0xFF80B8E8.toInt()
        assertNull(MarginTrim.find(200, 300, page(200, 300, 0, 120, 200, 300, background = sky, fill = 0xFF406020.toInt())))
    }

    @Test
    fun `dust specks in the margin do not stop the trim`() {
        val w = 200; val h = 300
        val pixels = page(w, h, 20, 30, 180, 270)
        // 위 여백 곳곳에 먼지 한 점씩.
        for (y in 0 until 30 step 3) pixels[y * w + (y * 7) % w] = ink
        val box = assertNotNull(MarginTrim.find(w, h, pixels))
        assertEquals(27, box.top)
    }

    @Test
    fun `a page number in the margin is never cut away`() {
        val w = 200; val h = 300
        val pixels = page(w, h, 20, 30, 180, 260)
        // 쪽 번호: 아래 여백 오른쪽 끝 가까이 8×8.
        for (y in 280 until 288) for (x in 186 until 194) pixels[y * w + x] = ink
        val box = assertNotNull(MarginTrim.find(w, h, pixels))
        assertTrue(box.right >= 194 && box.bottom >= 288, "쪽 번호가 잘렸다: $box")
        // 쪽 번호가 없는 왼쪽 · 위는 그대로 걷힌다.
        assertEquals(18, box.left)
        assertEquals(27, box.top)
    }

    @Test
    fun `a dark scan background is trimmed too`() {
        val box = assertNotNull(MarginTrim.find(200, 300, page(200, 300, 20, 30, 180, 270, background = 0xFF050505.toInt(), fill = paper)))
        assertEquals(MarginTrim.Box(18, 27, 182, 273), box)
    }

    @Test
    fun `a blank page and a page with almost no margin are not cut`() {
        assertNull(MarginTrim.find(200, 300, IntArray(200 * 300) { paper }))
        // 사방 1픽셀 여백: 걷어 봐야 2%도 안 된다.
        assertNull(MarginTrim.find(200, 300, page(200, 300, 1, 1, 199, 299)))
    }

    @Test
    fun `broken input gives no trim instead of a crash`() {
        assertNull(MarginTrim.find(200, 300, IntArray(10)))
        assertNull(MarginTrim.find(0, 0, IntArray(0)))
        assertNull(MarginTrim.find(4, 4, IntArray(16)))
    }
}
