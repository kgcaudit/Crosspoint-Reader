package io.github.kgcaudit.reader.layout.css

import io.github.kgcaudit.reader.layout.TextAlign
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CssLengthTest {

    @Test
    fun `units are parsed, with rem distinguished from em`() {
        assertEquals(CssLength(1.5f, CssUnit.Em), CssLength.parse("1.5em"))
        assertEquals(CssLength(1.5f, CssUnit.Rem), CssLength.parse("1.5rem"))
        assertEquals(CssLength(12f, CssUnit.Pt), CssLength.parse("12pt"))
        assertEquals(CssLength(20f, CssUnit.Px), CssLength.parse("20px"))
        assertEquals(CssLength(5f, CssUnit.Percent), CssLength.parse("5%"))
        assertEquals(CssLength(-1f, CssUnit.Em), CssLength.parse("-1em"))
        assertEquals(CssLength(1f, CssUnit.Em), CssLength.parse("  1EM "))
    }

    @Test
    fun `a bare zero is legal but other bare numbers are not`() {
        // CSS 에서 단위 없는 0 만 유효하다. `margin: 12` 를 12px 로 받아 주면
        // 브라우저와 다르게 조판된다.
        assertEquals(CssLength(0f, CssUnit.Em), CssLength.parse("0"))
        assertNull(CssLength.parse("12"))
        assertNull(CssLength.parse("auto"))
        assertNull(CssLength.parse(""))
        assertNull(CssLength.parse("em"))
    }

    @Test
    fun `absolute units convert against the base size`() {
        assertEquals(1f, CssLength(16f, CssUnit.Px).toEm(16f))
        assertEquals(1f, CssLength(12f, CssUnit.Pt).toEm(16f))
        assertEquals(2f, CssLength(2f, CssUnit.Em).toEm(16f))
    }

    @Test
    fun `a percentage without a container resolves to zero`() {
        // 기준 폭을 모르는 채로 추측하면 문단이 엉뚱하게 들여쓰인다. 0 이 안전하다.
        assertEquals(0f, CssLength(50f, CssUnit.Percent).toEm(16f))
        assertEquals(10f, CssLength(50f, CssUnit.Percent).toEm(16f, containerWidthPx = 320f))
    }
}

class CssParserTest {

    private fun declarations(body: String) = CssParser.parseDeclarations(body)

    @Test
    fun `basic text properties are read`() {
        val d = declarations("text-align: justify; text-indent: 1em; font-style: italic; font-weight: bold")
        assertEquals(TextAlign.Justify, d.textAlign)
        assertEquals(CssLength(1f, CssUnit.Em), d.textIndent)
        assertEquals(true, d.italic)
        assertEquals(true, d.bold)
    }

    @Test
    fun `explicit normal values are recorded as false, not as absent`() {
        // null 과 false 를 구분하지 않으면 `em { font-style: normal }` 이 바깥의
        // 기울임을 되돌리지 못한다.
        val d = declarations("font-style: normal; font-weight: normal")
        assertEquals(false, d.italic)
        assertEquals(false, d.bold)
    }

    @Test
    fun `numeric font weights become bold at 600 and above`() {
        assertEquals(false, declarations("font-weight: 400").bold)
        assertEquals(false, declarations("font-weight: 500").bold)
        assertEquals(true, declarations("font-weight: 600").bold)
        assertEquals(true, declarations("font-weight: 900").bold)
        assertNull(declarations("font-weight: heavyish").bold)
    }

    @Test
    fun `font sizes become a scale, whatever the unit`() {
        assertEquals(1.5f, declarations("font-size: 1.5em").fontSizeScale)
        assertEquals(0.8f, declarations("font-size: 80%").fontSizeScale)
        assertEquals(1.5f, declarations("font-size: 24px").fontSizeScale)
        assertEquals(1f, declarations("font-size: 12pt").fontSizeScale)
        assertEquals(2f, declarations("font-size: xx-large").fontSizeScale)
        assertNull(declarations("font-size: 0").fontSizeScale)
        assertNull(declarations("font-size: inherit").fontSizeScale)
    }

    @Test
    fun `text decoration reads both lines and clears them on none`() {
        val both = declarations("text-decoration: underline line-through")
        assertEquals(true, both.underline)
        assertEquals(true, both.strikethrough)

        val none = declarations("text-decoration: none")
        assertEquals(false, none.underline)
        assertEquals(false, none.strikethrough)
    }

