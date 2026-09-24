package io.github.kgcaudit.reader.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OLO 디자인 시스템의 품질 가드(§7)를 이 앱에도 둔다.
 *
 * 색을 눈으로 고르면 "비슷해 보이는데 흰 글자가 안 읽히는" 값이 들어온다. 대비를 숫자로
 * 붙들어 두면 누가 색을 조금 밝게 바꿔도 여기서 걸린다.
 */
class ContrastTest {

    private val themes = mapOf("라이트" to LightColors, "다크" to DarkColors)

    @Test
    fun `labels on the brand colour read at WCAG AA in both themes`() {
        // 선택된 칩·주 단추의 글자. 4.5:1 이 본문 글자의 기준이다.
        for ((name, c) in themes) {
            assertAtLeast(4.5, c.onAccent, c.accent, "$name: 강조색 위 글자")
        }
    }

    @Test
    fun `body text reads on every surface it is drawn on`() {
        for ((name, c) in themes) {
            for ((where, bg) in listOf("바탕" to c.background, "메뉴" to c.surface, "팝업" to c.dialog)) {
                assertAtLeast(4.5, c.text, bg, "$name: $where 위 본문")
                assertAtLeast(4.5, c.textMuted, bg, "$name: $where 위 보조 글자")
            }
            assertAtLeast(4.5, c.onAccentContainer, c.accentContainer, "$name: 고른 행의 글자")
        }
    }

    @Test
    fun `the page and the status line under it are readable`() {
        // 리더는 몇 시간씩 보는 화면이다. 상태바 글자(inkMuted)도 본문 기준으로 본다.
        for ((name, c) in themes) {
            assertAtLeast(7.0, c.ink, c.paper, "$name: 지면 위 본문")
            assertAtLeast(4.5, c.inkMuted, c.paper, "$name: 지면 위 상태바")
        }
    }

    @Test
    fun `every paper colour keeps the page and the status line readable`() {
        // 고르는 지면색마다 본문 7:1, 상태바(하단 정보) 4.5:1. 아이보리의 보조 글자를 눈으로 고른 값
        // (#7A6A58, 4.3:1)으로 두었으면 여기서 걸린다.
        for (theme in PaperTheme.entries) {
            val paper = theme.paper ?: continue
            assertAtLeast(7.0, paper.ink, paper.paper, "${theme.label}: 지면 위 본문")
            assertAtLeast(4.5, paper.inkMuted, paper.paper, "${theme.label}: 지면 위 상태바")
        }
        assertTrue(contrast(Color(0xFF7A6A58), Color(0xFFF4ECD8)) < 4.5, "검사가 옅은 아이보리 보조색을 통과시켰다")
    }

    @Test
    fun `white glyphs stand out on every tile`() {
        // 타일의 흰 글리프는 글자가 아니라 그림이라 3:1 이 기준이다(WCAG 1.4.11).
        for ((name, c) in themes) {
            val t = c.tiles
            for ((kind, fill) in listOf("폴더" to t.folder, "문서" to t.document, "책" to t.book, "기타" to t.other)) {
                assertAtLeast(3.0, Color.White, fill, "$name: $kind 타일")
            }
        }
    }

    @Test
    fun `the check rejects the clay that was too light`() {
        // 가드가 실제로 무는지. OLO Explorer 의 첫 클레이 #C5613F 는 흰 글자가 4.07:1 이라
        // 5% 어둡게 고쳤다 — 이 검사가 그 값을 통과시키면 검사가 빈 껍데기다.
        val ratio = contrast(Color.White, Color(0xFFC5613F))
        assertTrue(ratio < 4.5, "옛 클레이가 기준을 통과했다: $ratio")
        assertEquals(4.07, ratio, 0.02)
    }

    @Test
    fun `the brand values are the OLO Explorer ones`() {
        // 두 앱이 한 식구로 보이려면 값이 같아야 한다. 여기를 바꾸려면 원본 문서
        // (Filezilla-Client docs/OLO-Design-System.md)부터 바꾼다.
        assertEquals(Color(0xFFB95B3B), LightColors.accent)
        assertEquals(Color(0xFFE8A183), DarkColors.accent)
        assertEquals(Color(0xFFF7F4EF), LightColors.background)
        assertEquals(Color(0xFF181613), DarkColors.background)
        assertEquals(Color(0xFF55606B), LightColors.tiles.document)
        assertEquals(Color(0xFFA50E2E), LightColors.error)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun assertAtLeast(min: Double, fg: Color, bg: Color, label: String) {
        val ratio = contrast(fg, bg)
        assertTrue(ratio >= min, "$label 대비가 ${"%.2f".format(ratio)}:1 이다(기준 $min:1)")
    }
}
