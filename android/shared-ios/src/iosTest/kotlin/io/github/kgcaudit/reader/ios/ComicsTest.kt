@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.document.comic.ComicOverrides
import io.github.kgcaudit.reader.document.comic.ComicUnitKind
import io.github.kgcaudit.reader.document.comic.ShelfMark
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 만화 1단계(구상안 35A · 36): 무엇이 만화인가 · 작품 묶기 · 쪽 순서 · 두 쪽 · 권의 기록. 판정 규칙은 공통 코드다 — 여기는 iOS 가 파일을 제대로 넘기는지. */
class ComicsTest {
    private val root = NSTemporaryDirectory() + "olo-comics-" + NSUUID().UUIDString

    init {
        NSFileManager.defaultManager.createDirectoryAtPath(root, true, null, null)
    }

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(root, null)
    }

    @Test
    fun `a cbz with pictures is a comic and its comic info sets the reading direction`() {
        val path = write("a.cbz", storedZip(listOf("10.png" to png(600, 900), "2.png" to png(600, 900), "1.png" to png(600, 900),
            "ComicInfo.xml" to "<ComicInfo><Series>바다 마을 이야기</Series><Number>3</Number><Manga>YesAndRightToLeft</Manga></ComicInfo>".encodeToByteArray())))
        val unit = IosComics.probe(path, "app:a.cbz", "a.cbz", listOf("만화"), 100, 1)
        assertNotNull(unit)
        assertEquals(3, unit.pageCount)
        assertEquals(true, unit.info?.rightToLeft)
        assertEquals("바다 마을 이야기", unit.info?.series)
        // 쪽은 자연 순서: 1 · 2 · 10.
        assertEquals(listOf("1.png", "2.png", "10.png"), IosComicBook.openArchive(path)!!.pages)
    }

    @Test
    fun `a zip with other files or an archive that cannot be read is not on the shelf`() {
        val mixed = write("b.zip", storedZip(listOf("1.png" to png(10, 10), "2.png" to png(10, 10), "readme.txt" to "글".encodeToByteArray())))
        assertNull(IosComics.probe(mixed, "app:b.zip", "b.zip", emptyList(), 1, 1), "a plain zip must hold pictures only")
        // 깨진 cbr 은 cbz 처럼 서재에 남고(열 때 알린다), 깨진 .rar 는 만화라고 말하지 않으니 빠진다(해제기는 2026-10-25 부터).
        val rar = write("c.rar", "Rar!".encodeToByteArray())
        assertNull(IosComics.probe(rar, "app:c.rar", "c.rar", emptyList(), 1, 1), "a plain rar that cannot be read is not a comic")
        // 이름이 만화라고 말하는 cbz 는 깨져도 서재에 남는다 — 열 때 까닭을 알린다.
        val broken = write("d.cbz", "깨짐".encodeToByteArray())
        assertNotNull(IosComics.probe(broken, "app:d.cbz", "d.cbz", emptyList(), 1, 1))
        assertNull(IosComicBook.openArchive(broken))
    }

    @Test
    fun `volumes in different folders become one work`() {
        val a = IosComics.probe(write("1.cbz", oneComic()), "app:x/[작가] 원피스 01권.cbz", "[작가] 원피스 01권.cbz", listOf("x"), 1, 1)!!
        val b = IosComics.probe(write("2.cbz", oneComic()), "app:y/원피스 02권 (완).cbz", "원피스 02권 (완).cbz", listOf("y"), 1, 2)!!
        val works = IosComics.group(listOf(a, b), ComicOverrides())
        assertEquals(1, works.size)
        assertEquals("원피스", works.single().title)
        assertEquals(listOf("1권", "2권"), works.single().entries.map { it.label })
        assertTrue(works.single().complete)
    }

    @Test
    fun `a folder needs three pictures and no books`() {
        assertNotNull(IosComics.folder("app:f/", "f", emptyList(), listOf("1.jpg", "2.jpg", "3.jpg"), 1))
        assertNull(IosComics.folder("app:g/", "g", emptyList(), listOf("1.jpg", "2.jpg"), 1))
        assertNull(IosComics.folder("app:h/", "h", emptyList(), listOf("1.jpg", "2.jpg", "3.jpg", "책.epub"), 1))
        assertEquals(ComicUnitKind.IMAGE_FOLDER, IosComics.folder("app:f/", "f", emptyList(), listOf("1.jpg", "2.jpg", "3.jpg"), 1)!!.kind)
    }

    @Test
    fun `pages pair up with the cover alone and a wide page alone`() {
        val path = write("e.cbz", storedZip((1..6).map { i -> "$i.png" to if (i == 4) png(1400, 900) else png(600, 900) }))
        val book = IosComicBook.openArchive(path)!!
        val sizes = book.sizes()
        assertEquals(1400, sizes[3]?.width)
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3), listOf(4, 5)), book.spreads(sizes, coverAlone = true))
        assertNotNull(book.data(0))
        assertNull(book.data(9))
    }

    @Test
    fun `reading place bookmarks and work settings survive reopening and broken lines drop alone`() {
        val path = "$root/r.tsv"
        val store = ReadingStore(path)
        store.saveComic("app:a.cbz", 179, 180, atEnd = true)
        store.saveComic("app:a.cbz", 3, 180, atEnd = false)
        assertTrue(store.toggleComicBookmark("app:a.cbz", listOf(4, 5)))
        assertFalse(store.toggleComicBookmark("app:a.cbz", listOf(3, 4)), "a mark on either page of a spread comes out")
        assertTrue(store.toggleComicBookmark("app:a.cbz", listOf(10)))
        store.setComicSetting("RTL", "원피스", "1")
        store.setComicSetting("COVER", "원피스", "0")
        store.setComicSetting("SHELF", "원피스", ShelfMark.format(ShelfMark.ToRead(5)))
        val file = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)!!
        @Suppress("CAST_NEVER_SUCCEEDS")
        ((file + "C\tapp:b.cbz\t열\t9\t1\t-\nK\tapp:b.cbz\t-3\t1\nO\tSHELF\t고장\t아무말\n") as NSString).writeToFile(path, true, NSUTF8StringEncoding, null)
        val again = ReadingStore(path)
        val p = again.comicProgress().getValue("app:a.cbz")
        assertEquals(3, p.page)
        assertNotNull(p.finishedAtEpochMs, "finished stays finished after going back")
        assertNull(again.comicProgress()["app:b.cbz"])
        assertEquals(listOf(10), again.comicBookmarks("app:a.cbz"))
        assertTrue(again.comicBookmarks("app:b.cbz").isEmpty())
        val o = again.comicOverrides()
        assertEquals(true, o.rightToLeft["원피스"])
        assertEquals(false, o.coverAlone["원피스"])
        assertEquals(ShelfMark.ToRead(5), o.shelf["원피스"])
        assertNull(o.shelf["고장"], "a broken shelf mark is ignored")
    }

    private fun oneComic() = storedZip(listOf("1.png" to png(600, 900)))

    /** 머리만 맞는 PNG(크기 읽기용). */
    private fun png(w: Int, h: Int): ByteArray {
        val b = ArrayList<Byte>()
        b += listOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).map { it.toByte() }
        fun u32(v: Int) = listOf(v ushr 24, v ushr 16, v ushr 8, v).map { (it and 0xFF).toByte() }
        b += u32(13)
        b += "IHDR".encodeToByteArray().toList()
        b += u32(w)
        b += u32(h)
        b += listOf(8, 6, 0, 0, 0).map { it.toByte() }
        b += u32(0)
        return b.toByteArray()
    }

    private fun write(name: String, bytes: ByteArray): String {
        val path = "$root/$name"
        val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        assertTrue(data.writeToFile(path, true))
        return path
    }
}
