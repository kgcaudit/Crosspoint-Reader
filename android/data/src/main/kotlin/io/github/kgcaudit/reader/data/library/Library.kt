package io.github.kgcaudit.reader.data.library

import androidx.room.withTransaction
import io.github.kgcaudit.reader.data.db.BookEntity
import io.github.kgcaudit.reader.data.db.ComicBookmarkEntity
import io.github.kgcaudit.reader.data.db.ComicProgressEntity
import io.github.kgcaudit.reader.data.db.ComicOverrideEntity
import io.github.kgcaudit.reader.data.db.ComicUnitEntity
import io.github.kgcaudit.reader.data.db.ReaderDatabase
import io.github.kgcaudit.reader.document.BookFormat
import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.comic.ComicContents
import io.github.kgcaudit.reader.document.comic.ComicInfo
import io.github.kgcaudit.reader.document.comic.ComicOverrides
import io.github.kgcaudit.reader.document.comic.ComicProgress
import io.github.kgcaudit.reader.document.comic.ComicShelf
import io.github.kgcaudit.reader.document.comic.ComicUnit
import io.github.kgcaudit.reader.document.comic.ComicUnitKind
import io.github.kgcaudit.reader.document.comic.ComicView
import io.github.kgcaudit.reader.document.comic.ShelfMark
import io.github.kgcaudit.reader.document.comic.Work
import io.github.kgcaudit.reader.document.comic.WorkEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** 책장의 책. [finishedAtEpochMs] 가 있으면 다 읽은 책. */
data class ShelfBook(val book: LibraryBook, val finishedAtEpochMs: Long?)

/** 책 한 권의 독서노트 수. 메모는 칠 가운데 메모가 달린 것이다. */
data class NoteCounts(val bookmarks: Int = 0, val highlights: Int = 0, val memos: Int = 0)

/** 라이브러리 목록 한 줄. */
data class LibraryBook(
    val id: BookId,
    val format: BookFormat,
    val displayName: String,
    /** 책을 한 번 열어 메타데이터를 읽기 전에는 null. */
    val title: String?,
    val author: String?,
    val sizeBytes: Long?,
    /** 라이브러리에 처음 들어온 때(읽을 책 목록의 "9월 25일 추가" · 추가한 순). */
    val addedAtEpochMs: Long? = null,
) {
    /** 목록에 보일 이름. 제목을 모르면 파일 이름이다. */
    val label: String get() = title?.takeIf { it.isNotBlank() } ?: displayName
}

/**
 * 라이브러리(등록 폴더에서 찾은 책들)와 책장(열어 본 책).
 *
 * 스캔 결과를 반영할 때 **지우지 않는다.** 스캔이 책을 놓치는 일은 흔하다(SD 카드가
 * 빠짐, 클라우드 제공자가 잠시 응답 없음, 권한 일시 회수). 그때 행을 지우면 진도·책갈피를
 * 찾을 열쇠가 사라진다. 대신 숨겨 두었다가 다시 보이면 그대로 되살린다.
 */
class Library(private val db: ReaderDatabase) {

    private val books = db.books()
    private val recent = db.recent()

    fun books(): Flow<List<LibraryBook>> = books.observeVisible().map { rows -> rows.mapNotNull(::toBook) }

    suspend fun get(id: BookId): LibraryBook? = books.get(id.value)?.let(::toBook)

    /**
     * 다른 앱이 넘긴 파일과 같은 책이 라이브러리에 있는가. 이름과 크기가 모두 같아야 한다.
     *
     * 파일 관리자가 넘기는 URI 는 라이브러리의 SAF URI 와 모양이 달라 id 로는 못 찾는다. 같은 책을
     * 다른 id 로 열면 진도·책갈피가 두 벌로 갈라진다. 크기를 모르면 찾지 않는다 — 이름만 같은
     * 다른 판을 열 수 있다.
     */
    suspend fun findByFile(displayName: String, sizeBytes: Long?): LibraryBook? =
        sizeBytes?.let { books.byFile(displayName, it) }?.let(::toBook)

