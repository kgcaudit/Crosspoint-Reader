package io.github.kgcaudit.reader.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.data.ReadingTime
import io.github.kgcaudit.reader.data.TimeItem
import io.github.kgcaudit.reader.data.TimeMode
import io.github.kgcaudit.reader.data.TimeRow
import io.github.kgcaudit.reader.data.db.ReaderDatabase
import io.github.kgcaudit.reader.data.library.ComicLibrary
import io.github.kgcaudit.reader.data.library.Library
import io.github.kgcaudit.reader.data.library.ScanResult
import io.github.kgcaudit.reader.data.library.ScannedBook
import io.github.kgcaudit.reader.data.library.ScannedComic
import io.github.kgcaudit.reader.document.BookFormat
import io.github.kgcaudit.reader.document.comic.ComicUnitKind
import io.github.kgcaudit.reader.document.comic.ShelfMark
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 독서 기록(0.51.0)도 백업 파일로 옮겨 간다 — 휴대폰을 바꾸면 "올해 읽은 시간" 이 0 으로 돌아가면 안 된다. 만화 작품을 손으로
 * 옮긴 서재 갈래(SHELF)도 함께: 0.50 까지 백업에서 빠져 있었다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReadingTimeBackupTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir = createTempDirectory("time-records").toFile()
    private val seoul = ZoneId.of("Asia/Seoul")

    private fun at(day: Int, hour: Int = 9) = ZonedDateTime.of(2026, 10, day, hour, 0, 0, 0, seoul).toInstant().toEpochMilli()

    private inner class Phone(name: String, private val folder: String) {
        val db: ReaderDatabase = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build()
        val library = Library(db)
        val comics = ComicLibrary(db)
        val time = ReadingTime(db) { seoul }
        val records = RecordsBackup(db, File(dir, "$name-pending.json")) { 1_000L }

        fun id(file: String) = "$folder/$file"

        suspend fun scan(books: List<Pair<String, Long?>> = emptyList(), comicFiles: List<Pair<String, Long?>> = emptyList()) {
            val scanned = books.map { (n, s) -> ScannedBook(id(n), n, BookFormat.EPUB, s, 1L) }
            val units = comicFiles.map { (n, s) -> ScannedComic(id(n), n, listOf("Comic"), ComicUnitKind.ARCHIVE, "cbz", s, 1L) }
            library.applyScan(folder, ScanResult(scanned, complete = true), 1L)
            comics.applyScan(folder, ScanResult(emptyList(), complete = true, units), 1L)
            records.resumePending()
        }

        fun backup(): String = ByteArrayOutputStream().also { kotlinx.coroutines.runBlocking { records.export(it) } }.toString("UTF-8")

        suspend fun bring(text: String): ImportResult {
            val file = assertNotNull(records.read(text.byteInputStream()), "백업을 읽지 못했다")
            return records.apply(records.plan(file))
        }

        suspend fun rows(item: TimeItem, file: String): List<TimeRow> = time.all().filter { it.item == item && it.id == id(file) }
    }

    private val phones = ArrayList<Phone>()
    private fun phone(name: String, folder: String) = Phone(name, folder).also { phones += it }

    @After
    fun close() {
        phones.forEach { it.db.close() }
        dir.deleteRecursively()
    }

    @Test
    fun `reading time and a hand moved comic shelf travel to a new phone`() = runTest {
        val old = phone("old", "content://old")
        old.scan(listOf("데미안.epub" to 9000L), listOf("별 1권.cbz" to 5000L))
        old.time.record(TimeItem.BOOK, old.id("데미안.epub"), TimeMode.READ, 40 * 60_000L, at(3))
        old.time.record(TimeItem.BOOK, old.id("데미안.epub"), TimeMode.LISTEN, 20 * 60_000L, at(4))
        old.time.record(TimeItem.COMIC, old.id("별 1권.cbz"), TimeMode.READ, 38 * 60_000L, at(5))
        // 종이책으로 다 읽어 길게 눌러 "다 읽음" 으로 옮긴 작품.
        old.comics.setShelf(old.comics.works().first().single(), ShelfMark.Finished(at(6), 1))
        val text = old.backup()

        val new = phone("new", "content://new")
        new.scan(listOf("데미안.epub" to 9000L), listOf("별 1권.cbz" to 5000L))
        new.bring(text)

        assertEquals(
            setOf(TimeRow(TimeItem.BOOK, new.id("데미안.epub"), LocalDate.of(2026, 10, 3), TimeMode.READ, 40 * 60_000L),
                TimeRow(TimeItem.BOOK, new.id("데미안.epub"), LocalDate.of(2026, 10, 4), TimeMode.LISTEN, 20 * 60_000L)),
            new.rows(TimeItem.BOOK, "데미안.epub").toSet(),
        )
        assertEquals(38 * 60_000L, new.rows(TimeItem.COMIC, "별 1권.cbz").single().millis)
        assertEquals(ShelfMark.Finished(at(6), 1), new.comics.works().first().single().shelfMark, "옮긴 서재 갈래가 백업에 빠졌다")
    }

    @Test
    fun `importing the same backup twice does not double the time and keeps the larger day of this phone`() = runTest {
        val old = phone("old", "content://old")
        old.scan(listOf("데미안.epub" to 9000L))
        old.time.record(TimeItem.BOOK, old.id("데미안.epub"), TimeMode.READ, 30 * 60_000L, at(3))
        old.time.record(TimeItem.BOOK, old.id("데미안.epub"), TimeMode.READ, 10 * 60_000L, at(4))
        val text = old.backup()

        val new = phone("new", "content://new")
        new.scan(listOf("데미안.epub" to 9000L))
        // 새 휴대폰에서도 4일에 더 오래 읽었다.
        new.time.record(TimeItem.BOOK, new.id("데미안.epub"), TimeMode.READ, 25 * 60_000L, at(4))
        new.bring(text)
        new.bring(text)

        val byDay = new.rows(TimeItem.BOOK, "데미안.epub").associate { it.day.dayOfMonth to it.millis }
        assertEquals(mapOf(3 to 30 * 60_000L, 4 to 25 * 60_000L), byDay, "두 번 가져오자 시간이 불어났거나 이 휴대폰의 날이 덮였다")
    }

    @Test
    fun `a book or comic that only has reading time is still backed up and waits for its file`() = runTest {
        val old = phone("old", "content://old")
        old.scan(listOf("어린 왕자.epub" to 700L), listOf("달 1권.cbz" to 300L))
        old.time.record(TimeItem.BOOK, old.id("어린 왕자.epub"), TimeMode.READ, 5 * 60_000L, at(7))
        old.time.record(TimeItem.COMIC, old.id("달 1권.cbz"), TimeMode.READ, 3 * 60_000L, at(7))
        val text = old.backup()

        // 새 휴대폰은 아직 책 폴더가 없다 — 가져온 시간은 기다렸다가 폴더를 더하면 붙는다.
        val new = phone("new", "content://new")
        val result = new.bring(text)
        assertEquals(listOf("어린 왕자.epub", "달 1권.cbz"), result.missing)
        new.scan(listOf("어린 왕자.epub" to 700L), listOf("달 1권.cbz" to 300L))
        assertEquals(5 * 60_000L, new.rows(TimeItem.BOOK, "어린 왕자.epub").single().millis)
        assertEquals(3 * 60_000L, new.rows(TimeItem.COMIC, "달 1권.cbz").single().millis)
    }

    @Test
    fun `a backup made before reading time existed still imports`() = runTest {
        val new = phone("new", "content://new")
        new.scan(listOf("데미안.epub" to 9000L))
        val old = """{"format":"olo-ebook-reading-records","version":1,"createdAt":5,
            "books":[{"name":"데미안.epub","size":9000,"progress":{"locator":"r:3:10","percent":20,"updatedAt":4}}],
            "comics":[],"works":[{"key":"별","title":"별빛"}]}"""
        val result = new.bring(old)
        assertEquals(1, result.books)
        assertEquals(emptyList(), new.time.all())
        assertNotNull(new.db.progress().get(new.id("데미안.epub")))
    }

    @Test
    fun `broken time entries and a broken shelf mark are dropped one by one`() = runTest {
        val new = phone("new", "content://new")
        new.scan(listOf("데미안.epub" to 9000L), listOf("별 1권.cbz" to 5000L))
        val text = """{"format":"olo-ebook-reading-records","version":1,"createdAt":5,
            "books":[{"name":"데미안.epub","size":9000,"time":[
                {"day":"2026-10-03","mode":"READ","ms":60000},
                {"day":"10월 4일","mode":"READ","ms":60000},
                {"day":"2026-10-05","mode":"READ","ms":-5},
                {"day":"2026-10-06","mode":"READ","ms":999999999999},
                {"day":"2026-10-07","ms":60000}]}],
            "comics":[{"name":"별 1권.cbz","size":5000,"time":[{"day":"2026-10-03","mode":"READ","ms":1000}]}],
            "works":[{"key":"별","shelf":"DONE:yesterday"}]}"""
        new.bring(text)
        val byDay = new.rows(TimeItem.BOOK, "데미안.epub").associate { it.day.dayOfMonth to it.millis }
        // 깨진 날 · 음수 · 방식 없는 칸은 버리고, 하루보다 긴 칸은 하루로 자른다. 멀쩡한 칸은 살린다.
        assertEquals(mapOf(3 to 60_000L, 6 to ReadingTime.DAY_MS), byDay)
        // 시간만 든 만화 권도 버리지 않는다.
        assertEquals(1_000L, new.rows(TimeItem.COMIC, "별 1권.cbz").single().millis)
        assertEquals(null, new.comics.works().first().single().shelfMark, "깨진 갈래 글을 옮겼다")
    }

    @Test
    fun `a moved book or comic takes its reading time along and adds it to time read at the new place`() = runTest {
        val p = phone("p", "A")
        p.scan(listOf("데미안.epub" to 9000L), listOf("별 1권.cbz" to 5000L))
        p.time.record(TimeItem.BOOK, "A/데미안.epub", TimeMode.READ, 30 * 60_000L, at(3))
        p.time.record(TimeItem.COMIC, "A/별 1권.cbz", TimeMode.READ, 10 * 60_000L, at(3))
        // 같은 파일을 B 로 옮겼다. 잇기 전에 새 자리에서 같은 날 조금 더 읽었다.
        p.library.applyScan("A", ScanResult(emptyList(), complete = true), 2L)
        p.comics.applyScan("A", ScanResult(emptyList(), complete = true, emptyList()), 2L)
        p.library.applyScan("B", ScanResult(listOf(ScannedBook("B/데미안.epub", "데미안.epub", BookFormat.EPUB, 9000L, 1L)), complete = true), 2L)
        p.comics.applyScan("B", ScanResult(emptyList(), complete = true, listOf(ScannedComic("B/별 1권.cbz", "별 1권.cbz", listOf("Comic"), ComicUnitKind.ARCHIVE, "cbz", 5000L, 1L))), 2L)
        p.time.record(TimeItem.BOOK, "B/데미안.epub", TimeMode.READ, 5 * 60_000L, at(3))

        val moved = p.records.adoptMoved()
        assertEquals(listOf("A/데미안.epub" to "B/데미안.epub"), moved.books)
        assertEquals(listOf("A/별 1권.cbz" to "B/별 1권.cbz"), moved.comics)
        val rows = p.time.all()
        // 옛 자리와 새 자리에서 읽은 시간은 둘 다 실제로 읽은 시간이다 — 큰 쪽이 아니라 합.
        assertEquals(35 * 60_000L, rows.single { it.item == TimeItem.BOOK }.millis)
        assertEquals("B/데미안.epub", rows.single { it.item == TimeItem.BOOK }.id)
        assertEquals(10 * 60_000L, rows.single { it.item == TimeItem.COMIC && it.id == "B/별 1권.cbz" }.millis)
        // 옛 행은 비웠다 — 한 번 더 이어도 두 번 더하지 않는다.
        assertTrue(p.records.adoptMoved().isEmpty)
        assertEquals(35 * 60_000L, p.time.all().single { it.item == TimeItem.BOOK }.millis)
    }
}
