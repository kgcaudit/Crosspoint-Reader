package io.github.kgcaudit.reader.data.sync

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.room.withTransaction
import io.github.kgcaudit.reader.data.ReaderData
import io.github.kgcaudit.reader.data.backup.BookRecord
import io.github.kgcaudit.reader.data.backup.BookmarkRecord
import io.github.kgcaudit.reader.data.backup.ComicBookmarkRecord
import io.github.kgcaudit.reader.data.backup.ComicRecord
import io.github.kgcaudit.reader.data.backup.ProgressRecord
import io.github.kgcaudit.reader.data.db.BookEntity
import io.github.kgcaudit.reader.data.db.BookmarkEntity
import io.github.kgcaudit.reader.data.db.ComicBookmarkEntity
import io.github.kgcaudit.reader.data.db.ComicProgressEntity
import io.github.kgcaudit.reader.data.db.ComicUnitEntity
import io.github.kgcaudit.reader.data.db.LocatorOrder
import io.github.kgcaudit.reader.data.db.ReaderDatabase
import io.github.kgcaudit.reader.data.saf.LibraryFolders
import io.github.kgcaudit.reader.data.saf.SafDocumentTree
import io.github.kgcaudit.reader.document.Locator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
    ) : SyncOffer
}

/** 함께 읽는 기기 하나: 이름과 마지막으로 기록을 남긴 때. */
data class SyncDevice(val id: String, val name: String, val updatedAtEpochMs: Long)

/**
 * 기기 간 이어 읽기(사용자 결정 8). 앱은 인터넷을 쓰지 않는다 — 책 폴더 안 `.olo/sync/` 에 이 기기의 읽기 기록을 적고, 사람이 쓰는
 * 동기화 앱(구글 드라이브 · 원드라이브 · Syncthing …)이 그 폴더를 다른 기기와 맞춘다. 다른 기기의 파일은 읽기만 한다.
 *
 * - 기본은 꺼짐(8-4). 꺼져 있으면 아무것도 쓰지도 읽지도 않는다.
 * - 쓰기 허락이 없는 폴더(0.50 까지 읽기만 받고 등록한 폴더)는 건너뛴다 — 실패로 다루지 않는다.
 * - 동기화하는 것: 읽은 자리 · 책갈피 · 다 읽은 때(8-3). 형광펜 · 메모는 아니다.
 *
 * 폴더 하나를 못 읽거나 못 써도(제공자 오류 · 저장소가 빠짐) 다른 폴더는 계속한다. 이 기능 때문에 책이 안 열리면 안 된다.
 */
