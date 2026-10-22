@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.Bookmark
import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.document.ReadingProgress
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.decomposedStringWithCanonicalMapping
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 옮긴 파일의 기록 잇기(안드로이드 0.50.0 `MovedRecordsTest`, iOS 1A 2A 3A). */
class MovedRecordsTest {
    private val root = NSTemporaryDirectory() + "olo-moved-" + NSUUID().UUIDString
    private val path = "$root/reading.tsv"

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(root, null)
    }

    private val scanned = listOf("app:", "link:A:")
    private fun book(id: String, name: String = "어린 왕자.epub", size: Long? = 3000) = KnownFile(id, KnownFile.BOOK, name, size, null)
    private val old = "app:책/어린 왕자.epub"
    private val new = "link:A:어린 왕자.epub"

    private suspend fun read(store: ReadingStore, id: String) {
        store.save(ReadingProgress(BookId(id), Locator.Reflow(3, 120), 40f, 1_000))
        store.add(Bookmark(Bookmark.NO_ID, BookId(id), Locator.Reflow(1, 0), "책갈피", 2_000))
        store.markOpened(id)
    }

    @Test
    fun `a book moved to another folder keeps its place and bookmarks even after restarting`() = runTest {
        val store = ReadingStore(path)
        read(store, old)
        store.adoptMoved(listOf(book(old)), scanned)
        // 파일을 옮겼다: 옛 자리는 사라지고 새 자리에 같은 이름 · 크기. 앱을 다시 켠 뒤에도 무엇이었는지 기억한다.
        val again = ReadingStore(path)
        val moved = again.adoptMoved(listOf(book(new)), scanned)
        assertEquals(listOf(MovedPair(old, new, comic = false)), moved)
        assertEquals(Locator.Reflow(3, 120), again.get(BookId(new))?.locator)
        assertEquals(1, again.forBook(BookId(new)).size)
        assertTrue(again.shelf(listOf(new)).single().openedAtEpochMs != null)
        // 옛 id 에는 남기지 않는다 — 파일이 돌아오면 기록이 둘로 갈린다.
        assertNull(again.get(BookId(old)))
        assertTrue(again.forBook(BookId(old)).isEmpty())
        // 두 번째 훑기는 아무것도 옮기지 않는다.
        assertTrue(again.adoptMoved(listOf(book(new)), scanned).isEmpty())
        assertEquals(Locator.Reflow(3, 120), ReadingStore(path).get(BookId(new))?.locator)
    }

    @Test
    fun `a book read at the new place too keeps the further position and both bookmarks`() = runTest {
        val store = ReadingStore(path)
        read(store, old)
        store.adoptMoved(listOf(book(old)), scanned)
        store.save(ReadingProgress(BookId(new), Locator.Reflow(9, 0), 80f, 5_000))
        store.add(Bookmark(Bookmark.NO_ID, BookId(new), Locator.Reflow(8, 0), "새 자리", 6_000))
        store.adoptMoved(listOf(book(new)), scanned)
        assertEquals(Locator.Reflow(9, 0), store.get(BookId(new))?.locator)
        assertEquals(2, store.forBook(BookId(new)).size)
    }

    @Test
    fun `nothing moves when the match is not certain`() = runTest {
        // 같은 이름 · 크기가 새 자리에 둘.
        ReadingStore("$root/a.tsv").let { s ->
            read(s, old)
            s.adoptMoved(listOf(book(old)), scanned)
            assertTrue(s.adoptMoved(listOf(book(new), book("link:A:사본/어린 왕자.epub")), scanned).isEmpty())
            assertEquals(Locator.Reflow(3, 120), s.get(BookId(old))?.locator)
        }
        // 크기가 다르면 다른 판이다.
        ReadingStore("$root/b.tsv").let { s ->
            read(s, old)
            s.adoptMoved(listOf(book(old)), scanned)
            assertTrue(s.adoptMoved(listOf(book(new, size = 3001)), scanned).isEmpty())
        }
        // 크기를 모르면 짝짓지 않는다 — 잘못 붙인 기록은 되돌릴 수 없다.
        ReadingStore("$root/c.tsv").let { s ->
            read(s, old)
            s.adoptMoved(listOf(book(old, size = null)), scanned)
            assertTrue(s.adoptMoved(listOf(book(new, size = null)), scanned).isEmpty())
        }
    }

    @Test
    fun `two vanished copies of the same file are not guessed between`() = runTest {
        val store = ReadingStore(path)
        val other = "app:다른 곳/어린 왕자.epub"
        read(store, old)
        read(store, other)
        store.adoptMoved(listOf(book(old), book(other)), scanned)
        assertTrue(store.adoptMoved(listOf(book(new)), scanned).isEmpty())
        assertTrue(store.get(BookId(new)) == null)
    }

    @Test
    fun `a book still visible in its old place is a copy and keeps its own records`() = runTest {
        val store = ReadingStore(path)
        read(store, old)
        assertTrue(store.adoptMoved(listOf(book(old), book(new)), scanned).isEmpty())
        assertEquals(Locator.Reflow(3, 120), store.get(BookId(old))?.locator)
        assertNull(store.get(BookId(new)))
    }

    @Test
    fun `a book in a folder that was not read this time stays where it was`() = runTest {
        // 구상안 2A: 서재에서 뺀 폴더 · 접근이 끊긴 폴더의 책은 사라진 것으로 치지 않는다 — 다시 추가하면 그 자리에서 이어져야 한다.
        val store = ReadingStore(path)
        val inFolder = "link:B:어린 왕자.epub"
        read(store, inFolder)
        store.adoptMoved(listOf(book(inFolder)), listOf("app:", "link:B:"))
        assertTrue(store.adoptMoved(listOf(book(new)), scanned).isEmpty())
        assertEquals(Locator.Reflow(3, 120), store.get(BookId(inFolder))?.locator)
    }

    @Test
    fun `a name written in decomposed Hangul still matches`() = runTest {
        // 구상안 1A: 맥에서 옮긴 파일 이름은 풀어 쓴 꼴(NFD)일 수 있다.
        val store = ReadingStore(path)
        read(store, old)
        store.adoptMoved(listOf(book(old)), scanned)
        @Suppress("CAST_NEVER_SUCCEEDS")
        val decomposed = ("어린 왕자.epub" as NSString).decomposedStringWithCanonicalMapping
        assertEquals(1, store.adoptMoved(listOf(book(new, name = decomposed)), scanned).size)
    }

    @Test
    fun `reading time from both places is added up`() = runTest {
        val store = ReadingStore(path)
        read(store, old)
        store.addTime(old, 20_000, "READ", 30 * 60_000L)
        store.adoptMoved(listOf(book(old)), scanned)
        store.addTime(new, 20_000, "READ", 5 * 60_000L)
        store.adoptMoved(listOf(book(new)), scanned)
        val t = store.times().filter { it.bookId == new }
        assertEquals(35 * 60_000L, t.single().millis)
        assertTrue(store.times().none { it.bookId == old })
    }

    @Test
    fun `a moved comic volume keeps its page and bookmarks and the work it was pulled out into`() {
        val store = ReadingStore(path)
        val a = "app:만화/바다 01권.cbz"
        val b = "link:A:바다 01권.cbz"
        store.saveComic(a, 5, 20, atEnd = false)
        store.toggleComicBookmark(a, listOf(5))
        store.setComicSetting("WORK_OF", a, "#own:$a")
        store.setComicSetting("VIEW", "#own:$a", "WEBTOON")
        fun vol(id: String) = KnownFile(id, "ARCHIVE", "바다 01권.cbz", 9_000, null)
        store.adoptMoved(listOf(vol(a)), scanned)
        assertEquals(listOf(MovedPair(a, b, comic = true)), store.adoptMoved(listOf(vol(b)), scanned))
        assertEquals(5, store.comicProgress()[b]?.page)
        assertEquals(listOf(5), store.comicBookmarks(b).map { it.toInt() })
        val o = store.comicOverrides()
        assertEquals("#own:$b", o.workOf[b])
        assertEquals("WEBTOON", o.view["#own:$b"]?.name)
        assertNull(o.workOf[a])
    }

    @Test
    fun `a picture folder is matched by its page count and a comic never matches a book`() {
        val store = ReadingStore(path)
        val a = "app:만화/1화"
        store.saveComic(a, 2, 10, atEnd = false)
        store.adoptMoved(listOf(KnownFile(a, "IMAGE_FOLDER", "1화", null, 10)), scanned)
        // 그림 수가 다르면 다른 폴더, 같은 이름의 책 파일과는 짝짓지 않는다.
        assertTrue(store.adoptMoved(listOf(KnownFile("link:A:1화", "IMAGE_FOLDER", "1화", null, 11)), scanned).isEmpty())
        assertTrue(store.adoptMoved(listOf(KnownFile("link:A:1화", KnownFile.BOOK, "1화", null, 10)), scanned).isEmpty())
        assertEquals(1, store.adoptMoved(listOf(KnownFile("link:A:1화", "IMAGE_FOLDER", "1화", null, 10)), scanned).size)
        assertEquals(2, store.comicProgress()["link:A:1화"]?.page)
    }
}
