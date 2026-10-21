package io.github.kgcaudit.reader.document.epub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NcxParserTest {

    private fun parse(xml: String, baseDir: String = "OEBPS") =
        NcxParser.parse(xml.reader(), baseDir)

    @Test
    fun `flat nav points become depth zero entries in document order`() {
        val entries = parse(
            """
            <ncx><navMap>
              <navPoint playOrder="1"><navLabel><text>제1장</text></navLabel><content src="ch1.xhtml"/></navPoint>
              <navPoint playOrder="2"><navLabel><text>제2장</text></navLabel><content src="ch2.xhtml"/></navPoint>
            </navMap></ncx>
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                RawTocEntry("제1장", "OEBPS/ch1.xhtml", null, 0),
                RawTocEntry("제2장", "OEBPS/ch2.xhtml", null, 0),
            ),
            entries,
        )
    }

    @Test
    fun `nested nav points get increasing depth and the parent is still emitted`() {
        val entries = parse(
            """
            <ncx><navMap>
              <navPoint><navLabel><text>제1장</text></navLabel><content src="ch1.xhtml"/>
                <navPoint><navLabel><text>1.1 절</text></navLabel><content src="ch1.xhtml#s1"/></navPoint>
                <navPoint><navLabel><text>1.2 절</text></navLabel><content src="ch1.xhtml#s2"/></navPoint>
              </navPoint>
              <navPoint><navLabel><text>제2장</text></navLabel><content src="ch2.xhtml"/></navPoint>
            </navMap></ncx>
            """.trimIndent(),
        )
        assertEquals(
            listOf("제1장" to 0, "1.1 절" to 1, "1.2 절" to 1, "제2장" to 0),
            entries.map { it.label to it.depth },
        )
        // 부모 항목이 사라지지 않는다 — 자식이 열릴 때 먼저 내보낸다.
        assertEquals("OEBPS/ch1.xhtml", entries.first().href)
    }

    @Test
    fun `fragments are kept separately from the file path`() {
        val entries = parse(
            """<ncx><navMap><navPoint><navLabel><text>각주</text></navLabel>
               <content src="notes.xhtml#n12"/></navPoint></navMap></ncx>""",
        )
        assertEquals("OEBPS/notes.xhtml", entries.single().href)
        assertEquals("n12", entries.single().fragment)
    }

    @Test
    fun `labels spanning several lines are collapsed to one`() {
        val entries = parse(
            """
            <ncx><navMap><navPoint><navLabel><text>
                  제3장
                  긴 제목이 여러 줄에
               </text></navLabel><content src="ch3.xhtml"/></navPoint></navMap></ncx>
            """.trimIndent(),
        )
        assertEquals("제3장 긴 제목이 여러 줄에", entries.single().label)
    }

    @Test
    fun `entries with no label or no target are dropped`() {
        // 빈 줄이나 눌러도 아무 일 없는 항목이 목록에 생기는 것보다 낫다.
        val entries = parse(
            """
            <ncx><navMap>
              <navPoint><navLabel><text></text></navLabel><content src="a.xhtml"/></navPoint>
              <navPoint><navLabel><text>목적지 없음</text></navLabel></navPoint>
              <navPoint><navLabel><text>정상</text></navLabel><content src="ok.xhtml"/></navPoint>
            </navMap></ncx>
            """.trimIndent(),
        )
        assertEquals(listOf("정상"), entries.map { it.label })
    }

    @Test
    fun `stray text outside the text element does not pollute the label`() {
        // navMap 안의 아무 텍스트나 받으면 요소 사이의 부스러기가 라벨에 섞인다.
        // 공백은 trim 으로 가려지지만 실제 글자는 그대로 남아 목차가 더러워진다.
        val entries = parse(
            """<ncx><navMap><navPoint><navLabel><text>제1장</text></navLabel>부스러기<content src="a.xhtml"/>
               </navPoint></navMap></ncx>""",
        )
        assertEquals("제1장", entries.single().label)
    }

    @Test
    fun `page lists and nav lists outside navMap are ignored`() {
        val entries = parse(
            """
            <ncx>
              <pageList><pageTarget><navLabel><text>12</text></navLabel><content src="p12.xhtml"/></pageTarget></pageList>
              <navMap><navPoint><navLabel><text>본문</text></navLabel><content src="ch1.xhtml"/></navPoint></navMap>
              <navList><navTarget><navLabel><text>그림 1</text></navLabel><content src="f1.xhtml"/></navTarget></navList>
            </ncx>
            """.trimIndent(),
        )
        assertEquals(listOf("본문"), entries.map { it.label })
    }

    @Test
    fun `parent relative sources resolve against the ncx directory`() {
        val entries = parse(
            """<ncx><navMap><navPoint><navLabel><text>장</text></navLabel>
               <content src="../text/ch1.xhtml"/></navPoint></navMap></ncx>""",
            baseDir = "OEBPS/nav",
        )
        assertEquals("OEBPS/text/ch1.xhtml", entries.single().href)
    }

    @Test
    fun `an empty or missing navMap yields no entries`() {
        assertTrue(parse("<ncx><navMap/></ncx>").isEmpty())
        assertTrue(parse("<ncx/>").isEmpty())
        assertTrue(parse("").isEmpty())
    }
}

class NavParserTest {

    private fun parse(xml: String, baseDir: String = "OEBPS") =
        NavParser.parse(xml.reader(), baseDir)

    @Test
    fun `the toc nav is read and ol nesting becomes depth`() {
        val entries = parse(
            """
            <html><body>
              <nav epub:type="toc">
                <ol>
                  <li><a href="ch1.xhtml">제1장</a>
                    <ol><li><a href="ch1.xhtml#s1">1.1 절</a></li></ol>
                  </li>
                  <li><a href="ch2.xhtml">제2장</a></li>
                </ol>
              </nav>
            </body></html>
            """.trimIndent(),
        )
        assertEquals(
            listOf("제1장" to 0, "1.1 절" to 1, "제2장" to 0),
            entries.map { it.label to it.depth },
        )
        assertEquals("s1", entries[1].fragment)
    }

    @Test
    fun `landmarks and page lists are skipped in favour of the toc nav`() {
        // 한 파일에 nav 이 여럿 있다. 목차가 아닌 것을 섞으면 목록이 오염된다.
        val entries = parse(
            """
            <html><body>
              <nav epub:type="landmarks"><ol><li><a href="ch1.xhtml">본문 시작</a></li></ol></nav>
              <nav epub:type="toc"><ol><li><a href="ch1.xhtml">제1장</a></li></ol></nav>
              <nav epub:type="page-list"><ol><li><a href="ch1.xhtml#p1">1</a></li></ol></nav>
            </body></html>
            """.trimIndent(),
        )
        assertEquals(listOf("제1장"), entries.map { it.label })
    }

    @Test
    fun `role doc-toc is accepted when epub type is absent`() {
        val entries = parse(
            """<html><body><nav role="doc-toc"><ol><li><a href="a.xhtml">가</a></li></ol></nav></body></html>""",
        )
        assertEquals(listOf("가"), entries.map { it.label })
    }

    @Test
    fun `a nav with no type at all is used as a fallback`() {
        // 타입을 안 적은 책이 있다. 목차를 통째로 잃는 것보다 낫다.
        val entries = parse(
            """<html><body><nav><ol><li><a href="a.xhtml">가</a></li></ol></nav></body></html>""",
        )
        assertEquals(listOf("가"), entries.map { it.label })
    }

    @Test
    fun `a label wrapped in inline markup is assembled whole`() {
        val entries = parse(
            """<nav epub:type="toc"><ol><li><a href="a.xhtml">제<em>1</em>장 &mdash; 시작</a></li></ol></nav>""",
        )
        assertEquals("제1장 — 시작", entries.single().label)
    }

    @Test
    fun `a span inside a link is part of the label, not a new entry`() {
        // Sigil·InDesign 이 흔히 만드는 모양. <span> 을 새 항목으로 보면 아직 빈 <a> 가 먼저 버려져
        // 목차 줄이 통째로 사라진다.
        val entries = parse(
            """<nav epub:type="toc"><ol><li><a href="c1.xhtml"><span class="n">1</span><span>장 시작</span></a></li>
               <li><a href="c2.xhtml">2장</a></li></ol></nav>""",
        )
        assertEquals(listOf("1장 시작" to "c1.xhtml", "2장" to "c2.xhtml"), entries.map { it.label to it.href.substringAfterLast('/') })
    }

    @Test
    fun `a span heading without an href is dropped, its children are kept`() {
        // 링크 없는 상위 제목은 이동할 곳이 없으므로 목록에 넣지 않는다.
        val entries = parse(
            """
            <nav epub:type="toc"><ol>
              <li><span>제1부</span>
                <ol><li><a href="ch1.xhtml">제1장</a></li></ol>
              </li>
            </ol></nav>
            """.trimIndent(),
        )
        assertEquals(listOf("제1장"), entries.map { it.label })
    }

    @Test
    fun `ul is treated like ol for depth`() {
        val entries = parse(
            """<nav epub:type="toc"><ul><li><a href="a.xhtml">가</a>
               <ul><li><a href="b.xhtml">나</a></li></ul></li></ul></nav>""",
        )
        assertEquals(listOf("가" to 0, "나" to 1), entries.map { it.label to it.depth })
    }

    @Test
    fun `relative hrefs resolve against the nav directory`() {
        val entries = parse(
            """<nav epub:type="toc"><ol><li><a href="../text/ch1.xhtml">장</a></li></ol></nav>""",
            baseDir = "OEBPS/nav",
        )
        assertEquals("OEBPS/text/ch1.xhtml", entries.single().href)
    }

    @Test
    fun `an empty document yields no entries`() {
        assertTrue(parse("").isEmpty())
        assertTrue(parse("<html><body><p>목차가 없다</p></body></html>").isEmpty())
    }

    @Test
    fun `entries outside any nav are ignored`() {
        val entries = parse(
            """<html><body><ol><li><a href="x.xhtml">본문 링크</a></li></ol>
               <nav epub:type="toc"><ol><li><a href="a.xhtml">가</a></li></ol></nav></body></html>""",
        )
        assertEquals(listOf("가"), entries.map { it.label })
    }

    @Test
    fun `a percent encoded href is decoded`() {
        val entries = parse(
            """<nav epub:type="toc"><ol><li><a href="%ED%95%9C%EA%B8%80.xhtml">한글</a></li></ol></nav>""",
        )
        assertEquals("OEBPS/한글.xhtml", entries.single().href)
        assertNull(entries.single().fragment)
    }
}
