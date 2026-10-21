package io.github.kgcaudit.reader.layout.html

import io.github.kgcaudit.reader.layout.css.CssParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 본문 링크: 글자 구간이 정확한가, 각주 표시와 본문 속 링크를 가르는가. */
class LinkTest {

    private fun parse(xhtml: String) = ChapterParser(CssParser.parse("")).parse(xhtml)

    private fun Chapter.label(link: Link) = text.substring(link.start, link.endExclusive)

    @Test
    fun `a link covers exactly its own letters, not the space before it`() {
        // 앞 공백까지 잡으면 누르는 칸 · 칠하는 칸이 한 칸 앞으로 삐져나온다.
        val c = parse("""<html><body><p>보아 구렁이 <a href="#n1">1</a> 그림이었다.</p></body></html>""")
        val link = c.links.single()
        assertEquals("1", c.label(link))
        assertEquals("#n1", link.href)
    }

    @Test
    fun `the book's own footnote marks are recognised`() {
        val c = parse(
            """
            <html><body>
              <p>하나<a epub:type="noteref" href="#a">가</a> 둘<sup><a href="#b">나</a></sup>
                 셋<a href="#c">[3]</a> 넷<a href="#d">*</a> 다섯<a href="#e">⑤</a></p>
              <p>자세한 것은 <a href="ch3.xhtml">제3장 장미 한 송이</a>를 보라. <a href="https://example.com/rose">누리집</a></p>
            </body></html>
            """.trimIndent(),
        )
        val byLabel = c.links.associateBy { c.label(it) }
        // 책이 밝힌 것 · 위첨자 · 짧은 표시는 각주다.
        for (mark in listOf("가", "나", "[3]", "*", "⑤")) assertTrue(byLabel.getValue(mark).isFootnote(c.text), mark)
        // 본문 속 링크는 각주가 아니다 — 판에 장 하나가 통째로 들어가면 안 된다(그 자리로 간다).
        assertFalse(byLabel.getValue("제3장 장미 한 송이").isFootnote(c.text))
        // 인터넷 주소는 책 밖이다.
        val web = byLabel.getValue("누리집")
        assertTrue(web.isExternal)
        assertFalse(web.isFootnote(c.text))
    }

    @Test
    fun `a link whose target is marked as a note is a footnote even with a long label`() {
        val c = parse("""<html><body><p><a href="#n9">보아 구렁이</a></p></body></html>""")
        assertFalse(c.links.single().isFootnote(c.text))
        assertTrue(c.links.single().isFootnote(c.text, targetIsNote = true))
    }

    @Test
    fun `note containers are recorded by id`() {
        val c = parse(
            """
            <html><body>
              <aside id="fn1"><p>첫 각주</p></aside>
              <div epub:type="footnote" id="fn2"><p>둘째</p></div>
              <p role="doc-endnote" id="fn3">셋째</p>
              <p id="plain">그냥 문단</p>
            </body></html>
            """.trimIndent(),
        )
        assertEquals(setOf("fn1", "fn2", "fn3"), c.noteIds)
    }

    @Test
    fun `broken links are dropped instead of breaking the chapter`() {
        // href 없는 a · 빈 a · 닫히지 않은 a(규칙 6). 본문은 그대로 읽힌다.
        val c = parse("""<html><body><p><a name="x">이름표</a> <a href="">빈</a> <a href="#z"></a> 끝 <a href="#q">열린 채</p></body></html>""")
        assertTrue(c.text.startsWith("이름표 빈"))
        assertTrue(c.links.none { it.href.isEmpty() })
        assertTrue(c.links.all { it.endExclusive > it.start })
    }
}
