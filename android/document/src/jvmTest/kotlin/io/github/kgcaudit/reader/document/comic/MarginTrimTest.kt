package io.github.kgcaudit.reader.document.comic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    private fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `pale picture backgrounds are never taken for paper`() {
        // 먼작귀 컬러판에서 뽑은 바탕색들. 하늘(D6EEF2)은 채널 차가 28 이라 옛 기준(40)에서 종이로 쳐져 하늘이 걷혔다.
        val pictures = mapOf(
            "하늘" to rgb(214, 238, 242),
            "연한 파랑" to rgb(160, 209, 242),
            "연한 풀빛" to rgb(210, 228, 168),
            "분홍" to rgb(244, 179, 203),
            "연보라" to rgb(222, 210, 240),
            "살구" to rgb(250, 215, 205),
        )
        for ((name, c) in pictures) assertFalse(MarginTrim.paper(c), "$name 바탕을 종이로 봤다")
    }

    @Test
    fun `white, grey, scanner black and yellowed paper still count as paper`() {
        val papers = mapOf(
            "흰색" to rgb(255, 255, 255),
            "JPEG 흰색" to rgb(250, 251, 249),
            "연회색" to rgb(224, 224, 224),
            "스캐너 검정" to rgb(20, 19, 17),
            "바랜 종이" to rgb(233, 221, 188),
            "옅게 바랜 종이" to rgb(240, 232, 216),
        )
        for ((name, c) in papers) assertTrue(MarginTrim.paper(c), "$name 을 종이로 보지 않았다")
    }

    @Test
    fun `a full-colour page with a pale sky at the top keeps its sky`() {
        // 위 1/3 이 연한 하늘, 그 아래 풀밭 — 위 두 모서리가 같은 하늘색이라 옛 판은 위를 15% 걷었다.
        val sky = rgb(214, 238, 242)
        val grass = rgb(210, 228, 168)
        assertNull(MarginTrim.find(200, 300, page(200, 300, 0, 100, 200, 300, background = sky, fill = grass)))
    }

    @Test
    fun `a scan on yellowed paper is still trimmed`() {
        val yellowed = rgb(233, 221, 188)
        val box = assertNotNull(MarginTrim.find(200, 300, page(200, 300, 20, 30, 180, 270, background = yellowed)))
        assertEquals(MarginTrim.Box(18, 27, 182, 273), box)
    }

    @Test
    fun `pages shown together share the smaller trim on every side`() {
        // 왼쪽 여백이 넓은 쪽과 위 여백이 넓은 쪽 — 변마다 덜 걷은 쪽을 따른다(어느 쪽도 그림을 잃지 않게).
        val a = MarginTrim.Frame(0.10f, 0.02f, 0.95f, 0.97f)
        val b = MarginTrim.Frame(0.04f, 0.08f, 0.90f, 0.99f)
        assertEquals(MarginTrim.Frame(0.04f, 0.02f, 0.95f, 0.99f), MarginTrim.shared(listOf(a, b)))
    }

    @Test
    fun `a full-bleed partner keeps the whole spread untrimmed`() {
        // 짝이 그림으로 꽉 찬 쪽이면 아무 쪽도 자르지 않는다 — 한 쪽만 커지면 이음매 그림이 어긋난다.
        val margins = MarginTrim.Frame(0.1f, 0.1f, 0.9f, 0.9f)
        assertTrue(MarginTrim.shared(listOf(margins, MarginTrim.Frame.WHOLE)).whole)
        // 합쳐서 2% 도 안 걷히면 자르지 않는다.
        assertTrue(MarginTrim.shared(listOf(MarginTrim.Frame(0.005f, 0f, 1f, 1f), MarginTrim.Frame(0f, 0.005f, 0.995f, 1f))).whole)
        assertTrue(MarginTrim.shared(emptyList()).whole)
    }

    @Test
    fun `a found box becomes the same fractions at any size`() {
        val f = MarginTrim.Frame.of(MarginTrim.Box(18, 27, 182, 273), 200, 300)
        assertEquals(MarginTrim.Frame(0.09f, 0.09f, 0.91f, 0.91f), f)
        assertTrue(MarginTrim.Frame.of(null, 200, 300).whole)
    }
}
