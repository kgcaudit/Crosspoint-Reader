@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.document.comic.ShelfMark
import io.github.kgcaudit.reader.document.comic.SpreadSplit
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 만화 기록 백업. 안드로이드 `ComicRecordsBackupTest` 와 같은 성질을 지킨다 — 두 앱이 같은 파일을 주고받는다. */
class ComicRecordsBackupTest {
    private val root = NSTemporaryDirectory() + "olo-comic-backup-" + NSUUID().UUIDString

    init {
        NSFileManager.defaultManager.createDirectoryAtPath(root, true, null, null)
    }

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(root, null)
    }

    /** 안드로이드가 만든 모양 그대로: 압축 한 권(웹툰 자리 · 반까지), 그림 폴더 한 권(크기 없음), 작품 설정 둘. */
    private val android = """
{
 "format": "olo-ebook-reading-records",
 "version": 1,
 "createdAt": 1790000000000,
 "books": [],
 "comics": [
  {
   "name": "원피스 01.cbz",
   "size": 99,
   "page": 12,
   "pageCount": 200,
   "offset": 0.25,
   "half": "RIGHT",
   "updatedAt": 1789000000000,
   "finishedAt": 1789500000000,
   "bookmarks": [{"page": 3, "createdAt": 1788000000000}, {"page": 40, "createdAt": 1788100000000}],
   "time": [{"day": "2026-10-03", "mode": "READ", "ms": 600000}]
  },
  {"name": "스캔 폴더", "page": 2, "pageCount": 30, "updatedAt": 1789000000000, "bookmarks": []}
 ],
 "works": [
  {"key": "원피스", "title": "ONE PIECE", "rightToLeft": true, "view": "WEBTOON", "shelf": "READING:1789000000000", "coverAlone": false, "splitSpreads": true},
  {"key": "없는작품", "rightToLeft": false}
 ]
}
""".trimIndent()

    private val shelf = listOf(ShelfComic("app:원피스 01.cbz", "원피스 01.cbz", 99), ShelfComic("app:스캔 폴더", "스캔 폴더", null))

    private fun fresh(name: String = "r.tsv") = ReadingStore("$root/$name")

    @Test
    fun `a comic read on Android opens on iOS where it was left with its bookmarks`() {
        val store = fresh()
        val backup = RecordsBackup(store, "$root/pending.json")
        val preview = backup.preview(android, emptyList(), shelf)!!
        assertEquals(2, preview.comicsFound)
        assertEquals(0, preview.missing)
        val done = backup.apply(android, emptyList(), shelf)!!
        assertEquals(2, done.comics)
        assertEquals(2, done.bookmarks)
        assertEquals(1, done.finished)
        val p = store.comicProgress()["app:원피스 01.cbz"]!!
        assertEquals(12, p.page)
        assertEquals(200, p.pageCount)
        assertEquals(1789500000000, p.finishedAtEpochMs)
        assertEquals(listOf(3, 40), store.comicBookmarks("app:원피스 01.cbz"))
        assertEquals(600_000L, store.times().single { it.bookId == "app:원피스 01.cbz" }.millis)
        // 그림 폴더는 크기가 없어 이름 하나로 맞는다.
        assertEquals(2, store.comicProgress()["app:스캔 폴더"]!!.page)
    }

    @Test
    fun `work settings from Android apply even before the work is on this phone`() {
        val store = fresh()
        RecordsBackup(store, "$root/pending.json").apply(android, emptyList(), emptyList())
        val o = store.comicOverrides()
        assertEquals(true, o.rightToLeft["원피스"])
        assertEquals(false, o.rightToLeft["없는작품"])
        assertEquals(false, o.coverAlone["원피스"])
        assertEquals("ONE PIECE", o.titles["원피스"], "a name changed on Android shows on iOS")
        assertEquals(ShelfMark.parse("READING:1789000000000"), o.shelf["원피스"])
    }

    @Test
    fun `a setting made on this phone is not overwritten by an import`() {
        val store = fresh()
        store.setComicSetting("RTL", "원피스", "0")
        RecordsBackup(store, "$root/pending.json").apply(android, emptyList(), emptyList())
        assertEquals(false, store.comicOverrides().rightToLeft["원피스"])
        // 정하지 않았던 칸은 채운다.
        assertEquals(false, store.comicOverrides().coverAlone["원피스"])
    }

    @Test
    fun `importing the same comics twice is the same as once`() {
        val store = fresh()
        val backup = RecordsBackup(store, "$root/pending.json")
        backup.apply(android, emptyList(), shelf)
        backup.apply(android, emptyList(), shelf)
        assertEquals(listOf(3, 40), store.comicBookmarks("app:원피스 01.cbz"))
        assertEquals(600_000L, store.times().single { it.bookId == "app:원피스 01.cbz" }.millis, "time must not double")
    }

    @Test
    fun `a further page on this phone stays but an unknown finish is filled and a known one kept`() {
        val store = fresh()
        store.saveComic("app:원피스 01.cbz", 50, 200, atEnd = false)
        store.saveComic("app:스캔 폴더", 29, 30, atEnd = true)
        val mine = store.comicProgress()["app:스캔 폴더"]!!.finishedAtEpochMs!!
        RecordsBackup(store, "$root/pending.json").apply(android, emptyList(), shelf)
        val a = store.comicProgress()["app:원피스 01.cbz"]!!
        assertEquals(50, a.page, "the backup's page 12 is behind")
        assertEquals(1789500000000, a.finishedAtEpochMs, "but its finish is filled in")
        assertEquals(mine, store.comicProgress()["app:스캔 폴더"]!!.finishedAtEpochMs, "the day finished here stays")
    }

    @Test
    fun `a volume of the same name but another size is another scan`() {
        val store = fresh()
        val done = RecordsBackup(store, "$root/pending.json").apply(android, emptyList(), listOf(ShelfComic("app:b", "원피스 01.cbz", 98)))!!
        assertEquals(0, done.comics)
        assertNull(store.comicProgress()["app:b"])
        assertTrue("원피스 01.cbz" in done.missingNames)
        // 압축을 풀어 같은 이름의 폴더로 옮겼다면 그 폴더 하나에 붙는다(안드로이드와 같다).
        val unpacked = RecordsBackup(fresh("s.tsv"), "$root/p2.json").apply(android, emptyList(), listOf(ShelfComic("app:f", "원피스 01.cbz", null)))!!
        assertEquals(1, unpacked.comics)
    }

    @Test
    fun `a comic not on this phone waits and joins when it appears`() {
        val store = fresh()
        val backup = RecordsBackup(store, "$root/pending.json")
        backup.apply(android, emptyList(), emptyList())
        // 다시 백업해도 기다리는 만화가 그대로 담긴다 — 웹툰 자리 · 반까지.
        val again = RecordsCodec.decode(backup.export(emptyList(), emptyList()))!!
        val c = again.comics.single { it.name == "원피스 01.cbz" }
        assertEquals(0.25, c.offset)
        assertEquals("RIGHT", c.half)
        assertEquals(2, again.works.size)
        assertEquals(1, backup.resumePending(emptyList(), listOf(ShelfComic("app:x", "원피스 01.cbz", 99))))
        assertEquals(12, store.comicProgress()["app:x"]!!.page)
        assertEquals(0, backup.resumePending(emptyList(), listOf(ShelfComic("app:x", "원피스 01.cbz", 99))), "joined records stop waiting")
    }

    @Test
    fun `work settings an older iOS kept waiting become this phone's settings`() {
        // 0.1.0 의 iOS 는 작품 설정을 붙일 곳이 없어 기다림 파일에 두었다. 이제는 이 휴대폰 설정으로 옮기고 기다림에서 뺀다.
        @Suppress("CAST_NEVER_SUCCEEDS")
        ("{\"format\":\"olo-ebook-reading-records\",\"works\":[{\"key\":\"원피스\",\"rightToLeft\":true}]}" as NSString)
            .writeToFile("$root/pending.json", true, NSUTF8StringEncoding, null)
        val store = fresh()
        val backup = RecordsBackup(store, "$root/pending.json")
        assertEquals(0, backup.resumePending(emptyList(), emptyList()), "no volume joined")
        assertEquals(true, store.comicOverrides().rightToLeft["원피스"])
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath("$root/pending.json"), "nothing waits any more")
        assertEquals(1, RecordsCodec.decode(backup.export(emptyList(), emptyList()))!!.works.size, "still in the next backup, once")
    }

    @Test
    fun `comics read on iOS go into the backup and come back the same`() {
        val store = fresh()
        store.saveComic("app:a", 7, 20, atEnd = false)
        store.toggleComicBookmark("app:a", listOf(4))
        store.addTime("app:a", 20740, "READ", 1000)
        store.setComicSetting("RTL", "원피스", "1")
        store.toggleComicBookmark("app:only-marks", listOf(2))
        val units = listOf(ShelfComic("app:a", "원피스 01.cbz", 99), ShelfComic("app:only-marks", "b.cbz", 5), ShelfComic("app:none", "c.cbz", 6))
        val text = RecordsBackup(store, "$root/pending.json").export(emptyList(), units)
        val file = RecordsCodec.decode(text)!!
        assertEquals(listOf("원피스 01.cbz", "b.cbz"), file.comics.map { it.name }, "a volume never opened is not listed")
        val a = file.comics.first()
        assertEquals(7, a.page)
        assertEquals(listOf(4), a.bookmarks.map { it.page })
        assertEquals(1000L, a.time.single().ms)
        assertNull(file.comics[1].page, "bookmarks only: no place")
        assertEquals(true, file.works.single().rightToLeft)
        assertTrue("\"rightToLeft\": true" in text, "a JSON boolean, not \"true\"")
        // 다른 휴대폰에서 받아도 같다.
        val other = fresh("other.tsv")
        RecordsBackup(other, "$root/other.json").apply(text, emptyList(), units)
        assertEquals(7, other.comicProgress()["app:a"]!!.page)
        assertEquals(listOf(4), other.comicBookmarks("app:a"))
        val summary = RecordsBackup(store, "$root/pending.json").summary(emptyList(), units)
        assertEquals(2, summary.comics)
        assertEquals(2, summary.bookmarks)
        assertEquals(1, summary.works)
    }

    @Test
    fun `the webtoon place and half survive this phone's record file`() {
        val store = fresh()
        RecordsBackup(store, "$root/pending.json").apply(android, emptyList(), shelf)
        val reopened = fresh()
        val p = reopened.comicProgress()["app:원피스 01.cbz"]!!
        assertEquals(0.25f, p.offset)
        assertEquals(SpreadSplit.Half.RIGHT, p.half)
        // iOS 에서 다시 읽으면 쪽 전체로 읽은 것이다 — 남은 반이 엉뚱한 쪽에 붙지 않게 지운다.
        reopened.saveComic("app:원피스 01.cbz", 13, 200, atEnd = false)
        assertNull(fresh().comicProgress()["app:원피스 01.cbz"]!!.half)
    }

    @Test
    fun `an old record line and a broken place cell still load`() {
        @Suppress("CAST_NEVER_SUCCEEDS")
        ("C\tapp:old\t4\t10\t1\t-\nC\tapp:bad\t5\t10\t1\t-\t9.5\tWHOLE\nC\tapp:neg\t-1\t10\t1\t-\n" as NSString)
            .writeToFile("$root/old.tsv", true, NSUTF8StringEncoding, null)
        val store = fresh("old.tsv")
        assertEquals(4, store.comicProgress()["app:old"]!!.page)
        val bad = store.comicProgress()["app:bad"]!!
        assertEquals(5, bad.page, "only the broken cells go")
        assertNull(bad.offset)
        assertNull(bad.half)
        assertNull(store.comicProgress()["app:neg"])
        store.saveComic("app:old", 5, 10, atEnd = false)
        val written = NSString.stringWithContentsOfFile("$root/old.tsv", NSUTF8StringEncoding, null)!!
        assertTrue("C\tapp:old\t5\t10\t" in written)
    }

    @Test
    fun `a broken comic entry drops only the broken part`() {
        val text = """
            {"format":"olo-ebook-reading-records","comics":[
             {"page":3},
             {"name":"","page":3},
             {"name":"이름만.cbz"},
             {"name":"a.cbz","page":-4,"bookmarks":[{"page":-1},{"page":2},{"page":2},{"page":"5"},9]},
             {"name":"b.cbz","size":"12","page":1,"pageCount":0,"offset":1.5,"half":"WHOLE"},
             {"name":"c.cbz","offset":0.5,"half":"LEFT","bookmarks":[{"page":0}]}
            ],"works":[
             {"key":"","rightToLeft":true},
             {"key":"글참","rightToLeft":"true","coverAlone":1},
             {"key":"갈래","shelf":"아무거나","title":"  "},
             {"key":"남는것","shelf":"READING:5","rightToLeft":"true"}
            ]}
        """.trimIndent()
        val file = RecordsCodec.decode(text)!!
        assertEquals(listOf("a.cbz", "b.cbz", "c.cbz"), file.comics.map { it.name }, "nothing to carry: dropped")
        val a = file.comics[0]
        assertNull(a.page, "a negative page is no page")
        assertEquals(listOf(2), a.bookmarks.map { it.page }, "but its good bookmark stays, once")
        val b = file.comics[1]
        assertNull(b.size, "a size written as text is no size")
        assertNull(b.pageCount)
        assertNull(b.offset, "outside 0..1")
        assertNull(b.half, "WHOLE is the whole page")
        val c = file.comics[2]
        assertNull(c.offset, "a place inside a page needs a page")
        assertNull(c.half)
        assertEquals(listOf("남는것"), file.works.map { it.key }, "a work with nothing valid set is dropped")
        val w = file.works.single()
        assertNull(w.rightToLeft, "the word true is not a boolean")
        assertEquals("READING:5", w.shelf)
        assertFalse(file.isEmpty)
        assertNotNull(ShelfMark.parse(w.shelf!!))
    }
}
