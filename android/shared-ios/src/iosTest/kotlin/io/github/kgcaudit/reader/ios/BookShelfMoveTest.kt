@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.Bookmark
import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.document.ReadingProgress
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** 책을 길게 눌러 갈래를 옮긴다(구상안 43, 안드로이드 `Library.setFinished` · `returnToUnread`). */
class BookShelfMoveTest {
    private val root = NSTemporaryDirectory() + "olo-shelf-" + NSUUID().UUIDString
    private val path = "$root/reading.tsv"
    private val id = "app:책.epub"

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(root, null)
    }

    private fun record(store: ReadingStore = ReadingStore(path)) = store.shelf(listOf(id)).single()

    @Test
    fun `a book never opened can be put on the finished shelf and stays there after reopening the app`() {
        ReadingStore(path).setFinished(id, 5_000, 5_000)
        // 다시 읽어 들인 기록으로 본다 — 저장하지 않으면 앱을 다시 켜는 순간 갈래가 되돌아간다.
        val r = record()
        assertEquals(5_000L, r.finishedAtEpochMs)
        assertNotNull(r.openedAtEpochMs, "a finished book also counts as opened so that unmarking it lands on the reading shelf")
    }

    @Test
    fun `unmarking a finished book moves it to the reading shelf not the unread one`() {
        val store = ReadingStore(path)
        store.markOpened(id)
        store.setFinished(id, 5_000, 6_000)
        store.setFinished(id, null, 7_000)
        val r = record()
        assertNull(r.finishedAtEpochMs)
        assertNotNull(r.openedAtEpochMs)
    }

    @Test
    fun `a never opened book marked finished by mistake goes to the reading shelf when unmarked`() {
        // 잘못 누른 것을 바로 되돌리는 경우. 연 때를 함께 적지 않으면 같은 실행 안에서 읽을 책으로 떨어진다.
        val store = ReadingStore(path)
        store.setFinished(id, 5_000, 5_000)
        store.setFinished(id, null, 6_000)
        assertNotNull(record(store).openedAtEpochMs)
        assertNotNull(record().openedAtEpochMs)
    }

    @Test
    fun `marking finished keeps the day the book was first opened`() {
        val store = ReadingStore(path)
        store.markOpened(id)
        val opened = record(store).openedAtEpochMs
        store.setFinished(id, Long.MAX_VALUE / 2, Long.MAX_VALUE / 2)
        assertEquals(opened, record().openedAtEpochMs)
    }

    @Test
    fun `returning a book to the unread shelf keeps its place and notes`() = runTest {
        val store = ReadingStore(path)
        store.save(ReadingProgress(BookId(id), Locator.Reflow(2, 40), 37f, 1_000))
        store.add(Bookmark(Bookmark.NO_ID, BookId(id), Locator.Reflow(1, 0), "책갈피", 2_000))
        store.markOpened(id)
        store.setFinished(id, 3_000, 3_000)

        store.returnToUnread(id)
        val again = ReadingStore(path)
        val r = record(again)
        assertNull(r.openedAtEpochMs)
        assertNull(r.finishedAtEpochMs)
        // 갈래만 옮겼다 — 읽던 자리 · 책갈피가 사라지면 되돌릴 수 없다.
        assertEquals(Locator.Reflow(2, 40), again.get(BookId(id))?.locator)
        assertEquals(1, r.bookmarkCount)
    }
}
