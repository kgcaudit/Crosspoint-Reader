package io.github.kgcaudit.reader.layout.html

import io.github.kgcaudit.reader.layout.TextAlign
import io.github.kgcaudit.reader.layout.TextStyle
import io.github.kgcaudit.reader.layout.css.CssParser
import io.github.kgcaudit.reader.layout.css.ElementInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StyleResolverTest {

    private fun resolver(css: String = "", context: StyleContext = StyleContext()) =
        StyleResolver(CssParser.parse(css), context)

    private fun stack(vararg tags: String) = tags.map { ElementInfo(it) }

    @Test
    fun `the book's css beats a tag default of higher specificity`() {
        // CSS 의 출처 규칙. 태그 기본값 h1(우선순위 1)이 책의 `*`(우선순위 0)을
        // 이기면 안 된다 — 우선순위 계산에 섞으면 정확히 그렇게 된다.
        val resolved = resolver("* { font-weight: normal }").declarationsFor(stack("h1"))
        assertEquals(false, resolved.bold)
    }

    @Test
    fun `a style attribute beats everything`() {
        val resolved = resolver("#a { text-align: left }")
            .declarationsFor(listOf(ElementInfo("p", id = "a")), "text-align: center")
        assertEquals(TextAlign.Center, resolved.textAlign)
    }

    @Test
    fun `with publisher styles off only tag defaults and the user's settings remain`() {
        val off = resolver(
            "p { text-align: center; margin-left: 3em } em { font-style: normal }",
            StyleContext(defaultAlign = TextAlign.Justify, usePublisherStyles = false),
        )
        val p = off.declarationsFor(stack("p"), "text-align: right")
        assertNull(p.textAlign)
        assertNull(p.marginLeft)
        // 태그 기본값은 그대로다.
        assertEquals(true, off.declarationsFor(stack("em")).italic)
    }

    @Test
    fun `font size multiplies but weight and slant replace`() {
        val r = resolver()
        val parent = TextStyle(bold = true, italic = true, sizeScale = 1.5f)
        val child = r.textStyle(parent, CssParser.parseDeclarations("font-size: 2em; font-weight: normal"))
        assertEquals(3f, child.sizeScale)
        assertFalse(child.bold)
        assertTrue(child.italic)
    }

    @Test
    fun `a line once drawn is not erased by a child`() {
        // CSS 에서 text-decoration 은 상속이 아니라 조상이 그어 놓는 선이다.
        val r = resolver()
        val parent = TextStyle(underline = true)
        val child = r.textStyle(parent, CssParser.parseDeclarations("text-decoration: none"))
        assertTrue(child.underline)
    }

    @Test
    fun `alignment and indent are inherited, margins accumulate`() {
        val r = resolver()
        val outer = r.inherit(InheritedStyle.Root, CssParser.parseDeclarations("text-align: center; margin-left: 2em"))
        val inner = r.inherit(outer, CssParser.parseDeclarations("margin-left: 1em"))

        assertEquals(TextAlign.Center, inner.align)
        assertEquals(3f, inner.indentStartEm)
    }

    @Test
    fun `an unset alignment falls back to the user's setting`() {
        val r = resolver(context = StyleContext(defaultAlign = TextAlign.Justify))
        assertEquals(TextAlign.Justify, r.blockStyle(InheritedStyle.Root, CssParser.parseDeclarations("")).align)
        assertNull(r.blockStyle(InheritedStyle.Root, CssParser.parseDeclarations("")).firstLineIndentEm)
    }

    @Test
    fun `absolute lengths resolve against the base size`() {
        val r = resolver(context = StyleContext(baseSizePx = 20f, contentWidthPx = 400f))
        val style = r.blockStyle(
            InheritedStyle.Root,
            CssParser.parseDeclarations("margin-top: 40px; margin-bottom: 10%"),
        )
        assertEquals(2f, style.marginTopEm)
        assertEquals(2f, style.marginBottomEm)
    }

    @Test
    fun `StyleContext follows the layout spec`() {
        val spec = io.github.kgcaudit.reader.layout.LayoutSpec(
            viewportWidthPx = 400f,
            viewportHeightPx = 600f,
            margin = io.github.kgcaudit.reader.layout.Insets.all(20f),
            baseSizePx = 18f,
            align = TextAlign.Center,
            usePublisherStyles = false,
        )
        val context = StyleContext.of(spec)
        assertEquals(18f, context.baseSizePx)
        assertEquals(360f, context.contentWidthPx)
        assertEquals(TextAlign.Center, context.defaultAlign)
        assertFalse(context.usePublisherStyles)
    }
}
