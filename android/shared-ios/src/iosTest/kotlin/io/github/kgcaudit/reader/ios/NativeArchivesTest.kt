@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.github.kgcaudit.reader.ios

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.getenv
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CBR · CB7(안드로이드 0.37.0): 같은 해제기 원본으로 iOS 서재에 나오고 열린다. 압축 속 rar · 7z 권도. */
class NativeArchivesTest {
    private val root = NSTemporaryDirectory() + "olo-native-" + NSUUID().UUIDString
    private val cache = "$root/cache"
    /** 안드로이드 :archive 의 시험 견본(comic.cb7 · unsafe.cb7) — 7z 를 만드는 도구가 이 맥에 없어 그쪽 파일을 그대로 쓴다. */
    private val samples = getenv("OLO_TEST_ARCHIVES")?.toKString() ?: error("OLO_TEST_ARCHIVES is not set (see shared-ios/build.gradle.kts)")

    init {
        NSFileManager.defaultManager.createDirectoryAtPath(root, true, null, null)
    }

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(root, null)
    }

    @Test
    fun `a cbr shows up as a comic and opens its pages in order`() {
        val path = write("작품 1권.cbr", storedRar(listOf("10.png" to page(10), "2.png" to page(2), "1.png" to page(1))))
        val unit = assertNotNull(IosComics.probe(path, "app:작품 1권.cbr", "작품 1권.cbr", emptyList(), 100, 1))
        assertEquals(3, unit.pageCount)
        val book = assertNotNull(IosComicBook.openArchive(path, cache))
        assertEquals(listOf("1.png", "2.png", "10.png"), book.pages, "natural order, as for cbz")
        assertTrue(book.data(2)!!.toByteArray().contentEquals(page(10)), "the bytes of that page, not another")
        book.close()
    }

    @Test
    fun `a cbr is unpacked once and a half unpacked one is unpacked again`() {
        val path = write("a.cbr", storedRar(listOf("1.png" to page(1), "2.png" to page(2))))
        IosComicBook.openArchive(path, cache)!!.close()
        val dir = assertNotNull(NSFileManager.defaultManager.contentsOfDirectoryAtPath("$cache/nested/unpacked", null)?.single()) as String
        val unpacked = "$cache/nested/unpacked/$dir"
        // 다 풀었다는 표시 없이 한 쪽이 빠진 꼴(앱이 풀다 닫힘) — 다시 열면 처음부터 다시 풀어야 한다.
        NSFileManager.defaultManager.removeItemAtPath("$unpacked/done", null)
        NSFileManager.defaultManager.removeItemAtPath("$unpacked/2.png", null)
        val book = IosComicBook.openArchive(path, cache)!!
        assertTrue(book.data(1)!!.toByteArray().contentEquals(page(2)), "the missing page comes back")
        book.close()
    }

    @Test
    fun `a locked or broken rar page drops out alone`() {
        val locked = write("잠김.cbr", storedRar(listOf("1.png" to page(1), "2.png" to page(2), "3.png" to page(3)), encrypted = setOf("2.png")))
        val book = assertNotNull(IosComicBook.openArchive(locked, cache))
        assertEquals(listOf("1.png", "3.png"), book.pages, "the locked page is not a page")
        assertTrue(book.data(1)!!.toByteArray().contentEquals(page(3)), "the pages after it still open")
        book.close()
        // 머리가 깨진 rar: 이름이 만화라고 말하면 서재에는 두고(열 때 알린다), 열면 null — 앱이 멈추지 않는다.
        val good = storedRar(listOf("1.png" to page(1)))
        val broken = write("깨짐.cbr", good.copyOf().also { it[7] = (it[7] + 1).toByte() })
        assertNotNull(IosComics.probe(broken, "app:깨짐.cbr", "깨짐.cbr", emptyList(), 1, 1))
        assertNull(IosComicBook.openArchive(broken, cache))
        assertNull(IosComics.probe(write("깨짐.rar", good.copyOf().also { it[7] = (it[7] + 1).toByte() }), "app:깨짐.rar", "깨짐.rar", emptyList(), 1, 1),
            "a plain .rar that cannot be read is not a comic")
    }

    @Test
    fun `a cb7 opens and an entry that would land outside the folder is not a page`() {
        val book = assertNotNull(IosComicBook.openArchive("$samples/comic.cb7", cache))
        assertEquals(listOf("001.png", "002.png", "003.png"), book.pages)
        assertTrue(book.data(2)!!.length > 0u)
        book.close()
        // "../evil.png" 는 풀 곳 밖에 쓰인다 — 쪽에서 빠지고, 캐시 폴더 밖에 파일이 생기지 않는다.
        val unsafe = assertNotNull(IosComicBook.openArchive("$samples/unsafe.cb7", cache))
        assertTrue(unsafe.pages.none { "evil" in it })
        unsafe.close()
        assertTrue(!NSFileManager.defaultManager.fileExistsAtPath("$cache/nested/evil.png"))
    }

    @Test
    fun `rar volumes packed in a series zip open as volumes`() {
        val outer = write("작품 (1-2).zip", deflatedZip(listOf(
            "작품 1권.cbr" to storedRar(listOf("1.png" to page(1), "2.png" to page(2))),
            "작품 2권.cb7" to readFile("$samples/comic.cb7"),
        )))
        val units = IosNestedVolumes.probe(outer, "app:작품 (1-2).zip", "작품 (1-2).zip", emptyList(), "1|1", 1, cache)
        assertEquals(listOf(2, 3), units.map { it.pageCount }, "a rar and a 7z volume, each with its own pages")
        val book = assertNotNull(IosComicBook.openNested(outer, units[0].id, "1|1", cache))
        assertTrue(book.data(1)!!.toByteArray().contentEquals(page(2)))
        book.close()
    }

    @Test
    fun `a volume list saved before rar support is read again`() {
        val outer = write("옛.zip", deflatedZip(listOf("1권.cbr" to storedRar(listOf("1.png" to page(1))))))
        // 옛 판(olo-nested-1)은 rar 권을 열지 못해 빈 목록으로 적었다 — 그대로 믿으면 권이 영영 나오지 않는다.
        val id = "app:옛.zip"
        val probe = "$cache/nested/probe"
        NSFileManager.defaultManager.createDirectoryAtPath(probe, true, null, null)
        TextFiles.write("$probe/" + IosNestedVolumes.key(id, "1|1", "1권.cbr") + ".txt", "olo-nested-1")
        assertEquals(1, IosNestedVolumes.probe(outer, id, "옛.zip", emptyList(), "1|1", 1, cache).size)
    }

    private fun page(n: Int) = "page $n".encodeToByteArray()

    private fun readFile(path: String) = NSData.dataWithContentsOfFile(path)!!.toByteArray()

    private fun write(name: String, bytes: ByteArray): String {
        val path = "$root/$name"
        val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        assertTrue(data.writeToFile(path, true))
        return path
    }
}
