package io.github.kgcaudit.reader.document.epub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpfParserTest {

    private fun parse(xml: String, opfPath: String = "OEBPS/content.opf") =
        OpfParser.parse(xml.reader(), opfPath)

    private val epub3 = """
        <?xml version="1.0" encoding="UTF-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:identifier id="uid">urn:uuid:1234</dc:identifier>
            <dc:title>어린 왕자</dc:title>
            <dc:creator>앙투안 드 생텍쥐페리</dc:creator>
            <dc:language>ko</dc:language>
          </metadata>
          <manifest>
            <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
            <item id="cov" href="images/cover.jpg" media-type="image/jpeg" properties="cover-image"/>
            <item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
            <item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
            <item id="css" href="style/main.css" media-type="text/css"/>
          </manifest>
          <spine>
            <itemref idref="c1"/>
            <itemref idref="c2"/>
          </spine>
        </package>
    """.trimIndent()

    @Test
    fun `metadata is read regardless of the dc prefix`() {
        val opf = parse(epub3)
        assertEquals("어린 왕자", opf.title)
        assertEquals("앙투안 드 생텍쥐페리", opf.creator)
        assertEquals("ko", opf.language)
        assertEquals("urn:uuid:1234", opf.identifier)
        assertEquals("3.0", opf.version)
    }

    @Test
    fun `manifest hrefs are resolved against the opf directory`() {
        val opf = parse(epub3)
        assertEquals("OEBPS/text/ch1.xhtml", opf.manifestById["c1"]?.href)
        assertEquals("OEBPS/style/main.css", opf.manifestById["css"]?.href)
        assertEquals("OEBPS", opf.baseDir)
    }

    @Test
    fun `spine order follows the document, not the manifest`() {
        val opf = parse(epub3)
        assertEquals(listOf("OEBPS/text/ch1.xhtml", "OEBPS/text/ch2.xhtml"), opf.spineItems.map { it.href })
    }

    @Test
    fun `epub3 nav and cover image are found by properties`() {
        val opf = parse(epub3)
        assertEquals("OEBPS/nav.xhtml", opf.navItem?.href)
        assertEquals("OEBPS/images/cover.jpg", opf.coverImageItem?.href)
    }

    @Test
    fun `epub2 toc is found through the spine toc attribute`() {
        val opf = parse(
            """
            <package version="2.0">
              <metadata><dc:title>제목</dc:title></metadata>
              <manifest>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                <item id="c1" href="ch1.html" media-type="application/xhtml+xml"/>
              </manifest>
              <spine toc="ncx"><itemref idref="c1"/></spine>
            </package>
            """.trimIndent(),
        )
        assertEquals("OEBPS/toc.ncx", opf.ncxItem?.href)
        assertNull(opf.navItem)
    }

    @Test
    fun `a missing spine toc attribute falls back to the ncx media type`() {
        // toc 속성을 빼먹은 책이 흔하다. 여기서 포기하면 목차가 사라진다.
        val opf = parse(
            """
            <package version="2.0">
              <manifest>
                <item id="whatever" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
              </manifest>
              <spine><itemref idref="whatever"/></spine>
            </package>
            """.trimIndent(),
        )
        assertEquals("OEBPS/toc.ncx", opf.ncxItem?.href)
    }

    @Test
    fun `epub2 cover is found through the meta name cover`() {
        val opf = parse(
            """
            <package version="2.0">
              <metadata><meta name="cover" content="coverimg"/></metadata>
              <manifest><item id="coverimg" href="cover.jpeg" media-type="image/jpeg"/></manifest>
              <spine/>
            </package>
            """.trimIndent(),
        )
        assertEquals("OEBPS/cover.jpeg", opf.coverImageItem?.href)
    }

    @Test
    fun `linear no items are kept out of the reading order`() {
        // 표지·판권처럼 본문 흐름에서 빠지는 항목이다. 읽기 순서에 넣으면
        // 진도 퍼센트가 어긋난다.
        val opf = parse(
            """
            <package>
              <manifest>
                <item id="cov" href="cover.xhtml" media-type="application/xhtml+xml"/>
                <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine>
                <itemref idref="cov" linear="no"/>
                <itemref idref="c1"/>
              </spine>
            </package>
            """.trimIndent(),
        )
        assertEquals(listOf("OEBPS/ch1.xhtml"), opf.spineItems.map { it.href })
    }

    @Test
    fun `a spine idref with no manifest item is skipped instead of failing the book`() {
        val opf = parse(
            """
            <package>
              <manifest><item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/></manifest>
              <spine><itemref idref="ghost"/><itemref idref="c1"/></spine>
            </package>
            """.trimIndent(),
        )
        assertEquals(listOf("OEBPS/ch1.xhtml"), opf.spineItems.map { it.href })
    }

    @Test
    fun `a title split across entity references is assembled whole`() {
        // 텍스트가 여러 이벤트로 쪼개져 오므로, 첫 조각만 받으면 제목이 잘린다.
        val opf = parse(
            """
            <package><metadata><dc:title>제1부 &mdash; 시작&amp;끝</dc:title></metadata>
            <manifest/><spine/></package>
            """.trimIndent(),
        )
        assertEquals("제1부 — 시작&끝", opf.title)
    }

    @Test
    fun `the first title wins when several are present`() {
        // 부제·원제가 뒤에 붙는 책이 있다.
        val opf = parse(
            """
            <package><metadata>
              <dc:title>본 제목</dc:title><dc:title>Original Title</dc:title>
            </metadata><manifest/><spine/></package>
            """.trimIndent(),
        )
        assertEquals("본 제목", opf.title)
    }

    @Test
    fun `an opf at the zip root resolves hrefs without a prefix`() {
        val opf = parse(
            """
            <package><manifest><item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/></manifest>
            <spine><itemref idref="c1"/></spine></package>
            """.trimIndent(),
            opfPath = "content.opf",
        )
        assertEquals("", opf.baseDir)
        assertEquals("ch1.xhtml", opf.spineItems.single().href)
    }

    @Test
    fun `an empty package parses without throwing`() {
        val opf = parse("<package/>")
        assertNull(opf.title)
        assertTrue(opf.spineItems.isEmpty())
        assertNull(opf.ncxItem)
    }

    @Test
    fun `multiple properties on one item are all recognised`() {
        val opf = parse(
            """
            <package><manifest>
              <item id="n" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav scripted"/>
            </manifest><spine/></package>
            """.trimIndent(),
        )
        assertEquals(setOf("nav", "scripted"), opf.manifestById["n"]?.properties)
        assertEquals("OEBPS/nav.xhtml", opf.navItem?.href)
    }
}