    /**
     * 책마다 읽은 정도(0~100). 목록 오른쪽에 보여 준다.
     *
     * 표시용이라 저장된 값을 범위 안으로만 자른다. 위치를 복원하는 데는 쓰지 않는다.
     */
    fun percents(): Flow<Map<BookId, Float>> = db.progress().observeAll().map { rows ->
        rows.associate { BookId(it.bookId) to it.percent.coerceIn(0f, 100f) }
    }

    /**
     * 책장: 열어 본 책 모두, 최근에 연 차례. [ShelfBook.finishedAtEpochMs] 가 있으면 "다 읽은 책" 줄에 간다. 몇 권으로
     * 자르지 않는다 — 세 권만 보이던 때(0.22.x)는 네 번째로 연 책을 다시 찾으려면 모든 책 목록을 뒤져야 했다.
     */
    fun shelf(): Flow<List<ShelfBook>> = recent.observeShelf().map { rows ->
        rows.mapNotNull { row -> toBook(row.book)?.let { ShelfBook(it, row.finishedAtEpochMs) } }
    }

    /** 책장에서 빼 읽을 책으로 되돌린다. 한 번 열어 본 책이 0% 로 "읽는 중" 에 남지 않게. 진도 · 책갈피는 남는다. */
    suspend fun returnToUnread(id: BookId) = recent.delete(id.value)

    /** 책마다 독서노트 수. 없는 책은 빠진다. */
    fun noteCounts(): Flow<Map<BookId, NoteCounts>> =
        combine(db.bookmarks().observeCounts(), db.annotations().observeCounts()) { marks, notes ->
            val out = HashMap<BookId, NoteCounts>()
            marks.forEach { out[BookId(it.bookId)] = NoteCounts(bookmarks = it.count) }
            notes.forEach { n ->
                val id = BookId(n.bookId)
                out[id] = (out[id] ?: NoteCounts()).copy(highlights = n.count, memos = n.memos)
            }
            out
        }

    /** 책을 열었다. 최근 목록의 맨 앞으로 온다. 다 읽은 표시는 그대로 둔다. */
    suspend fun markOpened(id: BookId, nowEpochMs: Long) = recent.opened(id.value, nowEpochMs)

    /**
     * 다 읽음을 손으로 표시하거나(시각) 푼다(null). 한 번도 열지 않은 책도 표시할 수 있다 — 종이책으로 읽은 책을
     * 정리하는 사람이 있다. 그때는 책장에 새로 올린다.
     */
    suspend fun setFinished(id: BookId, finishedAtEpochMs: Long?, nowEpochMs: Long) = db.withTransaction {
        if (finishedAtEpochMs != null) recent.insertIfAbsent(id.value, nowEpochMs)
        recent.setFinished(id.value, finishedAtEpochMs)
    }

    /** 책을 처음 열어 읽은 제목·저자를 기록한다. 다음부터 목록이 파일 이름 대신 보여 준다. */
    suspend fun updateMetadata(id: BookId, title: String?, author: String?) =
        books.updateMetadata(id.value, title?.takeIf { it.isNotBlank() }, author?.takeIf { it.isNotBlank() })

    /**
     * 한 폴더의 스캔 결과를 반영한다.
     *
     * - 찾은 책은 넣거나 갱신하고, 숨겨져 있었으면 되살린다.
     * - 파일이 바뀌었으면(크기·수정 시각) 읽어 둔 제목을 버린다. 같은 이름으로 다른 판을
     *   덮어쓴 경우 옛 제목이 계속 보이면 안 된다.
     * - [ScanResult.complete] 일 때만, 이번에 안 보인 책을 숨긴다.
     */
    suspend fun applyScan(folderUri: String, result: ScanResult, nowEpochMs: Long) = db.withTransaction {
        val existing = books.inFolder(folderUri).associateBy { it.id }

        books.upsert(
            result.books.map { found ->
                val old = existing[found.uri]
                val changed = old != null &&
                    (old.sizeBytes != found.sizeBytes || old.lastModifiedEpochMs != found.lastModifiedEpochMs)
                BookEntity(
                    id = found.uri,
                    folderUri = folderUri,
                    displayName = found.displayName,
                    format = found.format.name,
                    sizeBytes = found.sizeBytes,
                    lastModifiedEpochMs = found.lastModifiedEpochMs,
                    title = if (changed) null else old?.title,
                    author = if (changed) null else old?.author,
                    addedAtEpochMs = old?.addedAtEpochMs ?: nowEpochMs,
                    missing = false,
                )
            },
        )

        if (result.complete) {
            val seen = result.books.mapTo(HashSet()) { it.uri }
            val gone = existing.values.filter { !it.missing && it.id !in seen }.map { it.id }
            // SQLite 의 변수 개수 제한(999)에 걸리지 않게 나눠 보낸다.
            gone.chunked(500).forEach { books.markMissing(it) }
        }
    }

