@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.document.BookId
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 읽기 기록 백업(구상안 31A). 안드로이드 `RecordsBackupTest` · `ReadingTimeBackupTest` 와 같은 성질을 지킨다. */
class RecordsBackupTest {
    private val root = NSTemporaryDirectory() + "olo-backup-" + NSUUID().UUIDString

    init {
        NSFileManager.defaultManager.createDirectoryAtPath(root, true, null, null)
    }

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(root, null)
    }

    /** 안드로이드가 만든 백업 모양 그대로(org.json 한 칸 들여쓰기, 슬래시 이스케이프, 만화 · 작품 설정 포함). */
    private val android = """
{
 "format": "olo-ebook-reading-records",
 "version": 1,
 "createdAt": 1790000000000,
 "books": [
  {
   "name": "어린 왕자.epub",
   "size": 1234,
   "title": "어린 왕자",
   "author": "생텍쥐페리",
   "progress": {"locator": "r:3:120", "percent": 40.5, "updatedAt": 1789990000000},
   "openedAt": 1789990000000,
   "finishedAt": 1789995000000,
   "bookmarks": [{"locator": "r:2:30", "snippet": "책갈피 글", "createdAt": 1789980000000}, {"locator": "r:5:0", "createdAt": 1789981000000}],
   "annotations": [
    {"start": "r:1:5", "end": "r:1:20", "color": "Blue", "note": "메모", "snippet": "칠한 글", "createdAt": 1789982000000},
    {"start": "r:4:10", "end": "r:4:42", "color": "Yellow", "snippet": "메모 없는 형광펜", "createdAt": 1789983000000}
   ],
   "time": [{"day": "2026-10-03", "mode": "READ", "ms": 2400000}, {"day": "2026-10-04", "mode": "LISTEN", "ms": 1200000}]
  }
 ],
 "comics": [{"name": "원피스 01.cbz", "size": 99, "page": 12, "updatedAt": 1789000000000, "bookmarks": [], "half": "LEFT"}],
 "works": [{"key": "원피스", "rightToLeft": true, "shelf": "READING:1789000000000"}]
}
""".trimIndent()

    private val shelf = listOf(ShelfBook("app:어린 왕자.epub", "어린 왕자.epub", 1234, "어린 왕자", "생텍쥐페리"))

    @Test
    fun `a backup made on Android brings back the place bookmarks highlights and time`() = runTest {
        val store = ReadingStore("$root/r.tsv")
        val backup = RecordsBackup(store, "$root/pending.json")
        val preview = backup.preview(android, shelf)!!
        assertEquals(1, preview.found)
        assertEquals(1, preview.missing, "the comic waits: iOS has no comics yet")
        val done = backup.apply(android, shelf)!!
        assertEquals(2, done.bookmarks)
        assertEquals(2, done.notes)
        assertEquals(1, done.finished)
        val id = BookId("app:어린 왕자.epub")
        assertEquals(Locator.Reflow(3, 120), store.get(id)!!.locator)
        assertEquals(2, store.forBook(id).size)
        val notes = store.annotations.forBook(id)
        assertEquals("메모", notes.first().note)
        assertEquals(2_400_000L, store.times().single { it.mode == "READ" }.millis)
        assertEquals(RecordsCodec.parseDay("2026-10-03"), store.times().single { it.mode == "READ" }.day)
    }

    @Test
    fun `importing the same backup twice is the same as once`() = runTest {
        val store = ReadingStore("$root/r.tsv")
        val backup = RecordsBackup(store, "$root/pending.json")
        backup.apply(android, shelf)
        backup.apply(android, shelf)
        val id = BookId("app:어린 왕자.epub")
        assertEquals(2, store.forBook(id).size)
        assertEquals(2, store.annotations.forBook(id).size)
        assertEquals(2_400_000L, store.times().single { it.mode == "READ" }.millis, "time must not double")
    }

    @Test
    fun `a further place wins but a newer earlier place does not`() = runTest {
        val store = ReadingStore("$root/r.tsv")
        val id = BookId("app:어린 왕자.epub")
        store.save(ReadingProgress(id, Locator.Reflow(5, 0), 70f, 1L))
        RecordsBackup(store, "$root/pending.json").apply(android, shelf)
        // 백업은 더 나중에 고쳤지만 앞쪽(3장)이다 — 이 휴대폰의 5장이 남는다.
        assertEquals(Locator.Reflow(5, 0), store.get(id)!!.locator)
    }

    @Test
    fun `a record for a book not on this phone waits and joins when the book appears`() = runTest {
        val store = ReadingStore("$root/r.tsv")
        val backup = RecordsBackup(store, "$root/pending.json")
        val done = backup.apply(android, emptyList())!!
        assertEquals(0, done.found)
        assertTrue("어린 왕자.epub" in done.missingNames)
        // 다시 백업해도 기다리던 기록(책 · 만화 · 작품 설정)이 사라지지 않는다.
        val again = RecordsCodec.decode(backup.export(emptyList()))!!
        assertEquals("어린 왕자.epub", again.books.single().name)
        assertEquals(1, again.comics.size)
        assertTrue(again.comics.single().contains("\"half\":\"LEFT\""), "comic fields pass through untouched")
        assertTrue(again.works.single().contains("\"rightToLeft\":true"), "booleans stay booleans")
        // 책이 생겼다(맥을 거쳐 이름이 풀어 쓴 꼴이어도 맞는다).
        @Suppress("CAST_NEVER_SUCCEEDS")
        val nfd = ("어린 왕자.epub" as NSString).decomposedStringWithCanonicalMapping
        assertTrue(nfd != "어린 왕자.epub")
        assertEquals(1, backup.resumePending(listOf(ShelfBook("app:x.epub", nfd, 1234, null, null))))
        assertEquals(Locator.Reflow(3, 120), store.get(BookId("app:x.epub"))!!.locator)
        assertEquals(0, backup.resumePending(listOf(ShelfBook("app:x.epub", nfd, 1234, null, null))), "joined records stop waiting")
    }

    @Test
    fun `a book with the same name but another size is another edition`() = runTest {
        val store = ReadingStore("$root/r.tsv")
        val done = RecordsBackup(store, "$root/pending.json").apply(android, listOf(ShelfBook("app:a.epub", "어린 왕자.epub", 999, null, null)))!!
        assertEquals(0, done.found)
        assertNull(store.get(BookId("app:a.epub")))
    }

    @Test
    fun `files that are not backups change nothing and a newer version still reads`() {
        for (text in listOf("", "그냥 글", "{\"books\":[]}", "[1,2,3]", "{\"format\":\"something-else\",\"books\":[]}")) {
            assertNull(RecordsCodec.decode(text), "not a backup: $text")
        }
        val future = RecordsCodec.decode("{\"format\":\"olo-ebook-reading-records\",\"version\":9,\"future\":true,\"books\":[{\"name\":\"a.txt\"}]}")
        assertEquals("a.txt", future!!.books.single().name)
        assertTrue(RecordsCodec.decode("{\"format\":\"olo-ebook-reading-records\"}")!!.isEmpty)
    }

    @Test
    fun `a broken entry drops only itself`() {
        val text = """
            {"format":"olo-ebook-reading-records","books":[
             {"title":"이름 없는 책"},
             {"name":"b.epub","size":"12","title":null,"progress":{"locator":"r:x:1","percent":50},
              "bookmarks":[{"locator":"r:-1:0"},{"locator":"r:1:0"},7],
              "annotations":[{"start":"r:2:9","end":"r:2:3"},{"start":"r:1:0","end":"r:1:4","color":"Purple","note":"  "}],
              "time":[{"day":"어제","mode":"READ","ms":5},{"day":"2026-02-30","mode":"READ","ms":5},{"day":"2026-10-01","mode":"","ms":5},
                      {"day":"2026-10-01","mode":"READ","ms":-5},{"day":"2026-10-01","mode":"READ","ms":999999999999}]}]}
        """.trimIndent()
        val b = RecordsCodec.decode(text)!!.books.single()
        assertNull(b.size, "a size written as text is no size")
        assertNull(b.title, "JSON null is not the word null")
        assertNull(b.progress)
        assertEquals(1, b.bookmarks.size)
        assertEquals("Purple", b.annotations.single().color, "an unknown colour is carried as it is")
        assertNull(b.annotations.single().note, "a blank memo is no memo")
        assertEquals(24L * 3_600_000, b.time.single().ms, "a day holds a day at most")
    }

    @Test
    fun `days are written like Android dates`() {
        assertEquals("1970-01-01", RecordsCodec.isoDay(0))
        assertEquals("2026-10-21", RecordsCodec.isoDay(20747))
        assertEquals(20747L, RecordsCodec.parseDay("2026-10-21"))
        assertEquals("2024-02-29", RecordsCodec.isoDay(RecordsCodec.parseDay("2024-02-29")!!))
        assertNull(RecordsCodec.parseDay("2026-13-01"))
        assertNotNull(RecordsCodec.parseDay("1999-12-31"))
    }
}
