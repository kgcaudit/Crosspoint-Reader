@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.document.comic.NestedArchives
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/** 압축 속 책(안드로이드 0.61.0): 소설을 권별로 묶은 zip 이 서재에 한 권씩 펼쳐지고, 고른 권의 글이 그대로 열린다. */
class NestedBooksTest {
    private val root = NSTemporaryDirectory() + "olo-nested-books-" + NSUUID().UUIDString
    private val cache = "$root/cache"

    init {
        NSFileManager.defaultManager.createDirectoryAtPath(root, true, null, null)
    }

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(root, null)
    }

    @Test
    fun `a zip of novels shows each volume as a book in natural order`() {
        // 실기기의 "[윤현승] 하얀늑대들.zip" 꼴: 권별 TXT, 압축해 담김. 안내 쪽지 · 폴더 항목 · 맥 찌꺼기가 섞여 있다.
        val outer = write("하얀늑대들.zip", deflatedZip(listOf(
            "하얀늑대들/" to ByteArray(0),
            "하얀늑대들/하얀늑대들 10.txt" to novel(10),
            "하얀늑대들/하얀늑대들 2.txt" to novel(2),
            "하얀늑대들/읽어주세요.txt" to "받아 주셔서 고맙습니다".encodeToByteArray(),
            "__MACOSX/하얀늑대들/._하얀늑대들 2.txt" to novel(0),
            "하얀늑대들/외전.epub" to "epub".encodeToByteArray(),
        )))
        val books = IosNestedBooks.list(outer, "app:하얀늑대들.zip", "하얀늑대들.zip")
        // 4KB 보다 작은 TXT 는 쪽지라 빠지고, EPUB 은 크기와 무관하게 책이다.
        assertEquals(listOf("외전.epub", "하얀늑대들 2.txt", "하얀늑대들 10.txt"), books.map { it.name }, "natural order: 2 before 10")
        // id 는 안드로이드와 같은 `바깥 id#!/안쪽 경로` — 기록 · 백업 · 동기화가 이 id 로 두 기기를 잇는다.
        assertEquals("app:하얀늑대들.zip#!/하얀늑대들/하얀늑대들 2.txt", books[1].id)
        assertEquals(novel(2).size.toLong(), books[1].size, "the size is the unpacked one — it keys the reading cache")
    }

    @Test
    fun `an opened volume reads exactly that volume's text`() {
        val outer = write("묶음.zip", deflatedZip(listOf("1권.txt" to novel(1), "2권.txt" to novel(2))))
        val id = NestedArchives.id("app:묶음.zip", "2권.txt")
        val path = IosNestedBooks.extract(outer, id, STAMP, cache)
        assertTrue(path.endsWith(".txt"), "the copy keeps the extension — the reader picks TXT by it")
        assertTrue(read(path).contentEquals(novel(2)), "volume 2, not volume 1")
    }

    @Test
    fun `a half copied volume is copied again instead of opening cut short`() {
        val outer = write("묶음.zip", deflatedZip(listOf("1권.txt" to novel(1))))
        val id = NestedArchives.id("app:묶음.zip", "1권.txt")
        val path = IosNestedBooks.extract(outer, id, STAMP, cache)
        // 꺼내다 앱이 닫힌 꼴: 사본은 앞 절반, 다 꺼냈다는 표시는 없음.
        NSFileManager.defaultManager.removeItemAtPath(path.substringBeforeLast('/') + "/done", null)
        write(path, novel(1).copyOf(100))
        assertTrue(read(IosNestedBooks.extract(outer, id, STAMP, cache)).contentEquals(novel(1)), "the end of the book must not be lost")
    }

    @Test
    fun `a replaced bundle gives the new text and a removed volume fails to open`() {
        val outer = write("교체.zip", deflatedZip(listOf("1권.txt" to novel(1))))
        val id = NestedArchives.id("app:교체.zip", "1권.txt")
        IosNestedBooks.extract(outer, id, STAMP, cache)
        write("교체.zip", deflatedZip(listOf("1권.txt" to novel(7))))
        // 바깥이 바뀌면(크기 · 고친 때) 다른 사본 — 옛 글이 남지 않게.
        assertTrue(read(IosNestedBooks.extract(outer, id, "999|9", cache)).contentEquals(novel(7)))
        // 묶음에서 빠진 권은 "열리지 않음" 으로 끝난다(엉뚱한 권이 열리거나 앱이 멈추지 않는다).
        assertFails { IosNestedBooks.extract(outer, NestedArchives.id("app:교체.zip", "2권.txt"), "999|9", cache) }
        // 깨진 바깥 압축은 서재에서 책이 없을 뿐이다.
        assertEquals(emptyList(), IosNestedBooks.list(write("깨짐.zip", "zip 아님".encodeToByteArray()), "app:깨짐.zip", "깨짐.zip").map { it.name })
    }

    @Test
    fun `only the most recent volumes stay copied out`() {
        val outer = write("긴 소설.zip", deflatedZip((1..6).map { "${it}권.txt" to novel(it) }))
        for (v in 1..6) IosNestedBooks.extract(outer, NestedArchives.id("app:긴 소설.zip", "${v}권.txt"), STAMP, cache)
        assertEquals(IosNestedBooks.KEEP_UNPACKED, NSFileManager.defaultManager.contentsOfDirectoryAtPath("$cache/nested/books", null)?.size)
    }

    /** 권마다 다른 5KB 글 — 쪽지 문턱(4KB)을 넘고, 다른 권을 꺼내면 걸린다. */
    private fun novel(volume: Int) = ("$volume 권 " + "가".repeat(2000)).encodeToByteArray()

    private fun read(path: String) = NSData.dataWithContentsOfFile(path)!!.toByteArray()

    private fun write(name: String, bytes: ByteArray): String {
        val path = if (name.startsWith("/")) name else "$root/$name"
        val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        assertTrue(data.writeToFile(path, true))
        return path
    }

    private companion object {
        const val STAMP = "100|1"
    }
}
