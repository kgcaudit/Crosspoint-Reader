package io.github.kgcaudit.reader.layout.book

import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.ByteSource
import io.github.kgcaudit.reader.document.SeekableSource
import io.github.kgcaudit.reader.document.TxtDocument
import io.github.kgcaudit.reader.document.epub.EpubDocument
import io.github.kgcaudit.reader.layout.Block
import io.github.kgcaudit.reader.layout.FakeMeasurer
import io.github.kgcaudit.reader.layout.Insets
import io.github.kgcaudit.reader.layout.LayoutSpec
import io.github.kgcaudit.reader.layout.Paginator
import io.github.kgcaudit.reader.layout.TextAlign
import io.github.kgcaudit.reader.layout.html.StyleContext
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 파일 바이트에서 페이지까지, 실제 EPUB 과 TXT 로 확인한다.
 *
 * 앞선 테스트들이 층마다 따로 지키는 것과 달리 여기서는 **진짜 zip 을 만들어** 넣는다.
 * 외부 CSS 를 챕터 기준 상대 경로로 찾는 일(`../styles/main.css`)처럼, 층을 다 이어
 * 놓지 않으면 드러나지 않는 실수가 실제로 가장 많다.
 */
class ChapterLoaderTest {

    private val spec = LayoutSpec(
        viewportWidthPx = 360f,
        viewportHeightPx = 240f,
        margin = Insets.all(20f),
        baseSizePx = 10f,
        align = TextAlign.Justify,
        paragraphIndentEm = 1f,
    )

    // ── EPUB 조립 ───────────────────────────────────────────────────

    private fun epubBytes(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val mime = "application/epub+zip".toByteArray()
            zip.putNextEntry(
                ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED
                    size = mime.size.toLong()
                    compressedSize = mime.size.toLong()
                    crc = CRC32().apply { update(mime) }.value
                },
            )
            zip.write(mime)
            zip.closeEntry()
            entries.forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val opf = """
        <?xml version="1.0" encoding="UTF-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:title>어린 왕자</dc:title><dc:language>ko</dc:language>
          </metadata>
          <manifest>
            <item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
            <item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
            <item id="css" href="styles/main.css" media-type="text/css"/>
            <item id="rose" href="images/rose.png" media-type="image/png"/>
          </manifest>
          <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
        </package>
    """.trimIndent()

    /** 챕터는 `text/` 안에 있고 CSS 와 그림은 `../` 로 가리킨다. 실제 책의 배치다. */
    private fun book(): ByteArray = epubBytes(
        "META-INF/container.xml" to
            """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""",
        "OEBPS/content.opf" to opf,
        "OEBPS/styles/main.css" to """
            body { text-align: justify }
            h1 { text-align: center }
            .quote { margin-left: 3em; font-style: italic }
        """.trimIndent(),
        "OEBPS/text/ch1.xhtml" to """
            <?xml version="1.0" encoding="utf-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
            <head>
              <title>제1장</title>
              <link rel="stylesheet" type="text/css" href="../styles/main.css"/>
              <style>p.lead { font-weight: bold }</style>
            </head>
            <body>
              <h1 id="top">제1장</h1>
              <p class="lead">여섯 살 적에 나는 본 적이 있다.</p>
              <p class="quote" id="q">가장 중요한 것은 눈에 보이지 않아.</p>
              <img src="../images/rose.png"/>
            </body></html>
        """.trimIndent(),
        "OEBPS/text/ch2.xhtml" to
            """<html><head><link rel="stylesheet" href="../styles/main.css"/></head>
               <body><h1>제2장</h1><p>보아뱀이 코끼리를 삼켰다.</p></body></html>""",
        "OEBPS/images/rose.png" to "PNGDATA",
    )

    private fun openEpub(bytes: ByteArray = book()) =
        EpubDocument.open(BookId("t"), "book.epub", SeekableSource.of(bytes))

    // ── EPUB ────────────────────────────────────────────────────────

    @Test
    fun `an external stylesheet is found relative to the chapter`() = runTest {
        openEpub().use { doc ->
            val chapter = ChapterLoader(doc, spec).load(0)
            val quote = chapter.blocks.first { it.charStart == chapter.anchors.getValue("q") }
            // ../styles/main.css 를 못 찾았다면 이 값이 0 이다.
            assertEquals(3f, quote.style.indentStartEm)
            assertTrue((quote as Block.Paragraph).runs.all { it.style.italic })
        }
    }

    @Test
    fun `the head scan finds the stylesheet links and the title`() = runTest {
        openEpub().use { doc ->
            val loader = ChapterLoader(doc, spec)
            // stylesheetFor 는 <link> 로 가리킨 외부 CSS 만 본다(규칙 3개).
            // <style> 블록은 본문을 훑는 중에 ChapterParser 가 덧붙인다.
            assertEquals(3, loader.stylesheetFor(0).rules.size)
        }
    }