    /**
     * 등록을 푼 폴더의 책을 목록에서 뺀다.
     *
     * 진도·책갈피는 남긴다. 같은 폴더를 다시 등록하면 URI 가 같으므로 그대로 이어진다.
     *
     * 책 행은 지우지 않고 숨긴다(0.28.1). 지우던 때는 그 책들의 기록이 파일 이름을 잃어 백업 파일에서 소리 없이 빠졌다 —
     * 팝업은 "기록은 남습니다" 라고 약속하는데 새 휴대폰으로는 옮겨지지 않았다.
     */
    suspend fun forgetFolder(folderUri: String) = books.hideFolder(folderUri)

    /** 모르는 포맷 이름(다음 버전이 쓴 값 등)이 든 행은 목록에서만 빠진다. */
    private fun toBook(row: BookEntity): LibraryBook? {
        val format = BookFormat.entries.firstOrNull { it.name == row.format } ?: return null
        return LibraryBook(
            id = BookId(row.id),
            format = format,
            displayName = row.displayName,
            title = row.title,
            author = row.author,
            sizeBytes = row.sizeBytes,
            addedAtEpochMs = row.addedAtEpochMs,
        )
    }
}

/**
 * 만화 서재(0.33.0): 훑은 만화 단위 + 손 고침 → 작품들([ComicShelf.group]). 책 [Library] 와 같은 DB 를 쓰되 표가 다르다.
 *
 * 작품은 저장하지 않고 **매번 계산한다** — 저장하면 훑기 · 손 고침 · 살피기 결과가 바뀔 때마다 작품 표도 맞춰 고쳐야 하고,
 * 한 군데를 놓치면 옛 묶음이 남는다. 단위 수천 개도 묶기는 순수 계산이라 한 번에 끝난다.
 */
class ComicLibrary(private val db: ReaderDatabase) {

    private val comics = db.comics()

    /** 서재에 보일 만화 단위들(순수 모형). */
    fun units(): Flow<List<ComicUnit>> = comics.observeVisible().map { rows -> rows.map(::toUnit) }

    fun overrides(): Flow<ComicOverrides> = comics.observeOverrides().map(::toOverrides)

    /** 작품들. 훑기 · 살피기 · 손 고침이 바뀌면 다시 계산된다. */
    fun works(): Flow<List<Work>> = combine(units(), overrides()) { units, overrides -> ComicShelf.group(units, overrides) }

    /**
     * 한 폴더의 훑기 결과 중 만화를 반영한다. 책과 같은 규칙: 지우지 않고 숨기며, 끝까지 읽은 훑기만 숨긴다. 파일이 바뀌면
     * (크기 · 수정 시각) 살핀 결과는 그대로 두되 다시 살필 차례가 된다([ComicDao.needingProbe]).
     */
    suspend fun applyScan(folderUri: String, result: ScanResult, nowEpochMs: Long) = db.withTransaction {
        val existing = comics.inFolder(folderUri).associateBy { it.id }
        comics.upsert(
            result.comics.map { found ->
                val old = existing[found.uri]
                val folderPages = found.folderContents
                ComicUnitEntity(
                    id = found.uri,
                    folderUri = folderUri,
                    name = found.name,
                    kind = found.kind.name,
                    extension = found.extension,
                    folders = found.folders.joinToString(ComicUnitEntity.FOLDER_SEPARATOR),
                    sizeBytes = found.sizeBytes,
                    lastModifiedEpochMs = found.lastModifiedEpochMs,
                    addedAtEpochMs = old?.addedAtEpochMs ?: nowEpochMs,
                    missing = false,
                    probed = old?.probed ?: false,
                    probedSize = old?.probedSize,
                    probedModified = old?.probedModified,
                    notComic = old?.notComic ?: false,
                    // 그림 폴더는 훑으며 이미 쪽을 셌다. 압축은 살핀 값을 이어 쓴다.
                    pageCount = folderPages?.pages?.size ?: old?.pageCount,
                    coverEntry = folderPages?.cover ?: old?.coverEntry,
                    sections = old?.sections,
                    infoSeries = old?.infoSeries,
                    infoNumber = old?.infoNumber,
                    infoFormat = old?.infoFormat,
                    infoRightToLeft = old?.infoRightToLeft,
                )
            },
        )
        if (result.complete) {
            val seen = result.comics.mapTo(HashSet()) { it.uri }
            existing.values.filter { !it.missing && it.id !in seen }.map { it.id }.chunked(500).forEach { comics.markMissing(it) }
        }
    }