class ReadingSync(
    private val resolver: ContentResolver,
    private val db: ReaderDatabase,
    private val folders: LibraryFolders,
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

    var enabled: Boolean
        get() = File(stateDir, ENABLED).isFile
        set(value) {
            stateDir.mkdirs()
            if (value) File(stateDir, ENABLED).writeText("1") else File(stateDir, ENABLED).delete()
            // 다시 켜면 처음처럼 한 번 쓴다 — 꺼 둔 사이 바뀐 기록이 "이미 썼다" 로 걸러지지 않게.
            written.clear()
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

    /** 폴더(트리 URI 글) → 기기 id → 읽어 둔 파일. 마지막 수정 시각 · 크기가 같으면 다시 읽지 않는다. */
    private val others = ConcurrentHashMap<String, Map<String, Cached>>()

    /** 폴더 → `.olo/sync` 의 문서 id. 매번 폴더 전체 목록을 받지 않게. */
    private val syncDirs = ConcurrentHashMap<String, String>()

    /** 폴더 → 마지막으로 쓴 내용(때를 뺀 것). 같으면 쓰지 않는다 — 쪽을 넘길 때마다 동기화 앱이 파일을 올리지 않게. */
    private val written = ConcurrentHashMap<String, String>()

    private class Cached(val modified: Long?, val size: Long?, val file: SyncFile?)

    /** 쓰기 허락이 없어 건너뛰는 등록 폴더. 켤 때 다시 고르라고 묻는다. */
    fun foldersWithoutWrite(): List<Uri> = folders.folders().filter { !folders.canWrite(it) }

    /** 지금까지 본 다른 기기, 최근에 기록을 남긴 것부터. */
    fun devices(): List<SyncDevice> =
        others.values.flatMap { it.values.mapNotNull { c -> c.file } }
            .groupBy { it.deviceId }
            .map { (id, files) -> files.maxBy { it.updatedAtEpochMs }.let { SyncDevice(id, it.deviceName, it.updatedAtEpochMs) } }
            .sortedByDescending { it.updatedAtEpochMs }

    // ── 쓰기 ────────────────────────────────────────────────

    /** 쓸 수 있는 폴더마다 이 기기의 기록을 적는다. 쓴 폴더 수. 꺼져 있으면 0. */
    suspend fun writeOwn(): Int = withContext(io) {
        if (!enabled) return@withContext 0
        lock.withLock {
            var count = 0
            for (folder in folders.folders()) {
                if (!folders.canWrite(folder)) continue
                try {
                    if (writeFolder(folder)) count++
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 제공자의 오류(클라우드 앱의 버그 · 빠진 SD 카드)가 바인더를 건너 RuntimeException 으로 온다. 이 폴더만 건너뛴다.
                    android.util.Log.w(TAG, "cannot write reading state to $folder", e)
                    syncDirs.remove(folder.toString())
                }
            }
            count
        }
    }

    private suspend fun writeFolder(folder: Uri): Boolean {
        val key = folder.toString()
        val (books, comics) = collect(key)
        val empty = books.isEmpty() && comics.isEmpty()
        val fingerprint = SyncCodec.encode(SyncFile(deviceId, deviceName, 0L, books, comics))
        if (written[key] == fingerprint) return false
        // 적을 것이 없으면 `.olo` 를 만들지 않는다 — 책만 넣어 둔 폴더에 빈 숨은 폴더가 생기면 사람이 무엇인지 몰라 지운다.
        val dir = syncDirs[key] ?: syncDir(folder, create = !empty)
        if (dir == null) {
            written[key] = fingerprint
            return false
        }
        syncDirs[key] = dir
        val name = SyncCodec.fileName(deviceId)
        val existing = SafDocumentTree(resolver, folder).children(dir).firstOrNull { !it.isDirectory && it.name == name }
        if (existing == null && empty) {
            written[key] = fingerprint
            return false
        }
        val bytes = SyncCodec.encode(SyncFile(deviceId, deviceName, clock(), books, comics)).toByteArray(Charsets.UTF_8)
        val uri = existing?.let { Uri.parse(it.uri) } ?: create(folder, dir, name)
        try {
            write(uri, "wt", bytes)
        } catch (e: IllegalArgumentException) {
            rewrite(folder, dir, uri, name, bytes)
        } catch (e: java.io.FileNotFoundException) {
            // "wt"(비우고 쓰기)를 받지 않는 제공자가 있다. "w" 로 덮으면 새 글이 짧을 때 옛 글 끝이 남아 JSON 이 깨진다 — 지우고
            // 새로 만든다.
            rewrite(folder, dir, uri, name, bytes)
        }
        written[key] = fingerprint
        return true
    }

    private fun rewrite(folder: Uri, dir: String, old: Uri, name: String, bytes: ByteArray) {
        runCatching { DocumentsContract.deleteDocument(resolver, old) }
        write(create(folder, dir, name), "w", bytes)
    }

    private fun write(uri: Uri, mode: String, bytes: ByteArray) {
        (resolver.openOutputStream(uri, mode) ?: throw IOException("provider gave no stream for $uri")).use { it.write(bytes) }
    }

    private fun create(folder: Uri, parent: String, name: String): Uri =
        DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(folder, parent), MIME_JSON, name)
            ?: throw IOException("provider did not create $name")

    /**
     * `.olo/sync` 의 문서 id. 없으면 [create] 일 때만 만든다. 점으로 시작하는 이름은 SAF 에서도 그대로 만들어진다(파일 관리자에서
     * 숨은 폴더로 보인다).
     */
    private fun syncDir(folder: Uri, create: Boolean): String? {
        val tree = SafDocumentTree(resolver, folder)
        val olo = tree.children(tree.rootKey).firstOrNull { it.isDirectory && it.name == SyncCodec.DIR }?.key
            ?: if (create) createDir(folder, tree.rootKey, SyncCodec.DIR) else return null
        return tree.children(olo).firstOrNull { it.isDirectory && it.name == SyncCodec.SUBDIR }?.key
            ?: if (create) createDir(folder, olo, SyncCodec.SUBDIR) else null
    }

    private fun createDir(folder: Uri, parent: String, name: String): String {
        val made = DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(folder, parent), Document.MIME_TYPE_DIR, name)
            ?: throw IOException("provider did not create $name")
        return DocumentsContract.getDocumentId(made)
    }

    /** 이 폴더의 책 · 만화 가운데 남길 기록이 있는 것. 같은 기록이면 같은 글이 되게 이름순으로. */
    private suspend fun collect(folderKey: String): Pair<List<BookRecord>, List<ComicRecord>> {
        val books = db.books().inFolder(folderKey).filter { !it.missing }
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
        }.sortedWith(compareBy({ it.displayName }, { it.sizeBytes ?: -1 }))
        val comicDao = db.comics()
        val units = comicDao.inFolder(folderKey).filter { !it.missing && !it.notComic }
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
                updatedAtEpochMs = p?.updatedAtEpochMs ?: 0L,
                finishedAtEpochMs = p?.finishedAtEpochMs,
                bookmarks = m.map { ComicBookmarkRecord(it.page, it.createdAtEpochMs) }.sortedBy { it.page },
            )
        }.sortedWith(compareBy({ it.name }, { it.sizeBytes ?: -1 }))
        return bookRecords to comicRecords
    }

    // ── 읽기 ────────────────────────────────────────────────

    /** 등록 폴더 전부에서 다른 기기의 파일을 읽어 책갈피 · 다 읽은 때를 합친다. 앱을 열 때 · 다시 훑을 때. */
    suspend fun refreshAll(): Int {
        if (!enabled) return 0
        return folders.folders().sumOf { folder -> refreshSafely(folder) }
    }

    private suspend fun refreshSafely(folder: Uri): Int = try {
        refresh(folder)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.w(TAG, "cannot read reading state in $folder", e)
        syncDirs.remove(folder.toString())
        0
    }

    /**
     * 한 폴더의 다른 기기 파일을 읽는다. 바뀐 파일만 읽고(수정 시각 · 크기), 새로 읽은 파일의 책갈피 · 다 읽은 때를 합친다. 읽은
     * 자리는 합치지 않는다(8-2) — [offerForBook] · [offerForComic] 이 묻는다. 합친 것의 수.
     */
    suspend fun refresh(folder: Uri): Int = withContext(io) {
        if (!enabled) return@withContext 0
        lock.withLock {
            val key = folder.toString()
            val dir = syncDirs[key] ?: syncDir(folder, create = false)
            if (dir == null) {
                others.remove(key)
                return@withLock 0
            }
            syncDirs[key] = dir
            val before = others[key].orEmpty()
            val now = HashMap<String, Cached>()
            val fresh = ArrayList<SyncFile>()
            for (entry in SafDocumentTree(resolver, folder).children(dir)) {
                if (entry.isDirectory) continue
                // 모르는 파일(충돌 사본 · 사람이 둔 메모)은 건너뛴다. 이 기기의 파일은 읽지 않는다 — 제 기록은 DB 에 있다.
                val id = SyncCodec.deviceIdOf(entry.name) ?: continue
                if (id == deviceId) continue
                val old = before[id]
                if (old != null && entry.lastModifiedEpochMs != null && old.modified == entry.lastModifiedEpochMs && old.size == entry.sizeBytes) {
                    now[id] = old
                    continue
                }
                val file = read(Uri.parse(entry.uri), entry.sizeBytes)?.let { SyncCodec.decode(it, id) }
                // 깨진 파일도 "읽었음" 으로 적어 둔다 — 바뀌기 전에는 열 때마다 다시 읽지 않는다. 다음 동기화가 고쳐 쓰면 다시 읽는다.
                now[id] = Cached(entry.lastModifiedEpochMs, entry.sizeBytes, file)
                if (file != null) fresh += file
            }
            others[key] = now
            fresh.sumOf { merge(key, it) }
        }
    }

    /** 파일 글. 너무 크면(이 앱의 파일이 아니다) 읽지 않는다. 못 읽으면 null — 그 파일만 버린다. */
    private fun read(uri: Uri, size: Long?): String? {
        if (size != null && size > MAX_BYTES) return null
        return try {
            resolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_BYTES) return null
                }
                out.toString("UTF-8")
            }
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }

    /** 다른 기기 파일 하나의 책갈피 · 다 읽은 때를 이 폴더의 같은 책 · 만화에 합친다. 바꾼 것의 수. */
    private suspend fun merge(folderKey: String, file: SyncFile): Int = db.withTransaction {
        val seen = seen()
        val fresh = LinkedHashSet<String>()
        var changed = 0
        val books = db.books().inFolder(folderKey).filter { !it.missing }
        val finishedNow = db.recent().all().associateTo(HashMap()) { it.bookId to it.finishedAtEpochMs }
        for (r in file.books) {
            for (book in books.filter { matches(it, r) }) {
                val item = itemKey(folderKey, r.displayName, r.sizeBytes)
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
        val units = db.comics().inFolder(folderKey).filter { !it.missing && !it.notComic }
        for (r in file.comics) {
            for (unit in units.filter { matches(it, r) }) {
                val item = itemKey(folderKey, r.name, r.sizeBytes)
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

    private fun itemKey(folderKey: String, name: String, size: Long?) = "$folderKey\u0000$name\u0000${size ?: ""}"

    // ── 묻기(8-2) ───────────────────────────────────────────

    /** 이 책을 다른 기기가 더 읽었으면 그 자리. 그 책의 폴더만 다시 읽는다(싸다). 꺼져 있거나 없으면 null. */
    suspend fun offerForBook(bookId: String): SyncOffer.Book? = withContext(io) {
        if (!enabled) return@withContext null
        val book = db.books().get(bookId)?.takeIf { !it.missing } ?: return@withContext null
        val folder = folders.folders().firstOrNull { it.toString() == book.folderUri } ?: return@withContext null
        refreshSafely(folder)
        val remotes = others[book.folderUri].orEmpty().values.mapNotNull { c ->
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
        val folder = folders.folders().firstOrNull { it.toString() == unit.folderUri } ?: return@withContext null
        refreshSafely(folder)
        val remotes = others[unit.folderUri].orEmpty().values.mapNotNull { c ->
            val f = c.file ?: return@mapNotNull null
            f.comics.firstOrNull { matches(unit, it) }?.takeIf { it.page != null }?.let { f to it }
        }
        val local = db.comics().progress(unitId)?.let {
            ComicRecord(unit.name, unit.sizeBytes, it.page, it.pageCount, it.offset, it.updatedAtEpochMs, it.finishedAtEpochMs)
        }
        val (file, r) = SyncRules.furthestComic(local, remotes) ?: return@withContext null
        SyncOffer.Comic(file.deviceName, r.updatedAtEpochMs, r.page ?: return@withContext null, r.offset, r.pageCount)
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
        private const val ENABLED = "enabled"
        private const val DEVICE_ID = "device-id"
        private const val SEEN = "seen.txt"
        private const val MIME_JSON = "application/json"

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
 * 기기가 한 파일을 번갈아 덮는다.
 */
fun ReaderData.readingSync(context: Context): ReadingSync =
    ReadingSync(context.contentResolver, database, folders, File(context.noBackupFilesDir, "sync"), { ReadingSync.defaultDeviceName(context) })
