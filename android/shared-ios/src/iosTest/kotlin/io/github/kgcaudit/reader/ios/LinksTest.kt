@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.kgcaudit.reader.ios

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.writeToFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 각주 · 책 안 링크(구상안 18, 안드로이드 F1–F6). 링크 글자를 실제로 "누른다" — 쪽 위 자리에서 링크를 찾는 길까지 본다. */
class LinksTest {
    private val root = NSTemporaryDirectory() + "olo-links-" + NSUUID().UUIDString

    init {
        NSFileManager.defaultManager.createDirectoryAtPath(root, true, null, null)
    }

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(root, null)
    }

    private val spec = ReadingSpec.of(360f, 640f, 0f, 0f, 0f, 0f)

    @Test
    fun `tapping a footnote mark opens its text and tapping plain text is not a link`() = runTest {
        val reader = open()
        val (x, y) = center(reader, "c2.xhtml#n1")
        val action = assertNotNull(reader.tapLink(x, y))
        assertEquals("footnote", action.kind)
        assertEquals("각주 1", action.title)
        assertTrue(action.text.startsWith("이 찻집은 1952년"), action.text)
        // 링크 없는 자리(쪽 아래 빈 곳)는 링크가 아니다 — 화면이 넘김으로 처리한다.
        assertNull(reader.tapLink(180f, 600f))
    }

    @Test
    fun `a link inside the book jumps there and reading place comes back`() = runTest {
        val reader = open()
        val (x, y) = center(reader, "c2.xhtml#hist")
        val action = assertNotNull(reader.tapLink(x, y))
        assertEquals("jump", action.kind)
        val there = assertNotNull(action.frame)
        assertEquals(1, there.spineIndex)
        assertTrue(there.canReturn)
        val back = assertNotNull(reader.returnBack())
        assertEquals(0, back.spineIndex)
        assertEquals(0, back.pageIndex)
        assertFalse(back.canReturn)
        assertNull(reader.returnBack(), "nothing to go back to twice")
    }

    @Test
    fun `moving by the progress bar forgets the way back`() = runTest {
        val reader = open()
        val (x, y) = center(reader, "c2.xhtml#hist")
        reader.tapLink(x, y)
        // 진도 막대로 옮긴 뒤 "읽던 곳으로" 가 남으면 엉뚱한 옛 자리로 데려간다.
        assertFalse(reader.seek(50f).canReturn)
        assertNull(reader.returnBack())
    }

    @Test
    fun `an outside address asks before opening and a broken link says so instead of failing`() = runTest {
        val reader = open()
        val outside = center(reader, "https://example.com/lighthouse")
        val ask = assertNotNull(reader.tapLink(outside.first, outside.second))
        assertEquals("external", ask.kind)
        assertEquals("https://example.com/lighthouse", ask.url)
        val broken = center(reader, "gone.xhtml")
        val missing = assertNotNull(reader.tapLink(broken.first, broken.second))
        assertEquals("missing", missing.kind)
        assertEquals("링크가 가리키는 곳을 책에서 찾지 못했습니다", missing.text)
    }

    @Test
    fun `footnote marks stay readable on every paper`() {
        // 회색 지면(0xDAD8D3)에서 강조 글자색 그대로면 대비가 4.5 에 못 미친다 — 작은 위첨자는 읽기 어렵다.
        val gray = 0xDAD8D3
        val color = PagePainter.readableOn(0x9A4A2C, gray, 0x1F1D1A)
        assertTrue(contrast(color, gray) >= 4.5, "contrast ${contrast(color, gray)}")
        // 흰 지면에서는 이미 넉넉해 바꾸지 않는다.
        assertEquals(0x9A4A2C, PagePainter.readableOn(0x9A4A2C, 0xFFFFFF, 0x1D1A16))
    }

    private suspend fun center(reader: IosBookReader, href: String): Pair<Float, Float> =
        reader.linkCenters().firstOrNull { it.first == href }?.second ?: error("no link $href on the page: ${reader.linkCenters()}")

    private suspend fun open(): IosBookReader {
        val path = "$root/notes.epub"
        val bytes = epub()
        val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        assertTrue(data.writeToFile(path, true))
        val reader = IosBookReader.open(path, "app:notes.epub", "notes.epub", ReadingStore("$root/r.tsv"), "$root/pages", "1")
        reader.layOut(spec, 0xFFFFFF, 0, true)
        return reader
    }

    private fun contrast(a: Int, b: Int): Double {
        fun lin(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else kotlin.math.exp(2.4 * kotlin.math.ln((s + 0.055) / 1.055)) }
        fun lum(c: Int) = 0.2126 * lin((c shr 16) and 0xFF) + 0.7152 * lin((c shr 8) and 0xFF) + 0.0722 * lin(c and 0xFF)
        val la = lum(a)
        val lb = lum(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun epub(): ByteArray {
        val c1 = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><html xmlns=\"http://www.w3.org/1999/xhtml\"><body><h1>둘째 장 등대</h1>" +
            "<p>등대 아래에는 작은 찻집이 있었다.<sup><a href=\"c2.xhtml#n1\">1</a></sup> 주인은 말없이 보리차를 내주었다.</p>" +
            "<p>자세한 내력은 <a href=\"c2.xhtml#hist\">부록 등대의 역사</a> 에 적어 두었다.</p>" +
            "<p>사진은 <a href=\"https://example.com/lighthouse\">마을 누리집</a> 에 있다.</p>" +
            "<p>지도는 <a href=\"gone.xhtml\">사라진 장</a> 에 있었다.</p></body></html>"
        val c2 = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><html xmlns=\"http://www.w3.org/1999/xhtml\"><body>" +
            "<h1 id=\"hist\">부록 등대의 역사</h1>" + (1..12).joinToString("") { "<p>등대는 1931년에 세워졌다. 기록 $it.</p>" } +
            "<p id=\"n1\">이 찻집은 1952년 등대지기의 가족이 처음 열었다.</p></body></html>"
        return storedZip(
            listOf(
                "mimetype" to "application/epub+zip",
                "META-INF/container.xml" to "<?xml version=\"1.0\"?><container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">" +
                    "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>",
                "OEBPS/content.opf" to "<?xml version=\"1.0\"?><package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"id\">" +
                    "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>각주 책</dc:title><dc:identifier id=\"id\">n</dc:identifier></metadata>" +
                    "<manifest><item id=\"c1\" href=\"c1.xhtml\" media-type=\"application/xhtml+xml\"/><item id=\"c2\" href=\"c2.xhtml\" media-type=\"application/xhtml+xml\"/></manifest>" +
                    "<spine><itemref idref=\"c1\"/><itemref idref=\"c2\"/></spine></package>",
                "OEBPS/c1.xhtml" to c1,
                "OEBPS/c2.xhtml" to c2,
            ).map { (n, t) -> n to t.encodeToByteArray() },
        )
    }
}