    /** 등록을 푼 폴더의 만화를 숨긴다. 손 고침은 남긴다 — 다시 등록하면 같은 URI 라 그대로 이어진다. */
    suspend fun forgetFolder(folderUri: String) = comics.hideFolder(folderUri)

    /** 단위 하나(뷰어가 열 때). 숨긴 것도 준다 — 읽던 권을 되살릴 때 서재 훑기 사이에 잠깐 숨어 있을 수 있다. */
    suspend fun unit(id: String): ComicUnit? = comics.get(id)?.let(::toUnit)

    /** 살필 차례인 압축(zip · cbz). */
    suspend fun needingProbe(): List<ComicUnitEntity> = comics.needingProbe()

    /**
     * 살핀 결과를 적는다. [contents] 가 null 이면 만화가 아니다(그냥 zip) — 단, cbz 처럼 이름이 만화라고 말하는 것은 열지
     * 못했어도 숨기지 않는다: 깨진 압축 하나가 서재에서 사라지면 무엇이 깨졌는지조차 알 수 없다(규칙 6).
     */
    suspend fun saveProbe(unit: ComicUnitEntity, contents: ComicContents?, info: ComicInfo?) {
        val trusted = unit.extension in LibraryScanner.COMIC_ARCHIVES
        comics.saveProbe(
            id = unit.id,
            size = unit.sizeBytes,
            modified = unit.lastModifiedEpochMs,
            notComic = contents == null && !trusted,
            pages = contents?.pages?.size,
            cover = contents?.cover,
            // 이름과 쪽 수를 함께 적는다 — 작품 화면이 합본 안의 권마다 "181–360쪽" 처럼 범위를 보인다.
            sections = contents?.sections?.takeIf { it.isNotEmpty() }
                ?.joinToString(ComicUnitEntity.FOLDER_SEPARATOR) { it.name + ComicUnitEntity.COUNT_SEPARATOR + it.pageCount },
            series = info?.series,
            number = info?.number,
            format = info?.format,
            rightToLeft = info?.rightToLeft,
        )
    }

    // ── 손 고침 ─────────────────────────────────────────────

    /**
     * 작품 [from] 의 모든 단위를 작품 [into] 로 합친다. 합친 작품은 [into] 의 이름으로 보인다 — 이름을 적어 두지 않으면
     * 가장 많이 나온 이름이 이기는데, "One Piece" 1권과 "원피스" 1권처럼 동률이면 자연 순서로 엉뚱한 쪽 이름이 됐다.
     */
    suspend fun merge(from: Work, into: Work) = db.withTransaction {
        for (entry in from.entries) for (unit in listOf(entry.unit) + entry.copies) {
            comics.setOverride(ComicOverrideEntity(WORK_OF, unit.id, into.key))
        }
        comics.setOverride(ComicOverrideEntity(TITLE, into.key, into.title))
    }

