package io.github.kgcaudit.reader.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.data.db.ReaderDatabase
import io.github.kgcaudit.reader.data.library.ComicLibrary
import io.github.kgcaudit.reader.data.library.ScanResult
import io.github.kgcaudit.reader.data.library.ScannedComic
import io.github.kgcaudit.reader.document.comic.ComicUnitKind
import io.github.kgcaudit.reader.document.comic.ComicView
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 만화 · 웹툰의 읽기 기록도 백업 파일로 옮겨 간다(0.49.0). 0.48 까지는 휴대폰을 바꾸면 만화 읽던 자리가 모두 사라졌다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComicRecordsBackupTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir = createTempDirectory("comic-records").toFile()

    private inner class Phone(name: String, private val folder: String) {
        val db: ReaderDatabase = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build()
        val comics = ComicLibrary(db)
        var now = 1_000L
        val records = RecordsBackup(db, File(dir, "$name-pending.json")) { now }

        /** 휴대폰마다 문서 주소가 다르다 — 같은 파일이라도 id 가 다르게. */
        fun id(file: String) = "$folder/$file"

        suspend fun scan(vararg files: Pair<String, Long?>) {
            comics.applyScan(
                folder,
                ScanResult(emptyList(), complete = true, files.map { (n, size) -> ScannedComic(id(n), n, listOf("Comic"), ComicUnitKind.ARCHIVE, "cbz", size, 1L) }),
                now,
            )
            records.resumePending()
        }

        fun backup(): String = ByteArrayOutputStream().also { kotlinx.coroutines.runBlocking { records.export(it) } }.toString("UTF-8")

        suspend fun bring(text: String): ImportResult {
            val file = assertNotNull(records.read(text.byteInputStream()), "백업을 읽지 못했다")
            return records.apply(records.plan(file))
        }
    }

    private val phones = ArrayList<Phone>()
    private fun phone(name: String, folder: String) = Phone(name, folder).also { phones += it }

    @After
    fun close() {
        phones.forEach { it.db.close() }
        dir.deleteRecursively()
    }

    @Test
    fun `comic reading place, finish and bookmarks move to a new phone`() = runTest {
        val old = phone("old", "content://old/tree")
        old.scan("별 1권.cbz" to 5000L, "별 2권.cbz" to 6000L)
        old.comics.saveProgress(old.id("별 1권.cbz"), 120, 121, 900)
        old.comics.saveProgress(old.id("별 2권.cbz"), 33, 180, 950, offset = 0.4f)
        old.comics.toggleBookmark(old.id("별 2권.cbz"), 12, 960)
        val text = old.backup()

        val new = phone("new", "content://new/tree")
        new.scan("별 1권.cbz" to 5000L, "별 2권.cbz" to 6000L)
        val result = new.bring(text)

        assertEquals(2, result.comics)
        val one = assertNotNull(new.comics.progressOf(new.id("별 1권.cbz")))
        assertEquals(120, one.page)
        assertNotNull(one.finishedAtEpochMs, "다 읽은 권이 새 휴대폰에서 다 읽음이 아니다")
        val two = assertNotNull(new.comics.progressOf(new.id("별 2권.cbz")))
        assertEquals(33 to 0.4f, two.page to two.offset)
        assertEquals(listOf(12), new.comics.bookmarks(new.id("별 2권.cbz")).first())
    }

    @Test
    fun `importing keeps the further place and never removes this phone's bookmarks`() = runTest {
        val old = phone("old", "content://old/tree")
        old.scan("별 1권.cbz" to 5000L)
        old.comics.saveProgress(old.id("별 1권.cbz"), 10, 100, 900)
        old.comics.toggleBookmark(old.id("별 1권.cbz"), 5, 900)
        val text = old.backup()

        // 새 휴대폰에서 이미 더 읽었다 — 옛 백업이 그 자리를 앞으로 되돌리면 안 된다.
        val new = phone("new", "content://new/tree")
        new.scan("별 1권.cbz" to 5000L)
        new.comics.saveProgress(new.id("별 1권.cbz"), 60, 100, 2000)
        new.comics.toggleBookmark(new.id("별 1권.cbz"), 50, 2000)
        new.comics.toggleBookmark(new.id("별 1권.cbz"), 5, 2100)
        new.bring(text)
        assertEquals(60, new.comics.progressOf(new.id("별 1권.cbz"))?.page)
        assertEquals(listOf(5, 50), new.comics.bookmarks(new.id("별 1권.cbz")).first())
        // 양쪽에 같은 쪽 책갈피: 이 휴대폰의 것(꽂은 때)이 그대로다 — 덮으면 책갈피 목록의 순서 · 날짜가 옛 휴대폰 것으로 바뀐다.
        assertEquals(2100L, new.db.comics().allBookmarks().single { it.page == 5 }.createdAtEpochMs)
    }

    @Test
    fun `a comic not on the new phone yet waits and attaches when its folder appears`() = runTest {
        val old = phone("old", "content://old/tree")
        old.scan("별 1권.cbz" to 5000L)
        old.comics.saveProgress(old.id("별 1권.cbz"), 42, 100, 900)
        val text = old.backup()

        val new = phone("new", "content://new/tree")
        val result = new.bring(text)
        assertEquals(listOf("별 1권.cbz"), result.missing)
        // 기다리는 기록은 다음 백업에도 들어간다 — 빼면 "가져오기 → 못 찾음 → 다시 백업" 에서 사라진다.
        assertTrue(new.backup().contains("별 1권.cbz"))
        new.scan("별 1권.cbz" to 5000L)
        assertEquals(42, new.comics.progressOf(new.id("별 1권.cbz"))?.page)
    }

    @Test
    fun `a comic with the same name but another size is not the same comic`() = runTest {
        val old = phone("old", "content://old/tree")
        old.scan("별 1권.cbz" to 5000L)
        old.comics.saveProgress(old.id("별 1권.cbz"), 42, 100, 900)
        val new = phone("new", "content://new/tree")
        new.scan("별 1권.cbz" to 7777L)
        new.bring(old.backup())
        assertNull(new.comics.progressOf(new.id("별 1권.cbz")), "다른 판(크기가 다름)에 읽은 자리가 붙었다")
    }

    @Test
    fun `work settings move by work name and do not overwrite what this phone chose`() = runTest {
        val old = phone("old", "content://old/tree")
        old.scan("별 1권.cbz" to 5000L, "달 1권.cbz" to 7000L)
        val oldWorks = old.comics.works().first()
        old.comics.setRightToLeft(oldWorks.first { it.title == "별" }, true)
        old.comics.setView(oldWorks.first { it.title == "별" }, ComicView.WEBTOON)
        old.comics.rename(oldWorks.first { it.title == "달" }, "달빛 이야기")
        old.comics.setRightToLeft(old.comics.works().first().first { it.title == "달빛 이야기" }, true)
        val text = old.backup()

        val new = phone("new", "content://new/tree")
        new.scan("별 1권.cbz" to 5000L, "달 1권.cbz" to 7000L)
        // 이 휴대폰에서 이미 "달" 은 왼→오로 정했다.
        new.comics.setRightToLeft(new.comics.works().first().first { it.title == "달" }, false)
        new.bring(text)
        val works = new.comics.works().first()
        val star = works.first { it.title == "별" }
        assertEquals(true to ComicView.WEBTOON, star.rightToLeft to star.view)
        val moon = works.first { it.title == "달빛 이야기" }
        assertEquals(false, moon.rightToLeft, "가져오기가 이 휴대폰에서 정한 방향을 덮었다")
    }

    @Test
    fun `the summary counts work settings even with no volume opened yet`() = runTest {
        // 이름 · 방향만 고쳐 두고 아직 펼친 권이 없다. 요약이 권 수만 세면 "담을 것이 없다" 로 보여 백업을 만들 수 없었다.
        val old = phone("old", "content://old/tree")
        old.scan("별 1권.cbz" to 5000L, "달 1권.cbz" to 7000L)
        val works = old.comics.works().first()
        old.comics.setRightToLeft(works.first { it.title == "별" }, true)
        old.comics.rename(works.first { it.title == "달" }, "달빛 이야기")
        val summary = old.records.summary()
        assertEquals(0 to 0, summary.books to summary.comics)
        assertEquals(2, summary.works)
        // 파일로 써도 같은 수를 알린다.
        assertEquals(2, old.records.export(ByteArrayOutputStream()).works)
    }

    @Test
    fun `settings of a work split off by hand stay on this phone, the work's own settings still move`() = runTest {
        // 따로 뺀 작품의 열쇠는 이 휴대폰의 문서 주소다 — 새 휴대폰에서는 붙을 작품이 없어 백업에 쓸모없는 줄만 쌓였다.
        val old = phone("old", "content://old/tree")
        old.scan("별 1권.cbz" to 5000L, "별 2권.cbz" to 6000L)
        val star = old.comics.works().first().single()
        old.comics.setRightToLeft(star, true)
        old.comics.split(star.entries.last())
        val alone = old.comics.works().first().first { it.entries.single().unit.id == old.id("별 2권.cbz") }
        old.comics.setView(alone, ComicView.WEBTOON)
        val works = old.records.collect().works
        assertTrue(works.none { it.key.startsWith(ComicLibrary.OWN_PREFIX) }, "따로 뺀 작품의 열쇠가 백업에 들어갔다: $works")
        assertEquals(listOf(true), works.map { it.rightToLeft })
    }

    @Test
    fun `the day a volume or a book was finished is the earliest either copy remembers`() = runTest {
        // 두 휴대폰에서 끝낸 날이 다르면 먼저 끝낸 날이 맞다. 책은 목록 앞의 값을 골라, 합치는 순서에 따라 나중 날이 남았다.
        val early = BookRecord("책.epub", 10, finishedAtEpochMs = 100)
        val late = BookRecord("책.epub", 10, finishedAtEpochMs = 900)
        assertEquals(100L, late.mergedWith(early).finishedAtEpochMs)
        assertEquals(100L, early.mergedWith(late).finishedAtEpochMs)
        val comicEarly = ComicRecord("별 1권.cbz", 10, page = 3, finishedAtEpochMs = 100)
        assertEquals(100L, comicEarly.copy(finishedAtEpochMs = 900).mergedWith(comicEarly).finishedAtEpochMs)
    }

    @Test
    fun `a backup from an older version without comics still imports its books`() = runTest {
        // 0.48 까지의 백업: comics · works 칸이 없다.
        val text = """{"format":"olo-ebook-reading-records","version":1,"createdAt":5,"books":[{"name":"어린 왕자.epub","size":10,"bookmarks":[],"annotations":[]}]}"""
        val file = assertNotNull(RecordsCodec.decode(text))
        assertEquals(1, file.books.size)
        assertEquals(emptyList(), file.comics)
    }

    @Test
    fun `broken comic entries are dropped one by one and the rest still import`() = runTest {
        val text = """{"format":"olo-ebook-reading-records","version":1,"books":[],
            "comics":[
              {"name":"","page":3},
              {"name":"음수.cbz","page":-4},
              {"name":"비율 밖.cbz","size":10,"page":2,"offset":7.5,"bookmarks":[{"page":-1},{"page":"x"},{"page":4}]},
              {"name":"멀쩡.cbz","size":20,"page":9}
            ],
            "works":[{"key":""},{"key":"별","rightToLeft":"예"},{"key":"달","view":"WEBTOON"}]}"""
        val file = assertNotNull(RecordsCodec.decode(text))
        // 이름 없음 · 자리도 책갈피도 없음은 버리고, 깨진 칸(비율 7.5 · 음수 책갈피)은 그 칸만 버린다.
        assertEquals(listOf("비율 밖.cbz", "멀쩡.cbz"), file.comics.map { it.name })
        val odd = file.comics.first()
        assertEquals(null to listOf(4), odd.offset to odd.bookmarks.map { it.page })
        assertEquals(listOf("달"), file.works.map { it.key })
    }

    @Test
    fun `a backup written and read back keeps every comic field`() = runTest {
        val file = RecordsFile(
            7,
            emptyList(),
            listOf(ComicRecord("별 1권.cbz", 5000L, 3, 100, 0.25f, 900, 950, listOf(ComicBookmarkRecord(2, 800)))),
            listOf(WorkRecord("별", "별빛", true, "WEBTOON")),
        )
        assertEquals(file, RecordsCodec.decode(RecordsCodec.encode(file)))
    }
}
