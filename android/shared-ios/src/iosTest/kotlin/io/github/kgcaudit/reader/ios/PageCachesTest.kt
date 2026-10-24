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
        file("$root/${fnv1aHex(a)}/layout/0.idx")
        file("$root/${fnv1aHex(b)}/layout/0.idx")
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
        assertTrue(exists("${fnv1aHex(a)}/layout/0.idx"))
        assertFalse(exists(fnv1aHex(b)), "the vanished copy's pages")
        assertFalse(exists("bookfonts/075ca51a39e508f8"), "old per-book font folder")
        assertTrue(exists("bookfonts/books/${fnv1aHex(a)}.idx"))
        assertFalse(exists("bookfonts/books/${fnv1aHex(b)}.idx"))
        assertTrue(exists("bookfonts/files/shared.font"), "still used by the book in the library")
        assertFalse(exists("bookfonts/files/onlyb.font"))
        assertFalse(exists("bookfonts/files/orphan.font"))
    }

    @Test
    fun `an empty library view deletes nothing`() {
        // 일부러 망가뜨린 입력: 폴더 접근이 끊겨 서재가 잠깐 비어 보였다 — 모든 캐시를 날리면 큰 책마다 처음부터 쪽을 나눈다.
        lay()
        assertEquals(0, PageCaches.prune(root, emptyList(), now))
        assertTrue(exists(fnv1aHex(b)))
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
