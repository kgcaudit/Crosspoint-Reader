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
 * 두 기기가 한 구글 드라이브 앱 공간을 나눠 쓴다(0.57.0). 진짜 [DriveSyncStore] 가 가짜 드라이브([FakeDrive])에 REST 요청을 보낸다 —
 * 요청 모양(공간 · 부모 · 덮어쓰기)이 틀리면 기기에서는 "켰는데 아무 일도 없다" 로만 보인다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReadingSyncTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var base: File
    private lateinit var root: File
    private val tree: Uri get() = TestDocumentsProvider.treeUri
    private val dbs = ArrayList<ReaderDatabase>()
    private val drive = FakeDrive()

    /** 한 기기: 제 DB · 제 상태 폴더 · 제 이름 · 제 토큰. 드라이브는 함께 쓴다. */
    private inner class Device(name: String) {
        val db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build().also { dbs += it }
        var now = 1_000L
        val data = ReaderData(context, db, io = Dispatchers.Unconfined, clock = { now })
        val token = FakeToken(drive)
        val sync = ReadingSync(db, DriveSyncStore(token, drive.http), File(base, "state-$name"), { name }, clock = { now }, io = Dispatchers.Unconfined)

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
    fun `one device writes its own file to the drive app space and the other offers that place without moving`() = runTest {
        val (a, b) = twoDevices()
        val id = a.book("데미안.epub")
        a.data.progress.save(ReadingProgress(id, at(7, 300), 61f, 2_000))
        a.data.bookmarks.add(Bookmark(Bookmark.NO_ID, id, at(2, 40), "싱클레어", 1_500))
        assertEquals(1, a.sync.writeOwn())

        val written = assertNotNull(drive.text("${a.sync.deviceId}.json"), "드라이브 앱 공간에 이 기기의 파일이 없다")
        assertEquals("갤럭시 S24", SyncCodec.decode(written, a.sync.deviceId)?.deviceName)
        // 책 파일은 올리지 않는다 — 드라이브에는 기록 파일 하나뿐.
        assertEquals(1, drive.files.size)

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
    fun `nothing is sent to the internet while the setting is off`() = runTest {
        val (a, b) = twoDevices()
        a.sync.enabled = false
        b.sync.enabled = false
        val id = a.book("데미안.epub")
        a.data.progress.save(ReadingProgress(id, at(3, 0), 30f, 2_000))
        assertEquals(0, a.sync.writeOwn())
        assertEquals(0, a.sync.refreshAll())
        assertNull(b.sync.offerForBook(id.value))
        // 꺼 둔 기기는 한 번도 드라이브를 부르지 않는다(인터넷을 쓰지 않는다).
        assertTrue(drive.requests.isEmpty(), "꺼져 있는데 드라이브에 ${drive.requests.size}번 요청했다")
    }

    @Test
    fun `nothing is uploaded when nothing changed and a device that read nothing makes no file`() = runTest {
        val (a, _) = twoDevices()
        assertEquals(0, a.sync.writeOwn())
        assertTrue(drive.files.isEmpty(), "읽은 것이 없는데 빈 파일을 만들었다")
        val id = a.book("데미안.epub")
        a.data.progress.save(ReadingProgress(id, at(3, 0), 30f, 2_000))
        assertEquals(1, a.sync.writeOwn())
        val fileId = drive.files.keys.single()
        // 같은 기록이면 다시 올리지 않는다 — 쪽을 넘길 때마다 드라이브를 부르지 않게.
        val before = drive.requests.size
        a.now = 50_000
        assertEquals(0, a.sync.writeOwn())
        assertEquals(before, drive.requests.size)
        // 바뀌면 같은 파일을 덮는다(새 파일을 늘리지 않는다).
        a.data.progress.save(ReadingProgress(id, at(3, 900), 31f, 3_000))
        assertEquals(1, a.sync.writeOwn())
        assertEquals(listOf(fileId), drive.files.keys.toList())
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
    fun `copies, broken files and other files in the app space are ignored`() = runTest {
        val (a, b) = twoDevices()
        val id = a.book("데미안.epub")
        val other = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        val far = SyncFile(other, "옛 사본", 1, listOf(BookRecord("데미안.epub", 100, progress = ProgressRecord(Locator.encode(at(30, 0)), 99f, 1))))
        drive.put("$other (1).json", SyncCodec.encode(far))
        drive.put("bbbbbbbb-bbbb-cccc-dddd-eeeeeeeeeeee.json", "{\"format\":\"olo-ebook-sync\", 깨짐")
        drive.put("메모.txt", "hello")
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
    fun `opening books back to back asks the drive once, and a later open sees the other device's new place`() = runTest {
        val (a, b) = twoDevices()
        val id = a.book("데미안.epub")
        a.data.progress.save(ReadingProgress(id, at(3, 0), 30f, 2_000))
        a.sync.writeOwn()
        assertEquals(at(3, 0), b.sync.offerForBook(id.value)?.locator)
        val asked = drive.requests.size
        // 연달아 열면 받아 둔 목록을 쓴다.
        b.sync.offerForBook(id.value)
        assertEquals(asked, drive.requests.size)
        // a 가 더 읽고, 시간이 지난 뒤 b 가 다시 열면 새 자리를 본다.
        a.data.progress.save(ReadingProgress(id, at(8, 0), 80f, 3_000))
        a.sync.writeOwn()
        b.later()
        assertEquals(at(8, 0), b.sync.offerForBook(id.value)?.locator)
    }

    @Test
    fun `without internet nothing fails, the reason is shown and the last known place is still offered`() = runTest {
        val (a, b) = twoDevices()
        val id = a.book("데미안.epub")
        a.data.progress.save(ReadingProgress(id, at(6, 0), 60f, 2_000))
        a.sync.writeOwn()
        assertNotNull(b.sync.offerForBook(id.value))
        drive.offline = true
        b.later()
        // 던지지 않는다 — 인터넷이 없다고 책이 안 열리면 안 된다. 받아 둔 자리는 그대로 묻는다.
        assertEquals(at(6, 0), b.sync.offerForBook(id.value)?.locator)
        assertEquals(SyncProblem.Offline, b.sync.status.value.problem)
        b.data.progress.save(ReadingProgress(id, at(1, 0), 2f, 9_000))
        assertEquals(0, b.sync.writeOwn())
        // 다시 연결되면 까닭이 지워지고 밀린 기록이 올라간다.
        drive.offline = false
        assertEquals(1, b.sync.writeOwn())
        b.sync.refreshAll()
        assertNull(b.sync.status.value.problem)
    }

    @Test
    fun `an expired token is renewed once silently and a revoked one asks to reconnect`() = runTest {
        val (a, _) = twoDevices()
        val id = a.book("데미안.epub")
        a.data.progress.save(ReadingProgress(id, at(2, 0), 20f, 2_000))
        a.sync.writeOwn()
        // 드라이브가 토큰을 바꿨다(한 시간 만료). 한 번 새로 받아 그대로 이어간다.
        drive.validToken = "token-2"
        a.data.progress.save(ReadingProgress(id, at(3, 0), 30f, 3_000))
        assertEquals(1, a.sync.writeOwn())
        assertEquals(1, a.token.refreshes)
        assertNull(a.sync.status.value.problem)
        // 사람이 구글 계정에서 이 앱의 허락을 거뒀다 — 새로 받아도 거절된다. 끝없이 다시 받지 않고 "다시 연결" 을 묻는다.
        a.token.revoked = true
        drive.validToken = "token-3"
        a.data.progress.save(ReadingProgress(id, at(4, 0), 40f, 4_000))
        assertEquals(0, a.sync.writeOwn())
        assertEquals(SyncProblem.SignedOut, a.sync.status.value.problem)
        assertEquals(2, a.token.refreshes)
    }

    @Test
    fun `a full drive is reported as full`() = runTest {
        val (a, _) = twoDevices()
        drive.full = true
        a.data.progress.save(ReadingProgress(a.book("데미안.epub"), at(2, 0), 20f, 2_000))
        assertEquals(0, a.sync.writeOwn())
        assertEquals(SyncProblem.Full, a.sync.status.value.problem)
    }

    @Test
    fun `two copies of this device's file are cleaned to the newest`() = runTest {
        val (a, b) = twoDevices()
        val id = a.book("데미안.epub")
        // 두 번 동시에 만들어진 파일(이름이 같아도 드라이브는 둘을 둔다). 옛 것을 남기면 다른 기기가 옛 자리를 읽을 수 있다.
        val name = "${a.sync.deviceId}.json"
        drive.put(name, SyncCodec.encode(SyncFile(a.sync.deviceId, "갤럭시 S24", 1, listOf(BookRecord("데미안.epub", 100, progress = ProgressRecord(Locator.encode(at(1, 0)), 1f, 1))))))
        drive.put(name, SyncCodec.encode(SyncFile(a.sync.deviceId, "갤럭시 S24", 2, listOf(BookRecord("데미안.epub", 100, progress = ProgressRecord(Locator.encode(at(5, 0)), 50f, 2))))))
        a.sync.refreshAll()
        assertEquals(1, drive.files.values.count { it.name == name })
        assertEquals(at(5, 0), b.sync.offerForBook(id.value)?.locator)
    }

    @Test
    fun `forgetting a device and clearing the drive remove the files and clearing turns sync off`() = runTest {
        val (a, b) = twoDevices()
        val id = a.book("데미안.epub")
        a.data.progress.save(ReadingProgress(id, at(2, 0), 20f, 2_000))
        b.data.progress.save(ReadingProgress(id, at(1, 0), 10f, 2_000))
        a.sync.writeOwn()
        b.sync.writeOwn()
        b.sync.refreshAll()
        assertTrue(b.sync.forget(a.sync.deviceId))
        assertTrue(b.sync.devices().isEmpty())
        assertNull(drive.text("${a.sync.deviceId}.json"))
        assertTrue(b.sync.clearRemote())
        assertTrue(drive.files.isEmpty(), "드라이브에 기록이 남았다")
        assertFalse(b.sync.enabled)
    }

    @Test
    fun `a device that had the old folder sync on is noticed once and is not turned on by itself`() = runTest {
        val state = File(base, "state-옛 폰").apply { mkdirs() }
        File(state, "enabled").writeText("1")
        val old = Device("옛 폰")
        // 새 방식은 계정을 이어야 해 저절로 켜지 않는다 — 켜 둔 줄 아는 사람에게 화면이 한 번 알린다.
        assertTrue(old.sync.legacyWasOn)
        assertFalse(old.sync.enabled)
        old.sync.dismissLegacy()
        assertFalse(old.sync.legacyWasOn)
    }

    @Test
    fun `the device id stays the same across restarts`() = runTest {
        val a = Device("폰")
        val again = ReadingSync(a.db, DriveSyncStore(a.token, drive.http), File(base, "state-폰"), { "폰" })
        assertEquals(a.sync.deviceId, again.deviceId)
        assertTrue(SyncCodec.deviceIdOf("${a.sync.deviceId}.json") != null)
    }
}
