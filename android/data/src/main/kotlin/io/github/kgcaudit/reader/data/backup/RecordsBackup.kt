package io.github.kgcaudit.reader.data.backup

import android.util.AtomicFile
import androidx.room.withTransaction
import io.github.kgcaudit.reader.data.db.AnnotationEntity
import io.github.kgcaudit.reader.data.db.BookEntity
import io.github.kgcaudit.reader.data.db.BookmarkEntity
import io.github.kgcaudit.reader.data.db.ComicBookmarkEntity
import io.github.kgcaudit.reader.data.db.ComicOverrideEntity
import io.github.kgcaudit.reader.data.db.ComicProgressEntity
import io.github.kgcaudit.reader.data.db.LocatorOrder
import io.github.kgcaudit.reader.data.db.ProgressEntity
import io.github.kgcaudit.reader.data.db.ReaderDatabase
import io.github.kgcaudit.reader.document.Locator
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** 앱 정보의 "책 42권 · 만화 7권 · 책갈피 18개 · 형광펜 · 메모 63개". 만화는 0.49.0 부터. */
data class RecordsSummary(val books: Int, val bookmarks: Int, val annotations: Int, val comics: Int = 0)

/** 가져오기 전에 보이는 것: 이 휴대폰에서 찾은 책 · 만화와 못 찾은 것. */
class ImportPlan internal constructor(
    val file: RecordsFile,
    internal val found: List<Pair<BookRecord, List<String>>>,
    val missing: List<BookRecord>,
    internal val comicsFound: List<Pair<ComicRecord, List<String>>> = emptyList(),
    val comicsMissing: List<ComicRecord> = emptyList(),
) {
    val foundBooks: Int get() = found.size
    val foundComics: Int get() = comicsFound.size
}

data class ImportResult(
    val books: Int,
    val bookmarks: Int,
    val annotations: Int,
    val finished: Int,
    /** 못 찾은 책 · 만화의 파일 이름. 기억해 두었다가 폴더가 더해지면 이어 붙인다. */
    val missing: List<String>,
    val comics: Int = 0,
)

/**
 * 읽기 기록 백업 · 가져오기(0.27.0).
 *
 * 가져오기는 **늘 합친다**(사용자 결정). 이 휴대폰의 기록을 지우는 길을 두지 않는다 — 옛 백업을 잘못 골라도 잃는 것이
 * 없다. 규칙은 [BookRecord.mergedWith] 와 같다.
 *
 * 이 휴대폰에서 찾지 못한 책의 기록은 [pendingFile] 에 기억해 두고, 폴더를 훑을 때마다 [resumePending] 이 다시 맞춰
 * 본다. 새 휴대폰에서는 백업을 먼저 가져오고 책 폴더를 나중에 더하는 순서가 흔하다 — 그때 기록을 버리면 사용자는
 * 다시 가져와야 하는 줄 모른다.
 */
