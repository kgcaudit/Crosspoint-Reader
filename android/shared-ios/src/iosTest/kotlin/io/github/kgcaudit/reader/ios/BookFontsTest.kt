@file:OptIn(ExperimentalForeignApi::class)

package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.epub.EpubDocument
import io.github.kgcaudit.reader.layout.TextStyle
import io.github.kgcaudit.reader.layout.book.BookFontTable
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.getenv
import platform.posix.memcpy
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 출판사 글꼴(책에 든 글꼴). 시험 글꼴은 저장소의 "Olo Test Sans"(Pretendard 부분집합, 보통 400 · 굵게 700) — 휴대폰 글꼴과
 * 한글 모양 · 폭이 달라 "책 글꼴로 바꾸면 조판이 바뀐다" 를 잴 수 있다.
 */
class BookFontsTest {
    private val root = NSTemporaryDirectory() + "olo-bookfonts-" + NSUUID().UUIDString
    private val fonts = getenv("OLO_TEST_FONTS")?.toKString() ?: error("OLO_TEST_FONTS is not set (see shared-ios/build.gradle.kts)")

    init {
        NSFileManager.defaultManager.createDirectoryAtPath(root, true, null, null)
    }

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(root, null)
    }

    private val sample = "Hello 가나다라마바사 1234"

    @Test
    fun `text the book sets in its own font is measured in that font and the rest in the body font`() = runTest {
        val faces = prepare(epub(regular = true, bold = true))
        val measurer = CoreTextMeasurer(18f, bookFaces = faces)
        val body = measurer.advance(sample, 0, sample.length, TextStyle())
        val book = measurer.advance(sample, 0, sample.length, TextStyle(face = 1))
        // 같으면 책이 정한 글꼴이 무시된 것이다.
        assertNotEquals(body, book)
        assertEquals(CoreTextMeasurer(18f).advance(sample, 0, sample.length, TextStyle()), body)
        // 책에 없는 번호는 본문 글꼴 — 어긋난 표 하나로 글자가 사라지지 않는다.
        assertEquals(body, measurer.advance(sample, 0, sample.length, TextStyle(face = 9)))
        faces.release()
    }

    @Test
    fun `a bold run uses the bold file and a family of only a bold file is not thickened again`() = runTest {
        val both = prepare(epub(regular = true, bold = true))
        val (heavy, synthetic) = both.select(1, bold = true)!!
        assertEquals(700, heavy.weight)
        assertFalse(synthetic)
        both.release()

        val regularOnly = prepare(epub(regular = true, bold = false))
        assertTrue(regularOnly.select(1, bold = true)!!.second, "no bold file: thicken the regular one")
        regularOnly.release()

        // "바탕B" 처럼 굵은 파일 하나뿐인 가족(굵기는 @font-face 에 없고 파일이 700 이라 밝힌다).
        val boldOnly = prepare(epub(regular = false, bold = true, declareWeights = false))
        val (face, again) = boldOnly.select(1, bold = true)!!
        assertEquals(700, face.weight)
        assertFalse(again, "a bold-only family must not be bolded twice")
        boldOnly.release()
    }

    @Test
    fun `the fingerprint changes when a font file could not be taken out`() = runTest {
        val all = prepare(epub(regular = true, bold = true))
        val missing = prepare(epub(regular = true, bold = true, dropBoldFile = true))
        // 같으면 하나를 못 꺼낸 날의 쪽을 다 꺼낸 날 그대로 써서 줄 끝이 어긋난다.
        assertNotEquals(all.fingerprint, missing.fingerprint)
        all.release()
        missing.release()
    }

    @Test
    fun `a book whose only fonts are web fonts or missing files opens with the body font and offers no publisher font`() = runTest {
        val path = "$root/webfonts.epub"
        epub(regular = true, bold = true, webFonts = true, dropBoldFile = true).writeTo(path)
        val reader = IosBookReader.open(path, "app:webfonts.epub", "webfonts.epub", ReadingStore("$root/r.tsv"), "$root/pages", "1")
        // 선언은 있으니 꺼내 보기 전에는 있다고 본다.
        assertTrue(reader.hasBookFonts())
        val frame = reader.layOut(ReadingSpec.of(360f, 640f, 0f, 0f, 0f, 0f, useBookFonts = true), 0xFFFFFF, 0, true)
        assertNotNull(frame.image)
        assertFalse(reader.hasBookFonts(), "nothing readable: the font list must not offer a publisher font")
        assertNull(reader.bookFontPreviewPath)
        reader.close()
    }

    @Test
    fun `turning publisher fonts on lays the book out again in the book font`() = runTest {
        val path = "$root/fonts.epub"
        epub(regular = true, bold = true).writeTo(path)
        val reader = IosBookReader.open(path, "app:fonts.epub", "fonts.epub", ReadingStore("$root/r.tsv"), "$root/pages", "1")
        assertTrue(reader.hasBookFonts())
        reader.layOut(ReadingSpec.of(360f, 640f, 0f, 0f, 0f, 0f), 0xFFFFFF, 0, true)
        assertNull(reader.bookFontPreviewPath, "fonts are not taken out until someone turns them on")
        reader.layOut(ReadingSpec.of(360f, 640f, 0f, 0f, 0f, 0f, useBookFonts = true), 0xFFFFFF, 0, true)
        assertNotNull(reader.bookFontPreviewPath)
        assertTrue(reader.hasBookFonts())
        reader.close()
    }

    private suspend fun prepare(bytes: ByteArray): BookFaces {
        val path = "$root/${NSUUID().UUIDString}.epub"
        bytes.writeTo(path)
        val document = EpubDocument.open(BookId("t"), "t.epub", FileSeekableSource(path))
        val table = BookFontTable.load(document)
        assertFalse(table.isEmpty)
        return BookFaces.prepare(document, table, "$root/extract-${NSUUID().UUIDString}")
    }

    /**
     * 글꼴 가족 "olo" 를 선언하고 둘째 문단에 쓰는 한 장짜리 EPUB. [webFonts] 면 글꼴 자리에 WOFF 머리만 든 파일을,
     * [dropBoldFile] 이면 선언만 하고 굵은 파일은 넣지 않는다(내려받다 빠진 책).
     */
    private fun epub(
        regular: Boolean,
        bold: Boolean,
        declareWeights: Boolean = true,
        webFonts: Boolean = false,
        dropBoldFile: Boolean = false,
    ): ByteArray {
        val faces = buildString {
            if (regular) append("@font-face{font-family:\"olo\";src:url(fonts/r.ttf);${if (declareWeights) "font-weight:400;" else ""}}")
            if (bold) append("@font-face{font-family:\"olo\";src:url(fonts/b.ttf);${if (declareWeights) "font-weight:700;" else ""}}")
        }
        val css = "$faces p.book{font-family:\"olo\",serif}"
        val woff = "wOFF".encodeToByteArray() + ByteArray(128)
        val files = buildList {
            add("mimetype" to "application/epub+zip".encodeToByteArray())
            add(
                "META-INF/container.xml" to ("<?xml version=\"1.0\"?><container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">" +
                    "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>").encodeToByteArray(),
            )
            add(
                "OEBPS/content.opf" to ("<?xml version=\"1.0\"?><package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"id\">" +
                    "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>글꼴 책</dc:title><dc:identifier id=\"id\">f</dc:identifier></metadata>" +
                    "<manifest><item id=\"c1\" href=\"c1.xhtml\" media-type=\"application/xhtml+xml\"/>" +
                    "<item id=\"css\" href=\"style.css\" media-type=\"text/css\"/>" +
                    "<item id=\"r\" href=\"fonts/r.ttf\" media-type=\"font/ttf\"/><item id=\"b\" href=\"fonts/b.ttf\" media-type=\"font/ttf\"/></manifest>" +
                    "<spine><itemref idref=\"c1\"/></spine></package>").encodeToByteArray(),
            )
            add("OEBPS/style.css" to css.encodeToByteArray())
            add(
                "OEBPS/c1.xhtml" to ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><html xmlns=\"http://www.w3.org/1999/xhtml\"><head>" +
                    "<link rel=\"stylesheet\" href=\"style.css\"/></head><body><p>본문은 휴대폰 글꼴이다.</p>" +
                    (1..20).joinToString("") { "<p class=\"book\">책이 정한 글꼴로 적은 문단 $it 가나다라마바사.</p>" } + "</body></html>").encodeToByteArray(),
            )
            if (regular) add("OEBPS/fonts/r.ttf" to if (webFonts) woff else read("olo-test-regular.ttf"))
            if (bold && !dropBoldFile) add("OEBPS/fonts/b.ttf" to if (webFonts) woff else read("olo-test-bold.ttf"))
        }
        return storedZip(files)
    }

    private fun read(name: String): ByteArray {
        val data = NSData.dataWithContentsOfFile("$fonts/$name") ?: error("missing test font $fonts/$name")
        val bytes = ByteArray(data.length.toInt())
        bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
        return bytes
    }

    private fun ByteArray.writeTo(path: String) {
        val data = usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }
        assertTrue(data.writeToFile(path, true))
    }
}
