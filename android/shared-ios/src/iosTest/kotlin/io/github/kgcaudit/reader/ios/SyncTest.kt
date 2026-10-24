@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.Bookmark
import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.document.ReadingProgress
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.dataUsingEncoding
import platform.Foundation.writeToFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 기기 간 이어 읽기(구상안 50, 안드로이드 `SyncCodecTest` · `ReadingSyncTest` · `FtpConnectionTest` 의 서버 없는 부분). */
class SyncTest {
    private val root = NSTemporaryDirectory() + "olo-sync-" + NSUUID().UUIDString

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(root, null)
    }

    /** 기기 하나: 제 기록 저장소 · 제 상태 폴더 · 같은 책 파일(이름 · 크기는 같고 주소는 다르다). */
    private inner class Device(val name: String, id: String) {
        val store = ReadingStore("$root/$name/reading.tsv")
        val sync = IosReadingSync(store, "$root/$name/sync") { id }
        val book = ShelfBook("$name:/책/어린 왕자.epub", "어린 왕자.epub", 3000, "어린 왕자", "생텍쥐페리")
        val comic = ShelfComic("$name:/만화/바다 1권.cbz", "바다 1권.cbz", 9000)
        val shelf = listOf(book)
        val comics = listOf(comic)

        init {
            sync.enabled = true
        }
    }

    private val a = "aaaaaaaa-0000-4000-8000-000000000001"
    private val b = "bbbbbbbb-0000-4000-8000-000000000002"

    /** 서버 폴더(이름 → 글 · 고친 때). */
    private val server = LinkedHashMap<String, Pair<String, Long>>()
    private var clock = 1_000_000L

    /** Swift 의 맞추기 순서 그대로: 목록 → 바뀐 것만 읽기 → 합치기 → 올리기. 읽은 파일 수. */
    private fun syncNow(d: Device): Int {
        clock += 1000
        val entries = server.map { (n, v) -> SyncEntry(n, v.second, v.first.length.toLong()) }
        val toRead = d.sync.listed(entries, clock)
        for (e in toRead) d.sync.received(e, server[e.name]?.first)
        d.sync.mergeFresh(d.shelf, d.comics)
        d.sync.upload(d.shelf, d.comics, d.name, clock)?.let {
            server[SyncCodec.fileName(d.sync.deviceId)] = it to clock
            d.sync.uploaded()
        }
        d.sync.markSynced(clock)
        return toRead.size
    }

    @Test
    fun `one address box understands scheme and port and user and path and refuses what it cannot read`() {
        // 안드로이드 FtpConnectionTest 첫 시험과 같은 표.
        assertEquals(FtpAddress.Parsed("nas.local", null, null, null), FtpAddress.parse(" nas.local "))
        assertEquals(FtpAddress.Parsed("nas.local", 2121, null, null), FtpAddress.parse("nas.local:2121"))
        assertEquals(FtpAddress.Parsed("nas.local", 21, FtpProtocol.Ftp, null), FtpAddress.parse("ftp://reader@nas.local:21/책/"))
        assertEquals(FtpAddress.Parsed("nas.local", null, FtpProtocol.Sftp, null), FtpAddress.parse("SFTP://nas.local"))
        assertEquals(FtpAddress.Parsed("h", null, FtpProtocol.Ftp, FtpSecurity.Implicit), FtpAddress.parse("ftps://h"))
        assertEquals(FtpAddress.Parsed("h", null, FtpProtocol.Ftp, FtpSecurity.Explicit), FtpAddress.parse("ftpes://h/"))
        assertEquals(FtpProtocol.Sftp, FtpAddress.parse("nas.local:22")?.protocol)
        assertEquals(FtpSecurity.Implicit, FtpAddress.parse("nas.local:990")?.security)
        assertEquals(FtpAddress.Parsed("fe80::1", 2121, null, null), FtpAddress.parse("[fe80::1]:2121"))
        assertEquals(FtpAddress.Parsed("fe80::1", null, null, null), FtpAddress.parse("fe80::1"))
        listOf("", "  ", "nas:abc", "nas:70000", "nas:0", "http://nas", "nas local", "ftp://", "[::1]:x").forEach {
            assertNull(FtpAddress.parse(it), "'$it' 를 받아들였다")
        }
        assertEquals("sftp://nas.local", FtpConfig("nas.local", 22, FtpProtocol.Sftp, FtpSecurity.None, "u", "/").address)
        assertEquals("nas.local:2121", FtpConfig("nas.local", 2121, FtpProtocol.Ftp, FtpSecurity.None, "u", "/").address)
        assertEquals("ftps://[fe80::1]", FtpConfig("fe80::1", 990, FtpProtocol.Ftp, FtpSecurity.Implicit, "u", "/").address)
    }

    @Test
    fun `the address prefix wins over advanced settings and a trusted fingerprint follows only the same server`() {
        val before = FtpConfig("nas.local", 21, FtpProtocol.Ftp, FtpSecurity.Explicit, "u", "/OLO Ecosystem", trusted = "AB:CD")
        // 폴더만 바꿨다 — 같은 서버라 인증서를 다시 묻지 않는다.
        val samePlace = FtpAddress.configOf("nas.local", "u", "/책", FtpSecurity.Explicit, true, FtpEncoding.Auto, before)!!
        assertEquals("AB:CD", samePlace.trusted)
        // 다른 서버 · 다른 포트면 믿은 지문을 가져가지 않는다 — 가져가면 다른 서버의 인증서를 묻지 않고 받는다.
        assertNull(FtpAddress.configOf("nas2.local", "u", "/", FtpSecurity.Explicit, true, FtpEncoding.Auto, before)!!.trusted)
        assertNull(FtpAddress.configOf("nas.local:2121", "u", "/", FtpSecurity.Explicit, true, FtpEncoding.Auto, before)!!.trusted)
        // sftp:// 은 고급 설정의 FTPS 를 버린다. ftps:// 는 고급 설정 "암호화 없음" 보다 앞선다.
        val sftp = FtpAddress.configOf("sftp://nas.local", "u", "", FtpSecurity.Explicit, true, FtpEncoding.Auto, null)!!
        assertEquals(FtpProtocol.Sftp, sftp.protocol)
        assertEquals(FtpSecurity.None, sftp.security)
        assertEquals(22, sftp.port)
        assertEquals("/", sftp.path)
        assertEquals(FtpSecurity.Implicit, FtpAddress.configOf("ftps://h", "u", "/", FtpSecurity.None, true, FtpEncoding.Auto, null)!!.security)
        assertTrue(sftp.encrypted)
        assertFalse(FtpAddress.configOf("h", "u", "/", FtpSecurity.None, true, FtpEncoding.Auto, null)!!.encrypted)
        // 칸이 모자라면 무엇이 모자란지.
        assertNull(FtpAddress.configOf("h", " ", "/", FtpSecurity.Auto, true, FtpEncoding.Auto, null))
        assertEquals("서버 주소를 넣어 주세요.", FtpAddress.missing("", "u"))
        assertEquals("사용자 이름을 넣어 주세요.", FtpAddress.missing("h", ""))
        assertTrue(FtpAddress.missing("nas:abc", "u")!!.startsWith("서버 주소를 읽지 못했습니다"))
        assertNull(FtpAddress.missing("h", "u"))
    }

    @Test
    fun `what one device writes another reads back and without highlights or reading time`() = runTest {
        val file = SyncFile(
            a, "아이패드", 5_000,
            listOf(
                BackupBook(
                    "어린 왕자.epub", 3000, "어린 왕자", null, BackupProgress("r:3:120", 40f, 4_000), 1_000, null,
                    listOf(BackupBookmark("r:1:0", "책갈피", 2_000)),
                    listOf(BackupNote("r:1:0", "r:1:5", "Yellow", "메모", "어린", 3_000)),
                    listOf(BackupTime(20_000, "page", 60_000)),
                ),
            ),
            listOf(BackupComic("바다 1권.cbz", 9000, 4, 20, null, "RIGHT", 4_500, null, listOf(BackupComicMark(2, 1)), listOf(BackupTime(20_000, "comic", 1)))),
        )
        val text = SyncCodec.encode(file)
        val back = SyncCodec.decode(text, a)!!
        assertEquals("아이패드", back.deviceName)
        assertEquals(5_000, back.updatedAt)
        val book = back.books.single()
        assertEquals("r:3:120", book.progress?.locator)
        assertEquals(listOf("r:1:0"), book.bookmarks.map { it.locator })
        // 형광펜 · 메모 · 읽은 시간은 기기 사이를 오가지 않는다(결정 8-3).
        assertTrue(book.annotations.isEmpty())
        assertTrue(book.time.isEmpty())
        assertTrue("annotations\": []" in text && "메모" !in text && "\"time\"" !in text)
        val comic = back.comics.single()
        assertEquals(4, comic.page)
        assertEquals("RIGHT", comic.half)
        assertEquals(listOf(2), comic.bookmarks.map { it.page })
    }

    @Test
    fun `a file written by the android app is read as is`() {
        // 안드로이드 SyncCodec.encode 가 쓰는 모양(org.json toString(1)). records 안은 백업 파일과 같다.
        val android = """{
 "format": "olo-ebook-sync",
 "version": 1,
 "device": "${b.uppercase()}",
 "deviceName": "갤럭시 탭 S9",
 "updatedAt": 1761200000000,
 "records": {
  "format": "olo-ebook-reading-records",
  "version": 1,
  "createdAt": 1761200000000,
  "books": [{"name": "어린 왕자.epub", "size": 3000, "progress": {"locator": "r:5:10", "percent": 64.5, "updatedAt": 1761199990000}, "bookmarks": [], "annotations": []}],
  "comics": [{"name": "바다 1권.cbz", "size": 9000, "page": 7, "pageCount": 20, "updatedAt": 1761199990000, "bookmarks": []}],
  "works": []
 },
 "future": {"x": 1}
}"""
        val file = SyncCodec.decode(android, b)!!
        assertEquals(b, file.deviceId)
        assertEquals("갤럭시 탭 S9", file.deviceName)
        assertEquals(64.5f, file.books.single().progress?.percent)
        assertEquals(7, file.comics.single().page)
    }

    @Test
    fun `broken json and another app's json and a copied file and missing records are each handled alone`() {
        assertNull(SyncCodec.decode("{ 깨진"))
        assertNull(SyncCodec.decode("""{"format": "something-else", "device": "$a"}"""))
        assertNull(SyncCodec.decode("""{"format": "olo-ebook-sync", "device": "not-a-uuid"}"""))
        // 다른 기기의 파일을 이름만 바꿔 복사한 것: 이름의 id 와 안의 id 가 다르다.
        assertNull(SyncCodec.decode("""{"format": "olo-ebook-sync", "device": "$a"}""", b))
        // 기록 칸이 없거나 깨졌으면 빈 기록 · 이름이 없으면 "다른 기기" — 그것 때문에 파일을 버리지 않는다.
        val bare = SyncCodec.decode("""{"format": "olo-ebook-sync", "device": "$a", "records": "x"}""", a)!!
        assertTrue(bare.books.isEmpty() && bare.comics.isEmpty())
        assertEquals("다른 기기", bare.deviceName)
    }

    @Test
    fun `only plain device file names are read and sync-app copies and half uploads and the probe are not`() {
        assertEquals(a, SyncCodec.deviceIdOf("$a.json"))
        assertEquals(a, SyncCodec.deviceIdOf("${a.uppercase()}.json"))
        listOf(
            "$a (1).json", "$a.sync-conflict-20261008.json", "$a - 복사본.json", IosReadingSync.partName("$a.json"),
            IosReadingSync.PROBE, "$a.json.bak", "notes.json",
        ).forEach { assertNull(SyncCodec.deviceIdOf(it), it) }
    }

    @Test
    fun `one device uploads its place and the other offers it without moving its own place`() = runTest {
        val tab = Device("갤럭시 탭", b)
        val pad = Device("아이패드", a)
        tab.store.save(ReadingProgress(BookId(tab.book.id), Locator.Reflow(5, 10), 64f, 9_000))
        syncNow(tab)
        assertEquals(setOf("$b.json"), server.keys)
        pad.store.save(ReadingProgress(BookId(pad.book.id), Locator.Reflow(2, 0), 20f, 8_000))
        syncNow(pad)
        val offer = pad.sync.offerForBook(pad.book.id, pad.book.name, pad.book.size)!!
        assertEquals("갤럭시 탭", offer.deviceName)
        assertEquals(5, offer.spine)
        assertEquals(10, offer.offset)
        assertEquals(64f, offer.percent)
        // 묻기만 한다 — 아이패드의 자리는 그대로(결정 8-2).
        assertEquals(Locator.Reflow(2, 0), pad.store.progressOf(pad.book.id)?.locator)
        assertEquals(listOf("갤럭시 탭"), pad.sync.devices().map { it.name })
        assertEquals(1, pad.sync.devices().single().titles)
        // 탭이 아이패드보다 앞이면 묻지 않는다. 크기가 다른 책(다른 판)도 묻지 않는다.
        assertNull(tab.sync.offerForBook(tab.book.id, tab.book.name, tab.book.size).also { syncNow(tab) })
        assertNull(pad.sync.offerForBook(pad.book.id, pad.book.name, 3001))
        // 끄면 묻지 않는다.
        pad.sync.enabled = false
        assertNull(pad.sync.offerForBook(pad.book.id, pad.book.name, pad.book.size))
    }

    @Test
    fun `nothing is uploaded when nothing changed and a device that read nothing makes no file`() = runTest {
        val pad = Device("아이패드", a)
        syncNow(pad)
        // 읽은 것이 없으면 "함께 읽는 기기" 에 빈 파일로 끼지 않는다.
        assertTrue(server.isEmpty())
        pad.store.save(ReadingProgress(BookId(pad.book.id), Locator.Reflow(1, 0), 5f, 1))
        syncNow(pad)
        val first = server.getValue("$a.json")
        syncNow(pad)
        // 바뀐 것이 없으면 쓰지 않는다 — 쪽을 넘길 때마다 서버에 올리지 않게.
        assertEquals(first, server.getValue("$a.json"))
        pad.store.save(ReadingProgress(BookId(pad.book.id), Locator.Reflow(1, 50), 6f, 2))
        syncNow(pad)
        assertTrue(server.getValue("$a.json").second > first.second)
        // 서재가 비어 보이면(다 훑기 전 · 책 폴더 접근이 끊김) 서버의 이 기기 파일을 빈 것으로 덮지 않는다.
        val kept = server.getValue("$a.json")
        clock += 1000
        assertNull(pad.sync.upload(emptyList(), emptyList(), pad.name, clock))
        assertEquals(kept, server.getValue("$a.json"))
        // 껐다 켜면 다시 한 번 올린다(서버를 바꿨을 수 있다).
        server.clear()
        pad.sync.enabled = false
        pad.sync.enabled = true
        syncNow(pad)
        assertNotNull(server["$a.json"])
    }

    @Test
    fun `a file that did not change is not read again and a broken one is not retried until it changes`() = runTest {
        val pad = Device("아이패드", a)
        server["$b.json"] = "{ 깨진" to 5L
        assertEquals(1, syncNow(pad))
        assertEquals(0, syncNow(pad))
        assertTrue(pad.sync.devices().isEmpty())
        server["$b.json"] = SyncCodec.encode(SyncFile(b, "탭", 6, emptyList(), emptyList())) to 6L
        assertEquals(1, syncNow(pad))
        assertEquals(listOf("탭"), pad.sync.devices().map { it.name })
    }

    @Test
    fun `a bookmark removed on this device does not come back from the other device`() = runTest {
        val tab = Device("갤럭시 탭", b)
        val pad = Device("아이패드", a)
        tab.store.addBookmark(Bookmark(Bookmark.NO_ID, BookId(tab.book.id), Locator.Reflow(3, 0), "셋째 장", 1))
        syncNow(tab)
        syncNow(pad)
        val got = pad.store.bookmarksOf(pad.book.id)
        assertEquals(listOf(Locator.Reflow(3, 0)), got.map { it.locator })
        pad.store.removeBookmark(got.single().id)
        // 탭이 다시 올려도(다른 책갈피를 더함) 아이패드에서 뺀 것은 돌아오지 않는다.
        tab.store.addBookmark(Bookmark(Bookmark.NO_ID, BookId(tab.book.id), Locator.Reflow(4, 0), "넷째 장", 2))
        syncNow(tab)
        syncNow(pad)
        assertEquals(listOf(Locator.Reflow(4, 0)), pad.store.bookmarksOf(pad.book.id).map { it.locator })
        // 앱을 다시 켜도 기억한다.
        val again = IosReadingSync(pad.store, "$root/아이패드/sync") { a }
        tab.store.addBookmark(Bookmark(Bookmark.NO_ID, BookId(tab.book.id), Locator.Reflow(5, 0), null, 3))
        syncNow(tab)
        clock += 1000
        val entries = server.map { (n, v) -> SyncEntry(n, v.second, v.first.length.toLong()) }
        again.listed(entries, clock).forEach { again.received(it, server[it.name]?.first) }
        again.mergeFresh(pad.shelf, pad.comics)
        assertEquals(listOf(Locator.Reflow(4, 0), Locator.Reflow(5, 0)), pad.store.bookmarksOf(pad.book.id).map { it.locator })
    }

    @Test
    fun `the earliest finished date wins and a book never opened here gets it and lands in finished`() = runTest {
        val tab = Device("갤럭시 탭", b)
        val pad = Device("아이패드", a)
        tab.store.setFinished(tab.book.id, 5_000, 4_000)
        syncNow(tab)
        syncNow(pad)
        assertEquals(5_000, pad.store.finishedOf(pad.book.id))
        // 한 번도 연 적 없는 책이라도 연 때가 생겨 "읽은 책" 갈래에 든다.
        assertNotNull(pad.store.shelf(listOf(pad.book.id)).single().openedAtEpochMs)
        // 아이패드에서 "다 읽음" 을 거두면 같은 날로 다시 들어오지 않는다.
        pad.store.returnToUnread(pad.book.id)
        syncNow(tab.also { it.store.addBookmark(Bookmark(Bookmark.NO_ID, BookId(it.book.id), Locator.Reflow(1, 0), null, 1)) })
        syncNow(pad)
        assertNull(pad.store.finishedOf(pad.book.id))
        // 이 기기가 나중에 다 읽었으면 다른 기기의 더 이른 날이 이긴다.
        val other = Device("휴대폰", "cccccccc-0000-4000-8000-000000000003")
        other.store.setFinished(other.book.id, 9_000, 9_000)
        syncNow(other)
        assertEquals(5_000, other.store.finishedOf(other.book.id))
    }

    @Test
    fun `comics share bookmarks silently and offer the further page with its half`() = runTest {
        val tab = Device("갤럭시 탭", b)
        val pad = Device("아이패드", a)
        tab.store.saveComic(tab.comic.id, 7, 20, atEnd = false, half = "RIGHT")
        tab.store.toggleComicBookmark(tab.comic.id, listOf(3))
        syncNow(tab)
        pad.store.saveComic(pad.comic.id, 2, 20, atEnd = false)
        syncNow(pad)
        assertEquals(listOf(3), pad.store.comicBookmarks(pad.comic.id))
        val offer = pad.sync.offerForComic(pad.comic.id, pad.comic.name, pad.comic.size)!!
        assertEquals(7, offer.page)
        assertEquals("RIGHT", offer.half)
        assertEquals(2, pad.store.comicProgress()[pad.comic.id]?.page)
        // 펼친 적 없는 권은 다 읽은 때만 받고 "최근" 에 끼지 않는다(고친 때 0).
        tab.store.saveComic(tab.comic.id, 19, 20, atEnd = true)
        syncNow(tab)
        val fresh = Device("휴대폰", "cccccccc-0000-4000-8000-000000000003")
        syncNow(fresh)
        val p = fresh.store.comicProgress()[fresh.comic.id]!!
        assertNotNull(p.finishedAtEpochMs)
        assertEquals(0, p.page)
        assertEquals(0L, p.updatedAtEpochMs)
    }

    @Test
    fun `offers are refreshed at most every twenty seconds and not for a minute after the server was unreachable`() {
        val pad = Device("아이패드", a)
        assertTrue(pad.sync.shouldRefreshForOffer(clock))
        pad.sync.listed(emptyList(), clock)
        assertFalse(pad.sync.shouldRefreshForOffer(clock + 19_999))
        assertTrue(pad.sync.shouldRefreshForOffer(clock + 20_000))
        pad.sync.unreachable(clock + 20_000)
        assertFalse(pad.sync.shouldRefreshForOffer(clock + 79_999))
        assertTrue(pad.sync.shouldRefreshForOffer(clock + 80_000))
    }

    @Test
    fun `a page further than the shown one is offered and the same page is not`() {
        // 보이는 쪽은 장 3 의 글자 500 앞에서 끝난다.
        assertTrue(SyncRules.reflowBeyond(3, 500, 3, 500))
        assertFalse(SyncRules.reflowBeyond(3, 499, 3, 500))
        assertTrue(SyncRules.reflowBeyond(4, 0, 3, 500))
        assertFalse(SyncRules.reflowBeyond(2, 9999, 3, 500))
        assertTrue(SyncRules.pageBeyond(6, 5))
        assertFalse(SyncRules.pageBeyond(5, 5))
        assertTrue(SyncRules.comicBeyond(4, null, 3, 0.9))
        assertTrue(SyncRules.comicBeyond(3, 0.8, 3, 0.3))
        assertFalse(SyncRules.comicBeyond(3, 0.7, 3, 0.3))
    }

    @Test
    fun `the device id and a chosen device name stay across restarts and a broken id file is replaced`() {
        val pad = Device("아이패드", a)
        assertEquals(a, pad.sync.deviceId)
        pad.sync.customName = "  거실 아이패드 "
        val again = IosReadingSync(pad.store, "$root/아이패드/sync") { "ffffffff-0000-4000-8000-00000000000f" }
        assertEquals(a, again.deviceId)
        assertEquals("거실 아이패드", again.customName)
        again.customName = " "
        assertNull(again.customName)
        @Suppress("CAST_NEVER_SUCCEEDS")
        ("망가진 id" as NSString).dataUsingEncoding(NSUTF8StringEncoding)!!.writeToFile("$root/아이패드/sync/device-id", true)
        assertEquals("ffffffff-0000-4000-8000-00000000000f", IosReadingSync(pad.store, "$root/아이패드/sync") { "ffffffff-0000-4000-8000-00000000000f" }.deviceId)
    }

    @Test
    fun `ftp replies and listings and times are read like commons-net did`() {
        assertEquals(227, FtpReplies.code("227 Entering Passive Mode (192,168,0,10,195,80)."))
        assertEquals(0, FtpReplies.code("hello"))
        assertTrue(FtpReplies.isLast("230 Login successful.", 230))
        assertFalse(FtpReplies.isLast("230-Welcome", 230))
        assertEquals(195 * 256 + 80, FtpReplies.pasvPort("227 Entering Passive Mode (192,168,0,10,195,80)."))
        assertNull(FtpReplies.pasvPort("227 Entering Passive Mode (300,1,1,1,1,1)"))
        assertEquals(50000, FtpReplies.epsvPort("229 Entering Extended Passive Mode (|||50000|)"))
        assertNull(FtpReplies.epsvPort("229 nothing"))
        assertTrue(FtpReplies.hasMlsd("211-Features:\r\n MDTM\r\n MLST type*;size*;modify*;\r\n211 End"))
        assertFalse(FtpReplies.hasMlsd("211-Features:\r\n MDTM\r\n211 End"))
        val m = FtpReplies.mlsd("type=file;size=1234;modify=20261023120000; $a.json")!!
        assertEquals("$a.json", m.name)
        assertEquals(1234, m.size)
        assertEquals(1_792_756_800_000, m.modified)
        assertNull(FtpReplies.mlsd("type=dir;modify=20261023120000; 책"))
        assertNull(FtpReplies.mlsd("type=cdir; ."))
        val unix = FtpReplies.list("-rw-r--r--   1 reader users   1234 Oct 23 12:00 $a.json", 2026)!!
        assertEquals(SyncEntry("$a.json", 1_792_756_800_000, 1234), unix)
        assertEquals("내 파일.json", FtpReplies.list("-rw-r--r-- 1 u g 5 Jan  2  2025 내 파일.json", 2026)!!.name)
        assertNull(FtpReplies.list("drwxr-xr-x   2 reader users 4096 Oct 23 12:00 책", 2026))
        assertEquals(SyncEntry("$a.json", 1_792_756_800_000, 1234), FtpReplies.list("10-23-26  12:00PM                 1234 $a.json", 2026))
        assertNull(FtpReplies.list("10-23-26  12:00PM       <DIR>          책", 2026))
        assertNull(FtpReplies.mdtm("2026102312"))
        assertNull(FtpReplies.mdtm("20261323120000"))
        assertEquals(SyncProblem.Full, FtpReplies.problemOf(552))
        assertEquals(SyncProblem.Auth, FtpReplies.problemOf(530))
        assertEquals(SyncProblem.NoAccess, FtpReplies.problemOf(550))
        assertEquals(SyncProblem.Server, FtpReplies.problemOf(421))
        assertEquals(listOf("OLO Ecosystem", "이어 읽기"), FtpReplies.folderParts(" /OLO Ecosystem/./이어 읽기/ "))
    }

    @Test
    fun `a fingerprint looks like the one a nas settings page shows`() {
        @Suppress("CAST_NEVER_SUCCEEDS")
        val data = ("abc" as NSString).dataUsingEncoding(NSUTF8StringEncoding)!!
        assertEquals("BA:78:16:BF:8F:01:CF:EA:41:41:40:DE:5D:AE:22:23:B0:03:61:A3:96:17:7A:9C:B4:10:FF:61:F2:00:15:AD", SyncHash.fingerprint(data))
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", SyncHash.sha1Hex("abc"))
    }

    @Test
    fun `an ssh key fingerprint looks like ssh-keygen and one trusted in the old form still matches`() {
        @Suppress("CAST_NEVER_SUCCEEDS")
        val key = ("abc" as NSString).dataUsingEncoding(NSUTF8StringEncoding)!!
        // `ssh-keygen -lf` 모양: SHA256: + base64(끝 = 없음) — NAS 의 SSH 화면과 눈으로 맞춰 본다(안드로이드 0.59.2).
        assertEquals("SHA256:ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0", SyncHash.sshFingerprint(key))
        assertTrue(SyncHash.sshMatches(key, "SHA256:ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0"))
        // 0.59 에 콜론 16진으로 믿어 둔 키는 다시 묻지 않는다(대소문자 무관).
        assertTrue(SyncHash.sshMatches(key, SyncHash.fingerprint(key).lowercase()))
        // 다른 키 · 믿은 것 없음 · base64 대소문자 바꿈(다른 지문)은 받지 않는다.
        assertFalse(SyncHash.sshMatches(key, "SHA256:ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa1"))
        assertFalse(SyncHash.sshMatches(key, null))
        assertFalse(SyncHash.sshMatches(key, "sha256:UNGWV48BZ+PBQUDEXA4II7ADYAOWF3QCTBD/YFIAFA0"))
    }

    @Test
    fun `a list-only server's minute times are not trusted so a file rewritten within the minute is read again`() {
        val text = "-rw-r--r--   1 reader users   1234 Oct 23 12:00 $a.json\r\ndrwxr-xr-x 2 u g 4096 Oct 23 12:00 책\r\n"
        assertEquals(listOf(SyncEntry("$a.json", null, 1234)), FtpReplies.listing(text, exact = false, nowYear = 2026))
        // MLSD 는 초까지라 믿는다.
        assertEquals(listOf(SyncEntry("$a.json", 1_792_756_800_000, 1234)),
            FtpReplies.listing("type=file;size=1234;modify=20261023120000; $a.json\r\n", exact = true, nowYear = 2026))
        // 시각을 모르면 같은 이름 · 크기라도 다시 읽는다.
        val pad = Device("아이패드", a)
        val same = listOf(SyncEntry("$b.json", null, 10))
        assertEquals(1, pad.sync.listed(same, 1).size)
        pad.sync.received(same.single(), null)
        assertEquals(1, pad.sync.listed(same, 2).size)
    }

    @Test
    fun `a server that needs tls session reuse is told apart and not retried`() {
        assertEquals(SyncProblem.Incompatible, FtpReplies.problemOf(522))
        assertFalse(SyncProblem.Incompatible.retryable)
        assertTrue(SyncText.problem(SyncProblem.Incompatible).contains("sftp://"))
    }

    @Test
    fun `a bookmark this device shared and then removed does not come back from a device that copied it`() = runTest {
        val tab = Device("갤럭시 탭", b)
        val pad = Device("아이패드", a)
        pad.store.addBookmark(Bookmark(Bookmark.NO_ID, BookId(pad.book.id), Locator.Reflow(3, 0), "셋째 장", 1))
        syncNow(pad)
        syncNow(tab)
        assertEquals(listOf(Locator.Reflow(3, 0)), tab.store.bookmarksOf(tab.book.id).map { it.locator })
        // 아이패드가 제 책갈피를 뺐다 — 탭이 받아 제 파일에 실어 둔 것이 되살아나면 안 된다.
        pad.store.removeBookmark(pad.store.bookmarksOf(pad.book.id).single().id)
        syncNow(pad)
        assertEquals(emptyList(), pad.store.bookmarksOf(pad.book.id))
    }

    @Test
    fun `the same book in two folders is shared as one record that keeps both copies' marks`() = runTest {
        val pad = Device("아이패드", a)
        val copy = ShelfBook("아이패드:/받은 책/어린 왕자.epub", "어린 왕자.epub", 3000, "어린 왕자", "생텍쥐페리")
        pad.store.save(ReadingProgress(BookId(pad.book.id), Locator.Reflow(5, 0), 50f, 9))
        pad.store.addBookmark(Bookmark(Bookmark.NO_ID, BookId(copy.id), Locator.Reflow(7, 0), "일곱째 장", 2))
        val text = pad.sync.upload(listOf(copy, pad.book), emptyList(), "아이패드", 10)!!
        val book = SyncCodec.decode(text, a)!!.books.single()
        // 앞 벌(받은 책)만 남기던 때는 다른 벌의 읽은 자리가 빠졌다.
        assertEquals(Locator.Reflow(5, 0), book.progress?.let { Locator.decodeOrNull(it.locator) })
        assertEquals(listOf("r:7:0"), book.bookmarks.map { it.locator })
    }

    @Test
    fun `problems that pass with time are retried and the others wait for the person`() {
        assertEquals(setOf(SyncProblem.Offline, SyncProblem.Unreachable, SyncProblem.Asleep, SyncProblem.Server, SyncProblem.Full), SyncProblem.entries.filter { it.retryable }.toSet())
        assertEquals(setOf(SyncProblem.Offline, SyncProblem.Unreachable, SyncProblem.Asleep), SyncProblem.entries.filter { it.unreached }.toSet())
        assertEquals("서버가 답하지 않습니다(잠들어 있거나 꺼져 있음). 깨어나면 다시 맞춥니다.", SyncText.problem(SyncProblem.Asleep))
    }
}
