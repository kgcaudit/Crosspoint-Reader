package io.github.kgcaudit.reader.data.sync

import android.content.Context
import androidx.room.withTransaction
import io.github.kgcaudit.reader.data.ReaderData
import io.github.kgcaudit.reader.data.backup.BookRecord
import io.github.kgcaudit.reader.data.backup.BookmarkRecord
import io.github.kgcaudit.reader.data.backup.ComicBookmarkRecord
import io.github.kgcaudit.reader.data.backup.ComicRecord
import io.github.kgcaudit.reader.data.library.halfValue
import io.github.kgcaudit.reader.document.comic.SpreadSplit
import io.github.kgcaudit.reader.data.backup.ProgressRecord
import io.github.kgcaudit.reader.data.db.BookEntity
import io.github.kgcaudit.reader.data.db.BookmarkEntity
import io.github.kgcaudit.reader.data.db.ComicBookmarkEntity
import io.github.kgcaudit.reader.data.db.ComicProgressEntity
import io.github.kgcaudit.reader.data.db.ComicUnitEntity
import io.github.kgcaudit.reader.data.db.LocatorOrder
import io.github.kgcaudit.reader.data.db.ReaderDatabase
import io.github.kgcaudit.reader.document.Locator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** 다른 기기가 더 읽은 자리(결정 8-2). 화면이 "거기로" 띠를 띄울지 정하는 재료다 — 이것만으로 자리를 옮기지 않는다. */
sealed interface SyncOffer {
    val deviceName: String
    val updatedAtEpochMs: Long

    data class Book(override val deviceName: String, override val updatedAtEpochMs: Long, val locator: Locator, val percent: Float) : SyncOffer

    data class Comic(
        override val deviceName: String,
        override val updatedAtEpochMs: Long,
        val page: Int,
        val offset: Float?,
        val pageCount: Int?,
        /** 그 기기가 보던 펼침면의 반(실제 쪽). 옛 파일 · 쪽 전체면 null. */
        val half: SpreadSplit.Half? = null,
    ) : SyncOffer
}

/** 함께 읽는 기기 하나: 이름과 마지막으로 기록을 남긴 때. */
data class SyncDevice(val id: String, val name: String, val updatedAtEpochMs: Long)

/**
 * 기기 간 이어 읽기(사용자 결정 8 → 0.58.0 FTP 서버). 이 기기의 읽기 기록을 저장소([SyncStore] — 사람이 정한 FTP 서버의 폴더)에
 * 기기별 파일 하나로 적고, 다른 기기의 파일을 읽는다. 다른 기기의 파일은 읽기만 한다.
 *
 * - 기본은 꺼짐. 꺼져 있으면 아무것도 쓰지도 읽지도 않는다(인터넷을 쓰지 않는다).
 * - 동기화하는 것: 읽은 자리 · 책갈피 · 다 읽은 때(8-3). 형광펜 · 메모는 아니다.
 * - 책은 **파일 이름 + 크기**로 맞춘다 — 기기마다 책 폴더 · 주소가 다르다. 0.56 까지는 폴더마다 따로 적었지만 이제 저장소가 하나라
 *   폴더를 가리지 않는다.
 *
 * 맞추지 못해도(인터넷 없음 · 로그인 거절 · 공간 부족) 던지지 않고 [status] 에 까닭을 남긴다. 이 기능 때문에 책이 안 열리면 안 된다.
 */
