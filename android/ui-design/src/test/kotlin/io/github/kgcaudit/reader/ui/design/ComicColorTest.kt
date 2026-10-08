package io.github.kgcaudit.reader.ui.design

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 만화 색 보정 행렬: 누런 종이 · 흐린 먹이 실제로 어떻게 바뀌는가. 화면 없이 행렬을 색 하나에 씌워 본다. */
class ComicColorTest {

    /** 안드로이드가 색 행렬을 씌우는 방식 그대로: 행 우선 4×5, 다섯째 열은 0..255 더하기, 끝에 0..255 로 자른다. */
    private fun FloatArray.apply(rgb: Int): IntArray {
        val c = floatArrayOf(((rgb shr 16) and 0xFF).toFloat(), ((rgb shr 8) and 0xFF).toFloat(), (rgb and 0xFF).toFloat(), 255f)
        return IntArray(3) { row ->
            (this[row * 5] * c[0] + this[row * 5 + 1] * c[1] + this[row * 5 + 2] * c[2] + this[row * 5 + 3] * c[3] + this[row * 5 + 4])
                .coerceIn(0f, 255f).toInt()
        }
    }

    private val yellowedPaper = 0xE6D9B5
    private val faintInk = 0x5A5650

    @Test
    fun `off adds no filter at all`() {
        // 끔이 단위 행렬이면 그림은 같아도 그릴 때마다 거르개를 거친다 — 아무것도 하지 않는 설정은 비용도 없어야 한다.
        assertNull(comicColorMatrix(ComicColor.Off))
        assertNull(ComicColor.Off.colorFilter)
    }

    @Test
    fun `clear turns yellowed paper bright and keeps its two bright channels at white`() {
        val m = comicColorMatrix(ComicColor.Clear)!!
        val (r, g, b) = m.apply(yellowedPaper).toList()
        // 빨강 · 초록은 흰 점 위라 하얗다. 파랑(종이에서 가장 어두운 채널)도 원래보다 밝아진다 — 옅은 미색(구상안 그대로).
        assertTrue(r >= 250 && g >= 245, "종이가 ($r, $g, $b)")
        assertTrue(b > 0xB5 + 5, "파랑이 밝아지지 않았다: $b")
        // 밝기(Rec.709)가 거의 흰색 — 사람 눈에는 흰 종이로 읽힌다.
        val luma = 0.213f * r + 0.715f * g + 0.072f * b
        assertTrue(luma > 240f, "종이 밝기가 $luma")
    }

    @Test
    fun `clear makes faint ink darker`() {
        val m = comicColorMatrix(ComicColor.Clear)!!
        val ink = m.apply(faintInk)
        val before = intArrayOf(0x5A, 0x56, 0x50)
        for (i in 0..2) assertTrue(ink[i] < before[i] - 20, "먹 채널 $i 가 ${before[i]} → ${ink[i]}")
        // 아주 어두운 먹은 검정으로 — 검은 점 아래.
        assertTrue(m.apply(0x303030).all { it == 0 })
    }

    @Test
    fun `gray gives equal channels and white paper`() {
        val m = comicColorMatrix(ComicColor.Gray)!!
        for (rgb in listOf(yellowedPaper, faintInk, 0xC03030, 0x2E7D6B)) {
            val (r, g, b) = m.apply(rgb).toList()
            assertTrue(r == g && g == b, "${rgb.toString(16)} → ($r, $g, $b)")
        }
        // 색을 뺐으니 누런 종이는 파랑까지 하얗다.
        assertTrue(m.apply(yellowedPaper).all { it >= 250 })
    }

    @Test
    fun `clear is not the identity and gray is not clear`() {
        // 일부러 망가뜨린 행렬(단위 · 서로 같은 값)을 잡는다: 선명하게가 아무것도 바꾸지 않으면 위 시험들이 모두 걸려야 한다.
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
        assertTrue(!comicColorMatrix(ComicColor.Clear)!!.contentEquals(identity))
        assertTrue(!comicColorMatrix(ComicColor.Clear)!!.contentEquals(comicColorMatrix(ComicColor.Gray)!!))
        // 시험의 도우미가 단위 행렬을 실제로 그대로 돌려주는지 — 도우미가 틀리면 위 시험들이 잘못 통과한다.
        assertEquals(listOf(0xE6, 0xD9, 0xB5), identity.apply(yellowedPaper).toList())
    }

    @Test
    fun `alpha is left alone so transparent pages do not turn opaque`() {
        for (mode in listOf(ComicColor.Clear, ComicColor.Gray)) {
            val m = comicColorMatrix(mode)!!
            assertEquals(listOf(0f, 0f, 0f, 1f, 0f), m.slice(15..19), mode.name)
        }
    }
}
