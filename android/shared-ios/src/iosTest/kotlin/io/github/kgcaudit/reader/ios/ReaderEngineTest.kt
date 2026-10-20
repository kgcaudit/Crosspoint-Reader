@file:OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.Bookmark
import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.document.ReadingProgress
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.zlib.crc32
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReaderEngineTest {
    private val root = NSTemporaryDirectory() + "reader-test-" + NSUUID().UUIDString
    private val files = NSFileManager.defaultManager

    init {
        files.createDirectoryAtPath(root, withIntermediateDirectories = true, attributes = null, error = null)
    }

    @AfterTest
    fun cleanUp() {
        files.removeItemAtPath(root, null)
    }

    private val book = (1..40).joinToString("\n") { "제${it}문단. 등대지기는 날마다 같은 시각에 계단을 올라 불을 밝혔다. 배들은 그 불빛을 보고 길을 찾았다." }

    private fun writeText(name: String, text: String): String {
        val path = "$root/$name"
        @Suppress("CAST_NEVER_SUCCEEDS")
        (text as NSString).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
        return path
    }

    private fun spec(size: Float) = ReadingSpec.of(360f, 640f, 0f, 0f, 0f, 0f, size)

    @Test
    fun `reading position and bookmarks survive closing the app`() = runTest {
        val store = ReadingStore("$root/state/reading.tsv")
        store.save(ReadingProgress(BookId("app:책/한 권.txt"), Locator.Reflow(0, 120), 42.5f, 1000))
        // 탭 · 줄바꿈 · % 가 든 글이 줄 형식을 깨면 다음 줄들이 엉뚱한 칸으로 읽힌다.
        store.add(Bookmark(Bookmark.NO_ID, BookId("app:책/한 권.txt"), Locator.Reflow(1, 5), "첫\t줄\n둘째 100%", 2000))
        store.markOpened("app:책/한 권.txt")

        val again = ReadingStore("$root/state/reading.tsv")
        assertEquals(Locator.Reflow(0, 120), again.get(BookId("app:책/한 권.txt"))?.locator)
        val marks = again.forBook(BookId("app:책/한 권.txt"))
        assertEquals(listOf("첫\t줄\n둘째 100%"), marks.map { it.snippet })
        val shelf = again.shelf(listOf("app:책/한 권.txt", "app:안 연 책.txt")).associateBy { it.bookId }
        assertNotNull(shelf.getValue("app:책/한 권.txt").openedAtEpochMs)
        assertEquals(1, shelf.getValue("app:책/한 권.txt").bookmarkCount)
        assertNull(shelf.getValue("app:안 연 책.txt").openedAtEpochMs)
    }

    @Test
    fun `a damaged record line is skipped and the rest of the shelf is kept`() = runTest {
        val path = writeText(
            "reading.tsv",
            "P\tapp:a.txt\tr:0:10\t5.0\t100\n" +
                "P\tapp:b.txt\t망가진 자리\tNaN\n" +
                "B\tnot-a-number\n" +
                "R\tapp:a.txt\t100\t-\n",
        )
        val store = ReadingStore(path)
        assertEquals(Locator.Reflow(0, 10), store.get(BookId("app:a.txt"))?.locator)
        assertNull(store.get(BookId("app:b.txt")))
        assertNotNull(store.shelf(listOf("app:a.txt")).single().openedAtEpochMs)
    }

    @Test
    fun `reaching the last page marks the book finished once`() = runTest {
        val store = ReadingStore("$root/reading.tsv")
        store.save(ReadingProgress(BookId("x"), Locator.Reflow(0, 0), 99.96f, 500))
        store.save(ReadingProgress(BookId("x"), Locator.Reflow(0, 0), 10f, 900))
        // 다 읽은 뒤 앞으로 돌아가 다시 봐도 "다 읽음" 은 남는다(안드로이드 finishOnce 와 같다).
        assertEquals(500L, store.shelf(listOf("x")).single().finishedAtEpochMs)
    }

    @Test
    fun `changing the font size keeps the first character of the page in view`() = runTest {
        val path = writeText("book.txt", book)
        val store = ReadingStore("$root/reading.tsv")
        val reader = IosBookReader.open(path, "app:book.txt", "book.txt", store, "$root/pages", edition = "1")
        reader.layOut(spec(18f), 0xFFFFFF, 0, true)
        repeat(3) { assertNotNull(reader.next()) }
        val before = store.get(BookId("app:book.txt"))!!.locator as Locator.Reflow
        val bigger = reader.layOut(spec(26f), 0xFFFFFF, 0, true)
        val after = store.get(BookId("app:book.txt"))!!.locator as Locator.Reflow
        // 큰 글자에서는 쪽 시작이 앞당겨질 수는 있어도, 보던 글자를 지나쳐서는 안 된다.
        assertTrue(after.charOffset <= before.charOffset, "after $after, before $before")
        assertTrue(bigger.pageIndex >= 3, "bigger letters need at least as many pages before this place")
    }

    @Test
    fun `a bookmark set on a page is there when the book is opened again`() = runTest {
        val path = writeText("book.txt", book)
        val store = ReadingStore("$root/reading.tsv")
        val reader = IosBookReader.open(path, "app:book.txt", "book.txt", store, "$root/pages", edition = "1")
        reader.layOut(spec(18f), 0xFFFFFF, 0, true)
        reader.next()
        assertTrue(reader.toggleBookmark().bookmarked)
        reader.close()

        val reopened = IosBookReader.open(path, "app:book.txt", "book.txt", ReadingStore("$root/reading.tsv"), "$root/pages", "1")
        val frame = reopened.layOut(spec(18f), 0xFFFFFF, 0, true)
        assertEquals(1, frame.pageIndex, "opens where the reader left off")
        assertTrue(frame.bookmarked)
        assertFalse(reopened.toggleBookmark().bookmarked, "a second tap removes it")
    }

    @Test
    fun `the first page has no previous page and the last has no next`() = runTest {
        val path = writeText("short.txt", "한 줄짜리 책.")
        val reader = IosBookReader.open(path, "app:short.txt", "short.txt", ReadingStore("$root/r.tsv"), "$root/pages", "1")
        val frame = reader.layOut(spec(18f), 0xFFFFFF, 0, true)
        assertEquals(1, frame.pageCount)
        assertNull(reader.previous())
        assertNull(reader.next())
        assertEquals(100f, frame.percent, "the only page of the book is its end")
    }

    @Test
    fun `an epub opened from a file shows its chapters in order`() = runTest {
        val path = "$root/tiny.epub"
        tinyEpub().writeTo(path)
        val reader = IosBookReader.open(path, "app:tiny.epub", "tiny.epub", ReadingStore("$root/r.tsv"), "$root/pages", "1")
        assertEquals("작은 책", reader.title)
        val first = reader.layOut(spec(18f), 0xFFFFFF, 0, true)
        assertEquals(0, first.spineIndex)
        assertEquals(2, first.chapterCount)
        var frame = first
        while (frame.spineIndex == 0) frame = reader.next() ?: break
        assertEquals(1, frame.spineIndex, "next page after the first chapter is the second chapter")
    }

    @Test
    fun `the contents list jumps to a chapter and marks the chapter being read`() = runTest {
        val path = "$root/tiny.epub"
        tinyEpub().writeTo(path)
        val reader = IosBookReader.open(path, "app:tiny.epub", "tiny.epub", ReadingStore("$root/r.tsv"), "$root/pages", "1")
        reader.layOut(spec(18f), 0xFFFFFF, 0, true)
        val rows = reader.contents()
        assertEquals(listOf("첫째 장", "둘째 장", "없는 절"), rows.take(3).map { it.label })
        // 절은 장 아래 한 단 — 화면이 들여 그린다.
        assertEquals(1, rows[2].depth)
        assertEquals(0, reader.currentContentsIndex())
        val second = assertNotNull(reader.goToContents(1))
        assertEquals(1, second.spineIndex)
        assertEquals(0, second.pageIndex)
        assertEquals(1, reader.currentContentsIndex())
        // 없는 앵커는 그 장의 처음으로(목차가 어긋나도 책은 계속 읽힌다).
        assertEquals(1, assertNotNull(reader.goToContents(2)).spineIndex)
    }

    @Test
    fun `a broken or missing contents entry does nothing instead of failing`() = runTest {
        val path = "$root/tiny.epub"
        tinyEpub().writeTo(path)
        val reader = IosBookReader.open(path, "app:tiny.epub", "tiny.epub", ReadingStore("$root/r.tsv"), "$root/pages", "1")
        val first = reader.layOut(spec(18f), 0xFFFFFF, 0, true)
        assertNull(reader.goToContents(99))
        val gone = reader.contents().indexOfFirst { it.label == "사라진 장" }
        if (gone >= 0) assertNull(reader.goToContents(gone), "a link to a file not in the book is ignored")
        assertEquals(first.spineIndex, reader.layOut(spec(18f), 0xFFFFFF, 0, true).spineIndex, "still on the same page")
    }

    @Test
    fun `the current chapter is the last contents entry at or before the reading place`() {
        val entries = listOf(
            io.github.kgcaudit.reader.document.TocEntry("1장", Locator.Reflow(0, 0)),
            io.github.kgcaudit.reader.document.TocEntry("1장 1절", Locator.Reflow(0, 0), depth = 1, anchor = "a"),
            io.github.kgcaudit.reader.document.TocEntry("2장", Locator.Reflow(5, 0)),
        )
        // 목차가 장보다 적어도(5장 사이에 항목이 없어도) 앞 항목이 지금 장이다.
        assertEquals(0, IosBookReader.currentIndex(entries, 3))
        assertEquals(2, IosBookReader.currentIndex(entries, 9))
        assertEquals(-1, IosBookReader.currentIndex(entries.drop(2), 1))
    }

    @Test
    fun `a bookmark in the list takes the reader back to its page`() = runTest {
        val path = writeText("book.txt", book)
        val reader = IosBookReader.open(path, "app:book.txt", "book.txt", ReadingStore("$root/r.tsv"), "$root/pages", "1")
        reader.layOut(spec(18f), 0xFFFFFF, 0, true)
        repeat(2) { reader.next() }
        reader.toggleBookmark()
        reader.seek(0f)
        val marks = reader.bookmarks()
        assertEquals(1, marks.size)
        assertTrue(marks.single().snippet.isNotBlank())
        val back = assertNotNull(reader.goToBookmark(marks.single().id))
        assertEquals(2, back.pageIndex)
        assertTrue(back.bookmarked)
        assertNull(reader.goToBookmark(12345), "a removed bookmark does nothing")
    }

    @Test
    fun `wider line spacing and margins give the book more pages`() = runTest {
        // 짧은 책은 쪽 수가 우연히 같을 수 있다(40문단에서 8쪽 · 8쪽). 차이가 쌓이도록 길게.
        val path = writeText("long.txt", (1..6).joinToString("\n") { book })
        val reader = IosBookReader.open(path, "app:long.txt", "long.txt", ReadingStore("$root/r.tsv"), "$root/pages", "1")
        val normal = reader.layOut(spec(18f), 0xFFFFFF, 0, true).pageCount
        val loose = reader.layOut(ReadingSpec.of(360f, 640f, 0f, 0f, 0f, 0f, 18f, lineSpacing = 1.9f, sideMargin = 48f), 0xFFFFFF, 0, true).pageCount
        assertTrue(loose > normal, "loose $loose vs normal $normal — a setting that does not reach the layout looks broken")
    }

    @Test
    fun `picture backgrounds blend into ivory and sepia and dim on black but stay on white`() {
        assertEquals(PictureBlend.None, PictureBlend.forPaper(0xFFFFFF, true))
        assertEquals(PictureBlend.Multiply, PictureBlend.forPaper(0xE9DCC0, true))
        assertEquals(PictureBlend.Dim, PictureBlend.forPaper(0x121110, true))
        assertEquals(PictureBlend.None, PictureBlend.forPaper(0xE9DCC0, false), "\"그대로\" leaves pictures alone")
    }

    @Test
    fun `a file that is not a zip fails to open with an error instead of crashing`() = runTest {
        val path = writeText("broken.epub", "이건 zip 이 아니다")
        val failure = runCatching {
            IosBookReader.open(path, "app:broken.epub", "broken.epub", ReadingStore("$root/r.tsv"), "$root/pages", "1")
        }
        assertTrue(failure.isFailure)
    }

    @Test
    fun `file reads at any offset return the same bytes as the file`() {
        val path = "$root/bytes.bin"
        val bytes = ByteArray(10_000) { (it * 31).toByte() }
        bytes.writeTo(path)
        FileSeekableSource(path).use { source ->
            assertEquals(10_000L, source.size)
            val out = ByteArray(100)
            assertEquals(100, source.readAt(9_000, out, 0, 100))
            assertEquals(bytes[9_050], out[50])
            assertEquals(-1, source.readAt(10_000, out, 0, 10))
        }
    }

    private fun ByteArray.writeTo(path: String) {
        val data = usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }
        data.writeToFile(path, atomically = true)
    }

    /** 압축 없이(stored) 지은 두 장짜리 EPUB. */
    private fun tinyEpub(): ByteArray {
        val chapter = { title: String, body: String ->
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><html xmlns=\"http://www.w3.org/1999/xhtml\"><body><h1>$title</h1>" +
                (1..30).joinToString("") { "<p>$body $it</p>" } + "</body></html>"
        }
        return storedZip(
            listOf(
                "mimetype" to "application/epub+zip",
                "META-INF/container.xml" to "<?xml version=\"1.0\"?><container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">" +
                    "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>",
                "OEBPS/content.opf" to "<?xml version=\"1.0\"?><package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"id\">" +
                    "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>작은 책</dc:title><dc:identifier id=\"id\">x</dc:identifier></metadata>" +
                    "<manifest><item id=\"c1\" href=\"c1.xhtml\" media-type=\"application/xhtml+xml\"/><item id=\"c2\" href=\"c2.xhtml\" media-type=\"application/xhtml+xml\"/>" +
                    "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/></manifest>" +
                    "<spine><itemref idref=\"c1\"/><itemref idref=\"c2\"/></spine></package>",
                "OEBPS/nav.xhtml" to "<?xml version=\"1.0\"?><html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\">" +
                    "<body><nav epub:type=\"toc\"><ol><li><a href=\"c1.xhtml\">첫째 장</a></li><li><a href=\"c2.xhtml\">둘째 장</a>" +
                    "<ol><li><a href=\"c2.xhtml#missing\">없는 절</a></li></ol></li><li><a href=\"gone.xhtml\">사라진 장</a></li></ol></nav></body></html>",
                "OEBPS/c1.xhtml" to chapter("첫째 장", "바닷가 마을에 등대가 있었다."),
                "OEBPS/c2.xhtml" to chapter("둘째 장", "비가 내리는 아침이었다."),
            ).map { (name, text) -> name to text.encodeToByteArray() },
        )
    }
}

private inline fun <T> FileSeekableSource.use(block: (FileSeekableSource) -> T): T = try { block(this) } finally { close() }

