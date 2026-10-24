@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.kgcaudit.reader.ios

import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.dateWithTimeIntervalSince1970
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 쪽 캐시 정리(2026-10-24 결정 2A): 서재에 없는 책의 캐시 · 쓰지 않는 글꼴을 지우고, 읽는 책의 것은 남긴다. */
class PageCachesTest {
    private val root = NSTemporaryDirectory() + "olo-caches-" + NSUUID().UUIDString
    private val fm = NSFileManager.defaultManager
    private val now = 10_000_000_000L

    @AfterTest
    fun cleanUp() {
        fm.removeItemAtPath(root, null)
    }

    private fun file(path: String, text: String = "x", ageMs: Long = PageCaches.FRESH_MS * 2) {
        fm.createDirectoryAtPath(path.substringBeforeLast('/'), true, null, null)
        TextFiles.write(path, text)
        val date = NSDate.dateWithTimeIntervalSince1970((now - ageMs) / 1000.0)
        fm.setAttributes(mapOf(NSFileModificationDate to date), path, null)
    }

    private fun exists(path: String) = fm.fileExistsAtPath("$root/$path")

    private val a = "app:삼체 1권.epub"
    private val b = "link:X:삼체 1권.epub"

    private fun lay() {
        file("$root/${PageCaches.pageDirName(a)}/layout/0.idx")
        file("$root/${PageCaches.pageDirName(b)}/layout/0.idx")
        file("$root/bookfonts/075ca51a39e508f8/5479e313023517e1.font")
        file("$root/bookfonts/books/${fnv1aHex(a)}.idx", "edition\t1\nfonts/r.ttf\tshared")
        file("$root/bookfonts/books/${fnv1aHex(b)}.idx", "edition\t1\nfonts/r.ttf\tshared\nfonts/b.ttf\tonlyb")
        file("$root/bookfonts/files/shared.font")
        file("$root/bookfonts/files/onlyb.font")
        file("$root/bookfonts/files/orphan.font")
    }

    @Test
    fun `caches of books no longer in the library go and fonts another book still uses stay`() {
        lay()
        PageCaches.prune(root, listOf(a), now)
        assertTrue(exists("${PageCaches.pageDirName(a)}/layout/0.idx"))
        assertFalse(exists(PageCaches.pageDirName(b)), "the vanished copy's pages")
        assertFalse(exists("bookfonts/075ca51a39e508f8"), "old per-book font folder")
        assertTrue(exists("bookfonts/books/${fnv1aHex(a)}.idx"))
        assertFalse(exists("bookfonts/books/${fnv1aHex(b)}.idx"))
        assertTrue(exists("bookfonts/files/shared.font"), "still used by the book in the library")
        assertFalse(exists("bookfonts/files/onlyb.font"))
        assertFalse(exists("bookfonts/files/orphan.font"))
    }

    @Test
    fun `the page cache a real reader made for a Korean-named book survives the clean-up`() = kotlinx.coroutines.test.runTest {
        // 일부러 한글 이름: 정리가 코어와 다른 해시로 견주어 서재에 있는 책의 쪽 캐시를 지웠다(실기기에서 찾음).
        fm.createDirectoryAtPath(root, true, null, null)
        val path = "$root/등대지기의 저녁.txt"
        TextFiles.write(path, (1..200).joinToString("\n") { "등대 아래 찻집에서 $it 번째 이야기를 들었다." })
        val id = "app:등대지기의 저녁.txt"
        val reader = IosBookReader.open(path, id, "등대지기의 저녁.txt", ReadingStore("$root/r.tsv"), "$root/pages", "1")
        reader.layOut(ReadingSpec.of(360f, 640f, 0f, 0f, 0f, 0f), 0xFFFFFF, 0, true)
        reader.close()
        val made = fm.contentsOfDirectoryAtPath("$root/pages", null).orEmpty().map { it.toString() }
        assertTrue(made.isNotEmpty(), "the reader wrote a page cache")
        PageCaches.prune("$root/pages", listOf(id, "app:다른 책.epub"), now)
        assertEquals(made.sorted(), fm.contentsOfDirectoryAtPath("$root/pages", null).orEmpty().map { it.toString() }.sorted())
        // 서재에서 빠지면 지운다.
        PageCaches.prune("$root/pages", listOf("app:다른 책.epub"), now)
        assertTrue(fm.contentsOfDirectoryAtPath("$root/pages", null).orEmpty().none { it.toString() in made })
    }

    @Test
    fun `an empty library view deletes nothing`() {
        // 일부러 망가뜨린 입력: 폴더 접근이 끊겨 서재가 잠깐 비어 보였다 — 모든 캐시를 날리면 큰 책마다 처음부터 쪽을 나눈다.
        lay()
        assertEquals(0, PageCaches.prune(root, emptyList(), now))
        assertTrue(exists(PageCaches.pageDirName(b)))
        assertTrue(exists("bookfonts/files/orphan.font"))
    }

    @Test
    fun `a font taken out moments ago is kept even before its book lists it`() {
        // 열리는 책이 글꼴을 꺼냈지만 목록을 아직 적지 않았다.
        lay()
        file("$root/bookfonts/files/justnow.font", ageMs = 1_000)
        PageCaches.prune(root, listOf(a), now)
        assertTrue(exists("bookfonts/files/justnow.font"))
    }
}