    /** 단위 하나(와 그 같은 권 사본들)를 작품에서 빼 따로 둔다. 빼낸 것은 제 이름으로 새 작품이 된다. */
    suspend fun split(entry: WorkEntry) = db.withTransaction {
        val own = "$OWN_PREFIX${entry.unit.id}"
        for (unit in listOf(entry.unit) + entry.copies) comics.setOverride(ComicOverrideEntity(WORK_OF, unit.id, own))
        comics.setOverride(ComicOverrideEntity(TITLE, own, entry.name.series.ifBlank { entry.name.cleaned }))
    }

    /** 작품 이름을 고친다. 빈 이름이면 고침을 지워 원래 이름으로 돌아간다. 파일 이름은 바꾸지 않는다. */
    suspend fun rename(work: Work, title: String) {
        val t = title.trim()
        if (t.isEmpty()) comics.clearOverride(TITLE, work.key) else comics.setOverride(ComicOverrideEntity(TITLE, work.key, t))
    }

    /** 작품의 넘기는 방향을 고른다. 작품 열쇠에 적어 같은 작품의 다른 권도 같은 방향으로 열린다(사용자 결정 3). */
    suspend fun setRightToLeft(work: Work, rightToLeft: Boolean) =
        comics.setOverride(ComicOverrideEntity(RTL, work.key, if (rightToLeft) "1" else "0"))

    /** 작품의 보는 방식을 고른다. null 이면 고른 것을 지워 자동(ComicInfo · 쪽 모양)으로 돌아간다. */
    suspend fun setView(work: Work, view: ComicView?) =
        if (view == null) comics.clearOverride(VIEW, work.key) else comics.setOverride(ComicOverrideEntity(VIEW, work.key, view.name))

    /** 작품을 다른 갈래로 옮긴다(길게 눌러). null 이면 표시를 지워 권 진도의 집계로 돌아간다. */
    suspend fun setShelf(work: Work, mark: ShelfMark?) =
        if (mark == null) comics.clearOverride(SHELF, work.key) else comics.setOverride(ComicOverrideEntity(SHELF, work.key, ShelfMark.format(mark)))

    // ── 읽기 ────────────────────────────────────────────────

    /** 권마다의 읽은 자리. 열쇠는 단위 id. */
    fun progress(): Flow<Map<String, ComicProgress>> = comics.observeProgress().map { rows ->
        rows.associate { it.unitId to ComicProgress(it.page, it.pageCount, it.updatedAtEpochMs, it.finishedAtEpochMs, it.offset) }
    }

    suspend fun progressOf(unitId: String): ComicProgress? =
        comics.progress(unitId)?.let { ComicProgress(it.page, it.pageCount, it.updatedAtEpochMs, it.finishedAtEpochMs, it.offset) }

    /**
     * 읽은 자리를 적는다. 마지막 쪽에 닿으면 다 읽은 것으로 — 한 번 다 읽은 권은 앞 쪽을 다시 들춰 봐도 "다 읽음" 이 남는다
     * (다 읽은 때를 지우면 서재의 "다 읽음" 이 들춰 볼 때마다 깜빡였다).
     */
    suspend fun saveProgress(
        unitId: String,
        page: Int,
        pageCount: Int,
        nowEpochMs: Long,
        /** 웹툰: 그림 안의 비율. 쪽 넘김이면 null. */
        offset: Float? = null,
        /**
         * 끝에 닿았는가. 쪽 넘김은 마지막 쪽이면 끝이다. 웹툰은 마지막 그림이 화면에 들어온 것만으로는 아니다 — 긴 그림의
         * 머리만 보였을 수 있어, 화면이 더 내려가지 않을 때 부르는 쪽이 알려 준다.
         */
        atEnd: Boolean = pageCount > 0 && page >= pageCount - 1,
    ) {
        val old = comics.progress(unitId)
        val finished = old?.finishedAtEpochMs ?: if (atEnd) nowEpochMs else null
        comics.saveProgress(ComicProgressEntity(unitId, page.coerceAtLeast(0), pageCount, nowEpochMs, finished, offset?.coerceIn(0f, 1f)))
    }

    fun bookmarks(unitId: String): Flow<List<Int>> = comics.observeBookmarks(unitId).map { rows -> rows.map { it.page } }

