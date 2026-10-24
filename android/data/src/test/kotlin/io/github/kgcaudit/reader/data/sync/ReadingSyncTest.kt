package io.github.kgcaudit.reader.data.sync

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.data.ReaderData
import io.github.kgcaudit.reader.data.backup.BookRecord
import io.github.kgcaudit.reader.data.backup.ProgressRecord
import io.github.kgcaudit.reader.data.db.ReaderDatabase
import io.github.kgcaudit.reader.data.saf.TestDocumentsProvider
import io.github.kgcaudit.reader.document.Bookmark
import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.document.ReadingProgress
import io.github.kgcaudit.reader.document.comic.SpreadSplit
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
 * 두 기기가 한 FTP 서버 폴더를 나눠 쓴다(0.58.0). 진짜 [FtpSyncStore] 가 이 프로세스 안의 진짜 FTP 서버([TestFtpServer])와 말한다 —
 * 명령이 틀리면(수동 모드 · 폴더 만들기 · 이름 바꾸기) 기기에서는 "켰는데 아무 일도 없다" 로만 보인다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReadingSyncTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var base: File
    private lateinit var root: File
    private val tree: Uri get() = TestDocumentsProvider.treeUri
    private val dbs = ArrayList<ReaderDatabase>()
    private lateinit var ftp: TestFtpServer

    /** 서버 폴더 안의 파일(사람이 FTP 프로그램으로 보는 그대로). */
    private fun remote(name: String) = File(ftp.root, "OLO/$name")
    private fun remoteText(name: String) = remote(name).takeIf { it.isFile }?.readText()
    private fun remoteFiles() = File(ftp.root, "OLO").listFiles().orEmpty().map { it.name }.sorted()

    /** 한 기기: 제 DB · 제 상태 폴더 · 제 이름 · 제 설정. 서버는 함께 쓴다. */
    private inner class Device(name: String) {
        val db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build().also { dbs += it }
        var now = 1_000L
        val data = ReaderData(context, db, io = Dispatchers.Unconfined, clock = { now })
        var config: FtpConfig? = ftp.config()
        val store = CountingStore(FtpSyncStore({ config }, timeoutMs = 5_000))
        val sync = ReadingSync(db, store, File(base, "state-$name"), { name }, clock = { now }, io = Dispatchers.Unconfined)

        suspend fun book(name: String) = data.library.books().first().single { it.displayName == name }.id
    }

    @Before
    fun setUp() {
        base = File(context.cacheDir, "sync-test").apply { deleteRecursively(); mkdirs() }
        root = TestDocumentsProvider.install(base)
        ftp = TestFtpServer(File(base, "ftp-root")).start()
    }

    @After
    fun tearDown() {
        ftp.stop()
        dbs.forEach { it.close() }
        base.deleteRecursively()
    }

    private fun put(path: String, bytes: ByteArray = ByteArray(100)) =
        File(root, path).apply { parentFile!!.mkdirs(); writeBytes(bytes) }

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

    /** 다른 기기가 다음 번에 목록을 다시 받게 시계를 넘긴다(책을 열 때마다 묻지 않는 간격). */
    private fun Device.later() {
        now += 60_000
    }

    @Test
    fun `one device uploads its own file to the ftp folder and the other offers that place without moving`() = runTest {
        val (a, b) = twoDevices()
        val id = a.book("데미안.epub")
        a.data.progress.save(ReadingProgress(id, at(7, 300), 61f, 2_000))
        a.data.bookmarks.add(Bookmark(Bookmark.NO_ID, id, at(2, 40), "싱클레어", 1_500))
        assertEquals(1, a.sync.writeOwn())

        // 정한 경로(/OLO)가 없으면 만든다. 그 안에 이 기기의 파일 하나 — 책 파일은 올리지 않는다.
        val written = assertNotNull(remoteText("${a.sync.deviceId}.json"), "서버 폴더에 이 기기의 파일이 없다")
        assertEquals("갤럭시 S24", SyncCodec.decode(written, a.sync.deviceId)?.deviceName)
        assertEquals(listOf("${a.sync.deviceId}.json"), remoteFiles())

        // 탭은 앞쪽을 읽고 있었다.
        b.data.progress.save(ReadingProgress(id, at(1, 0), 5f, 9_000))
        val offer = assertNotNull(b.sync.offerForBook(id.value))
        assertEquals(at(7, 300), offer.locator)
        assertEquals("갤럭시 S24", offer.deviceName)
        // 책갈피는 조용히 합쳐지지만 읽은 자리는 그대로다 — 사람이 "거기로" 를 눌러야 간다(8-2).
        assertEquals(listOf(at(2, 40)), b.data.bookmarks.forBook(id).map { it.locator })
        assertEquals(at(1, 0), b.data.progress.get(id)?.locator)
        assertEquals(listOf("갤럭시 S24"), b.sync.devices().map { it.name })
        assertEquals(1, b.sync.titlesOf(a.sync.deviceId))
        assertNotNull(b.sync.status.value.lastSyncedAtEpochMs)
    }

    @Test
    fun `nothing is sent to the server while the setting is off`() = runTest {
        val (a, b) = twoDevices()
        a.sync.enabled = false
        b.sync.enabled = false
        val id = a.book("데미안.epub")
        a.data.progress.save(ReadingProgress(id, at(3, 0), 30f, 2_000))
        assertEquals(0, a.sync.writeOwn())
        assertEquals(0, a.sync.refreshAll())
        assertNull(b.sync.offerForBook(id.value))
        // 꺼 둔 기기는 한 번도 서버를 부르지 않는다(인터넷을 쓰지 않는다).
        assertEquals(0, a.store.calls + b.store.calls, "꺼져 있는데 서버를 불렀다")
        assertFalse(File(ftp.root, "OLO").exists())
    }

    @Test
    fun `nothing is uploaded when nothing changed, a device that read nothing makes no file and no half file is left`() = runTest {
        val (a, _) = twoDevices()
        assertEquals(0, a.sync.writeOwn())
        assertTrue(remoteFiles().isEmpty(), "읽은 것이 없는데 빈 파일을 만들었다")
        val id = a.book("데미안.epub")
        a.data.progress.save(ReadingProgress(id, at(3, 0), 30f, 2_000))
        assertEquals(1, a.sync.writeOwn())
        // 같은 기록이면 다시 올리지 않는다 — 쪽을 넘길 때마다 서버를 부르지 않게.
        val before = a.store.calls
        a.now = 50_000
        assertEquals(0, a.sync.writeOwn())
        assertEquals(before, a.store.calls)
        // 바뀌면 같은 이름을 통째로 바꾼다 — 임시 파일(.part)이 남지 않는다.
        a.data.progress.save(ReadingProgress(id, at(3, 900), 31f, 3_000))
        assertEquals(1, a.sync.writeOwn())
        assertEquals(listOf("${a.sync.deviceId}.json"), remoteFiles())
        assertEquals(at(3, 900), Locator.decodeOrNull(SyncCodec.decode(remoteText("${a.sync.deviceId}.json")!!)!!.books.single().progress!!.locator))
    }

    @Test
    fun `the library scan does not list an old sync folder or anything inside it`() = runTest {
        // 0.56 까지 책 폴더에 남긴 `.olo` 는 그대로 남아 있을 수 있다. 서재에 오르지 않는다.
        put("데미안.epub")
        put(".olo/sync/섞인 책.epub")
        val a = Device("폰")
        a.data.folders.register(tree)
        a.data.rescan(tree)
        assertEquals(listOf("데미안.epub"), a.data.library.books().first().map { it.displayName })
    }

    @Test
    fun `copies, half uploaded and other files in the folder are ignored`() = runTest {
        val (a, b) = twoDevices()
        val id = a.book("데미안.epub")
        val other = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        val far = SyncFile(other, "옛 사본", 1, listOf(BookRecord("데미안.epub", 100, progress = ProgressRecord(Locator.encode(at(30, 0)), 99f, 1))))
        File(ftp.root, "OLO").mkdirs()
        remote("$other (1).json").writeText(SyncCodec.encode(far))
        // 다른 기기가 올리는 중이던 임시 파일 — 반쪽 글이다.
        remote(".$other.json.part").writeText(SyncCodec.encode(far))
        remote("bbbbbbbb-bbbb-cccc-dddd-eeeeeeeeeeee.json").writeText("{\"format\":\"olo-ebook-sync\", 깨짐")
        remote("메모.txt").writeText("hello")
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
        b.sync.refreshAll()
        assertEquals(listOf(at(4, 0)), b.data.bookmarks.forBook(id).map { it.locator })
    }

    @Test
    fun `a bookmark removed here before reading the other device's copy of it does not come back`() = runTest {
        val (a, b) = twoDevices()
        val id = a.book("데미안.epub")
        a.data.bookmarks.add(Bookmark(Bookmark.NO_ID, id, at(2, 40), "싱클레어", 1_500))
        a.sync.writeOwn()
        // b 가 받아 제 파일에도 싣는다.
        b.sync.refreshAll()
        b.sync.writeOwn()
        // a 는 b 의 파일을 읽기 전에 그 책갈피를 뺀다 — 제가 내놓은 것이니 b 에서 돌아와도 들이지 않는다.
        a.data.bookmarks.remove(a.data.bookmarks.forBook(id).single().id)
        a.later()
        a.sync.refreshAll()
        assertTrue(a.data.bookmarks.forBook(id).isEmpty(), "뺀 책갈피가 다른 기기를 다녀와 되살아났다")
    }

    @Test
    fun `a book kept in two folders sends the place and the bookmarks of both copies`() = runTest {
        put("소설/데미안.epub")
        put("받은 책/데미안.epub")
        val a = Device("갤럭시 S24")
        a.data.folders.register(tree)
        a.data.rescan(tree)
        a.sync.enabled = true
        val (one, two) = a.data.library.books().first().filter { it.displayName == "데미안.epub" }.map { it.id }
        // 한 벌에는 책갈피만, 다른 벌에는 읽은 자리만.
        a.data.bookmarks.add(Bookmark(Bookmark.NO_ID, one, at(2, 40), "싱클레어", 1_500))
        a.data.progress.save(ReadingProgress(two, at(7, 300), 61f, 2_000))
        a.sync.writeOwn()
        val sent = assertNotNull(SyncCodec.decode(assertNotNull(remoteText("${a.sync.deviceId}.json")), a.sync.deviceId)).books.single()
        // 앞 벌만 남기던 때는 읽은 자리가 다른 기기로 가지 않았다.
        assertEquals(Locator.encode(at(7, 300)), sent.progress?.locator)
        assertEquals(listOf(Locator.encode(at(2, 40))), sent.bookmarks.map { it.locator })
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
        a.data.progress.save(ReadingProgress(id, at(9, 0), 99f, 4_000))
        a.sync.writeOwn()
        b.sync.refreshAll()
        assertEquals(1_000, b.data.library.shelf().first().single().finishedAtEpochMs)
    }

    @Test
    fun `comics share bookmarks silently and offer the further page with its half`() = runTest {
        // 다른 기기가 펼침면의 오른쪽 반을 보고 있었다. 쪽만 넘기면 "거기로" 를 눌렀을 때 그 쪽의 첫 반이 떴다.
        put("만화/옛날 만화 1권.cbz", ByteArray(500))
        val a = Device("폰")
        val b = Device("탭")
        a.data.folders.register(tree)
        a.data.rescan(tree)
        b.data.rescan(tree)
        a.sync.enabled = true
        b.sync.enabled = true
        val unit = a.data.comics.units().first().single().id
        a.data.comics.saveProgress(unit, 127, 180, 2_000, half = SpreadSplit.Half.RIGHT)
        a.data.comics.toggleBookmark(unit, 12, 2_000)
        a.sync.writeOwn()
        b.data.comics.saveProgress(unit, 3, 180, 9_000)
        val offer = assertNotNull(b.sync.offerForComic(unit))
        assertEquals(127 to SpreadSplit.Half.RIGHT, offer.page to offer.half)
        assertEquals(listOf(12), b.data.comics.bookmarks(unit).first())
        assertEquals(3, b.data.comics.progressOf(unit)?.page)
    }

    @Test
    fun `opening books back to back asks the server once, and a later open sees the other device's new place`() = runTest {
        val (a, b) = twoDevices()
        val id = a.book("데미안.epub")
        a.data.progress.save(ReadingProgress(id, at(3, 0), 30f, 2_000))
        a.sync.writeOwn()
        assertEquals(at(3, 0), b.sync.offerForBook(id.value)?.locator)
        val asked = b.store.calls
        // 연달아 열면 받아 둔 목록을 쓴다.
        b.sync.offerForBook(id.value)
        assertEquals(asked, b.store.calls)
        // a 가 더 읽고, 시간이 지난 뒤 b 가 다시 열면 새 자리를 본다. 같은 1초 안에 바뀐 파일도 크기로 알아챈다.
        a.data.progress.save(ReadingProgress(id, at(8, 12345), 80f, 3_000))
        a.sync.writeOwn()
        b.later()
        assertEquals(at(8, 12345), b.sync.offerForBook(id.value)?.locator)
    }

    @Test
    fun `with the server down nothing fails, the reason is shown and the last known place is still offered`() = runTest {
        val (a, b) = twoDevices()
        val id = a.book("데미안.epub")
        a.data.progress.save(ReadingProgress(id, at(6, 0), 60f, 2_000))
        a.sync.writeOwn()
        assertNotNull(b.sync.offerForBook(id.value))
        ftp.stop()
        b.later()
        // 던지지 않는다 — 서버가 꺼졌다고 책이 안 열리면 안 된다. 받아 둔 자리는 그대로 묻는다.
        assertEquals(at(6, 0), b.sync.offerForBook(id.value)?.locator)
        assertEquals(SyncProblem.Unreachable, b.sync.status.value.problem)
        // 닿지 못한 뒤 1분 동안은 책을 열어도 다시 묻지 않는다 — 꺼진 서버에 책마다 시간 제한만큼 기다리지 않게.
        val asked = b.store.sessions
        b.now += 30_000
        assertEquals(at(6, 0), b.sync.offerForBook(id.value)?.locator)
        assertEquals(asked, b.store.sessions)
        b.now += 31_000
        b.sync.offerForBook(id.value)
        assertEquals(asked + 1, b.store.sessions)
        b.data.progress.save(ReadingProgress(id, at(1, 0), 2f, 9_000))
        assertEquals(0, b.sync.writeOwn())
        // 서버가 돌아오면 까닭이 지워지고 밀린 기록이 올라간다.
        ftp.start()
        assertEquals(1, b.sync.writeOwn())
        b.sync.refreshAll()
        assertNull(b.sync.status.value.problem)
    }

    @Test
    fun `a wrong password is reported as a refused login and a folder without write permission as no access`() = runTest {
        val (a, _) = twoDevices()
        val id = a.book("데미안.epub")
        a.data.progress.save(ReadingProgress(id, at(2, 0), 20f, 2_000))
        a.config = ftp.config(password = "틀린 비밀번호")
        assertEquals(0, a.sync.writeOwn())
        assertEquals(SyncProblem.Auth, a.sync.status.value.problem)
        assertEquals(SyncProblem.Auth, a.sync.checkConnection().problem)
        // 읽기만 되는 계정: 로그인은 되지만 폴더를 만들 수 없다.
        a.config = ftp.config(user = "guest", password = "guest")
        assertEquals(SyncProblem.NoAccess, a.sync.checkConnection().problem)
        // 주소가 틀리면(아무도 듣지 않는 포트) "서버를 찾지 못함".
        a.config = ftp.config().copy(port = java.net.ServerSocket(0).use { it.localPort })
        assertEquals(SyncProblem.Unreachable, a.sync.checkConnection().problem)
        // 바로잡으면 확인이 되고, 확인용 파일은 남지 않는다.
        a.config = ftp.config()
        assertNull(a.sync.checkConnection().problem)
        assertTrue(remoteFiles().isEmpty())
        assertEquals(1, a.sync.writeOwn())
        assertNull(a.sync.status.value.problem)
    }

    @Test
    fun `a path with korean folders is created step by step`() = runTest {
        val (a, b) = twoDevices()
        a.config = ftp.config(path = "책/이어 읽기")
        b.config = ftp.config(path = "책/이어 읽기")
        val id = a.book("데미안.epub")
        a.data.progress.save(ReadingProgress(id, at(4, 0), 40f, 2_000))
        assertEquals(1, a.sync.writeOwn())
        assertTrue(File(ftp.root, "책/이어 읽기/${a.sync.deviceId}.json").isFile)
        assertEquals(at(4, 0), b.sync.offerForBook(id.value)?.locator)
    }

    @Test
    fun `forgetting a device and clearing the server remove the files and clearing turns sync off`() = runTest {
        val (a, b) = twoDevices()
        val id = a.book("데미안.epub")
        a.data.progress.save(ReadingProgress(id, at(2, 0), 20f, 2_000))
        b.data.progress.save(ReadingProgress(id, at(1, 0), 10f, 2_000))
        a.sync.writeOwn()
        b.sync.writeOwn()
        b.sync.refreshAll()
        assertTrue(b.sync.forget(a.sync.deviceId))
        assertTrue(b.sync.devices().isEmpty())
        assertNull(remoteText("${a.sync.deviceId}.json"))
        // 사람이 둔 다른 파일은 건드리지 않는다.
        remote("메모.txt").writeText("hello")
        assertTrue(b.sync.clearRemote())
        assertEquals(listOf("메모.txt"), remoteFiles(), "서버에 기록이 남았거나 남의 파일을 지웠다")
        assertFalse(b.sync.enabled)
    }

    @Test
    fun `a device that had the old folder sync on is noticed once and is not turned on by itself`() = runTest {
        val state = File(base, "state-옛 폰").apply { mkdirs() }
        File(state, "enabled").writeText("1")
        val old = Device("옛 폰")
        // 새 방식은 서버를 정해야 해 저절로 켜지 않는다 — 켜 둔 줄 아는 사람에게 화면이 한 번 알린다.
        assertTrue(old.sync.legacyWasOn)
        assertFalse(old.sync.enabled)
        old.sync.dismissLegacy()
        assertFalse(old.sync.legacyWasOn)
    }

    @Test
    fun `the device id stays the same across restarts`() = runTest {
        val a = Device("폰")
        val again = ReadingSync(a.db, a.store, File(base, "state-폰"), { "폰" })
        assertEquals(a.sync.deviceId, again.deviceId)
        assertTrue(SyncCodec.deviceIdOf("${a.sync.deviceId}.json") != null)
    }
}
