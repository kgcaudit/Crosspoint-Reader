package io.github.kgcaudit.reader.document.epub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContainerParserTest {

    @Test
    fun `the opf path is read from the first rootfile`() {
        val xml = """
            <?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
        """.trimIndent()
        assertEquals("OEBPS/content.opf", ContainerParser.parse(xml.reader()))
    }

    @Test
    fun `the first rootfile wins when several are declared`() {
        val xml = """
            <container><rootfiles>
              <rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/>
              <rootfile full-path="alt/other.opf" media-type="application/oebps-package+xml"/>
            </rootfiles></container>
        """.trimIndent()
        assertEquals("EPUB/package.opf", ContainerParser.parse(xml.reader()))
    }

    @Test
    fun `a percent encoded path is decoded to the zip entry name`() {
        val xml = """<container><rootfiles>
            <rootfile full-path="%ED%95%9C%EA%B8%80/content.opf"/></rootfiles></container>"""
        assertEquals("한글/content.opf", ContainerParser.parse(xml.reader()))
    }

    @Test
    fun `a leading slash is stripped`() {
        val xml = """<container><rootfiles><rootfile full-path="/OEBPS/content.opf"/></rootfiles></container>"""
        assertEquals("OEBPS/content.opf", ContainerParser.parse(xml.reader()))
    }

    @Test
    fun `no rootfile yields null so the caller can try the fallback paths`() {
        assertNull(ContainerParser.parse("<container><rootfiles/></container>".reader()))
        assertNull(ContainerParser.parse("".reader()))
        assertNull(ContainerParser.parse("""<container><rootfiles><rootfile full-path=""/></rootfiles></container>""".reader()))
    }
}