class RecordsBackup(
    private val db: ReaderDatabase,
    private val pendingFile: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val pendingLock = Mutex()

    /** 이 휴대폰의 기록 전부 + 아직 못 찾은 책의 기록. 백업 파일에 그대로 들어간다. */
    suspend fun collect(): RecordsFile {
        val progress = db.progress().all().associateBy { it.bookId }
        val recent = db.recent().all().associateBy { it.bookId }
        val marks = db.bookmarks().all().groupBy { it.bookId }
        val notes = db.annotations().all().groupBy { it.bookId }
        // 책 표에 없는 id(다른 앱이 넘겨 열었던 파일)의 기록은 담지 못한다 — 파일 이름을 모르니 새 휴대폰에서 찾을 길이 없다.
        val fromDb = db.books().all().mapNotNull { book ->
            val id = book.id
            if (id !in progress && id !in recent && id !in marks && id !in notes) return@mapNotNull null
            BookRecord(
                displayName = book.displayName,
                sizeBytes = book.sizeBytes,
                title = book.title,
                author = book.author,
                progress = progress[id]?.let { ProgressRecord(it.locator, it.percent, it.updatedAtEpochMs) },
                openedAtEpochMs = recent[id]?.openedAtEpochMs,
                finishedAtEpochMs = recent[id]?.finishedAtEpochMs,
                bookmarks = marks[id].orEmpty().map { BookmarkRecord(it.locator, it.snippet, it.createdAtEpochMs) },
                annotations = notes[id].orEmpty().map { AnnotationRecord(it.start, it.end, it.color, it.note, it.snippet, it.createdAtEpochMs) },
            )
        }
        // 기다리는 기록도 담는다. 빼면 "가져오기 → 못 찾음 → 다시 백업" 에서 그 책들의 기록이 조용히 사라진다.
        val waiting = pending()
        return RecordsFile(
            clock(),
            (fromDb + waiting.books).mergedByBook(),
            (collectComics() + waiting.comics).mergedByComic(),
            collectWorks(),
        )
    }

    /**
     * 만화 권마다 읽은 자리 · 다 읽은 때 · 책갈피(0.49.0). 0.48 까지는 백업에 만화가 없어, 휴대폰을 바꾸면 만화 · 웹툰을 읽던
     * 자리가 모두 사라졌다. 단위 표에 없는 id(지운 지 오래된 권)는 이름을 몰라 담지 못한다.
     */
    private suspend fun collectComics(): List<ComicRecord> {
        val comics = db.comics()
        val progress = comics.allProgress().associateBy { it.unitId }
        val marks = comics.allBookmarks().groupBy { it.unitId }
        val ids = (progress.keys + marks.keys).toList()
        val units = ids.chunked(500).flatMap { comics.unitsOf(it) }
        return units.map { unit ->
            val p = progress[unit.id]
            ComicRecord(
                name = unit.name,
                sizeBytes = unit.sizeBytes,
                page = p?.page,
                pageCount = p?.pageCount ?: unit.pageCount,
                offset = p?.offset,
                updatedAtEpochMs = p?.updatedAtEpochMs ?: 0L,
                finishedAtEpochMs = p?.finishedAtEpochMs,
                bookmarks = marks[unit.id].orEmpty().map { ComicBookmarkRecord(it.page, it.createdAtEpochMs) },
            )
        }
    }

    /** 작품마다 손으로 정한 이름 · 넘기는 방향 · 보는 방식(사용자 결정 1-가). 작품 열쇠는 이름에서 나와 새 휴대폰에서도 같다. */
    private suspend fun collectWorks(): List<WorkRecord> {
        val rows = db.comics().allOverrides()
        val keys = rows.filter { it.kind in WORK_KINDS }.map { it.subject }.distinct()
        return keys.map { key ->
            fun value(kind: String) = rows.firstOrNull { it.kind == kind && it.subject == key }?.value
            WorkRecord(
                key = key,
                title = value(TITLE),
                rightToLeft = when (value(RTL)) { "1" -> true; "0" -> false; else -> null },
                view = value(VIEW),
            )
        }.filter { it.title != null || it.rightToLeft != null || it.view != null }
    }

    suspend fun summary(): RecordsSummary = collect().let { RecordsSummary(it.books.size, it.bookmarkCount, it.annotationCount, it.comics.size) }

    suspend fun export(out: OutputStream): RecordsSummary {
        val file = collect()
        out.write(RecordsCodec.encode(file).toByteArray(Charsets.UTF_8))
        out.flush()
        return RecordsSummary(file.books.size, file.bookmarkCount, file.annotationCount, file.comics.size)
    }

    /** 이 앱의 백업이 아니면 null. 너무 큰 파일(동영상을 골랐다 등)은 끝까지 읽지 않는다. */
    fun read(input: InputStream): RecordsFile? {
        // readNBytes 는 안드로이드 13 부터라 쓰지 않는다 — 옛 휴대폰에서 NoSuchMethodError 로 죽는다.
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_BYTES) return null
        }
        return RecordsCodec.decode(out.toString("UTF-8"))
    }

    suspend fun plan(file: RecordsFile): ImportPlan {
        val found = ArrayList<Pair<BookRecord, List<String>>>()
        val missing = ArrayList<BookRecord>()
        for (record in file.books.mergedByBook()) {
            val targets = targetsOf(record)
            if (targets.isEmpty()) missing += record else found += record to targets.map { it.id }
        }
        val comicsFound = ArrayList<Pair<ComicRecord, List<String>>>()
        val comicsMissing = ArrayList<ComicRecord>()
        for (record in file.comics.mergedByComic()) {
            val targets = comicTargetsOf(record)
            if (targets.isEmpty()) comicsMissing += record else comicsFound += record to targets
        }
        return ImportPlan(file, found, missing, comicsFound, comicsMissing)
    }

    /** 합쳐 넣는다. 못 찾은 책은 기다리는 기록에 더한다. */
    suspend fun apply(plan: ImportPlan): ImportResult {
        // 도중에 멈추지 않는다. 합치기를 끝낸 뒤 취소가 오면 기다리는 기록을 쓰기 전에 끊겨, 못 찾은 책의 기록이 알림 없이
        // 사라졌다(가져오는 중 뒤로 가기).
        withContext(NonCancellable) {
            mergeFound(plan)
            mergeWorks(plan.file.works)
            pendingLock.withLock {
                val waiting = readPending()
                writePending((waiting.books + plan.missing).mergedByBook(), (waiting.comics + plan.comicsMissing).mergedByComic())
            }
        }
        return ImportResult(
            books = plan.found.size,
            bookmarks = plan.found.sumOf { it.first.bookmarks.size } + plan.comicsFound.sumOf { it.first.bookmarks.size },
            annotations = plan.found.sumOf { it.first.annotations.size },
            finished = plan.found.count { it.first.finishedAtEpochMs != null } + plan.comicsFound.count { it.first.finishedAtEpochMs != null },
            missing = plan.missing.map { it.displayName } + plan.comicsMissing.map { it.name },
            comics = plan.comicsFound.size,
        )
    }

    /** 기다리던 기록 가운데 이제 책이 보이는 것을 붙인다. 폴더를 훑은 뒤 부른다. 붙인 책 수. */
    suspend fun resumePending(): Int = pendingLock.withLock {
        val waiting = readPending()
        if (waiting.books.isEmpty() && waiting.comics.isEmpty()) return 0
        val plan = plan(waiting)
        if (plan.found.isEmpty() && plan.comicsFound.isEmpty()) return 0
        mergeFound(plan)
        writePending(plan.missing, plan.comicsMissing)
        plan.found.size + plan.comicsFound.size
    }

    suspend fun pending(): RecordsFile = pendingLock.withLock { readPending() }

    private suspend fun targetsOf(record: BookRecord): List<BookEntity> {
        val size = record.sizeBytes
        if (size != null) {
            val same = db.books().visibleByFile(record.displayName, size)
            if (same.isNotEmpty()) return same
            // 이 휴대폰의 제공자가 크기를 알려 주지 않는 책(일부 클라우드)은 크기로는 영영 맞지 않는다. 이름이 하나뿐이면 붙인다.
            return db.books().visibleByNameUnknownSize(record.displayName).takeIf { it.size == 1 }.orEmpty()
        }
        // 크기를 모르는 기록은 이름으로만 찾되, 같은 이름이 둘 이상이면 붙이지 않는다 — 다른 판에 붙을 수 있다.
        return db.books().visibleByName(record.displayName).takeIf { it.size == 1 }.orEmpty()
    }

    private suspend fun mergeFound(plan: ImportPlan) = db.withTransaction {
        for ((record, ids) in plan.found) for (id in ids) mergeInto(id, record)
        for ((record, ids) in plan.comicsFound) for (id in ids) mergeComicInto(id, record)
    }

    /** 책과 같은 찾기: 이름 + 크기, 크기를 모르면 이름이 하나뿐일 때만. 같은 권이 두 곳에 있으면 둘 다에 붙인다. */
    private suspend fun comicTargetsOf(record: ComicRecord): List<String> {
        val comics = db.comics()
        val size = record.sizeBytes
        val found = if (size != null) {
            comics.visibleByFile(record.name, size).ifEmpty { comics.visibleByName(record.name).filter { it.sizeBytes == null }.takeIf { it.size == 1 }.orEmpty() }
        } else {
            comics.visibleByName(record.name).takeIf { it.size == 1 }.orEmpty()
        }
        return found.map { it.id }
    }

    private suspend fun mergeComicInto(id: String, r: ComicRecord) {
        val comics = db.comics()
        val old = comics.progress(id)
        if (r.page != null) {
            val mine = old?.let { ComicRecord(r.name, r.sizeBytes, it.page, it.pageCount, it.offset, it.updatedAtEpochMs, it.finishedAtEpochMs) }
            if (mine == null || r.isFurtherThan(mine)) {
                comics.saveProgress(
                    ComicProgressEntity(
                        unitId = id,
                        page = r.page,
                        pageCount = r.pageCount ?: old?.pageCount ?: (r.page + 1),
                        updatedAtEpochMs = r.updatedAtEpochMs,
                        // 이 휴대폰에서 다 읽은 때가 있으면 그대로 둔다(끝낸 날이 백업의 날로 바뀌지 않게).
                        finishedAtEpochMs = old?.finishedAtEpochMs ?: r.finishedAtEpochMs,
                        offset = r.offset,
                    ),
                )
            } else if (old.finishedAtEpochMs == null && r.finishedAtEpochMs != null) {
                comics.saveProgress(old.copy(finishedAtEpochMs = r.finishedAtEpochMs))
            }
        }
        val marks = comics.allBookmarks().filter { it.unitId == id }.mapTo(HashSet()) { it.page }
        for (b in r.bookmarks) if (marks.add(b.page)) comics.addBookmark(ComicBookmarkEntity(id, b.page, b.createdAtEpochMs))
    }

    /** 작품 설정은 이 휴대폰에 정한 것이 없을 때만 채운다 — 가져오기가 지금 손으로 고친 것을 덮지 않게. */
    private suspend fun mergeWorks(works: List<WorkRecord>) {
        if (works.isEmpty()) return
        val comics = db.comics()
        val have = comics.allOverrides().filter { it.kind in WORK_KINDS }.mapTo(HashSet()) { it.kind to it.subject }
        for (w in works.mergedByWork()) {
            if (w.title != null && (TITLE to w.key) !in have) comics.setOverride(ComicOverrideEntity(TITLE, w.key, w.title))
            if (w.rightToLeft != null && (RTL to w.key) !in have) comics.setOverride(ComicOverrideEntity(RTL, w.key, if (w.rightToLeft) "1" else "0"))
            if (w.view != null && (VIEW to w.key) !in have) comics.setOverride(ComicOverrideEntity(VIEW, w.key, w.view))
        }
    }

    private suspend fun mergeInto(id: String, r: BookRecord) {
        val now = clock()
        r.progress?.let { p ->
            val old = db.progress().get(id)
            if (old == null || p.isFurtherThan(ProgressRecord(old.locator, old.percent, old.updatedAtEpochMs))) {
                db.progress().upsert(ProgressEntity(id, p.locator, p.percent, p.updatedAtEpochMs))
            }
        }
        if (r.openedAtEpochMs != null || r.finishedAtEpochMs != null) {
            db.recent().insertIfAbsent(id, r.openedAtEpochMs ?: now)
            r.openedAtEpochMs?.let { db.recent().touchIfLater(id, it) }
            // 이 휴대폰에 다 읽은 때가 있으면 그대로 둔다(끝낸 날이 백업의 날로 바뀌지 않게).
            r.finishedAtEpochMs?.let { db.recent().finishOnce(id, it) }
        }
        val marks = db.bookmarks().forBook(id).mapTo(HashSet()) { it.locator }
        for (b in r.bookmarks) {
            if (!marks.add(b.locator)) continue
            val order = LocatorOrder.of(Locator.decodeOrNull(b.locator) ?: continue)
            db.bookmarks().insert(BookmarkEntity(0, id, b.locator, order.major, order.minor, order.patch, b.snippet, b.createdAtEpochMs))
        }
        val notes = db.annotations().forBook(id).associateByTo(HashMap()) { "${it.start}-${it.end}" }
        for (a in r.annotations) {
            val old = notes[a.range]
            if (old != null) {
                // 같은 자리를 양쪽에서 칠했으면 이 휴대폰의 색을 두되, 이쪽에 없는 메모는 가져온다 — 메모를 잃지 않게.
                if (old.note == null && a.note != null) db.annotations().update(old.id, old.color, a.note)
                continue
            }
            val order = LocatorOrder.of(Locator.decodeOrNull(a.start) ?: continue)
            val entity = AnnotationEntity(0, id, a.start, a.end, order.major, order.minor, a.color, a.note, a.snippet, a.createdAtEpochMs)
            notes[a.range] = entity.copy(id = db.annotations().insert(entity))
        }
        // 제목을 아직 모르는 책(한 번도 열지 않음)은 백업의 제목을 쓴다. 목록이 파일 이름 대신 제목을 보인다.
        val book = db.books().get(id)
        if (book != null && book.title == null && r.title != null) db.books().updateMetadata(id, r.title, book.author ?: r.author)
    }

    private fun readPending(): RecordsFile {
        if (!pendingFile.exists()) return RecordsFile(0, emptyList())
        // 상한 기다리는 기록은 버리지 않고 비어 있는 것으로 본다. 다음 쓰기가 덮는다 — 가져오기를 막지 않게.
        val text = runCatching { AtomicFile(pendingFile).readFully().toString(Charsets.UTF_8) }.getOrNull()
        return text?.let(RecordsCodec::decode) ?: RecordsFile(0, emptyList())
    }

    private fun writePending(books: List<BookRecord>, comics: List<ComicRecord> = readPending().comics) {
        val file = AtomicFile(pendingFile)
        if (books.isEmpty() && comics.isEmpty()) {
            file.delete()
            return
        }
        pendingFile.parentFile?.mkdirs()
        // 쓰는 중에 앱이 죽어도 반쯤 쓴 파일이 남지 않게 AtomicFile 로 바꿔 끼운다.
        val out = file.startWrite()
        try {
            out.write(RecordsCodec.encode(RecordsFile(clock(), books, comics)).toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    private companion object {
        /** 책 수천 권의 기록도 몇 MB 다. 이보다 크면 백업이 아니다. */
        const val MAX_BYTES = 32 * 1024 * 1024
        // 만화 손 고침의 종류(ComicLibrary 와 같은 글). 작품 열쇠로 적는 것만 백업한다 — WORK_OF · PREFERRED 는 이 휴대폰의 문서
        // 주소로 적혀 새 휴대폰에서 맞지 않는다.
        const val TITLE = "TITLE"
        const val RTL = "RTL"
        const val VIEW = "VIEW"
        val WORK_KINDS = setOf(TITLE, RTL, VIEW)
    }
}
