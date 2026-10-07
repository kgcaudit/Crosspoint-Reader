package io.github.kgcaudit.reader.document.epub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PictureBookTest {

    private fun page(body: String, head: String = "<title>제목이 길어도 세지 않는다 제목이 길어도 세지 않는다</title>") =
        "<html><head>$head<style>p { color: red }</style></head><body>$body</body></html>"

    private fun scan(path: String, xhtml: String) = PictureBooks.scan(path, xhtml.reader())

    @Test
    fun `a chapter's pictures are found from img and svg image and resolved against the chapter`() {
        val c = scan(
            "OEBPS/Text/p001.xhtml",
            page("<svg><image xlink:href=\"../Images/001.jpg\"/></svg><img src=\"../Images/002.jpg\"/><p>1</p>"),
        )
        assertEquals(listOf("OEBPS/Images/001.jpg", "OEBPS/Images/002.jpg"), c.images)
        // 머리(제목) · 스타일 글은 세지 않는다 — 보이는 것은 쪽 번호 "1" 뿐.
        assertEquals(1, c.textLength)
    }

    @Test
    fun `a book of one picture per chapter becomes pages in reading order`() {
        val chapters = (1..5).map { i -> scan("p$i.xhtml", page("<img src=\"i$i.jpg\"/>")) }
        val book = assertNotNull(PictureBooks.decide(chapters, rightToLeft = true))
        assertEquals((1..5).map { "i$it.jpg" }, book.pages.map { it.path })
        assertEquals((0..4).toList(), book.pages.map { it.spineIndex })
        assertEquals(true, book.rightToLeft)
    }

    @Test
    fun `a colophon page of text is skipped but a novel with an illustration is not a picture book`() {
        val pictures = (1..12).map { i -> scan("p$i.xhtml", page("<img src=\"i$i.jpg\"/>")) }
        val colophon = scan("end.xhtml", page("<p>" + "판권 지은이 펴낸곳 펴낸날 ".repeat(5) + "</p>"))
        // 판권 한 장은 건너뛴다(열에 하나까지).
        assertEquals(12, assertNotNull(PictureBooks.decide(pictures + colophon)).pages.size)
        // 망가뜨린 경우 1: 그림 옆에 글이 많은 장(삽화 소설 · 글을 얹은 그림책) — 그림만 보이면 글이 사라진다.
        val illustrated = scan("c1.xhtml", page("<img src=\"a.jpg\"/><p>" + "가나다라마바사아자차".repeat(5) + "</p>"))
        assertNull(PictureBooks.decide(pictures + illustrated))
        // 망가뜨린 경우 2: 글만 있는 장이 많은 책(표지 그림 몇 장 든 소설).
        val novel = (1..10).map { scan("t$it.xhtml", page("<p>" + "글".repeat(200) + "</p>")) }
        assertNull(PictureBooks.decide(pictures.take(2) + novel))
        // 그림 한 장뿐(표지만)이면 그림책이 아니다.
        assertNull(PictureBooks.decide(pictures.take(1)))
    }

    @Test
    fun `broken markup, data pictures and duplicated pictures do not break the pages`() {
        // 닫히지 않은 태그 · 꺼낼 수 없는 data: 그림 · 같은 그림을 두 번 부르는 마크업.
        val broken = scan("a.xhtml", "<html><body><div><img src=\"x.png\"><img src=\"data:image/png;base64,AAAA\"/><img src=\"x.png\"/>")
        assertEquals(listOf("x.png", "x.png"), broken.images)
        val book = assertNotNull(PictureBooks.decide(listOf(broken, scan("b.xhtml", page("<img src=\"y.png\"/>")))))
        assertEquals(listOf("x.png", "y.png"), book.pages.map { it.path })
        assertNull(PictureBooks.decide(emptyList()))
    }

    @Test
    fun `scanning stops early for a text book`() {
        val text = scan("t.xhtml", page("<p>" + "글".repeat(200) + "</p>"))
        // 100 장짜리 책: 글만 있는 장 열한 장째에서 그림책이 아님이 정해진다.
        assertEquals(false, PictureBooks.alreadyRejected(List(10) { text }, total = 100))
        assertEquals(true, PictureBooks.alreadyRejected(List(11) { text }, total = 100))
        assertEquals(true, PictureBooks.alreadyRejected(listOf(scan("c.xhtml", page("<img src=\"a.jpg\"/><p>" + "글".repeat(50) + "</p>"))), total = 100))
    }

    @Test
    fun `the opf page progression direction is read`() {
        fun opf(spine: String) = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0">
            <metadata/><manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/></manifest>
            $spine<itemref idref="a"/></spine></package>"""
        assertEquals(true, OpfParser.parse(opf("<spine page-progression-direction=\"rtl\">").reader(), "OEBPS/content.opf").rightToLeft)
        assertEquals(false, OpfParser.parse(opf("<spine page-progression-direction=\"ltr\">").reader(), "OEBPS/content.opf").rightToLeft)
        // 적혀 있지 않으면 모름(null) — 왼→오로 정한 것과 다르다.
        assertNull(OpfParser.parse(opf("<spine>").reader(), "OEBPS/content.opf").rightToLeft)
    }
}