    /** 책갈피를 꽂거나 뺀다. 꽂았으면 true. */
    suspend fun toggleBookmark(unitId: String, page: Int, nowEpochMs: Long): Boolean = db.withTransaction {
        if (comics.removeBookmark(unitId, page) > 0) false else {
            comics.addBookmark(ComicBookmarkEntity(unitId, page, nowEpochMs))
            true
        }
    }

    /** 같은 권 여러 곳 중 읽을 것을 고른다. */
    suspend fun prefer(entry: WorkEntry, unitId: String) = comics.setOverride(ComicOverrideEntity(PREFERRED, entry.slot, unitId))

    private fun toOverrides(rows: List<ComicOverrideEntity>): ComicOverrides = ComicOverrides(
        workOf = rows.filter { it.kind == WORK_OF }.associate { it.subject to it.value },
        titles = rows.filter { it.kind == TITLE }.associate { it.subject to it.value },
        preferred = rows.filter { it.kind == PREFERRED }.associate { it.subject to it.value },
        // "1"/"0" 밖의 값(손상)은 버린다 — "정하지 않음" 으로 남아 ComicInfo 와 기본값을 따른다(규칙 5 · 6).
        view = rows.filter { it.kind == VIEW }.mapNotNull { r -> ComicView.entries.firstOrNull { it.name == r.value }?.let { r.subject to it } }.toMap(),
        shelf = rows.filter { it.kind == SHELF }.mapNotNull { r -> ShelfMark.parse(r.value)?.let { r.subject to it } }.toMap(),
        rightToLeft = rows.filter { it.kind == RTL }.mapNotNull { r -> when (r.value) { "1" -> r.subject to true; "0" -> r.subject to false; else -> null } }.toMap(),
    )

    private fun toUnit(row: ComicUnitEntity): ComicUnit {
        val sections = parseSections(row.sections)
        val info = ComicInfo(series = row.infoSeries, number = row.infoNumber, format = row.infoFormat, rightToLeft = row.infoRightToLeft)
            .takeIf { it != ComicInfo() }
        return ComicUnit(
            id = row.id,
            name = row.name,
            folders = row.folders.split(ComicUnitEntity.FOLDER_SEPARATOR).filter { it.isNotEmpty() },
            kind = runCatching { ComicUnitKind.valueOf(row.kind) }.getOrDefault(ComicUnitKind.ARCHIVE),
            sizeBytes = row.sizeBytes,
            info = info,
            // 서재는 쪽 이름이 아니라 합본 목차 · 쪽 수만 쓴다. 쪽 이름은 뷰어가 열 때 다시 읽는다.
            contents = if (sections.size >= 2 || row.pageCount != null || row.coverEntry != null) {
                ComicContents(emptyList(), sections.takeIf { it.size >= 2 }.orEmpty(), null, row.coverEntry)
            } else null,
            pageCount = row.pageCount,
            addedAtEpochMs = row.addedAtEpochMs,
        )
    }

    /** "4권␞180␟5권␞180" → 목차. 쪽 수가 빠지거나 깨진 칸은 0 쪽으로 읽는다 — 목차 이름은 그래도 보인다(규칙 6). */
    private fun parseSections(raw: String?): List<ComicContents.Section> {
        if (raw.isNullOrEmpty()) return emptyList()
        var first = 0
        return raw.split(ComicUnitEntity.FOLDER_SEPARATOR).map { part ->
            val count = part.substringAfterLast(ComicUnitEntity.COUNT_SEPARATOR, "").toIntOrNull()?.coerceAtLeast(0) ?: 0
            ComicContents.Section(part.substringBeforeLast(ComicUnitEntity.COUNT_SEPARATOR), first, count).also { first += count }
        }
    }

    companion object {
        const val WORK_OF: String = "WORK_OF"
        const val TITLE: String = "TITLE"
        const val PREFERRED: String = "PREFERRED"
        const val RTL: String = "RTL"
        const val VIEW: String = "VIEW"
        const val SHELF: String = "SHELF"
        /** 빼낸 단위의 작품 열쇠 머리. 이름 열쇠(글자 · 숫자뿐)와 겹치지 않게 기호를 넣는다. */
        const val OWN_PREFIX: String = "#own:"
    }
}
