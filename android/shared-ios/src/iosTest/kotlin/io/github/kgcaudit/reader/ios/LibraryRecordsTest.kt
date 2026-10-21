@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.github.kgcaudit.reader.ios

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.decomposedStringWithCanonicalMapping
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 서재 이름표(구상안 29A) · 읽은 시간 기록(30). */
class LibraryRecordsTest {
    private val root = NSTemporaryDirectory() + "olo-records-" + NSUUID().UUIDString

    init {
        NSFileManager.defaultManager.createDirectoryAtPath(root, true, null, null)
    }

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(root, null)
    }

    @Test
    fun `the library shows the title and author written in the book`() {
        val path = write("b.epub", epub("<dc:title>어린 왕자</dc:title><dc:creator>생텍쥐페리</dc:creator>"))
        val info = IosBookReader.info(path, "어린왕자_스캔본(1).epub")
        assertEquals("어린 왕자", info.title)
        assertEquals("생텍쥐페리", info.author)
    }

    @Test
    fun `a book without a title or one that cannot be read keeps its file name`() {
        val untitled = IosBookReader.info(write("u.epub", epub("")), "옛 일기.epub")
        assertEquals("옛 일기", untitled.title)
        assertNull(untitled.author)
        // zip 이 아닌 EPUB · TXT: 열지 않거나 열지 못해도 서재에서 사라지지 않는다.
        assertEquals("깨진 책", IosBookReader.info(write("x.epub", "zip 아님".encodeToByteArray()), "깨진 책.epub").title)
        assertEquals("메모", IosBookReader.info("$root/없는 파일.txt", "메모.txt").title)
    }

    @Test
    fun `reading time adds up per book day and way and survives reopening`() {
        val path = "$root/r.tsv"
        val store = ReadingStore(path)
        store.addTime("app:a.epub", 20_000, "READ", 60_000)
        store.addTime("app:a.epub", 20_000, "READ", 30_000)
        store.addTime("app:a.epub", 20_000, "AUTO", 10_000)
        store.addTime("app:a.epub", 20_001, "READ", 5_000)
        store.addTime("app:a.epub", 20_001, "READ", -9_000) // 시계가 뒤로 갔다 — 버린다.
        val again = ReadingStore(path).times().associate { (it.day to it.mode) to it.millis }
        assertEquals(90_000, again[20_000L to "READ"])
        assertEquals(10_000, again[20_000L to "AUTO"], "auto turning is counted apart from reading")
        assertEquals(5_000, again[20_001L to "READ"])
    }

    @Test
    fun `one day never holds more than a day and a broken time line drops only itself`() {
        val path = "$root/r.tsv"
        val store = ReadingStore(path)
        store.addTime("app:a.epub", 20_000, "READ", 20 * 3_600_000L)
        store.addTime("app:a.epub", 20_000, "READ", 20 * 3_600_000L)
        assertEquals(24 * 3_600_000L, store.times().single().millis)
        val file = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)!!
        @Suppress("CAST_NEVER_SUCCEEDS")
        ((file + "T\tapp:b.epub\t어제\tREAD\t100\n" + "T\tapp:c.epub\t20000\tSKIM\t700\n") as NSString)
            .writeToFile(path, true, NSUTF8StringEncoding, null)
        val again = ReadingStore(path).times()
        assertEquals(2, again.size)
        // 모르는 방식은 버리지 않는다 — 새 판이 더한 방식을 옛 판이 지우면 안 된다.
        assertTrue(again.any { it.mode == "SKIM" && it.millis == 700L })
    }

    @Test
    fun `records written under a decomposed hangul id are found under the composed id`() {
        val path = "$root/r.tsv"
        @Suppress("CAST_NEVER_SUCCEEDS")
        val nfd = ("app:짧은 메모.txt" as NSString).decomposedStringWithCanonicalMapping
        assertTrue(nfd != "app:짧은 메모.txt")
        @Suppress("CAST_NEVER_SUCCEEDS")
        ("T\t$nfd\t20000\tREAD\t500\nR\t$nfd\t1\t-\n" as NSString).writeToFile(path, true, NSUTF8StringEncoding, null)
        val store = ReadingStore(path)
        store.addTime("app:짧은 메모.txt", 20_000, "READ", 100)
        // 옛 판이 풀어 쓴 꼴로 적은 줄과 새 줄이 한 책 · 한 칸으로 모인다(둘로 갈라지면 통계 · 백업에서 반이 사라진다).
        assertEquals(600, store.times().single().millis)
        assertEquals(1, store.shelf(listOf("app:짧은 메모.txt")).single().openedAtEpochMs)
    }

    private fun write(name: String, bytes: ByteArray): String {
        val path = "$root/$name"
        val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        assertTrue(data.writeToFile(path, true))
        return path
    }

    private fun epub(meta: String): ByteArray = storedZip(
        listOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to "<?xml version=\"1.0\"?><container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">" +
                "<rootfiles><rootfile full-path=\"content.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>",
            "content.opf" to "<?xml version=\"1.0\"?><package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"id\">" +
                "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">$meta<dc:identifier id=\"id\">n</dc:identifier></metadata>" +
                "<manifest><item id=\"c\" href=\"c.xhtml\" media-type=\"application/xhtml+xml\"/></manifest><spine><itemref idref=\"c\"/></spine></package>",
            "c.xhtml" to "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>글</p></body></html>",
        ).map { (n, t) -> n to t.encodeToByteArray() },
    )
}
