package io.github.kgcaudit.reader.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.data.RoomAnnotationRepository
import io.github.kgcaudit.reader.data.RoomBookmarkRepository
import io.github.kgcaudit.reader.data.RoomProgressRepository
import io.github.kgcaudit.reader.data.db.ReaderDatabase
import io.github.kgcaudit.reader.data.library.Library
import io.github.kgcaudit.reader.data.library.ScanResult
import io.github.kgcaudit.reader.data.library.ScannedBook
import io.github.kgcaudit.reader.document.Annotation
import io.github.kgcaudit.reader.document.BookFormat
import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.Bookmark
import io.github.kgcaudit.reader.document.HighlightColor
import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.document.ReadingProgress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

/** 휴대폰을 바꿀 때 읽기 기록이 백업 파일로 옮겨 가는가. 옛 휴대폰 · 새 휴대폰을 DB 두 개로 흉내 낸다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecordsBackupTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir = createTempDirectory("records").toFile()

    private inner class Phone(name: String, private val folder: String) {
        val db: ReaderDatabase = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build()
        val library = Library(db)
        val bookmarks = RoomBookmarkRepository(db.bookmarks())
        val progress = RoomProgressRepository(db.progress(), db.recent())
        val annotations = RoomAnnotationRepository(db.annotations())
        var now = 1_000L
        val records = RecordsBackup(db, File(dir, "$name-pending.json")) { now }

        /** 휴대폰마다 책 주소가 다르다 — 같은 파일이라도 id 가 다르게 만든다. */
        fun id(file: String) = BookId("$folder/$file")

        suspend fun scan(vararg files: Pair<String, Long?>) {
            library.applyScan(
                folder,
                ScanResult(files.map { (n, size) -> ScannedBook("$folder/$n", n, BookFormat.fromFileName(n)!!, size, 1L) }, complete = true),
                now,
            )
            records.resumePending()
        }

        fun backup(): String = ByteArrayOutputStream().also { runTestBlocking { records.export(it) } }.toString("UTF-8")
    }

    private val phones = ArrayList<Phone>()
    private fun phone(name: String, folder: String) = Phone(name, folder).also { phones += it }

    @After
    fun close() {
        phones.forEach { it.db.close() }
        dir.deleteRecursively()
    }

    private suspend fun Phone.bring(text: String): ImportResult {
        val file = assertNotNull(records.read(text.byteInputStream()), "백업을 읽지 못했다")
        return records.apply(records.plan(file))
    }

    private suspend fun Phone.readSome(file: String) {
        val id = id(file)
        library.markOpened(id, 500)
        progress.save(ReadingProgress(id, Locator.Reflow(3, 120), 40f, 600))
        bookmarks.add(Bookmark(Bookmark.NO_ID, id, Locator.Reflow(2, 30), "책갈피 글", 700))
        annotations.add(Annotation(0, id, Locator.Reflow(1, 5), Locator.Reflow(1, 20), HighlightColor.Blue, "메모", "칠한 글", 800))
    }

    @Test
    fun `records move to a new phone whose book addresses are different`() = runTest {
        val old = phone("old", "content://old/tree")
        old.scan("어린 왕자.epub" to 1234L, "다른 책.txt" to 50L)
        old.readSome("어린 왕자.epub")
        old.library.setFinished(old.id("어린 왕자.epub"), 900, 900)
        val text = old.backup()

        val new = phone("new", "content://new/tree")
        new.scan("어린 왕자.epub" to 1234L)
        val result = new.bring(text)

        // 새 휴대폰에서 같은 책을 열면 읽던 자리 · 책갈피 · 형광펜 · 메모 · 다 읽은 표시가 모두 있다.
        val id = new.id("어린 왕자.epub")
        assertEquals(ImportResult(books = 1, bookmarks = 1, annotations = 1, finished = 1, missing = emptyList()), result)
        assertEquals(Locator.Reflow(3, 120), new.progress.get(id)?.locator)
        assertEquals(listOf(Locator.Reflow(2, 30)), new.bookmarks.forBook(id).map { it.locator })
        val note = new.annotations.forBook(id).single()
        assertEquals(HighlightColor.Blue to "메모", note.color to note.note)
        assertEquals(900L, new.library.shelf().first().single().finishedAtEpochMs)
    }

    @Test
    fun `importing merges and never throws away what this phone already has`() = runTest {
        val old = phone("old", "content://old/tree")
        old.scan("책.epub" to 10L)
        old.readSome("책.epub")
        val text = old.backup()

        val new = phone("new", "content://new/tree")
        new.scan("책.epub" to 10L)
        val id = new.id("책.epub")
        // 새 휴대폰에서 이미 더 뒤까지 읽었고, 다른 자리에 책갈피가 있다.
        new.library.markOpened(id, 2_000)
        new.progress.save(ReadingProgress(id, Locator.Reflow(9, 0), 80f, 100))
        new.bookmarks.add(Bookmark(Bookmark.NO_ID, id, Locator.Reflow(5, 0), null, 1))
        new.annotations.add(Annotation(0, id, Locator.Reflow(1, 5), Locator.Reflow(1, 20), HighlightColor.Yellow, null, "칠한 글", 1))

        new.bring(text)
        new.bring(text) // 같은 백업을 두 번 가져와도 두 벌로 쌓이지 않는다.

        // 읽은 자리는 더 뒤쪽(새 휴대폰의 9장) — 백업이 더 나중에 적혔어도(600 > 100) 앞으로 되돌리지 않는다.
        assertEquals(Locator.Reflow(9, 0), new.progress.get(id)?.locator)
        assertEquals(listOf(Locator.Reflow(2, 30), Locator.Reflow(5, 0)), new.bookmarks.forBook(id).map { it.locator })
        // 같은 자리의 형광펜은 하나다. 이 휴대폰의 색을 두고, 없던 메모만 가져온다.
        val note = new.annotations.forBook(id).single()
        assertEquals(HighlightColor.Yellow to "메모", note.color to note.note)
        // 이 휴대폰에서 방금 연 책이 책장 뒤로 밀리지 않는다(연 시각 2,000 이 백업의 500 보다 나중).
        assertEquals(2_000L, new.db.recent().all().single().openedAtEpochMs)
    }

    @Test
    fun `records of books not found yet attach when their folder is added later`() = runTest {
        val old = phone("old", "content://old/tree")
        old.scan("운수 좋은 날.epub" to 77L)
        old.readSome("운수 좋은 날.epub")
        val text = old.backup()

        // 새 휴대폰: 백업을 먼저 가져오고, 책 폴더는 나중에 더한다.
        val new = phone("new", "content://new/tree")
        val result = new.bring(text)
        assertEquals(0, result.books)
        assertEquals(listOf("운수 좋은 날.epub"), result.missing)
        // 아직 붙지 않은 기록도 다음 백업에 담긴다 — 다시 백업해도 사라지지 않는다.
        assertEquals(1, new.records.summary().books)

        new.scan("운수 좋은 날.epub" to 77L)
        val id = new.id("운수 좋은 날.epub")
        assertEquals(Locator.Reflow(3, 120), new.progress.get(id)?.locator)
        assertEquals(1, new.bookmarks.forBook(id).size)
        assertTrue(new.records.pending().books.isEmpty(), "붙인 기록이 계속 기다리고 있다")
        // 붙은 뒤 다시 훑어도 두 벌이 되지 않는다.
        new.scan("운수 좋은 날.epub" to 77L)
        assertEquals(1, new.bookmarks.forBook(id).size)
    }

    @Test
    fun `a book with the same name but a different size is not treated as the same book`() = runTest {
        // 같은 이름의 다른 판(개정판)에 옛 판의 자리를 붙이면 엉뚱한 쪽이 열린다.
        val old = phone("old", "content://old/tree")
        old.scan("책.epub" to 10L)
        old.readSome("책.epub")
        val new = phone("new", "content://new/tree")
        new.scan("책.epub" to 11L)
        val result = new.bring(old.backup())
        assertEquals(0, result.books)
        assertNull(new.progress.get(new.id("책.epub")))
    }

    @Test
    fun `a record without a size attaches only when the name is unique`() = runTest {
        val text = """{"format":"olo-ebook-reading-records","version":1,"books":[
            {"name":"한 권.txt","progress":{"locator":"r:0:10","percent":5,"updatedAt":1}},
            {"name":"두 권.txt","progress":{"locator":"r:0:10","percent":5,"updatedAt":1}}]}"""
        val new = phone("new", "content://new/tree")
        new.scan("한 권.txt" to 5L)
        new.library.applyScan("content://other", ScanResult(listOf(ScannedBook("content://other/1", "두 권.txt", BookFormat.fromFileName("a.txt")!!, 1L, 1L)), true), 1)
        new.library.applyScan("content://third", ScanResult(listOf(ScannedBook("content://third/2", "두 권.txt", BookFormat.fromFileName("a.txt")!!, 2L, 1L)), true), 1)
        val result = new.bring(text)
        assertEquals(1, result.books)
        assertEquals(listOf("두 권.txt"), result.missing)
    }

    @Test
    fun `files that are not a backup change nothing`() = runTest {
        val new = phone("new", "content://new/tree")
        for (junk in listOf("", "그냥 글", "{\"books\":[]}", "[1,2,3]", "{\"format\":\"다른 앱\",\"books\":[{\"name\":\"a.epub\"}]}")) {
            assertNull(new.records.read(junk.byteInputStream()), "백업이 아닌 것을 백업으로 읽었다: $junk")
        }
        // 동영상처럼 큰 파일은 끝까지 읽지 않고 거절한다.
        val huge = object : java.io.InputStream() {
            var left = 40L * 1024 * 1024
            override fun read(): Int = if (left-- > 0) 'a'.code else -1
        }
        assertNull(new.records.read(huge))
    }

    @Test
    fun `a damaged entry is skipped and the rest of the backup is still imported`() = runTest {
        // 손으로 고치다 한 줄을 망가뜨린 백업 — 그 줄만 버린다. "null" 글자가 제목이 되지도 않는다.
        val text = """{"format":"olo-ebook-reading-records","version":9,"future":true,"books":[
            {"size":3},
            {"name":"책.epub","size":3,"title":null,"progress":{"locator":"망가짐","percent":50},
             "bookmarks":[{"locator":"r:1:2"},{"locator":"??"},{"nope":1}],
             "annotations":[{"start":"r:2:9","end":"r:2:1","color":"Blue","snippet":"뒤집힘"},
                            {"start":"r:2:1","end":"r:2:9","color":"Purple","note":"  ","snippet":"모르는 색"}]}]}"""
        val new = phone("new", "content://new/tree")
        new.scan("책.epub" to 3L)
        val result = new.bring(text)
        val id = new.id("책.epub")
        assertEquals(1, result.books)
        assertNull(new.progress.get(id), "망가진 자리를 읽은 자리로 삼았다")
        assertEquals(listOf(Locator.Reflow(1, 2)), new.bookmarks.forBook(id).map { it.locator })
        // 모르는 색은 버리지 않고 노랑으로 보인다. 빈 메모는 메모가 아니다.
        val note = new.annotations.forBook(id).single()
        assertEquals(HighlightColor.Yellow, note.color)
        assertNull(note.note)
        assertEquals("책.epub", new.library.get(id)?.label)
    }

    @Test
    fun `a broken pending file does not block importing`() = runTest {
        val new = phone("new", "content://new/tree")
        File(dir, "new-pending.json").writeText("{망가진")
        new.scan("책.epub" to 3L)
        assertTrue(new.records.pending().books.isEmpty())
        val old = phone("old", "content://old/tree")
        old.scan("없는 책.epub" to 1L)
        old.readSome("없는 책.epub")
        new.bring(old.backup())
        assertEquals(listOf("없는 책.epub"), new.records.pending().books.map { it.displayName })
    }

    @Test
    fun `records of a removed folder still go into the backup`() = runTest {
        // 폴더를 빼도 "기록은 남습니다" 라고 약속한다. 백업에서 빠지면 새 휴대폰으로 옮길 때 그 기록을 잃는다.
        val old = phone("old", "content://old/tree")
        old.scan("뺀 폴더의 책.epub" to 9L)
        old.readSome("뺀 폴더의 책.epub")
        old.library.forgetFolder("content://old/tree")
        val summary = old.records.summary()
        assertEquals(1, summary.books)
        assertEquals(1, summary.bookmarks)

        val new = phone("new", "content://new/tree")
        new.scan("뺀 폴더의 책.epub" to 9L)
        assertEquals(1, new.bring(old.backup()).books)
    }

    @Test
    fun `a book whose size this phone does not know still gets its records`() = runTest {
        // 크기를 알려 주지 않는 제공자(일부 클라우드)의 책. 크기로만 맞추면 기록이 영영 기다리기만 한다.
        val old = phone("old", "content://old/tree")
        old.scan("구름 위의 책.epub" to 1234L)
        old.readSome("구름 위의 책.epub")
        val new = phone("new", "content://new/tree")
        new.scan("구름 위의 책.epub" to null)
        assertEquals(1, new.bring(old.backup()).books)
        assertEquals(1, new.bookmarks.forBook(new.id("구름 위의 책.epub")).size)
    }

    @Test
    fun `leaving the screen in the middle of an import does not lose the records waiting for their books`() = runTest {
        val old = phone("old", "content://old/tree")
        old.scan("있는 책.epub" to 1L, "없는 책.epub" to 2L)
        old.readSome("있는 책.epub")
        old.readSome("없는 책.epub")
        val text = old.backup()
        val new = phone("new", "content://new/tree")
        new.scan("있는 책.epub" to 1L)
        val plan = new.records.plan(assertNotNull(new.records.read(text.byteInputStream())))
        // 가져오기가 처음 멈추는 곳(트랜잭션)까지 가자마자 취소한다 — 사용자가 뒤로 간 것과 같다.
        val job = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { new.records.apply(plan) }
        job.cancel()
        job.join()
        assertEquals(listOf("없는 책.epub"), new.records.pending().books.map { it.displayName })
        assertEquals(1, new.bookmarks.forBook(new.id("있는 책.epub")).size)
    }
}

private fun runTestBlocking(block: suspend () -> Unit) = kotlinx.coroutines.runBlocking { block() }
