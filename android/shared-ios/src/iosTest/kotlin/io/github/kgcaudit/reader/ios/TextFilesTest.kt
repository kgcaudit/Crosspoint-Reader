@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.Bookmark
import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.document.ReadingProgress
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 기록 파일이 깨지거나 쓰지 못하는 글이 섞여도 읽은 기록을 잃지 않는다(기기 설치 전 점검). */
class TextFilesTest {
    private val root = NSTemporaryDirectory() + "olo-textfiles-" + NSUUID().UUIDString
    private val path = "$root/reading.tsv"

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(root, null)
    }

    private fun writeBytes(p: String, bytes: ByteArray) {
        NSFileManager.defaultManager.createDirectoryAtPath(root, true, null, null)
        bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }.writeToFile(p, true)
    }

    @Test
    fun `a bookmark whose excerpt cuts an emoji in half does not stop everything else from being saved`() = runTest {
        val store = ReadingStore(path)
        val id = BookId("app:웹소설.txt")
        // 쪽 첫머리 80자를 자르다 이모지(서로게이트 쌍)의 앞 반만 남은 발췌.
        val half = "오늘도 웃었다 " + "😀".substring(0, 1)
        store.add(Bookmark(Bookmark.NO_ID, id, Locator.Reflow(0, 10), half, 1))
        store.save(ReadingProgress(id, Locator.Reflow(2, 300), 40f, 2))
        // 앱을 다시 켜도 자리 · 책갈피가 남아 있다(전에는 파일 전체 쓰기가 실패해 둘 다 사라졌다).
        val again = ReadingStore(path)
        assertEquals(Locator.Reflow(2, 300), again.progressOf(id.value)?.locator)
        assertEquals("오늘도 웃었다 \uFFFD", again.bookmarksOf(id.value).single().snippet)
        // 멀쩡한 파일은 옆에 사본을 만들지 않는다.
        val files = NSFileManager.defaultManager.contentsOfDirectoryAtPath(root, null).orEmpty().map { it.toString() }
        assertEquals(listOf("reading.tsv"), files.filter { it.startsWith("reading.tsv") })
    }

    @Test
    fun `a record file that is not valid utf-8 keeps its readable lines and the original is set aside`() = runTest {
        val good = "P\tapp:a.epub\tr:1:5\t10.0\t7\n".encodeToByteArray()
        val broken = byteArrayOf(0x50, 0x09, 0xC3.toByte(), 0x28, 0x0A) // 깨진 UTF-8 한 줄
        writeBytes(path, good + broken)
        val store = ReadingStore(path)
        assertEquals(Locator.Reflow(1, 5), store.progressOf("app:a.epub")?.locator)
        // 원본은 옆에 남는다 — 사람이 되살릴 수 있게.
        val aside = NSFileManager.defaultManager.contentsOfDirectoryAtPath(root, null).orEmpty().map { it.toString() }
        assertTrue(aside.any { it.startsWith("reading.tsv.unreadable-") }, "$aside")
        // 다음 저장이 읽은 기록을 지우지 않는다.
        store.save(ReadingProgress(BookId("app:b.epub"), Locator.Reflow(0, 1), 1f, 8))
        assertNotNull(ReadingStore(path).progressOf("app:a.epub"))
    }

    @Test
    fun `cutting text never splits an emoji and well formed text is left alone`() {
        assertEquals("ab", "ab😀".takeWhole(3))
        assertEquals("ab😀", "ab😀".takeWhole(4))
        assertEquals("abc", "abc".takeWhole(10))
        assertEquals("가😀나", "가😀나".wellFormed())
        assertEquals("가\uFFFD나", "가\uD83D나".wellFormed())
        assertEquals("\uFFFD가", "\uDE00가".wellFormed())
    }

    @Test
    fun `a stale sentence range reads as empty speech instead of crashing`() {
        val layer = IosPdfText.layer("등대 아래 찻집", FloatArray(0))
        assertEquals("", IosPdfText.spoken(layer, 5, 2))
        assertEquals("", IosPdfText.spoken(layer, 50, 60))
    }
}