class ReadingSync(
    private val db: ReaderDatabase,
    private val store: SyncStore,
    /**
     * 기기 id · 켬 표시 · 받은 책갈피 목록을 둘 곳. **백업에서 빠지는 곳**(noBackupFilesDir)이어야 한다 — 기기 id 가 새 휴대폰으로
     * 따라가면 두 기기가 같은 파일을 번갈아 덮어써, 서로의 읽은 자리를 지운다.
     */
    private val stateDir: File,
    private val deviceNameOf: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Mutex()

    private val _status = MutableStateFlow(SyncStatus(lastSyncedAtEpochMs = readLong(LAST_SYNC)))

    /** 마지막으로 맞춘 때 · 맞추지 못한 까닭. */
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /**
     * 0.56 까지의 "책 폴더에 읽은 자리 남기기" 를 켜 둔 기기인가. 새 방식은 서버를 정해야 해 저절로 켜지 않는다 — 화면이 한 번 알린다.
     * 알린 뒤 [dismissLegacy] 로 지운다.
     */
    val legacyWasOn: Boolean get() = File(stateDir, LEGACY_ENABLED).isFile

    fun dismissLegacy() {
        File(stateDir, LEGACY_ENABLED).delete()
    }

    var enabled: Boolean
        get() = File(stateDir, ENABLED).isFile
        set(value) {
            stateDir.mkdirs()
            if (value) File(stateDir, ENABLED).writeText("1") else File(stateDir, ENABLED).delete()
            // 다시 켜면 처음처럼 한 번 쓴다 — 꺼 둔 사이 바뀐 기록이 "이미 썼다" 로 걸러지지 않게.
            written = null
            if (!value) _status.value = _status.value.copy(problem = null, busy = false)
        }

    /** 이 기기의 id. 처음 부를 때 만든다. */
    val deviceId: String by lazy {
        val file = File(stateDir, DEVICE_ID)
        runCatching { file.readText().trim().lowercase() }.getOrNull()?.takeIf { SyncCodec.deviceIdOf("$it.json") != null }
            ?: UUID.randomUUID().toString().also { id ->
                stateDir.mkdirs()
                runCatching { file.writeText(id) }
            }
    }

    val deviceName: String get() = deviceNameOf()

    /** 기기 id → 읽어 둔 파일. 수정 시각 · 크기가 같으면 다시 읽지 않는다. */
    private var others: Map<String, Cached> = emptyMap()

    /** 저장소에 있는 이 기기의 파일. 덮어쓸 때 쓴다. */
    private var own: SyncStore.Entry? = null

    /** 마지막으로 쓴 내용(때를 뺀 것). 같으면 쓰지 않는다 — 쪽을 넘길 때마다 서버에 올리지 않게. */
    private var written: String? = null

    /** 마지막으로 목록을 받은 때. 책을 열 때마다 서버에 묻지 않게 잠깐은 받아 둔 것을 쓴다. */
    private var listedAt: Long = Long.MIN_VALUE / 2

    private class Cached(val entry: SyncStore.Entry, val file: SyncFile?)

    /** 지금까지 본 다른 기기, 최근에 기록을 남긴 것부터. */
    fun devices(): List<SyncDevice> =
        others.values.mapNotNull { it.file }
            .map { SyncDevice(it.deviceId, it.deviceName, it.updatedAtEpochMs) }
            .sortedByDescending { it.updatedAtEpochMs }

    /** 기기마다 기록을 남긴 책 · 만화 수. 화면의 "책 14권". */
    fun titlesOf(deviceId: String): Int = others[deviceId]?.file?.let { it.books.size + it.comics.size } ?: 0

    // ── 쓰기 ────────────────────────────────────────────────

    /** 이 기기의 기록을 저장소에 적는다. 썼으면 1, 바뀐 것이 없거나 꺼져 있거나 못 썼으면 0(까닭은 [status]). */
    suspend fun writeOwn(): Int = withContext(io) {
        if (!enabled) return@withContext 0
        lock.withLock {
            guarded(0) {
                val (books, comics) = collect()
                val fingerprint = SyncCodec.encode(SyncFile(deviceId, deviceName, 0L, books, comics))
                if (written == fingerprint) return@guarded 0
                if (own == null) listLocked()
                // 적을 것이 없고 저장소에도 없으면 만들지 않는다 — 책을 하나도 안 읽은 기기가 빈 파일로 "함께 읽는 기기" 에 끼지 않게.
                if (own == null && books.isEmpty() && comics.isEmpty()) {
                    written = fingerprint
                    return@guarded 0
                }
                own = store.write(SyncCodec.fileName(deviceId), own, SyncCodec.encode(SyncFile(deviceId, deviceName, clock(), books, comics)))
                written = fingerprint
                markSynced()
                1
            }
        }
    }

    /**
     * 이 기기의 책 · 만화 가운데 남길 기록이 있는 것. 같은 기록이면 같은 글이 되게 이름순으로. 같은 파일이 두 폴더에 있으면 한 번만 —
     * 다른 기기에서는 이름 · 크기로 맞추니 둘은 구별되지 않는다.
     */
    private suspend fun collect(): Pair<List<BookRecord>, List<ComicRecord>> {
        val books = db.books().all().filter { !it.missing }
        val progress = db.progress().all().associateBy { it.bookId }
        val recent = db.recent().all().associateBy { it.bookId }
        val marks = db.bookmarks().all().groupBy { it.bookId }
        val bookRecords = books.mapNotNull { b ->
            val p = progress[b.id]
            val finished = recent[b.id]?.finishedAtEpochMs
            val m = marks[b.id].orEmpty()
            if (p == null && finished == null && m.isEmpty()) return@mapNotNull null
            BookRecord(
                displayName = b.displayName,
                sizeBytes = b.sizeBytes,
                title = b.title,
                author = b.author,
                progress = p?.let { ProgressRecord(it.locator, it.percent, it.updatedAtEpochMs) },
                openedAtEpochMs = recent[b.id]?.openedAtEpochMs,
                finishedAtEpochMs = finished,
                bookmarks = m.map { BookmarkRecord(it.locator, it.snippet, it.createdAtEpochMs) }.sortedBy { it.locator },
            )
        }.distinctBy { it.displayName to it.sizeBytes }.sortedWith(compareBy({ it.displayName }, { it.sizeBytes ?: -1 }))
        val comicDao = db.comics()
        val units = comicDao.present()
        val comicProgress = comicDao.allProgress().associateBy { it.unitId }
        val comicMarks = comicDao.allBookmarks().groupBy { it.unitId }
        val comicRecords = units.mapNotNull { u ->
            val p = comicProgress[u.id]
            val m = comicMarks[u.id].orEmpty()
            if (p == null && m.isEmpty()) return@mapNotNull null
            ComicRecord(
                name = u.name,
                sizeBytes = u.sizeBytes,
                page = p?.page,
                pageCount = p?.pageCount ?: u.pageCount,
                offset = p?.offset,
                half = halfValue(p?.half),
                updatedAtEpochMs = p?.updatedAtEpochMs ?: 0L,
                finishedAtEpochMs = p?.finishedAtEpochMs,
                bookmarks = m.map { ComicBookmarkRecord(it.page, it.createdAtEpochMs) }.sortedBy { it.page },
            )
        }.distinctBy { it.name to it.sizeBytes }.sortedWith(compareBy({ it.name }, { it.sizeBytes ?: -1 }))
        return bookRecords to comicRecords
    }

    // ── 읽기 ────────────────────────────────────────────────

    /** 다른 기기의 파일을 읽어 책갈피 · 다 읽은 때를 합친다. 앱을 열 때 · "지금 맞추기". 합친 것의 수. */
    suspend fun refreshAll(): Int = withContext(io) {
        if (!enabled) return@withContext 0
        lock.withLock { guarded(0) { listLocked() } }
    }

    /** 이 기기 기록을 쓰고 다른 기기 것을 읽는다 — "지금 맞추기" · 켤 때. */
    suspend fun syncNow(): Int {
        writeOwn()
        return refreshAll()
    }

    /**
     * 저장소 목록을 받아 바뀐 파일만 읽고(수정 시각 · 크기), 새로 읽은 파일의 책갈피 · 다 읽은 때를 합친다. 읽은 자리는 합치지
     * 않는다(8-2) — [offerForBook] · [offerForComic] 이 묻는다.
     */
    private suspend fun listLocked(): Int {
        val entries = store.list()
        listedAt = clock()
        own = entries.firstOrNull { SyncCodec.deviceIdOf(it.name) == deviceId }
        val before = others
        val now = HashMap<String, Cached>()
        val fresh = ArrayList<SyncFile>()
        for (entry in entries) {
            // 모르는 파일은 건너뛴다. 이 기기의 파일은 읽지 않는다 — 제 기록은 DB 에 있다.
            val id = SyncCodec.deviceIdOf(entry.name) ?: continue
            if (id == deviceId || id in now) continue
            val old = before[id]
            if (old != null && old.entry.id == entry.id && entry.modifiedEpochMs != null && old.entry.modifiedEpochMs == entry.modifiedEpochMs && old.entry.sizeBytes == entry.sizeBytes) {
                now[id] = old
                continue
            }
            val file = store.read(entry, MAX_BYTES)?.let { SyncCodec.decode(it, id) }
            // 깨진 파일도 "읽었음" 으로 적어 둔다 — 바뀌기 전에는 열 때마다 다시 읽지 않는다.
            now[id] = Cached(entry, file)
            if (file != null) fresh += file
        }
        others = now
        markSynced()
        return fresh.sumOf { merge(it) }
    }

    /**
     * 저장소 일을 하고, 맞추지 못한 까닭을 [status] 에 남긴다. 던지지 않는다 — 인터넷이 없다고 책이 안 열리거나 앱이 닫히면 안 된다.
     */
    private suspend fun <T> guarded(fallback: T, work: suspend () -> T): T {
        _status.value = _status.value.copy(busy = true)
        return try {
            // 한 번 맞추는 동안 연결 하나 — FTP 는 일마다 잇고 로그인하면 몇 초씩 걸린다.
            store.session { work() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: SyncStoreException) {
            android.util.Log.w(TAG, "reading state not synced: ${e.problem}", e)
            _status.value = _status.value.copy(problem = e.problem)
            fallback
        } catch (e: IOException) {
            _status.value = _status.value.copy(problem = SyncProblem.Offline)
            fallback
        } catch (e: RuntimeException) {
            // 서버의 엉뚱한 답(읽을 수 없는 목록 모양)이 라이브러리 안에서 런타임 오류가 되기도 한다. 앱이 닫히면 안 된다.
            android.util.Log.w(TAG, "reading state not synced", e)
            _status.value = _status.value.copy(problem = SyncProblem.Server)
            fallback
        } finally {
            _status.value = _status.value.copy(busy = false)
        }
    }

    private fun markSynced() {
        val at = clock()
        _status.value = _status.value.copy(lastSyncedAtEpochMs = at, problem = null)
        runCatching { stateDir.mkdirs(); File(stateDir, LAST_SYNC).writeText(at.toString()) }
    }

    private fun readLong(name: String): Long? = runCatching { File(stateDir, name).readText().trim().toLong() }.getOrNull()

    /**
     * 연결 확인(설정 화면의 단추). 되면 null, 안 되면 까닭. 켜 있지 않아도 한다 — 켜기 전에 확인한다.
     *
     * @param via 확인할 저장소. 새로 넣은 서버 설정은 저장하기 전에 따로 만든 저장소로 확인한다 — 쓰고 있는 저장소의 설정을 잠깐
     *   바꿔 확인하면, 그 사이 돌던 맞추기가 확인 중인(틀렸을 수도 있는) 서버로 기록을 올린다.
     */
    suspend fun checkConnection(via: SyncStore = store): SyncProblem? = withContext(io) {
        try {
            via.check()
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: SyncStoreException) {
            e.problem
        } catch (e: IOException) {
            SyncProblem.Offline
        } catch (e: RuntimeException) {
            SyncProblem.Server
        }
    }

    // ── 지우기 ──────────────────────────────────────────────

    /** "함께 읽는 기기" 에서 하나를 지운다(오래 쓰지 않는 기기). 그 기기가 다시 켜지면 제 파일을 새로 쓴다. */
    suspend fun forget(deviceId: String): Boolean = withContext(io) {
        lock.withLock {
            guarded(false) {
                val entry = others[deviceId]?.entry ?: return@guarded false
                store.delete(entry)
                others = others - deviceId
                true
            }
        }
    }

    /**
     * 서버에 저장한 기록을 모두 지우고 이 기기에서 끈다. 다른 기기는 켜져 있으면 다음에 제 파일을 다시 쓴다 — 그 기기에서도 끄라고
     * 화면이 알린다.
     */
    suspend fun clearRemote(): Boolean = withContext(io) {
        lock.withLock {
            guarded(false) {
                store.list().filter { SyncCodec.deviceIdOf(it.name) != null }.forEach { store.delete(it) }
                others = emptyMap()
                own = null
                enabled = false
                true
            }
        }
    }

    /** 다른 기기 파일 하나의 책갈피 · 다 읽은 때를 이 폴더의 같은 책 · 만화에 합친다. 바꾼 것의 수. */
    private suspend fun merge(file: SyncFile): Int = db.withTransaction {
        val seen = seen()
        val fresh = LinkedHashSet<String>()
        var changed = 0
        val books = db.books().all().filter { !it.missing }
        val finishedNow = db.recent().all().associateTo(HashMap()) { it.bookId to it.finishedAtEpochMs }
        for (r in file.books) {
            for (book in books.filter { matches(it, r) }) {
                val item = itemKey(r.displayName, r.sizeBytes)
                val local = db.bookmarks().forBook(book.id).mapTo(HashSet()) { it.locator }
                // 이미 있는 것도 "본 것" 으로 적는다 — 그래야 나중에 이 기기에서 빼면 다시 들어오지 않는다.
                r.bookmarks.filter { it.locator in local }.forEach { fresh += hash(SyncRules.bookmarkKey(item, it.locator)) }
                for (b in SyncRules.bookmarksToImport(item, local, r.bookmarks, seen.mapped(item, r.bookmarks))) {
                    val locator = Locator.decodeOrNull(b.locator) ?: continue
                    val order = LocatorOrder.of(locator)
                    db.bookmarks().insert(BookmarkEntity(0, book.id, b.locator, order.major, order.minor, order.patch, b.snippet, b.createdAtEpochMs))
                    fresh += hash(SyncRules.bookmarkKey(item, b.locator))
                    changed++
                }
                val remote = r.finishedAtEpochMs ?: continue
                val finishedKey = hash("f\u0000$item\u0000$remote")
                SyncRules.finishedToApply(finishedNow[book.id], remote, finishedKey in seen)?.let { at ->
                    // 이 기기에서 연 적 없는 책이면 최근 줄을 만든다 — 다 읽은 때는 그 줄에 적힌다. 연 때는 다른 기기의 것을 쓴다.
                    db.recent().insertIfAbsent(book.id, r.openedAtEpochMs ?: at)
                    db.recent().setFinished(book.id, at)
                    finishedNow[book.id] = at
                    changed++
                }
                fresh += finishedKey
            }
        }
        val units = db.comics().present()
        for (r in file.comics) {
            for (unit in units.filter { matches(it, r) }) {
                val item = itemKey(r.name, r.sizeBytes)
                val local = db.comics().bookmarksOf(unit.id).mapTo(HashSet()) { it.page.toString() }
                val remoteMarks = r.bookmarks.map { BookmarkRecord(it.page.toString(), null, it.createdAtEpochMs) }
                remoteMarks.filter { it.locator in local }.forEach { fresh += hash(SyncRules.bookmarkKey(item, it.locator)) }
                for (b in SyncRules.bookmarksToImport(item, local, remoteMarks, seen.mapped(item, remoteMarks))) {
                    db.comics().addBookmark(ComicBookmarkEntity(unit.id, b.locator.toInt(), b.createdAtEpochMs))
                    fresh += hash(SyncRules.bookmarkKey(item, b.locator))
                    changed++
                }
                val remote = r.finishedAtEpochMs ?: continue
                val finishedKey = hash("f\u0000$item\u0000$remote")
                val old = db.comics().progress(unit.id)
                SyncRules.finishedToApply(old?.finishedAtEpochMs, remote, finishedKey in seen)?.let { at ->
                    // 읽은 자리는 건드리지 않는다. 이 기기에서 펼친 적 없는 권은 첫 쪽에 다 읽은 때만 적는다 — 고친 때(0)가 가장
                    // 오래되어 서재 · 위젯의 "최근" 에 끼지 않는다.
                    db.comics().saveProgress(
                        old?.copy(finishedAtEpochMs = at)
                            ?: ComicProgressEntity(unit.id, 0, r.pageCount ?: unit.pageCount ?: 1, 0L, at, null),
                    )
                    changed++
                }
                fresh += finishedKey
            }
        }
        remember(fresh - seen)
        changed
    }

    /** [SyncRules.bookmarksToImport] 가 보는 "본 것" 열쇠 — 저장은 해시로 하므로 이 책갈피들의 열쇠를 해시로 맞춰 본다. */
    private fun Set<String>.mapped(item: String, remote: List<BookmarkRecord>): Set<String> =
        remote.map { SyncRules.bookmarkKey(item, it.locator) }.filterTo(HashSet()) { hash(it) in this }

    private fun matches(book: BookEntity, r: BookRecord): Boolean =
        book.displayName == r.displayName && (book.sizeBytes == null || r.sizeBytes == null || book.sizeBytes == r.sizeBytes)

    private fun matches(unit: ComicUnitEntity, r: ComicRecord): Boolean =
        unit.name == r.name && (unit.sizeBytes == null || r.sizeBytes == null || unit.sizeBytes == r.sizeBytes)

    private fun itemKey(name: String, size: Long?) = "$name\u0000${size ?: ""}"

    // ── 묻기(8-2) ───────────────────────────────────────────

    /**
     * 책을 열 때 목록을 다시 받을지. 방금 받았으면(책을 연달아 열 때) 받아 둔 것을 쓴다 — 책마다 서버에 묻지 않게.
     */
    private suspend fun refreshForOffer() {
        if (clock() - listedAt < OFFER_REFRESH_MS) return
        lock.withLock { guarded(0) { listLocked() } }
    }

    /** 이 책을 다른 기기가 더 읽었으면 그 자리. 꺼져 있거나 없으면 null. 인터넷이 없으면 받아 둔 것으로 본다. */
    suspend fun offerForBook(bookId: String): SyncOffer.Book? = withContext(io) {
        if (!enabled) return@withContext null
        val book = db.books().get(bookId)?.takeIf { !it.missing } ?: return@withContext null
        refreshForOffer()
        val remotes = others.values.mapNotNull { c ->
            val f = c.file ?: return@mapNotNull null
            f.books.firstOrNull { matches(book, it) }?.progress?.let { f to it }
        }
        val local = db.progress().get(bookId)?.let { ProgressRecord(it.locator, it.percent, it.updatedAtEpochMs) }
        val (file, p) = SyncRules.furthest(local, remotes) ?: return@withContext null
        val locator = Locator.decodeOrNull(p.locator) ?: return@withContext null
        SyncOffer.Book(file.deviceName, p.updatedAtEpochMs, locator, p.percent)
    }

    /** 만화 한 권판 [offerForBook]. */
    suspend fun offerForComic(unitId: String): SyncOffer.Comic? = withContext(io) {
        if (!enabled) return@withContext null
        val unit = db.comics().get(unitId)?.takeIf { !it.missing } ?: return@withContext null
        refreshForOffer()
        val remotes = others.values.mapNotNull { c ->
            val f = c.file ?: return@mapNotNull null
            f.comics.firstOrNull { matches(unit, it) }?.takeIf { it.page != null }?.let { f to it }
        }
        val local = db.comics().progress(unitId)?.let {
            ComicRecord(unit.name, unit.sizeBytes, it.page, it.pageCount, it.offset, it.updatedAtEpochMs, it.finishedAtEpochMs)
        }
        val (file, r) = SyncRules.furthestComic(local, remotes) ?: return@withContext null
        SyncOffer.Comic(file.deviceName, r.updatedAtEpochMs, r.page ?: return@withContext null, r.offset, r.pageCount, r.half)
    }

    // ── 받은 것 목록 ─────────────────────────────────────────

    private var seenCache: MutableSet<String>? = null

    private fun seen(): Set<String> = seenCache ?: HashSet<String>().also { set ->
        runCatching { File(stateDir, SEEN).forEachLine { line -> line.trim().takeIf { it.isNotEmpty() }?.let(set::add) } }
        seenCache = set
    }

    private fun remember(keys: Set<String>) {
        if (keys.isEmpty()) return
        seenCache?.addAll(keys)
        // 못 적어도(저장 공간이 가득) 합치기는 끝났다. 다음에 같은 것을 다시 "새것" 으로 볼 뿐이다.
        runCatching {
            stateDir.mkdirs()
            File(stateDir, SEEN).appendText(keys.joinToString("\n", postfix = "\n"))
        }
    }

    /** 파일 이름 · 책갈피 자리에 줄바꿈이 들어 있어도 한 줄로 적히게 해시로 둔다. */
    private fun hash(key: String): String =
        MessageDigest.getInstance("SHA-1").digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "OloSync"
        /** 0.58 의 켬 표시. 0.56 까지의 것([LEGACY_ENABLED])과 이름을 달리 한다 — 옛 켬이 서버 설정 없는 새 방식을 켜 두지 않게. */
        private const val ENABLED = "ftp-enabled"
        private const val LEGACY_ENABLED = "enabled"
        private const val DEVICE_ID = "device-id"

        /**
         * 받은 책갈피 · 다 읽은 때의 열쇠. 0.56 은 폴더를 열쇠에 넣었다(`seen.txt`) — 새 열쇠와 맞지 않아 이름을 바꿔 새로 센다. 옛
         * 것으로 들였던 책갈피는 이미 이 기기에 있으니 다시 들어오지 않는다.
         */
        private const val SEEN = "seen-v2.txt"
        private const val LAST_SYNC = "last-sync"

        /** 책을 열 때 서버 목록을 다시 받는 간격. 연달아 여는 책마다 묻지 않되, 다른 기기에서 막 읽고 온 것은 놓치지 않게 짧게. */
        private const val OFFER_REFRESH_MS = 20_000L

        /** 기기 파일 하나는 책 수천 권이라도 몇 MB 다. 이보다 크면 이 앱의 파일이 아니다. */
        private const val MAX_BYTES = 8L * 1024 * 1024

        /**
         * 사람이 알아보는 기기 이름. 설정의 "휴대폰 정보 > 기기 이름"(갤럭시 S24)을 먼저 쓴다 — 모델 번호("SM-S921N")로는 어느
         * 기기인지 사람이 모른다. 없으면 모델 이름.
         */
        fun defaultDeviceName(context: Context): String =
            runCatching { android.provider.Settings.Global.getString(context.contentResolver, android.provider.Settings.Global.DEVICE_NAME) }
                .getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                ?: android.os.Build.MODEL?.trim()?.takeIf { it.isNotEmpty() }
                ?: SyncCodec.UNKNOWN_DEVICE
    }
}

/**
 * 이 데이터의 기기 간 이어 읽기. 상태(기기 id · 켬 · 받은 것)는 백업에서 빠지는 곳에 둔다 — 기기 id 가 새 휴대폰으로 따라가면 두
 * 기기가 한 파일을 번갈아 덮는다. [store] 는 앱이 FTP 설정으로 만든다.
 */
fun ReaderData.readingSync(context: Context, store: SyncStore): ReadingSync =
    ReadingSync(database, store, File(context.noBackupFilesDir, "sync"), { ReadingSync.defaultDeviceName(context) })
