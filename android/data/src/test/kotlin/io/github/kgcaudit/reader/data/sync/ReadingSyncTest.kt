package io.github.kgcaudit.reader.data.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.data.ReaderData
import io.github.kgcaudit.reader.data.db.ReaderDatabase
import io.github.kgcaudit.reader.data.saf.TestDocumentsProvider
import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.Bookmark
import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.document.ReadingProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 두 기기가 한 책 폴더를 나눠 쓴다(동기화 앱이 맞춰 준 폴더를 같은 폴더로 흉내 낸다). 진짜 `DocumentsProvider` 를 거쳐 `.olo/sync/`
 * 를 만들고 쓰고 읽는다 — URI 조립이 틀리면 기기에서는 "켰는데 아무 일도 없다" 로만 보인다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReadingSyncTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val resolver = context.contentResolver
    private lateinit var base: File
    private lateinit var root: File
    private val tree: Uri get() = TestDocumentsProvider.treeUri
    private val dbs = ArrayList<ReaderDatabase>()

    /** 한 기기: 제 DB · 제 상태 폴더 · 제 이름. 폴더 허락은 휴대폰 하나(이 시험 프로세스)의 것을 함께 쓴다. */
    private inner class Device(name: String) {
        val db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build().also { dbs += it }
        var now = 1_000L
        val data = ReaderData(context, db, io = Dispatchers.Unconfined, clock = { now })
        val sync = ReadingSync(resolver, db, data.folders, File(base, "state-$name"), { name }, clock = { now }, io = Dispatchers.Unconfined)

        suspend fun book(name: String) = data.library.books().first().single { it.displayName == name }.id
    }

    @Before
    fun setUp() {
        base = File(context.cacheDir, "sync-test").apply { deleteRecursively(); mkdirs() }
        root = TestDocumentsProvider.install(base)
    }

    @After
    fun tearDown() {
        dbs.forEach { it.close() }
        base.deleteRecursively()
    }

    private fun put(path: String, bytes: ByteArray = ByteArray(100)) =
        File(root, path).apply { parentFile!!.mkdirs(); writeBytes(bytes) }

    private fun syncDir() = File(root, ".olo/sync")

    private suspend fun twoDevices(): Pair<Device, Device> {
        put("소설/데미안.epub")
        val a = Device("갤럭시 S24")
        val b = Device("갤럭시 탭 S9")
        a.data.folders.register(tree)
        a.data.rescan(tree)
        b.data.rescan(tree)
        a.sync.enabled = true
        b.sync.enabled = true
        return a to b
    }

    private fun at(spine: Int, offset: Int) = Locator.Reflow(spine, offset)

    @Test
    fun `one device writes its own file under the hidden folder and the other offers that place without moving`() = runTest {
        val (a, b) = twoDevices()
        val id = a.book("데미안.epub")
        a.data.progress.save(ReadingProgress(id, at(7, 300), 61f, 2_000))
        a.data.bookmarks.add(Bookmark(Bookmark.NO_ID, id, at(2, 40), "싱클레어", 1_500))
        assertEquals(1, a.sync.writeOwn())

        val written = File(syncDir(), "${a.sync.deviceId}.json")
        assertTrue(written.isFile, "책 폴더 안 .olo/sync 에 이 기기의 파일이 없다")
        assertEquals("갤럭시 S24", SyncCodec.decode(written.readText(), a.sync.deviceId)?.deviceName)

        // 탭은 앞쪽을 읽고 있었다.
        b.data.progress.save(ReadingProgress(id, at(1, 0), 5f, 9_000))
        val offer = assertNotNull(b.sync.offerForBook(id.value))
        assertEquals(at(7, 300), offer.locator)
        assertEquals("갤럭시 S24", offer.deviceName)
        // 책갈피는 조용히 합쳐지지만 읽은 자리는 그대로다 — 사람이 "거기로" 를 눌러야 간다(8-2).
        assertEquals(listOf(at(2, 40)), b.data.bookmarks.forBook(id).map { it.locator })
        assertEquals(at(1, 0), b.data.progress.get(id)?.locator)
        assertEquals(listOf(SyncDevice(a.sync.deviceId, "갤럭시 S24", 1_000)), b.sync.devices())
    }

    @Test
    fun `nothing is written or read while the setting is off`() = runTest {
        val (a, b) = twoDevices()
        a.sync.enabled = false
        val id = a.book("데미안.epub")
        a.data.progress.save(ReadingProgress(id, at(3, 0), 30f, 2_000))
        assertEquals(0, a.sync.writeOwn())
        assertFalse(File(root, ".olo").exists(), "꺼 두었는데 책 폴더에 숨은 폴더가 생겼다")
        // 다른 기기가 써 둔 파일이 있어도 꺼 둔 기기는 묻지 않는다.
        a.sync.enabled = true
        a.sync.writeOwn()
        b.sync.enabled = false
        assertNull(b.sync.offerForBook(id.value))
        assertTrue(b.data.bookmarks.forBook(id).isEmpty())
    }

    @Test
    fun `a folder registered with read access only is skipped, not fatal`() = runTest {
        put("데미안.epub")
        val a = Device("폰")
        // 0.50 까지의 등록: 읽기만 받았다.
        resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        a.data.rescan(tree)
        a.sync.enabled = true
        a.data.progress.save(ReadingProgress(a.book("데미안.epub"), at(3, 0), 30f, 2_000))
        assertEquals(listOf(tree), a.sync.foldersWithoutWrite())
        assertEquals(0, a.sync.writeOwn())
        assertFalse(File(root, ".olo").exists())
        // 다시 고르면(등록) 읽기 · 쓰기를 함께 받아 쓸 수 있다.
        a.data.folders.register(tree)
        assertTrue(a.sync.foldersWithoutWrite().isEmpty())
        assertEquals(1, a.sync.writeOwn())
    }

    @Test
    fun `nothing is written when nothing changed and an empty folder gets no hidden folder`() = runTest {
        val (a, _) = twoDevices()
        // 아직 읽은 것이 없다 — 책만 둔 폴더에 빈 숨은 폴더를 만들지 않는다.
        assertEquals(0, a.sync.writeOwn())
        assertFalse(File(root, ".olo").exists())
        val id = a.book("데미안.epub")
        a.data.progress.save(ReadingProgress(id, at(3, 0), 30f, 2_000))
        assertEquals(1, a.sync.writeOwn())
        // 같은 기록이면 다시 쓰지 않는다 — 쪽마다 동기화 앱이 파일을 올리지 않게.
        a.now = 50_000
        assertEquals(0, a.sync.writeOwn())
        a.data.progress.save(ReadingProgress(id, at(3, 900), 31f, 3_000))
        assertEquals(1, a.sync.writeOwn())
    }

    @Test
    fun `the library scan does not list the sync folder or anything inside it`() = runTest {
        val (a, _) = twoDevices()
        a.data.progress.save(ReadingProgress(a.book("데미안.epub"), at(3, 0), 30f, 2_000))
        a.sync.writeOwn()
        // 숨은 폴더 안의 것은 책이든 기록이든 서재에 오르지 않는다.
        put(".olo/sync/섞인 책.epub")
        a.data.rescan(tree)
        assertEquals(listOf("데미안.epub"), a.data.library.books().first().map { it.displayName })
    }

    @Test
    fun `conflict copies, broken files and other files in the sync folder are ignored`() = runTest {
        val (a, b) = twoDevices()
        val id = a.book("데미안.epub")
        val other = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        val far = SyncFile(other, "옛 사본", 1, listOf(io.github.kgcaudit.reader.data.backup.BookRecord("데미안.epub", 100, progress = io.github.kgcaudit.reader.data.backup.ProgressRecord(Locator.encode(at(30, 0)), 99f, 1))))
        syncDir().mkdirs()
        File(syncDir(), "$other (1).json").writeText(SyncCodec.encode(far))
        File(syncDir(), "bbbbbbbb-bbbb-cccc-dddd-eeeeeeeeeeee.json").writeText("{\"format\":\"olo-ebook-sync\", 깨짐")
        File(syncDir(), "메모.txt").writeText("hello")
        File(syncDir(), "folder.json").mkdirs()
        // 쓸 만한 것은 a 의 파일 하나뿐이다.
        a.data.progress.save(ReadingProgress(id, at(5, 0), 40f, 2_000))
        a.sync.writeOwn()
        assertEquals(at(5, 0), b.sync.offerForBook(id.value)?.locator)
        assertEquals(listOf("갤럭시 S24"), b.sync.devices().map { it.name })
    }

    @Test
    fun `a bookmark removed on this device does not come back from the other device`() = runTest {
        val (a, b) = twoDevices()
        val id = a.book("데미안.epub")
        a.data.bookmarks.add(Bookmark(Bookmark.NO_ID, id, at(2, 40), "싱클레어", 1_500))
        a.sync.writeOwn()
        b.sync.refreshAll()
        val mark = b.data.bookmarks.forBook(id).single()
        b.data.bookmarks.remove(mark.id)
        // a 가 다른 것을 더해 파일이 바뀌어도, b 에서 뺀 책갈피는 돌아오지 않는다. 새 책갈피는 들어온다.
        a.data.bookmarks.add(Bookmark(Bookmark.NO_ID, id, at(4, 0), "데미안", 1_600))
        a.now = 5_000
        a.sync.writeOwn()
        File(syncDir(), "${a.sync.deviceId}.json").setLastModified(99_000)
        b.sync.refreshAll()
        assertEquals(listOf(at(4, 0)), b.data.bookmarks.forBook(id).map { it.locator })
    }

    @Test
    fun `the earliest finished date wins and a book never opened here gets it`() = runTest {
        val (a, b) = twoDevices()
        val id = a.book("데미안.epub")
        a.data.library.setFinished(id, 3_000, 3_000)
        a.sync.writeOwn()
        b.sync.refreshAll()
        assertEquals(3_000, b.data.library.shelf().first().single().finishedAtEpochMs)
        // b 가 더 이르게 끝냈으면 b 의 날이 남는다.
        b.data.library.setFinished(id, 1_000, 1_000)
        File(syncDir(), "${a.sync.deviceId}.json").setLastModified(99_000)
        b.sync.refreshAll()
        assertEquals(1_000, b.data.library.shelf().first().single().finishedAtEpochMs)
    }

    @Test
    fun `comics share bookmarks silently and offer the further page`() = runTest {
        put("만화/옛날 만화 1권.cbz", ByteArray(500))
        val a = Device("폰")
        val b = Device("탭")
        a.data.folders.register(tree)
        a.data.rescan(tree)
        b.data.rescan(tree)
        a.sync.enabled = true
        b.sync.enabled = true
        val unit = a.data.comics.units().first().single().id
        a.data.comics.saveProgress(unit, 127, 180, 2_000)
        a.data.comics.toggleBookmark(unit, 12, 2_000)
        a.sync.writeOwn()
        b.data.comics.saveProgress(unit, 3, 180, 9_000)
        val offer = assertNotNull(b.sync.offerForComic(unit))
        assertEquals(127, offer.page)
        assertEquals(listOf(12), b.data.comics.bookmarks(unit).first())
        assertEquals(3, b.data.comics.progressOf(unit)?.page)
    }

    @Test
    fun `a provider that cannot truncate still leaves a whole json file`() = runTest {
        val (a, b) = twoDevices()
        val id = a.book("데미안.epub")
        repeat(5) { a.data.bookmarks.add(Bookmark(Bookmark.NO_ID, id, at(it + 1, 0), "긴 글 ".repeat(40), 1)) }
        a.sync.writeOwn()
        TestDocumentsProvider.truncates = false
        // 짧아진 글을 "w" 로 덮으면 옛 글 끝이 남아 JSON 이 깨진다.
        a.data.bookmarks.forBook(id).forEach { a.data.bookmarks.remove(it.id) }
        a.data.progress.save(ReadingProgress(id, at(9, 0), 90f, 2_000))
        assertEquals(1, a.sync.writeOwn())
        val text = File(syncDir(), "${a.sync.deviceId}.json").readText()
        // 안드로이드의 JSON 읽개는 닫는 괄호 뒤를 보지 않아 읽기만으로는 드러나지 않는다 — 옛 글의 꼬리가 남았는지 직접 본다.
        assertTrue("긴 글" !in text && text.trimEnd().endsWith("}"), "옛 글의 끝이 파일에 남았다")
        assertNotNull(SyncCodec.decode(text, a.sync.deviceId))
        assertEquals(at(9, 0), b.sync.offerForBook(id.value)?.locator)
    }

    @Test
    fun `the device id stays the same across restarts`() = runTest {
        val a = Device("폰")
        val again = ReadingSync(resolver, a.db, a.data.folders, File(base, "state-폰"), { "폰" })
        assertEquals(a.sync.deviceId, again.deviceId)
        assertTrue(SyncCodec.deviceIdOf("${a.sync.deviceId}.json") != null)
    }
}
