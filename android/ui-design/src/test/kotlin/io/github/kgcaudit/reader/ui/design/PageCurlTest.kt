package io.github.kgcaudit.reader.ui.design

import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 말림 그물(0.32.0)과 넘김 소리의 조건. 그물은 순수 계산이라 기기 없이 모양을 잰다. */
class PageCurlTest {

    private val w = 1080f
    private val h = 2340f

    /** 그물의 (열, 행) 꼭짓점. */
    private fun at(mesh: FloatArray, col: Int, row: Int): Pair<Float, Float> {
        val i = (row * (CURL_COLS + 1) + col) * 2
        return mesh[i] to mesh[i + 1]
    }

    private fun flat(col: Int, row: Int) = w * col / CURL_COLS to h * row / CURL_ROWS

    @Test
    fun `before the turn starts the page lies flat and nothing is curled`() {
        // 넘기기 전에 모서리가 들려 보이면 누르기만 했는데 쪽이 찢긴 것처럼 보인다.
        val g = curlGeometry(w, h, 0f)
        for (row in 0..CURL_ROWS) for (col in 0..CURL_COLS) {
            val (x, y) = at(g.front, col, row)
            val (fx, fy) = flat(col, row)
            assertTrue(abs(x - fx) < 0.01f && abs(y - fy) < 0.01f, "($col,$row) 가 움직였다: $x,$y / $fx,$fy")
        }
        // 뒷면은 원통 꼭대기 한 줄로 모여 넓이가 없다 — 보이지 않는다. 소수 계산 오차(수 제곱픽셀)만 허용한다.
        assertTrue(area(g.back) < w * h * 1e-5f, "넘기기 전인데 뒷면이 보인다: 넓이 ${area(g.back)}")
    }

    @Test
    fun `paper left of the fold stays exactly where it was`() {
        // 접힌 줄 왼쪽은 아직 책상 위의 종이다. 거기 글자가 흔들리면 읽던 줄을 놓친다.
        for (t in listOf(0.2f, 0.5f, 0.8f)) {
            val g = curlGeometry(w, h, t)
            for (row in 0..CURL_ROWS) for (col in 0..CURL_COLS) {
                val (fx, fy) = flat(col, row)
                val d = (fx - g.foldX) * g.nx + (fy - g.foldY) * g.ny
                if (d > -1f) continue
                val (x, y) = at(g.front, col, row)
                assertTrue(abs(x - fx) < 0.01f && abs(y - fy) < 0.01f, "t=$t ($col,$row) 가 접힌 줄 왼쪽인데 움직였다")
            }
        }
    }

    @Test
    fun `the bottom right corner lifts first because that is where the finger holds the page`() {
        val g = curlGeometry(w, h, 0.15f)
        val (cx, cy) = at(g.back, CURL_COLS, CURL_ROWS)
        val (tx, ty) = at(g.front, CURL_COLS, 0)
        // 아래 모서리는 원통을 넘어 왼쪽 위로 젖혀졌고, 위 모서리는 아직 거의 제자리다.
        assertTrue(cx < w - 50f && cy < h, "아래 모서리가 들리지 않았다: $cx,$cy")
        assertTrue(abs(tx - w) < w * 0.12f && abs(ty) < 1f, "위 모서리가 먼저 움직였다: $tx,$ty")
    }

    @Test
    fun `mid turn the turned over paper lies on the reader's side of the fold`() {
        // 뒷면은 접힌 줄 너머(왼쪽)에 눕는다. 오른쪽에 그리면 종이가 거꾸로 접힌 것처럼 보인다.
        val g = curlGeometry(w, h, 0.5f)
        val (x, y) = at(g.back, CURL_COLS, CURL_ROWS)
        val side = (x - g.foldX) * g.nx + (y - g.foldY) * g.ny
        assertTrue(side < 0f, "넘어간 모서리가 접힌 줄 너머에 있지 않다: $side")
    }

    @Test
    fun `at the end the sheet lands exactly mirrored so a spread's left page sits in place`() {
        // 두 쪽 보기: 오른쪽 쪽의 뒷면(다음 펼침의 왼쪽 쪽)이 왼쪽 쪽 자리에 딱 맞게 내려앉아야 효과가 끝날 때 튀지 않는다.
        val g = curlGeometry(w, h, 1f)
        for (row in listOf(0, CURL_ROWS / 2, CURL_ROWS)) for (col in listOf(0, CURL_COLS / 3, CURL_COLS)) {
            val (fx, fy) = flat(col, row)
            val (x, y) = at(g.back, col, row)
            assertTrue(abs(x + fx) < 0.5f && abs(y - fy) < 0.5f, "($col,$row) 가 거울 자리 ${-fx} 에 놓이지 않았다: $x")
        }
        assertTrue(area(g.front) < w * h * 1e-5f, "다 넘어갔는데 앞면이 남았다: 넓이 ${area(g.front)}")
    }

    @Test
    fun `broken sizes and progress still give a drawable mesh`() {
        // 크기가 0 이거나 NaN 이면 숫자가 아닌 점이 나와 그리기가 통째로 실패했을 것이다.
        for ((ww, hh, t) in listOf(Triple(0f, 0f, 0.5f), Triple(Float.NaN, 100f, 0.5f), Triple(100f, 100f, Float.NaN), Triple(100f, -5f, 2f))) {
            val g = curlGeometry(ww, hh, t)
            assertFalse(g.front.any { !it.isFinite() } || g.back.any { !it.isFinite() }, "w=$ww h=$hh t=$t 에서 숫자가 아닌 점")
        }
    }

    @Test
    fun `turn sounds stay silent when the phone is silenced or other audio is playing`() {
        // 도서관 · 지하철(무음 · 진동)과 음악 위에서는 바스락거리지 않는다. 끔이면 언제나 조용하다.
        assertTrue(turnSoundAllowed(TurnSound.Rustle, ringerNormal = true, otherAudio = false))
        assertFalse(turnSoundAllowed(TurnSound.Rustle, ringerNormal = false, otherAudio = false))
        assertFalse(turnSoundAllowed(TurnSound.Rustle, ringerNormal = true, otherAudio = true))
        assertFalse(turnSoundAllowed(TurnSound.Off, ringerNormal = true, otherAudio = false))
    }

    @Test
    fun `a new reader turns pages with the curl but hears nothing until they ask`() {
        // 0.32.0 사용자 결정: 보이는 효과는 말림이 기본, 소리 · 진동은 고른 사람만.
        val prefs = ScreenPrefs()
        assertEquals(PageTurn.Curl, prefs.pageTurn)
        assertEquals(TurnSound.Off, prefs.turnSound)
        assertFalse(prefs.turnHaptic)
    }

    /** 그물 전체가 덮는 넓이(삼각형 넓이의 합). 0 이면 화면에 아무것도 그려지지 않는다. */
    private fun area(mesh: FloatArray): Float {
        var sum = 0f
        for (row in 0 until CURL_ROWS) for (col in 0 until CURL_COLS) {
            val a = at(mesh, col, row); val b = at(mesh, col + 1, row); val c = at(mesh, col, row + 1); val d = at(mesh, col + 1, row + 1)
            sum += tri(a, b, c) + tri(b, d, c)
        }
        return sum
    }

    private fun tri(a: Pair<Float, Float>, b: Pair<Float, Float>, c: Pair<Float, Float>) =
        abs((b.first - a.first) * (c.second - a.second) - (c.first - a.first) * (b.second - a.second)) / 2f
}
