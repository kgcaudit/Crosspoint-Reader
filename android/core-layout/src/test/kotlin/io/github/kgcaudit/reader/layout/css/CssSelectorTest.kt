package io.github.kgcaudit.reader.layout.css

import io.github.kgcaudit.reader.layout.TextAlign
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CssSelectorTest {

    private fun el(tag: String, classes: String? = null, id: String? = null) =
        ElementInfo.of(tag, classes, id)

    private fun matches(selector: String, vararg stack: ElementInfo): Boolean =
        CssSelector.parse(selector)!!.matches(stack.toList())

    @Test
    fun `type class and id selectors match`() {
        assertTrue(matches("p", el("p")))
        assertFalse(matches("p", el("div")))
        assertTrue(matches(".quote", el("p", classes = "quote")))
        assertTrue(matches("#note", el("p", id = "note")))
        assertFalse(matches("#note", el("p", id = "other")))
    }

    @Test
    fun `tag names are matched case-insensitively`() {
        assertTrue(matches("P", el("p")))
        assertTrue(CssSelector.parse("DIV")!!.parts.single().tag == "div")
    }

    @Test
    fun `a compound part requires every condition`() {
        assertTrue(matches("p.quote", el("p", classes = "quote")))
        assertFalse(matches("p.quote", el("div", classes = "quote")))
        assertFalse(matches("p.quote", el("p", classes = "other")))
        assertTrue(matches("p.a.b", el("p", classes = "a b c")))
        assertFalse(matches("p.a.b", el("p", classes = "a c")))
    }

    @Test
    fun `descendant selectors match through any depth`() {
        assertTrue(matches("div p", el("div"), el("p")))
        assertTrue(matches("div p", el("div"), el("section"), el("p")))
        assertFalse(matches("div p", el("section"), el("p")))
        // 마지막 마디는 반드시 현재 요소여야 한다.
        assertFalse(matches("div p", el("div"), el("p"), el("span")))
    }

    @Test
    fun `combinators are downgraded to descendant rather than dropped`() {
        // EPUB 에서 흔한 `div > p` 를 버리면 본문 서식이 통째로 사라진다.
        assertTrue(matches("div > p", el("div"), el("p")))
        assertEquals(2, CssSelector.parse("h1 + p")!!.parts.size)
        assertEquals(2, CssSelector.parse("h1~p")!!.parts.size)
        assertTrue(matches("div>p", el("div"), el("p")))
    }

    @Test
    fun `pseudo-classes and attribute selectors are stripped, keeping the rest`() {
        assertTrue(matches("p:first-child", el("p")))
        assertTrue(matches("p::before", el("p")))
        assertTrue(matches("p[lang]", el("p")))
        assertTrue(matches("p[lang=\"ko\"].quote", el("p", classes = "quote")))
        // 태그 없이 의사 클래스만 남으면 버린다 — 전체에 잘못 적용되는 것보다 낫다.
        assertNull(CssSelector.parse(":root"))
        assertNull(CssSelector.parse(""))
    }

    @Test
    fun `the universal selector matches anything`() {
        assertTrue(matches("*", el("span")))
        assertEquals(0, CssSelector.parse("*")!!.specificity)
    }

    @Test
    fun `specificity orders id over class over type`() {
        val id = CssSelector.parse("#a")!!.specificity
        val cls = CssSelector.parse(".a")!!.specificity
        val tag = CssSelector.parse("a")!!.specificity
        assertTrue(id > cls)
        assertTrue(cls > tag)
        // 타입 셀렉터를 아무리 쌓아도 클래스 하나를 못 넘는다.
        assertTrue(cls > CssSelector.parse("a b c d e f g h i j")!!.specificity)
    }
}

class StylesheetCascadeTest {

    private fun stackOf(vararg pairs: Pair<String, String?>) =
        pairs.map { (tag, cls) -> ElementInfo.of(tag, cls, null) }

    @Test
    fun `higher specificity wins regardless of order`() {
        val sheet = CssParser.parse(
            """
            p.quote { text-align: center }
            p { text-align: justify }
            """.trimIndent(),
        )
        val d = sheet.declarationsFor(stackOf("p" to "quote"))
        assertEquals(TextAlign.Center, d.textAlign)
    }

    @Test
    fun `equal specificity is settled by document order`() {
        val sheet = CssParser.parse("p { text-align: left } p { text-align: center }")
        assertEquals(TextAlign.Center, sheet.declarationsFor(stackOf("p" to null)).textAlign)
    }

    @Test
    fun `properties from different rules are combined, not replaced`() {
        // 낮은 우선순위 규칙이 건드리지 않은 속성은 살아남아야 한다.
        val sheet = CssParser.parse(
            """
            p { text-indent: 1em; text-align: justify }
            .quote { text-align: center }
            """.trimIndent(),
        )
        val d = sheet.declarationsFor(stackOf("p" to "quote"))
        assertEquals(TextAlign.Center, d.textAlign)
        assertEquals(CssLength(1f, CssUnit.Em), d.textIndent)
    }

    @Test
    fun `an empty stylesheet and a non-matching stack give nothing`() {
        assertTrue(Stylesheet.EMPTY.declarationsFor(stackOf("p" to null)).isEmpty)
        val sheet = CssParser.parse("h1 { text-align: center }")
        assertTrue(sheet.declarationsFor(stackOf("p" to null)).isEmpty)
    }
}
