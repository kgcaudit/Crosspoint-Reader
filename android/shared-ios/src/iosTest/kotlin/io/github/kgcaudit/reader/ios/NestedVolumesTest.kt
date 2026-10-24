@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.document.comic.ComicUnitKind
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.writeToFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 압축 속 권(안드로이드 0.48.0): 권별 zip 을 다시 묶은 zip 이 서재에 권으로 펼쳐지고, 고른 권이 열린다. */
class NestedVolumesTest {
    private val root = NSTemporaryDirectory() + "olo-nested-" + NSUUID().UUIDString
    private val cache = "$root/cache"

    init {
        NSFileManager.defaultManager.createDirectoryAtPath(root, true, null, null)
    }

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(root, null)
    }

    @Test
    fun `volumes packed inside a completed series zip show up as volumes and open`() {
        // 실기기의 "[김민혜] 인스타 걸 (1-3, 완결).zip" 꼴: 바깥 이름과 같은 폴더 안에 권 zip 들, 압축해 담김.
        val outer = write("작품 (1-2, 완결).zip", deflatedZip(listOf(
            "작품 (1-2, 완결)/" to ByteArray(0),
            "작품 (1-2, 완결)/작품 2권.zip" to storedZip(listOf("1.png" to page(2, 1), "2.png" to page(2, 2))),
            "작품 (1-2, 완결)/작품 1권.zip" to storedZip(listOf("2.png" to page(1, 2), "1.png" to page(1, 1), "3.png" to page(1, 3))),
        )))
        val units = probe(outer, "작품 (1-2, 완결).zip")
        assertEquals(listOf("작품 1권.zip", "작품 2권.zip"), units.map { it.name }, "volumes in natural order")
        assertEquals(listOf(3, 2), units.map { it.pageCount })
        assertTrue(units.all { it.kind == ComicUnitKind.NESTED })
        // 모은 곳: 바깥이 든 폴더 + 바깥 이름. 바깥 이름과 같은 맨 위 폴더는 겹치지 않게 걷힌다.
        assertEquals(listOf("만화", "작품 (1-2, 완결).zip"), units[0].folders)

        val book = assertNotNull(IosComicBook.openNested(outer, units[0].id, STAMP, cache), "a deflated volume opens from its copy")
        assertEquals(listOf("1.png", "2.png", "3.png"), book.pages)
        assertTrue(book.data(1)!!.toByteArray().contentEquals(page(1, 2)), "the second page of volume 1, not of another volume")
        book.close()
        // 표지는 살필 때 적어 둔 것 — 서재가 표지 하나 보려고 90MB 권을 꺼내지 않는다.
        assertTrue(IosNestedVolumes.cover(units[1].id, STAMP, cache)!!.toByteArray().contentEquals(page(2, 1)))
    }

    @Test
    fun `a volume stored without compression is read in place`() {
        val outer = write("묶음.zip", storedZip(listOf(
            "묶음/1권.cbz" to storedZip(listOf("a.jpg" to page(1, 1))),
            "묶음/2권.cbz" to storedZip(listOf("a.jpg" to page(2, 1), "b.jpg" to page(2, 2))),
        )))
        val units = probe(outer, "묶음.zip")
        val book = assertNotNull(IosComicBook.openNested(outer, units[1].id, STAMP, cache))
        assertTrue(book.data(1)!!.toByteArray().contentEquals(page(2, 2)))
        book.close()
        // 그 자리에서 읽었으니 꺼낸 사본이 없다.
        assertEquals(null, NSFileManager.defaultManager.contentsOfDirectoryAtPath("$cache/nested/open", null)?.takeIf { it.isNotEmpty() })
    }

    @Test
    fun `a broken volume drops out alone and a bundle of novels is not a comic`() {
        val outer = write("섞임.zip", deflatedZip(listOf(
            "1권.zip" to "깨진 권".encodeToByteArray(),
            "2권.zip" to storedZip(listOf("1.png" to page(2, 1))),
        )))
        assertEquals(listOf("2권.zip"), probe(outer, "섞임.zip").map { it.name }, "the broken volume must not hide the good one")
        // 소설 TXT 를 묶은 zip("하얀늑대들.zip") — 권 압축이 없으니 만화 단위가 없다.
        val novels = write("소설.zip", deflatedZip(listOf("01.txt" to "글".encodeToByteArray(), "02.txt" to "글".encodeToByteArray())))
        assertEquals(emptyList(), probe(novels, "소설.zip"))
    }

    @Test
    fun `a replaced bundle is probed again while the same bundle uses the saved list`() {
        val path = write("교체.zip", deflatedZip(listOf("1권.zip" to storedZip(listOf("1.png" to page(1, 1))))))
        assertEquals(listOf(1), probe(path, "교체.zip").map { it.pageCount })
        write("교체.zip", deflatedZip(listOf("1권.zip" to storedZip(listOf("1.png" to page(1, 1), "2.png" to page(1, 2))))))
        // 같은 크기 · 시각이면 적어 둔 목록 — 앱을 열 때마다 권을 다시 꺼내지 않는다.
        assertEquals(listOf(1), probe(path, "교체.zip").map { it.pageCount })
        // 바뀌었으면 다시 살핀다 — 옛 쪽 수가 남지 않게.
        assertEquals(listOf(2), IosNestedVolumes.probe(path, "app:만화/교체.zip", "교체.zip", listOf("만화"), "200|2", 1, cache).map { it.pageCount })
    }

    @Test
    fun `only the most recent volumes stay unpacked`() {
        val outer = write("긴 작품.zip", deflatedZip((1..6).map { v -> "${v}권.zip" to storedZip(listOf("1.png" to page(v, 1))) }))
        for (unit in probe(outer, "긴 작품.zip")) IosComicBook.openNested(outer, unit.id, STAMP, cache)!!.close()
        assertEquals(IosNestedVolumes.KEEP_UNPACKED, NSFileManager.defaultManager.contentsOfDirectoryAtPath("$cache/nested/open", null)?.size)
    }

    private fun probe(path: String, name: String) =
        IosNestedVolumes.probe(path, "app:만화/$name", name, listOf("만화"), STAMP, 1, cache)

    /** 권 · 쪽마다 다른 바이트 — 다른 권의 쪽을 꺼내면 걸린다. 그림 이름만 보고 판정하므로 내용은 그림이 아니어도 된다. */
    private fun page(volume: Int, page: Int) = "volume $volume page $page".encodeToByteArray()

    private fun write(name: String, bytes: ByteArray): String {
        val path = "$root/$name"
        val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        assertTrue(data.writeToFile(path, true))
        return path
    }

    private companion object {
        const val STAMP = "100|1"
    }
}
