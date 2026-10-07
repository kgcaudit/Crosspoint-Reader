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
import io.github.kgcaudit.reader.data.library.ComicLibrary
import io.github.kgcaudit.reader.document.Locator
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * 앱 정보의 "책 42권 · 만화 7권 · 책갈피 18개 · 형광펜 · 메모 63개". 만화는 0.49.0 부터.
 *
 * [works] 는 백업에 들어가는 작품 설정(손으로 고친 이름 · 넘기는 방향 · 보는 방식) 수다. 이것만 가진 사람(만화 이름만 고쳐
 * 두고 아직 펼친 권이 없음)도 백업할 것이 있다 — 세지 않으면 백업 단추가 흐려 그 설정을 옮길 길이 없었다.
 */
data class RecordsSummary(val books: Int, val bookmarks: Int, val annotations: Int, val comics: Int = 0, val works: Int = 0)

private fun RecordsFile.summary() = RecordsSummary(books.size, bookmarkCount, annotationCount, comics.size, works.size)

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
/** [RecordsBackup.adoptMoved] 가 옮긴 (옛 id, 새 id). */
data class MovedRecords(val books: List<Pair<String, String>>, val comics: List<Pair<String, String>>) {
    val isEmpty: Boolean get() = books.isEmpty() && comics.isEmpty()
}

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
        val fromDb = db.books().all().mapNotNull { book -> recordOf(book, progress, recent, marks, notes) }
        // 기다리는 기록도 담는다. 빼면 "가져오기 → 못 찾음 → 다시 백업" 에서 그 책들의 기록이 조용히 사라진다.
        val waiting = pending()
        return RecordsFile(
            clock(),
            (fromDb + waiting.books).mergedByBook(),
            (collectComics() + waiting.comics).mergedByComic(),
            collectWorks(),
        )
    }

    /** 책 한 권의 기록. 아무 기록도 없으면 null. 백업과 옮긴 파일 잇기(0.50.0)가 같은 모양을 쓴다. */
    private fun recordOf(
        book: BookEntity,
        progress: Map<String, ProgressEntity>,
        recent: Map<String, io.github.kgcaudit.reader.data.db.RecentEntity>,
        marks: Map<String, List<BookmarkEntity>>,
        notes: Map<String, List<AnnotationEntity>>,
    ): BookRecord? {
        val id = book.id
        if (id !in progress && id !in recent && id !in marks && id !in notes) return null
        return BookRecord(
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

    /**
     * 작품마다 손으로 정한 이름 · 넘기는 방향 · 보는 방식(사용자 결정 1-가). 작품 열쇠는 이름에서 나와 새 휴대폰에서도 같다.
     *
     * 따로 뺀 작품의 열쇠([ComicLibrary.OWN_PREFIX] + 단위 id)는 담지 않는다 — 이 휴대폰의 문서 주소라 새 휴대폰에서 맞는
     * 작품이 없다. 담으면 백업에 쓸모없는 줄만 쌓이고, 가져온 쪽에도 붙을 데 없는 손 고침이 남는다.
     */
    private suspend fun collectWorks(): List<WorkRecord> {
        val rows = db.comics().allOverrides()
        val keys = rows.filter { it.kind in WORK_KINDS && !it.subject.startsWith(ComicLibrary.OWN_PREFIX) }.map { it.subject }.distinct()
        return keys.map { key ->
            fun value(kind: String) = rows.firstOrNull { it.kind == kind && it.subject == key }?.value
            WorkRecord(
                key = key,
                title = value(ComicLibrary.TITLE),
                rightToLeft = when (value(ComicLibrary.RTL)) { "1" -> true; "0" -> false; else -> null },
                view = value(ComicLibrary.VIEW),
            )
        }.filter { it.title != null || it.rightToLeft != null || it.view != null }
    }

    suspend fun summary(): RecordsSummary = collect().summary()

    suspend fun export(out: OutputStream): RecordsSummary {
        val file = collect()
        out.write(RecordsCodec.encode(file).toByteArray(Charsets.UTF_8))
        out.flush()
        return file.summary()
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

    /**
     * 옮긴 파일의 기록을 새 자리에 잇는다(0.50.0). 책 · 만화의 열쇠는 파일 주소라, 파일을 다른 폴더로 옮기거나 폴더 이름을
     * 바꾸면 주소가 달라져 진도 · 책갈피 · 형광펜이 숨은 옛 행에 남고 새 자리는 처음부터였다(사용자가 가장 아까워하는 것).
     *
     * 숨은(사라진) 행 가운데 기록이 있는 것을 보이는 행과 **이름 + 크기**로 맞춘다. 백업 가져오기와 같은 합치기를 쓴다 — 더
     * 나간 자리가 이기고 책갈피 · 형광펜은 합친다. 묻지 않고 옮기므로 백업보다 엄격하다(사용자 결정 1-나):
     * - 크기를 모르면 옮기지 않는다. 이름만 같은 다른 판에 붙으면 되돌릴 길이 없다. 그림 폴더는 크기 대신 쪽 수로 맞춘다.
     * - 옛 쪽이든 새 쪽이든 같은 이름 · 크기가 둘 이상이면 옮기지 않는다 — 어느 것이 어느 것인지 모른다.
     * 옛 행은 남기고 기록만 비운다. 파일이 옛 자리로 돌아오면 그 행이 다시 보이되 기록은 따라간 자리에 있다.
     *
     * @return 옮긴 (옛 id, 새 id). 책은 앱이 직접 고른 표지를 함께 옮기는 데 쓴다.
     */
    suspend fun adoptMoved(): MovedRecords = db.withTransaction {
        val progress = db.progress().all().associateBy { it.bookId }
        val recent = db.recent().all().associateBy { it.bookId }
        val marks = db.bookmarks().all().groupBy { it.bookId }
        val notes = db.annotations().all().groupBy { it.bookId }
        val all = db.books().all()
        val orphans = all.filter { it.missing && it.sizeBytes != null }
            .mapNotNull { book -> recordOf(book, progress, recent, marks, notes)?.let { book to it } }
        val orphanKeys = all.filter { it.missing && it.sizeBytes != null }.groupingBy { it.displayName to it.sizeBytes }.eachCount()
        val books = ArrayList<Pair<String, String>>()
        for ((old, record) in orphans) {
            if (orphanKeys[old.displayName to old.sizeBytes] != 1) continue
            val target = db.books().visibleByFile(old.displayName, old.sizeBytes!!).singleOrNull() ?: continue
            mergeInto(target.id, record)
            db.progress().delete(old.id)
            db.recent().delete(old.id)
            db.bookmarks().deleteForBook(old.id)
            db.annotations().deleteForBook(old.id)
            books += old.id to target.id
        }
        MovedRecords(books, adoptMovedComics())
    }

    private suspend fun adoptMovedComics(): List<Pair<String, String>> {
        val comics = db.comics()
        val progress = comics.allProgress().associateBy { it.unitId }
        val marks = comics.allBookmarks().groupBy { it.unitId }
        val withRecords = (progress.keys + marks.keys).toList().chunked(500).flatMap { comics.unitsOf(it) }
        val orphans = withRecords.filter { it.missing && matchKey(it) != null }
        if (orphans.isEmpty()) return emptyList()
        val orphanKeys = orphans.groupingBy { matchKey(it) }.eachCount()
        val moved = ArrayList<Pair<String, String>>()
        for (old in orphans) {
            val key = matchKey(old) ?: continue
            if (orphanKeys[key] != 1) continue
            val target = comics.visibleByName(old.name).filter { matchKey(it) == key }.singleOrNull() ?: continue
            val p = progress[old.id]
            mergeComicInto(
                target.id,
                ComicRecord(
                    name = old.name,
                    sizeBytes = old.sizeBytes,
                    page = p?.page,
                    pageCount = p?.pageCount ?: old.pageCount,
                    offset = p?.offset,
                    updatedAtEpochMs = p?.updatedAtEpochMs ?: 0L,
                    finishedAtEpochMs = p?.finishedAtEpochMs,
                    bookmarks = marks[old.id].orEmpty().map { ComicBookmarkRecord(it.page, it.createdAtEpochMs) },
                ),
            )
            comics.deleteProgress(old.id)
            comics.deleteBookmarks(old.id)
            moveOverrides(old.id, target.id)
            moved += old.id to target.id
        }
        return moved
    }

    /**
     * 만화 권을 맞추는 열쇠: 압축은 (이름, 크기), 그림 폴더는 크기가 없어 (이름, 쪽 수). 모르면 null — 옮기지 않는다.
     * 종류도 열쇠에 넣는다: 같은 이름의 폴더와 압축은 다른 권이다.
     */
    private fun matchKey(unit: io.github.kgcaudit.reader.data.db.ComicUnitEntity): Triple<String, String, Long>? = when {
        unit.sizeBytes != null -> Triple(unit.kind, unit.name, unit.sizeBytes)
        unit.kind == io.github.kgcaudit.reader.document.comic.ComicUnitKind.IMAGE_FOLDER.name && unit.pageCount != null ->
            Triple(unit.kind, unit.name, -unit.pageCount.toLong() - 1)
        else -> null
    }

    /**
     * 단위 id 에 걸린 손 고침을 새 id 로: 작품에서 뺀 권(`WORK_OF` 주어 · [ComicLibrary.OWN_PREFIX] 작품 열쇠)과 같은 권 여러
     * 사본 중 고른 것(`PREFERRED` 값). 이름에서 나온 작품 열쇠는 옮길 것이 없다. 새 id 에 이미 같은 손 고침이 있으면 그것을 둔다.
     */
    private suspend fun moveOverrides(oldId: String, newId: String) {
        val comics = db.comics()
        val rows = comics.allOverrides()
        val have = rows.mapTo(HashSet()) { it.kind to it.subject }
        val oldOwn = ComicLibrary.OWN_PREFIX + oldId
        val newOwn = ComicLibrary.OWN_PREFIX + newId
        for (r in rows) {
            val subject = when (r.subject) {
                oldId -> newId
                oldOwn -> newOwn
                else -> r.subject
            }
            val value = when (r.value) {
                oldId -> newId
                oldOwn -> newOwn
                else -> r.value
            }
            if (subject == r.subject && value == r.value) continue
            if (subject != r.subject) {
                comics.clearOverride(r.kind, r.subject)
                if ((r.kind to subject) in have) continue
            }
            comics.setOverride(ComicOverrideEntity(r.kind, subject, value))
        }
    }

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
        val marks = comics.bookmarksOf(id).mapTo(HashSet()) { it.page }
        for (b in r.bookmarks) if (marks.add(b.page)) comics.addBookmark(ComicBookmarkEntity(id, b.page, b.createdAtEpochMs))
    }

    /** 작품 설정은 이 휴대폰에 정한 것이 없을 때만 채운다 — 가져오기가 지금 손으로 고친 것을 덮지 않게. */
    private suspend fun mergeWorks(works: List<WorkRecord>) {
        if (works.isEmpty()) return
        val comics = db.comics()
        val have = comics.allOverrides().filter { it.kind in WORK_KINDS }.mapTo(HashSet()) { it.kind to it.subject }
        for (w in works.mergedByWork()) {
            if (w.title != null && (ComicLibrary.TITLE to w.key) !in have) comics.setOverride(ComicOverrideEntity(ComicLibrary.TITLE, w.key, w.title))
            if (w.rightToLeft != null && (ComicLibrary.RTL to w.key) !in have) {
                comics.setOverride(ComicOverrideEntity(ComicLibrary.RTL, w.key, if (w.rightToLeft) "1" else "0"))
            }
            if (w.view != null && (ComicLibrary.VIEW to w.key) !in have) comics.setOverride(ComicOverrideEntity(ComicLibrary.VIEW, w.key, w.view))
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

    private fun writePending(books: List<BookRecord>, comics: List<ComicRecord>) {
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
        // 만화 손 고침 가운데 작품 열쇠로 적는 것만 백업한다 — WORK_OF · PREFERRED 는 이 휴대폰의 문서 주소로 적혀 새 휴대폰에서
        // 맞지 않는다.
        val WORK_KINDS = setOf(ComicLibrary.TITLE, ComicLibrary.RTL, ComicLibrary.VIEW)
    }
}
