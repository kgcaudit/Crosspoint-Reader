package io.github.kgcaudit.reader.document.xml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XmlScannerTest {

    private fun scan(xml: String): List<XmlEvent> = XmlScanner(xml.reader()).events().toList()

    /** 텍스트 이벤트만 이어 붙인다. 본문이 온전히 나오는지 보는 데 쓴다. */
    private fun textOf(xml: String): String =
        scan(xml).filterIsInstance<XmlEvent.Text>().joinToString("") { it.value }

    private fun starts(xml: String): List<XmlEvent.StartElement> =
        scan(xml).filterIsInstance<XmlEvent.StartElement>()

    // ── 기본 구조 ───────────────────────────────────────────────────

    @Test
    fun `elements and text come out in document order`() {
        assertEquals(
            listOf(
                XmlEvent.StartElement(XmlName(null, "p")),
                XmlEvent.Text("첫 문장"),
                XmlEvent.EndElement(XmlName(null, "p")),
            ),
            scan("<p>첫 문장</p>"),
        )
    }

    @Test
    fun `a self closing element yields start then end`() {
        // 소비자가 자기닫힘 태그를 따로 다루지 않아도 되게 만든 계약이다.
        assertEquals(
            listOf(
                XmlEvent.StartElement(XmlName(null, "br")),
                XmlEvent.EndElement(XmlName(null, "br")),
            ),
            scan("<br/>"),
        )
        assertEquals(2, scan("<img src='a.png' />").size)
    }

    @Test
    fun `nesting is preserved`() {
        val xml = "<div><p>가</p><p>나</p></div>"
        val shape = scan(xml).map {
            when (it) {
                is XmlEvent.StartElement -> "<${it.name.local}>"
                is XmlEvent.EndElement -> "</${it.name.local}>"
                is XmlEvent.Text -> it.value
            }
        }
        assertEquals(
            listOf("<div>", "<p>", "가", "</p>", "<p>", "나", "</p>", "</div>"),
            shape,
        )
    }

    // ── 속성 ────────────────────────────────────────────────────────

    @Test
    fun `attributes are read with either quote style and looked up by local name`() {
        val e = starts("""<item id="c1" href='ch1.xhtml' media-type="application/xhtml+xml"/>""").first()
        assertEquals("c1", e.attribute("id"))
        assertEquals("ch1.xhtml", e.attribute("href"))
        assertEquals("application/xhtml+xml", e.attribute("media-type"))
        assertEquals(null, e.attribute("missing"))
    }

    @Test
    fun `prefixes on names and attributes are separated from local names`() {
        // EPUB은 파일마다 opf· dc· ncx· epub 접두사를 섞어 쓴다. 지역명으로 비교해야
        // 그 변주를 전부 흡수한다.
        val e = starts("""<opf:item opf:href="a.xhtml" epub:type="bodymatter"/>""").first()
        assertEquals(XmlName("opf", "item"), e.name)
        assertTrue(e.isLocal("item"))
        assertEquals("a.xhtml", e.attribute("href"))
        assertEquals("bodymatter", e.attribute("epub", "type"))
        assertEquals(null, e.attribute("opf", "type"))
    }

    @Test
    fun `tag and attribute lookup ignores case for xhtml`() {
        val e = starts("""<IMG SRC="a.png"/>""").first()
        assertTrue(e.isLocal("img"))
        assertEquals("a.png", e.attribute("src"))
    }

    @Test
    fun `an unquoted attribute value is still read`() {
        // 엄격히는 잘못된 마크업이지만 시중의 책에 있다. 여기서 포기하면 그 책이 안 열린다.
        assertEquals("ch1.xhtml", starts("<item href=ch1.xhtml />").first().attribute("href"))
    }

    @Test
    fun `a slash inside an unquoted value is part of the path`() {
        // `<img src=images/a.jpg>` 가 "images" 로 잘리고 자기닫힘으로 읽혀 그림이 사라지던 결함.
        val img = starts("<p><img src=images/a.jpg><span>x</span></p>").first { it.isLocal("img") }
        assertEquals("images/a.jpg", img.attribute("src"))
        val closing = scan("<br class=x/><p>y</p>")
        assertEquals("x", (closing[0] as XmlEvent.StartElement).attribute("class"))
        assertTrue(closing[1] is XmlEvent.EndElement, "`x/>` 는 자기닫힘이다")
    }

    @Test
    fun `an attribute with no value reads as empty rather than dropping the element`() {
        val e = starts("<option selected/>").first()
        assertEquals("", e.attribute("selected"))
    }

    @Test
    fun `garbage in the attribute area does not lose the attributes after it`() {
        // 여기서 태그 파싱을 중단하면 뒤의 href 를 잃어 목차 항목이 이동 불가가 된다.
        assertEquals("real.xhtml", starts("""<a " href="real.xhtml"/>""").first().attribute("href"))
    }

    // ── 엔티티 ──────────────────────────────────────────────────────

    @Test
    fun `xml predefined entities are resolved`() {
        assertEquals("""a<b>c&d"e'f""", textOf("<p>a&lt;b&gt;c&amp;d&quot;e&apos;f</p>"))
    }

    @Test
    fun `html entities common in epubs are resolved`() {
        // DTD 없이 쓰이므로 엄격히는 잘못된 문서지만 매우 흔하다. 처리하지 않으면
        // 본문에 &nbsp; 가 그대로 박혀 보인다.
        assertEquals(" —…‘’“”", textOf("<p>&nbsp;&mdash;&hellip;&lsquo;&rsquo;&ldquo;&rdquo;</p>"))
    }

    @Test
    fun `numeric references are resolved in both decimal and hex`() {
        assertEquals("가", textOf("<p>&#44032;</p>"))
        assertEquals("가", textOf("<p>&#xAC00;</p>"))
        assertEquals("😀", textOf("<p>&#x1F600;</p>")) // BMP 밖(이모지)
    }

    @Test
    fun `entities are resolved inside attribute values too`() {
        assertEquals("a&b<c", starts("""<a title="a&amp;b&lt;c"/>""").first().attribute("title"))
    }

    @Test
    fun `an unknown entity is passed through rather than dropped`() {
        // 지우면 글자가 조용히 사라지고, 던지면 책이 안 열린다. 남기면 무슨 일인지 보인다.
        assertEquals("&unknownref;", textOf("<p>&unknownref;</p>"))
    }

    @Test
    fun `a bare ampersand does not swallow the rest of the text`() {
        assertEquals("Tom & Jerry", textOf("<p>Tom & Jerry</p>"))
        assertEquals("a&", textOf("<p>a&</p>"))
    }

    @Test
    fun `out of range numeric references are passed through`() {
        assertEquals("&#x110000;", textOf("<p>&#x110000;</p>")) // 유니코드 범위 초과
        assertEquals("&#xD800;", textOf("<p>&#xD800;</p>")) // 서로게이트
        assertEquals("&#0;", textOf("<p>&#0;</p>"))
    }

    // ── CDATA · 주석 · 선언 ─────────────────────────────────────────

    @Test
    fun `cdata content becomes text without entity expansion`() {
        assertEquals("a < b && c", textOf("<p><![CDATA[a < b && c]]></p>"))
    }

    @Test
    fun `comments are skipped and do not split the surrounding text`() {
        assertEquals("앞뒤", textOf("<p>앞<!-- 주석 -->뒤</p>"))
        assertEquals("", textOf("<p><!-- <div>태그처럼 생긴 주석</div> --></p>"))
    }

    @Test
    fun `the xml declaration and doctype are skipped`() {
        assertEquals(
            "본문",
            textOf(
                """<?xml version="1.0" encoding="UTF-8"?>""" +
                    """<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "x.dtd">""" +
                    "<p>본문</p>",
            ),
        )
    }

    @Test
    fun `a doctype with an internal subset does not swallow the body`() {
        // 내부 서브셋의 '[' ... ']' 안에 '>' 가 있다. 대괄호 깊이를 세지 않으면
        // 여기서 선언이 일찍 끝난 것으로 보고 본문 앞부분이 통째로 사라진다.
        val xml = """<!DOCTYPE html [ <!ENTITY x "y"> <!ELEMENT p (#PCDATA)> ]><p>본문이 살아 있다</p>"""
        assertEquals("본문이 살아 있다", textOf(xml))
    }

    // ── 깨진 마크업 견고성 ──────────────────────────────────────────

    @Test
    fun `an unescaped less than sign in text is kept`() {
        // '<' 를 이스케이프하지 않은 책이 있다. 태그로 오인해 뒤를 버리면 본문을 잃는다.
        assertEquals("5 < 10 이다", textOf("<p>5 < 10 이다</p>"))
    }

    @Test
    fun `an unclosed tag at end of input does not throw`() {
        assertEquals(listOf(XmlEvent.StartElement(XmlName(null, "p"))), scan("<p"))
        assertEquals("앞", textOf("<p>앞"))
    }

    @Test
    fun `a mismatched end tag is reported and parsing continues`() {
        // 짝을 맞추는 일은 소비자 몫이다. 스캐너는 본 것을 그대로 전달한다.
        val locals = scan("<p>가</div>나</p>").mapNotNull {
            (it as? XmlEvent.EndElement)?.name?.local
        }
        assertEquals(listOf("div", "p"), locals)
        assertEquals("가나", textOf("<p>가</div>나</p>"))
    }

    @Test
    fun `an empty document yields no events`() {
        assertEquals(emptyList(), scan(""))
    }

    @Test
    fun `text outside any element is still delivered`() {
        assertEquals("헐벗은 텍스트", textOf("헐벗은 텍스트"))
    }

    // ── 버퍼 경계 ───────────────────────────────────────────────────

    @Test
    fun `constructs that straddle the internal buffer boundary parse correctly`() {
        // 내부 버퍼는 8KB다. CDATA 종료, 주석 종료, 자기닫힘 슬래시처럼 여러 글자를
        // 비교하는 지점이 버퍼 경계에 걸리면 조용히 오작동하기 쉬우므로, 경계를
        // 지나도록 앞을 채워 확인한다.
        val filler = "가".repeat(9_000)
        assertEquals("$filler!", textOf("<p>$filler<![CDATA[!]]></p>"))
        assertEquals(filler, textOf("<p>$filler<!-- 주석 --></p>"))
        val events = scan("<p>$filler<br/></p>")
        assertTrue(events.any { it is XmlEvent.StartElement && it.isLocal("br") })
        assertTrue(events.any { it is XmlEvent.EndElement && it.isLocal("br") })
    }

    @Test
    fun `a realistic opf fragment yields the spine and manifest`() {
        val opf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>어린 왕자</dc:title>
                <dc:creator>생텍쥐페리</dc:creator>
                <dc:language>ko</dc:language>
              </metadata>
              <manifest>
                <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="c2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
                <item id="css" href="style.css" media-type="text/css"/>
              </manifest>
              <spine toc="ncx">
                <itemref idref="c1"/>
                <itemref idref="c2"/>
              </spine>
            </package>
        """.trimIndent()

        val events = scan(opf)
        val items = events.filterIsInstance<XmlEvent.StartElement>().filter { it.isLocal("item") }
        assertEquals(listOf("ch1.xhtml", "ch2.xhtml", "style.css"), items.map { it.attribute("href") })

        val itemrefs = events.filterIsInstance<XmlEvent.StartElement>().filter { it.isLocal("itemref") }
        assertEquals(listOf("c1", "c2"), itemrefs.map { it.attribute("idref") })

        // dc: 접두사가 붙은 메타데이터도 지역명으로 찾힌다.
        val titleIndex = events.indexOfFirst { it is XmlEvent.StartElement && it.isLocal("title") }
        assertEquals("어린 왕자", (events[titleIndex + 1] as XmlEvent.Text).value)
    }
}