    @Test
    fun `the margin shorthand expands the way css says`() {
        val one = declarations("margin: 1em")
        assertEquals(CssLength(1f, CssUnit.Em), one.marginTop)
        assertEquals(CssLength(1f, CssUnit.Em), one.marginRight)
        assertEquals(CssLength(1f, CssUnit.Em), one.marginBottom)
        assertEquals(CssLength(1f, CssUnit.Em), one.marginLeft)

        // 2개: 세로 가로
        val two = declarations("margin: 1em 2em")
        assertEquals(CssLength(1f, CssUnit.Em), two.marginTop)
        assertEquals(CssLength(1f, CssUnit.Em), two.marginBottom)
        assertEquals(CssLength(2f, CssUnit.Em), two.marginLeft)
        assertEquals(CssLength(2f, CssUnit.Em), two.marginRight)

        // 3개: 위 가로 아래
        val three = declarations("margin: 1em 2em 3em")
        assertEquals(CssLength(1f, CssUnit.Em), three.marginTop)
        assertEquals(CssLength(2f, CssUnit.Em), three.marginLeft)
        assertEquals(CssLength(2f, CssUnit.Em), three.marginRight)
        assertEquals(CssLength(3f, CssUnit.Em), three.marginBottom)

        // 4개: 위 오른쪽 아래 왼쪽 (시계 방향)
        val four = declarations("margin: 1em 2em 3em 4em")
        assertEquals(CssLength(1f, CssUnit.Em), four.marginTop)
        assertEquals(CssLength(2f, CssUnit.Em), four.marginRight)
        assertEquals(CssLength(3f, CssUnit.Em), four.marginBottom)
        assertEquals(CssLength(4f, CssUnit.Em), four.marginLeft)
    }

    @Test
    fun `padding folds into margin`() {
        // 배경도 테두리도 그리지 않으므로 눈에 보이는 결과가 같다.
        assertEquals(CssLength(2f, CssUnit.Em), declarations("padding-left: 2em").marginLeft)
        assertEquals(CssLength(1f, CssUnit.Em), declarations("padding: 1em").marginTop)
    }

    @Test
    fun `display none and page breaks are read`() {
        assertEquals(true, declarations("display: none").hidden)
        assertTrue(declarations("display: block").isEmpty)
        assertEquals(true, declarations("page-break-before: always").pageBreakBefore)
        assertEquals(true, declarations("break-before: page").pageBreakBefore)
        assertTrue(declarations("page-break-before: auto").isEmpty)
    }

    @Test
    fun `important is tolerated and unknown properties are dropped`() {
        assertEquals(TextAlign.Center, declarations("text-align: center !important").textAlign)
        assertTrue(declarations("color: red; float: left; -webkit-hyphens: auto").isEmpty)
    }

    @Test
    fun `later declarations in the same block win`() {
        assertEquals(TextAlign.Center, declarations("text-align: left; text-align: center").textAlign)
    }

    @Test
    fun `a stylesheet splits groups and keeps document order`() {
        val sheet = CssParser.parse("h1, h2 { text-align: center }")
        assertEquals(2, sheet.rules.size)
        assertEquals(TextAlign.Center, sheet.declarationsFor(listOf(ElementInfo("h2"))).textAlign)
    }

    @Test
    fun `comments are removed, including an unclosed one`() {
        val sheet = CssParser.parse("/* 주석 */ p { text-align: center } /* 끝나지 않은 주석 p { text-align: left }")
        assertEquals(TextAlign.Center, sheet.declarationsFor(listOf(ElementInfo("p"))).textAlign)
        assertEquals(1, sheet.rules.size)
    }

    @Test
    fun `at-rules are skipped whole, wherever they sit`() {
        // @media 를 선택자로 오인하면 안쪽 규칙이 통째로 잘못 붙는다.
        val css = """
            @charset "utf-8";
            p { text-align: justify }
            @media screen and (min-width: 600px) {
                p { text-align: center }
            }
            @font-face { font-family: X; src: url(x.ttf) }
            h1 { text-align: center }
        """.trimIndent()
        val sheet = CssParser.parse(css)
        assertEquals(2, sheet.rules.size)
        assertEquals(TextAlign.Justify, sheet.declarationsFor(listOf(ElementInfo("p"))).textAlign)
        assertEquals(TextAlign.Center, sheet.declarationsFor(listOf(ElementInfo("h1"))).textAlign)
    }

    @Test
    fun `an unclosed block does not swallow the parser`() {
        val sheet = CssParser.parse("p { text-align: center")
        assertEquals(TextAlign.Center, sheet.declarationsFor(listOf(ElementInfo("p"))).textAlign)
    }

    @Test
    fun `broken declarations are dropped one by one`() {
        val d = declarations("text-align; : center; text-indent: ; font-weight: bold")
        assertNull(d.textAlign)
        assertNull(d.textIndent)
        assertEquals(true, d.bold)
    }
}
