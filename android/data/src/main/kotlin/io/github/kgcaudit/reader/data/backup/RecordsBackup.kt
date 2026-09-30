package io.github.kgcaudit.reader.data.backup

import android.util.AtomicFile
import androidx.room.withTransaction
import io.github.kgcaudit.reader.data.db.AnnotationEntity
import io.github.kgcaudit.reader.data.db.BookEntity
import io.github.kgcaudit.reader.data.db.BookmarkEntity
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

/** 앱 정보의 "책 42권 · 책갈피 18개 · 형광펜 · 메모 63개". */
data class RecordsSummary(val books: Int, val bookmarks: Int, val annotations: Int)

/** 가져오기 전에 보이는 것: 이 휴대폰에서 찾은 책과 못 찾은 책. */
class ImportPlan internal constructor(
    val file: RecordsFile,
    internal val found: List<Pair<BookRecord, List<String>>>,
    val missing: List<BookRecord>,
) {
    val foundBooks: Int get() = found.size
}

data class ImportResult(
    val books: Int,
    val bookmarks: Int,
    val annotations: Int,
    val finished: Int,
    /** 못 찾은 책의 파일 이름. 기억해 두었다가 책 폴더가 더해지면 이어 붙인다. */
    val missing: List<String>,
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
        return RecordsFile(clock(), (fromDb + pending().books).mergedByBook())
    }

    suspend fun summary(): RecordsSummary = collect().let { RecordsSummary(it.books.size, it.bookmarkCount, it.annotationCount) }

    suspend fun export(out: OutputStream): RecordsSummary {
        val file = collect()
        out.write(RecordsCodec.encode(file).toByteArray(Charsets.UTF_8))
        out.flush()
        return RecordsSummary(file.books.size, file.bookmarkCount, file.annotationCount)
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
        return ImportPlan(file, found, missing)
    }

    /** 합쳐 넣는다. 못 찾은 책은 기다리는 기록에 더한다. */
    suspend fun apply(plan: ImportPlan): ImportResult {
        // 도중에 멈추지 않는다. 합치기를 끝낸 뒤 취소가 오면 기다리는 기록을 쓰기 전에 끊겨, 못 찾은 책의 기록이 알림 없이
        // 사라졌다(가져오는 중 뒤로 가기).
        withContext(NonCancellable) {
            mergeFound(plan)
            pendingLock.withLock { writePending((readPending().books + plan.missing).mergedByBook()) }
        }
        return ImportResult(
            books = plan.found.size,
            bookmarks = plan.found.sumOf { it.first.bookmarks.size },
            annotations = plan.found.sumOf { it.first.annotations.size },
            finished = plan.found.count { it.first.finishedAtEpochMs != null },
            missing = plan.missing.map { it.displayName },
        )
    }

    /** 기다리던 기록 가운데 이제 책이 보이는 것을 붙인다. 폴더를 훑은 뒤 부른다. 붙인 책 수. */
    suspend fun resumePending(): Int = pendingLock.withLock {
        val waiting = readPending()
        if (waiting.books.isEmpty()) return 0
        val plan = plan(waiting)
        if (plan.found.isEmpty()) return 0
        mergeFound(plan)
        writePending(plan.missing)
        plan.found.size
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

    private fun writePending(books: List<BookRecord>) {
        val file = AtomicFile(pendingFile)
        if (books.isEmpty()) {
            file.delete()
            return
        }
        pendingFile.parentFile?.mkdirs()
        // 쓰는 중에 앱이 죽어도 반쯤 쓴 파일이 남지 않게 AtomicFile 로 바꿔 끼운다.
        val out = file.startWrite()
        try {
            out.write(RecordsCodec.encode(RecordsFile(clock(), books)).toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    private companion object {
        /** 책 수천 권의 기록도 몇 MB 다. 이보다 크면 백업이 아니다. */
        const val MAX_BYTES = 32 * 1024 * 1024
    }
}