    @Test
    fun `an embedded style still beats the external stylesheet`() = runTest {
        openEpub().use { doc ->
            val chapter = ChapterLoader(doc, spec).load(0)
            val lead = chapter.blocks
                .filterIsInstance<Block.Paragraph>()
                .first { chapter.text.substring(it.charStart, it.charEndExclusive).startsWith("여섯") }
            assertTrue(lead.runs.single().style.bold)
        }
    }

    @Test
    fun `an image href stays relative and the document resolves it`() = runTest {
        openEpub().use { doc ->
            val chapter = ChapterLoader(doc, spec).load(0)
            val image = chapter.blocks.filterIsInstance<Block.Image>().single()
            assertEquals("../images/rose.png", image.href)

            // 그림을 실제로 열 수 있어야 한다. 경로 해석은 문서의 일이다.
            val bytes = doc.openChapterResource(0, image.href)!!.use { it.readBytes() }
            assertEquals("PNGDATA", String(bytes))
            assertNull(doc.openChapterResource(0, "../images/missing.png"))
        }
    }

    @Test
    fun `every chapter of the book reaches the page`() = runTest {
        openEpub().use { doc ->
            val loader = ChapterLoader(doc, spec)
            val paginator = Paginator(spec, FakeMeasurer(baseSizePx = spec.baseSizePx))

            for (item in doc.spine()) {
                val chapter = loader.load(item.index)
                val pages = paginator.paginate(chapter.text, chapter.blocks).toList()
                assertTrue(pages.isNotEmpty(), "챕터 ${item.index} 에서 페이지가 안 나왔다")
                assertEquals(0, pages.first().startChar)
                assertEquals(chapter.text.length, pages.last().endCharExclusive)
                // 제목은 외부 CSS 대로 가운데 정렬이다.
                assertEquals(TextAlign.Center, chapter.blocks.first().style.align)
            }
        }
    }

    @Test
    fun `a missing stylesheet leaves the book readable`() = runTest {
        val broken = epubBytes(
            "META-INF/container.xml" to
                """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""",
            "OEBPS/content.opf" to opf,
            // styles/main.css 를 일부러 넣지 않는다.
            "OEBPS/text/ch1.xhtml" to
                """<html><head><link rel="stylesheet" href="../styles/main.css"/></head>
                   <body><p>본문은 남아야 한다.</p></body></html>""",
            "OEBPS/text/ch2.xhtml" to "<html><body><p>둘</p></body></html>",
        )
        openEpub(broken).use { doc ->
            val chapter = ChapterLoader(doc, spec).load(0)
            assertEquals("본문은 남아야 한다.", chapter.text)
        }
    }

    // ── TXT ─────────────────────────────────────────────────────────

    @Test
    fun `a txt file becomes one paragraph per line`() = runTest {
        val content = "첫째 줄\r\n\r\n둘째 줄\n   \n셋째 줄"
        val doc = TxtDocument.open(BookId("t"), "책.txt", ByteSource.of(content.toByteArray()))
        val chapter = ChapterLoader(doc, spec).load(0)

        assertEquals(3, chapter.blocks.size)
        assertEquals(
            listOf("첫째 줄", "둘째 줄", "셋째 줄"),
            chapter.blocks.map { chapter.text.substring(it.charStart, it.charEndExclusive) },
        )
        // 줄바꿈은 텍스트에 남는다 — 파일과 같은 좌표계라야 책갈피가 밀리지 않는다.
        assertTrue(chapter.text.contains('\n'))
        assertFalse(chapter.text.contains('\r'))
        assertTrue(chapter.anchors.isEmpty())
    }

    @Test
    fun `a txt paragraph carries no book styling of its own`() = runTest {
        val doc = TxtDocument.open(BookId("t"), "책.txt", ByteSource.of("한 줄".toByteArray()))
        val style = ChapterLoader(doc, StyleContext.of(spec)).load(0).blocks.single().style
        // 들여쓰기·정렬은 전부 사용자 설정이 맡는다.
        assertNull(style.firstLineIndentEm)
        assertEquals(0f, style.indentStartEm)
        assertEquals(TextAlign.Justify, style.align)
    }

    @Test
    fun `a txt file paginates from the first character to the last`() = runTest {
        val content = (1..40).joinToString("\n") { "$it 번째 줄, 어린 왕자는 사막에서 조종사를 만났다." }
        val doc = TxtDocument.open(BookId("t"), "책.txt", ByteSource.of(content.toByteArray()))
        val chapter = ChapterLoader(doc, spec).load(0)

        val pages = Paginator(spec, FakeMeasurer(baseSizePx = spec.baseSizePx))
            .paginate(chapter.text, chapter.blocks).toList()

        assertTrue(pages.size > 1, "한 페이지에 다 들어가면 이 테스트는 아무것도 안 지킨다")
        assertEquals(0, pages.first().startChar)
        assertEquals(chapter.text.length, pages.last().endCharExclusive)
        pages.zipWithNext { a, b -> assertTrue(b.startChar >= a.startChar) }
    }
}
