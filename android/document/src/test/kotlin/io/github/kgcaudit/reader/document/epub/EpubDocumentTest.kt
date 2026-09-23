package io.github.kgcaudit.reader.document.epub

import io.github.kgcaudit.reader.document.BookFormat
import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.document.SeekableSource
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** EPUB 를 인메모리로 조립해 열기까지 통째로 검증한다. 기기도 파일도 필요 없다. */
class EpubDocumentTest {

    // ── EPUB 조립 도우미 ────────────────────────────────────────────

    private fun epub(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            // mimetype 은 규격상 첫 엔트리이고 무압축이어야 한다.
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

    private fun container(opfPath: String = "OEBPS/content.opf") =
        "META-INF/container.xml" to
            """<container><rootfiles><rootfile full-path="$opfPath"/></rootfiles></container>"""

    private fun open(bytes: ByteArray, name: String = "book.epub") =
        EpubDocument.open(BookId("t"), name, SeekableSource.of(bytes))

    private val epub3Opf = """
        <?xml version="1.0" encoding="UTF-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:title>어린 왕자</dc:title>
            <dc:creator>생텍쥐페리</dc:creator>
            <dc:language>ko</dc:language>
          </metadata>
          <manifest>
            <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
            <item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
            <item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
          </manifest>
          <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
        </package>
    """.trimIndent()

    private val nav = """
        <html><body><nav epub:type="toc"><ol>
          <li><a href="text/ch1.xhtml">제1장</a>
            <ol><li><a href="text/ch1.xhtml#s2">1.2 절</a></li></ol>
          </li>
          <li><a href="text/ch2.xhtml">제2장</a></li>
        </ol></nav></body></html>
    """.trimIndent()

    private fun epub3(): ByteArray = epub(
        container(),
        "OEBPS/content.opf" to epub3Opf,
        "OEBPS/nav.xhtml" to nav,
        "OEBPS/text/ch1.xhtml" to "<html><body><p>제1장 본문</p></body></html>",
        "OEBPS/text/ch2.xhtml" to "<html><body><p>제2장 본문</p></body></html>",
    )

    // ── 기본 경로 ───────────────────────────────────────────────────

    @Test
    fun `metadata comes from the opf`() {
        open(epub3()).use { doc ->
            assertEquals("어린 왕자", doc.meta.title)
            assertEquals("생텍쥐페리", doc.meta.author)
            assertEquals("ko", doc.meta.language)
            assertEquals(BookFormat.EPUB, doc.meta.format)
        }
    }

    @Test
    fun `spine follows reading order and carries entry sizes`() = runTest {
        open(epub3()).use { doc ->
            val spine = doc.spine()
            assertEquals(listOf(0, 1), spine.map { it.index })
            assertEquals(
                listOf("OEBPS/text/ch1.xhtml", "OEBPS/text/ch2.xhtml"),
                spine.map { it.href },
            )
            assertTrue(spine.all { it.sizeBytes > 0 }, "크기가 채워지지 않았다: $spine")
        }
    }

    @Test
    fun `chapter content is decoded as utf8`() = runTest {
        open(epub3()).use { doc ->
            assertTrue(doc.openChapter(0).use { it.readText() }.contains("제1장 본문"))
            assertTrue(doc.openChapter(1).use { it.readText() }.contains("제2장 본문"))
        }
    }

    @Test
    fun `the epub3 nav supplies the outline with depth and anchors`() = runTest {
        open(epub3()).use { doc ->
            val toc = doc.outline()
            assertEquals(listOf("제1장", "1.2 절", "제2장"), toc.map { it.label })
            assertEquals(listOf(0, 1, 0), toc.map { it.depth })
            assertEquals(
                listOf(Locator.Reflow(0, 0), Locator.Reflow(0, 0), Locator.Reflow(1, 0)),
                toc.map { it.locator },
            )
            // 앵커는 조판 단계가 글자 오프셋으로 좁힐 때까지 보존된다.
            assertEquals(listOf(null, "s2", null), toc.map { it.anchor })
        }
    }

    @Test
    fun `resources such as images can be opened by resolved path`() = runTest {
        val bytes = epub(
            container(),
            "OEBPS/content.opf" to epub3Opf,
            "OEBPS/nav.xhtml" to nav,
            "OEBPS/text/ch1.xhtml" to "<p/>",
            "OEBPS/text/ch2.xhtml" to "<p/>",
            "OEBPS/images/a.png" to "PNGDATA",
        )
        open(bytes).use { doc ->
            assertEquals("PNGDATA", doc.openResource("OEBPS/images/a.png")!!.use { String(it.readBytes()) })
            assertNull(doc.openResource("OEBPS/images/missing.png"))
        }
    }

    // ── EPUB 2 경로 ─────────────────────────────────────────────────

    @Test
    fun `an epub2 book reads its outline from the ncx`() = runTest {
        val bytes = epub(
            container("OEBPS/content.opf"),
            "OEBPS/content.opf" to """
                <package version="2.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>구간</dc:title></metadata>
                  <manifest>
                    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    <item id="c1" href="ch1.html" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine toc="ncx"><itemref idref="c1"/></spine>
                </package>
            """.trimIndent(),
            "OEBPS/toc.ncx" to """
                <ncx><navMap><navPoint><navLabel><text>첫 장</text></navLabel>
                <content src="ch1.html"/></navPoint></navMap></ncx>
            """.trimIndent(),
            "OEBPS/ch1.html" to "<p>본문</p>",
        )
        open(bytes).use { doc ->
            assertEquals("구간", doc.meta.title)
            assertEquals(listOf("첫 장"), doc.outline().map { it.label })
            assertEquals(Locator.Reflow(0, 0), doc.outline().single().locator)
        }
    }

    @Test
    fun `an empty nav falls back to the ncx`() = runTest {
        // nav 를 선언했지만 내용이 빈 책이 있다. 목차를 통째로 잃는 것보다 낫다.
        val bytes = epub(
            container(),
            "OEBPS/content.opf" to """
                <package version="3.0">
                  <manifest>
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine><itemref idref="c1"/></spine>
                </package>
            """.trimIndent(),
            "OEBPS/nav.xhtml" to "<html><body><nav epub:type=\"toc\"><ol/></nav></body></html>",
            "OEBPS/toc.ncx" to """<ncx><navMap><navPoint><navLabel><text>NCX 항목</text></navLabel>
                                  <content src="ch1.xhtml"/></navPoint></navMap></ncx>""",
            "OEBPS/ch1.xhtml" to "<p/>",
        )
        open(bytes).use { doc ->
            assertEquals(listOf("NCX 항목"), doc.outline().map { it.label })
        }
    }

    // ── 깨진 책 견고성 ──────────────────────────────────────────────

    @Test
    fun `a missing container falls back to a conventional opf location`() = runTest {
        // container.xml 은 필수인데도 없는 책이 있다.
        val bytes = epub(
            "OEBPS/content.opf" to epub3Opf,
            "OEBPS/nav.xhtml" to nav,
            "OEBPS/text/ch1.xhtml" to "<p>본문</p>",
            "OEBPS/text/ch2.xhtml" to "<p>본문</p>",
        )
        open(bytes).use { doc ->
            assertEquals("어린 왕자", doc.meta.title)
            assertEquals(2, doc.spine().size)
        }
    }

    @Test
    fun `an opf in an unexpected place is still found`() = runTest {
        val bytes = epub(
            "weird/place/book.opf" to """
                <package version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>기묘한 배치</dc:title></metadata>
                  <manifest><item id="c1" href="a.xhtml" media-type="application/xhtml+xml"/></manifest>
                  <spine><itemref idref="c1"/></spine>
                </package>
            """.trimIndent(),
            "weird/place/a.xhtml" to "<p>본문</p>",
        )
        open(bytes).use { doc ->
            assertEquals("기묘한 배치", doc.meta.title)
            assertEquals(listOf("weird/place/a.xhtml"), doc.spine().map { it.href })
        }
    }

    @Test
    fun `a spine item absent from the archive is skipped`() = runTest {
        // 선언은 있는데 파일이 없는 책이 있다. 여기서 실패하면 나머지가 멀쩡한 책을 잃는다.
        val bytes = epub(
            container(),
            "OEBPS/content.opf" to epub3Opf,
            "OEBPS/nav.xhtml" to nav,
            "OEBPS/text/ch1.xhtml" to "<p>본문</p>",
            // ch2.xhtml 을 일부러 넣지 않는다
        )
        open(bytes).use { doc ->
            assertEquals(listOf("OEBPS/text/ch1.xhtml"), doc.spine().map { it.href })
            // 사라진 챕터를 가리키던 목차 항목도 빠진다(눌러도 아무 일 없는 항목 방지).
            assertEquals(listOf("제1장", "1.2 절"), doc.outline().map { it.label })
        }
    }

    @Test
    fun `a book with no title falls back to the file name`() {
        // 제목이 빈 책이 실제로 있다. 목록에 빈 줄이 생기면 고를 수가 없다.
        val bytes = epub(
            container(),
            "OEBPS/content.opf" to """
                <package version="3.0"><metadata/>
                  <manifest><item id="c1" href="a.xhtml" media-type="application/xhtml+xml"/></manifest>
                  <spine><itemref idref="c1"/></spine></package>
            """.trimIndent(),
            "OEBPS/a.xhtml" to "<p/>",
        )
        open(bytes, name = "제목 없는 책.epub").use { doc ->
            assertEquals("제목 없는 책", doc.meta.title)
            assertNull(doc.meta.author)
        }
    }

    @Test
    fun `a chapter with a utf8 bom does not leak an invisible character`() = runTest {
        val bom = "﻿"
        val bytes = epub(
            container(),
            "OEBPS/content.opf" to """
                <package version="3.0"><metadata/>
                  <manifest><item id="c1" href="a.xhtml" media-type="application/xhtml+xml"/></manifest>
                  <spine><itemref idref="c1"/></spine></package>
            """.trimIndent(),
            "OEBPS/a.xhtml" to "$bom<html><body><p>본문</p></body></html>",
        )
        open(bytes).use { doc ->
            assertEquals('<', doc.openChapter(0).use { it.readText() }.first())
        }
    }

    @Test
    fun `asking for a chapter outside the spine fails loudly`() = runTest {
        open(epub3()).use { doc ->
            assertFailsWith<IOException> { doc.openChapter(99) }
        }
    }

    @Test
    fun `a non epub archive fails to open`() {
        val notAnEpub = epub("readme.txt" to "그냥 zip 이다")
        assertFailsWith<IOException> { open(notAnEpub) }
        assertFailsWith<IOException> { open("zip 도 아니다".toByteArray()) }
    }

    @Test
    fun `the cover image is exposed when declared`() {
        val bytes = epub(
            container(),
            "OEBPS/content.opf" to """
                <package version="3.0"><metadata/>
                  <manifest>
                    <item id="cov" href="cover.jpg" media-type="image/jpeg" properties="cover-image"/>
                    <item id="c1" href="a.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine><itemref idref="c1"/></spine></package>
            """.trimIndent(),
            "OEBPS/cover.jpg" to "JPEGDATA",
            "OEBPS/a.xhtml" to "<p/>",
        )
        open(bytes).use { doc ->
            assertNotNull(doc.openCoverImage())
            assertEquals("JPEGDATA", doc.openCoverImage()!!.use { String(it.readBytes()) })
        }
    }
}
