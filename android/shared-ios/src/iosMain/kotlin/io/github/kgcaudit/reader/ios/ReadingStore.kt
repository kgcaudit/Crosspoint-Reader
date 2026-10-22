@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.document.Annotation
import io.github.kgcaudit.reader.document.AnnotationRepository
import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.Bookmark
import io.github.kgcaudit.reader.document.HighlightColor
import io.github.kgcaudit.reader.document.BookmarkRepository
import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.document.ProgressRepository
import io.github.kgcaudit.reader.document.ReadingProgress
import io.github.kgcaudit.reader.document.comic.ComicOverrides
import io.github.kgcaudit.reader.document.comic.ComicProgress
import io.github.kgcaudit.reader.document.comic.ComicView
import io.github.kgcaudit.reader.document.comic.ShelfMark
import io.github.kgcaudit.reader.document.comic.SpreadSplit
import io.github.kgcaudit.reader.document.comic.Work
import io.github.kgcaudit.reader.document.comic.WorkEntry
import io.github.kgcaudit.reader.document.io.currentTimeMillis
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * 옮긴 파일 짝짓기(안드로이드 0.50.0 `adoptMoved`)에 쓰는 파일의 됨됨이. [kind] 는 책이면 "BOOK", 만화 권이면 `ComicUnitKind` 이름 —
 * 책과 만화, 압축과 폴더는 서로 짝짓지 않는다. [size] 는 파일 크기, 그림 폴더는 크기가 없어 [pages](그림 수)로 가린다.
 */
data class KnownFile(val id: String, val kind: String, val name: String, val size: Long?, val pages: Int?) {
    companion object {
        const val BOOK = "BOOK"
    }
}

/** 기록을 옮긴 짝(옛 id → 새 id). [comic] 이면 만화 권 — 고른 표지 열쇠가 다르다. */
data class MovedPair(val from: String, val to: String, val comic: Boolean)

/** 서재의 한 줄에 필요한 읽기 기록. [openedAtEpochMs] 가 null 이면 아직 열지 않은 책(읽을 책). */
data class ShelfRecord(
    val bookId: String,
    val openedAtEpochMs: Long?,
    val finishedAtEpochMs: Long?,
    val percent: Float?,
    val bookmarkCount: Int,
)

/**
 * 읽은 시간 한 줄(구상안 30, 안드로이드 `reading_time` 표): 책 · 날(epoch day, 그 기기의 날짜) · 방식마다 하나. [mode] 는
 * "READ" · "LISTEN" · "AUTO" — 모르는 방식도 버리지 않고 남긴다(새 판이 더한 방식을 옛 판이 지우면 안 된다), 셈에서만 뺀다.
 */
data class TimeRecord(val bookId: String, val day: Long, val mode: String, val millis: Long)

/**
 * 읽은 자리 · 책갈피 · 연 때 · 다 읽은 때를 한 파일에 둔다(iOS). 안드로이드는 Room 의 progress · bookmarks · recent 표다 —
 * 칸은 같게 두어 나중에 기록 백업 형식(두 앱이 서로 옮길 수 있어야 한다)을 맞추기 쉽게 한다.
 *
 * 왜 데이터베이스가 아니라 글 파일인가: 한 사람의 책은 수백 권, 줄은 수천 개라 통째로 읽고 쓰는 값이 무시할 만하고,
 * 의존을 더하지 않는다. 바꿀 때마다 통째로 바꿔 넣으므로(atomically) 쓰다 죽어도 반쯤 쓴 파일이 남지 않는다.
 *
 * 줄 형식(탭으로 나눔, 값 안의 탭 · 줄바꿈 · % 는 %XX):
 * - `P bookId locator percent updatedAt`
 * - `B id bookId locator createdAt snippet`
 * - `R bookId openedAt finishedAt|-`
 * - `A id bookId start end color createdAt snippet note` — note 칸이 없으면 메모 없음. 빈 메모는 만들지 않는다(공통 `withNote`)
 * - `T bookId day mode millis` — 읽은 시간(책 · 날 · 방식마다 한 줄)
 * - `C unitId page pageCount updatedAt finishedAt|- offset|- half|-` — 만화 권의 읽은 쪽(안드로이드 `comic_progress`). offset ·
 *   half 는 안드로이드 백업에서 온 웹툰 자리 · 펼침면 반으로, iOS 1단계는 쓰지 않고 간직만 한다(옛 줄에는 없다)
 * - `K unitId page createdAt` — 만화 책갈피(쪽 단위, 발췌 글이 없다)
 * - `F id kind name size|- pages|-` — 기록이 있는 파일의 이름 · 크기(옮긴 파일 짝짓기). 파일이 사라져도 무엇이었는지 알아야 짝을 찾는다
 * - `O kind subject value` — 만화 작품 설정(안드로이드 `comic_overrides`). iOS 가 쓰는 것은 RTL · COVER · SHELF · TITLE, 백업에서 온
 *   VIEW · SPLIT 은 간직했다가 다시 백업에 담는다
 *
 * 여러 스레드(조판 · 서재)에서 함께 부르므로 잠근다.
 */
class ReadingStore(private val path: String) : BookmarkRepository, ProgressRepository {
    private val lock = NSRecursiveLock()
    private val progress = LinkedHashMap<String, ReadingProgress>()
    private val bookmarks = ArrayList<Bookmark>()
    private val opened = LinkedHashMap<String, Long>()
    private val finished = LinkedHashMap<String, Long>()
    private var nextBookmarkId = 1L
    private val notes = ArrayList<Annotation>()
    private var nextNoteId = 1L
    private val times = LinkedHashMap<Triple<String, Long, String>, Long>()
    private val comics = LinkedHashMap<String, ComicProgress>()
    private val comicMarks = ArrayList<Triple<String, Int, Long>>()
    private val comicSettings = LinkedHashMap<Pair<String, String>, String>()
    private val known = LinkedHashMap<String, KnownFile>()

    /**
     * 형광펜 보관소. 함수 이름(forBook · add · remove)이 책갈피 규약과 같아 한 클래스에 함께 둘 수 없다 — 같은 파일 · 같은
     * 잠금을 쓰는 안쪽 객체로 둔다. 따로 파일을 두면 백업 · 옮기기가 파일 둘을 맞춰야 한다.
     */
    val annotations: AnnotationRepository = object : AnnotationRepository {
        override suspend fun forBook(bookId: BookId): List<Annotation> = locked {
            notes.filter { it.bookId == bookId }.sortedWith(compareBy({ order(it.start) }, { it.id }))
        }

        override suspend fun add(annotation: Annotation): Annotation = locked {
            val saved = annotation.copy(id = nextNoteId++)
            notes.add(saved)
            persist()
            saved
        }

        override suspend fun update(annotation: Annotation) = locked {
            // 색 · 메모만 바꾼다. 자리 · 책은 처음 것 그대로 — 칠한 글과 자리가 어긋나면 안 된다(규약).
            val i = notes.indexOfFirst { it.id == annotation.id }
            if (i >= 0) {
                notes[i] = notes[i].copy(color = annotation.color, note = annotation.note)
                persist()
            }
        }

        override suspend fun remove(id: Long) = locked {
            notes.removeAll { it.id == id }
            persist()
        }
    }

    init {
        load()
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    // ── ProgressRepository ────────────────────────────────────────────────

    override suspend fun get(bookId: BookId): ReadingProgress? = progressOf(bookId.value)

    override suspend fun save(progress: ReadingProgress) = saveProgress(progress)

    /** 읽은 자리(기다리지 않는 판 — 만화 뷰어가 쪽마다 부른다, 구상안 46). */
    fun progressOf(bookId: String): ReadingProgress? = locked { progress[bookId] }

    fun saveProgress(progress: ReadingProgress) = locked {
        this.progress[progress.bookId.value] = progress
        // 안드로이드와 같은 자동 "다 읽음": 끝 쪽(99.95% 이상)을 처음 본 때. 다시 앞으로 가도 지우지 않는다.
        if (progress.percent >= FINISHED_PERCENT && progress.bookId.value !in finished) {
            finished[progress.bookId.value] = progress.updatedAtEpochMs
        }
        persist()
    }

    override suspend fun remove(bookId: BookId) = locked {
        progress.remove(bookId.value)
        persist()
    }

    // ── BookmarkRepository ────────────────────────────────────────────────

    override suspend fun forBook(bookId: BookId): List<Bookmark> = bookmarksOf(bookId.value)

    override suspend fun add(bookmark: Bookmark): Bookmark = addBookmark(bookmark)

    override suspend fun remove(id: Long) = removeBookmark(id)

    fun bookmarksOf(bookId: String): List<Bookmark> = locked {
        bookmarks.filter { it.bookId.value == bookId }.sortedWith(compareBy({ order(it.locator) }, { it.id }))
    }

    fun addBookmark(bookmark: Bookmark): Bookmark = locked {
        val saved = bookmark.copy(id = nextBookmarkId++)
        bookmarks.add(saved)
        persist()
        saved
    }

    fun removeBookmark(id: Long) = locked {
        bookmarks.removeAll { it.id == id }
        persist()
    }

    /** 작품 · 책 설정 하나(없으면 null). 그림책의 보기 · 방향 · 판별 결과도 여기 둔다(구상안 46 — 안드로이드와 같은 표). */
    fun comicSetting(kind: String, subject: String): String? = locked { comicSettings[kind to subject] }

    // ── 서재 ──────────────────────────────────────────────────────────────

    /** 책을 열었다(읽는 중으로 올라간다). */
    fun markOpened(bookId: String) = locked {
        opened[bookId] = currentTimeMillis()
        persist()
    }

    /**
     * 다 읽음을 손으로 표시하거나(시각) 푼다(null) — 서재에서 책을 길게 눌러 갈래를 옮긴다(구상안 43, 안드로이드 `Library.setFinished`).
     * 한 번도 열지 않은 책도 표시할 수 있다(종이책으로 읽은 책). 그때 연 때도 함께 적는다 — 없으면 푼 뒤에 읽는 책이 아니라 읽을 책으로
     * 떨어진다. 푼 책은 연 기록이 남아 읽는 책이 된다.
     */
    fun setFinished(bookId: String, finishedAtEpochMs: Long?, nowEpochMs: Long) = locked {
        if (finishedAtEpochMs != null) {
            opened.getOrPut(bookId) { nowEpochMs }
            finished[bookId] = finishedAtEpochMs
        } else {
            finished.remove(bookId)
        }
        persist()
    }

    /**
     * 읽을 책으로 되돌린다(안드로이드 `returnToUnread`): 연 때 · 다 읽은 때만 지운다. 읽던 자리 · 책갈피 · 형광펜 · 읽은 시간은 남긴다 —
     * 갈래만 옮기려 했는데 노트가 사라지면 되돌릴 수 없다. 다시 열면 남은 자리에서 이어진다.
     */
    fun returnToUnread(bookId: String) = locked {
        opened.remove(bookId)
        finished.remove(bookId)
        persist()
    }

    /** [ids] 각각의 기록. 서재가 한 번에 묻는다 — 책마다 잠그면 수백 권에서 느려진다. */
    fun shelf(ids: List<String>): List<ShelfRecord> = locked {
        val counts = bookmarks.groupingBy { it.bookId.value }.eachCount()
        ids.map { id ->
            ShelfRecord(id, opened[id], finished[id], progress[id]?.percent, counts[id] ?: 0)
        }
    }

    // ── 만화(구상안 35A · 36) ─────────────────────────────────────────────

    /** 권마다 읽은 쪽. 서재 갈래 · 이어 읽기 · 작품 화면이 한 번에 묻는다. */
    fun comicProgress(): Map<String, ComicProgress> = locked { LinkedHashMap(comics) }

    /**
     * 읽은 쪽을 적는다. [half] 는 펼침면을 반으로 보던 중이면 그 반("LEFT" · "RIGHT", 구상안 38) — 다시 열면 그 반에서, 백업의 half 칸으로
     * 안드로이드에도 간다. [atEnd] 면(마지막 판에 닿았다) 다 읽은 때를 처음 한 번만 적고 지우지 않는다 — 다시 앞으로 가도 "다 읽음"
     * 이 풀리면 작품이 읽은 책에서 읽는 책으로 튀었다(안드로이드 `Library.saveComic`).
     */
    fun saveComic(unitId: String, page: Int, pageCount: Int, atEnd: Boolean, half: String? = null, offset: Float? = null): ComicProgress = locked {
        val now = currentTimeMillis()
        val finishedAt = comics[unitId]?.finishedAtEpochMs ?: if (atEnd) now else null
        val side = SpreadSplit.Half.entries.firstOrNull { it.name == half && it != SpreadSplit.Half.WHOLE }
        // 웹툰(구상안 39)은 그림 안의 비율도 — 폭이 바뀌어도 같은 칸으로 돌아온다. 범위 밖(셈이 넘침)은 가둔다.
        val inside = offset?.takeIf { !it.isNaN() }?.coerceIn(0f, 1f)
        ComicProgress(page.coerceAtLeast(0), pageCount.coerceAtLeast(0), now, finishedAt, offset = inside, half = side).also {
            comics[unitId] = it
            persist()
        }
    }

    /** 이 권의 책갈피 쪽(차례대로). */
    fun comicBookmarks(unitId: String): List<Int> = locked { comicMarks.filter { it.first == unitId }.map { it.second }.distinct().sorted() }

    /** 보이는 쪽(두 쪽이면 둘)에 책갈피가 있으면 모두 빼고, 없으면 첫 쪽에 꽂는다. 꽂았으면 true. */
    fun toggleComicBookmark(unitId: String, pages: List<Int>): Boolean = locked {
        val removed = comicMarks.removeAll { it.first == unitId && it.second in pages }
        if (!removed) pages.firstOrNull()?.let { comicMarks.add(Triple(unitId, it, currentTimeMillis())) }
        persist()
        !removed
    }

    /** 작품마다 고른 것(넘기는 방향 · 표지 · 갈래 · 고친 이름). 공통 `ComicShelf.group` 에 그대로 넘긴다. */
    fun comicOverrides(): ComicOverrides = locked {
        fun bools(kind: String) = comicSettings.filterKeys { it.first == kind }.mapNotNull { (k, v) ->
            when (v) { "1" -> k.second to true; "0" -> k.second to false; else -> null }
        }.toMap()
        ComicOverrides(
            // 손으로 합치고 뺀 것 · 같은 권 중 고른 파일(구상안 40). 이 휴대폰의 파일 주소라 백업에는 담지 않는다(안드로이드와 같다).
            workOf = comicSettings.filterKeys { it.first == WORK_OF }.map { (k, v) -> k.second to v }.toMap(),
            preferred = comicSettings.filterKeys { it.first == PREFERRED }.map { (k, v) -> k.second to v }.toMap(),
            // 고친 이름은 아직 iOS 에서 고칠 수 없지만 안드로이드에서 고쳐 백업으로 온 이름은 따른다 — 두 휴대폰에서 작품 이름이 달라 보이면
            // 같은 작품인지 헷갈린다.
            titles = comicSettings.filterKeys { it.first == TITLE }.mapNotNull { (k, v) -> v.trim().takeIf { it.isNotEmpty() }?.let { k.second to it } }.toMap(),
            rightToLeft = bools(RTL),
            coverAlone = bools(COVER),
            splitSpreads = bools(SPLIT),
            // 보는 방식(웹툰 · 쪽 넘김, 구상안 39). 모르는 글은 "자동"(정하지 않음).
            view = comicSettings.filterKeys { it.first == VIEW }.mapNotNull { (k, v) -> ComicView.entries.firstOrNull { it.name == v }?.let { k.second to it } }.toMap(),
            shelf = comicSettings.filterKeys { it.first == SHELF }.mapNotNull { (k, v) -> ShelfMark.parse(v)?.let { k.second to it } }.toMap(),
        )
    }

    /** 작품 설정 하나. [value] 가 null 이면 지운다(기본값을 따른다). [kind] 는 "RTL" · "COVER" · "SHELF" · "SPLIT" · "VIEW". */
    fun setComicSetting(kind: String, subject: String, value: String?) = locked {
        if (value == null) comicSettings.remove(kind to subject) else comicSettings[kind to subject] = value
        persist()
    }

    /** 이 만화 권의 기록(백업에 담을 것). 쪽 · 책갈피 · 읽은 시간이 모두 없으면 null(안드로이드 `collectComics`). */
    fun recordOfComic(unitId: String, name: String, size: Long?): BackupComic? = locked {
        val p = comics[unitId]
        val marks = comicMarks.filter { it.first == unitId }.distinctBy { it.second }.sortedBy { it.second }.map { BackupComicMark(it.second, it.third) }
        val time = times.filter { it.key.first == unitId }.map { (k, v) -> BackupTime(k.second, k.third, v) }.sortedWith(compareBy({ it.day }, { it.mode }))
        if (p == null && marks.isEmpty() && time.isEmpty()) return@locked null
        BackupComic(
            name, size, p?.page, p?.pageCount, p?.offset?.toDouble(), p?.half?.takeIf { it != SpreadSplit.Half.WHOLE }?.name,
            p?.updatedAtEpochMs ?: 0L, p?.finishedAtEpochMs, marks, time,
        )
    }

    /**
     * 백업의 만화 기록을 이 권에 합친다(안드로이드 `mergeComicInto`). 읽은 쪽은 백업이 더 뒤일 때만, 다 읽은 때는 이 휴대폰에 없을
     * 때만(끝낸 날이 백업의 날로 바뀌지 않게), 책갈피는 같은 쪽이 없을 때만, 읽은 시간은 날 · 방식마다 큰 쪽.
     */
    fun mergeComic(unitId: String, c: BackupComic, addTime: Boolean = false) = locked {
        for (t in c.time) addTime(unitId, t, addTime)
        val page = c.page
        if (page != null) {
            val old = comics[unitId]
            val mine = old?.let { BackupComic(c.name, c.size, it.page, it.pageCount, it.offset?.toDouble(), null, it.updatedAtEpochMs, null, emptyList(), emptyList()) }
            if (mine == null || furtherComic(c, mine)) {
                comics[unitId] = ComicProgress(
                    page, c.pageCount ?: old?.pageCount ?: (page + 1), c.updatedAt, old?.finishedAtEpochMs ?: c.finishedAt,
                    c.offset?.toFloat(), c.half?.let { h -> SpreadSplit.Half.entries.firstOrNull { it.name == h } },
                )
            } else if (old.finishedAtEpochMs == null && c.finishedAt != null) {
                comics[unitId] = old.copy(finishedAtEpochMs = c.finishedAt)
            }
        }
        val have = comicMarks.filter { it.first == unitId }.mapTo(HashSet()) { it.second }
        for (m in c.bookmarks) if (have.add(m.page)) comicMarks.add(Triple(unitId, m.page, m.createdAt))
        persist()
    }

    /** 작품 설정들(백업에 담을 것). 빼낸 작품(`#own:` 열쇠)은 이 휴대폰의 파일 주소라 담지 않는다(안드로이드 `collectWorks`). */
    fun workRecords(): List<BackupWork> = locked {
        val keys = comicSettings.keys.filter { it.first in WORK_KINDS && !it.second.startsWith(OWN_PREFIX) }.map { it.second }.distinct()
        keys.map { key ->
            fun value(kind: String) = comicSettings[kind to key]
            fun bool(kind: String) = when (value(kind)) { "1" -> true; "0" -> false; else -> null }
            BackupWork(key, value(TITLE), bool(RTL), value(VIEW), value(SHELF), bool(COVER), bool(SPLIT))
        }.filter { it.title != null || it.rightToLeft != null || it.view != null || it.shelf != null || it.coverAlone != null || it.splitSpreads != null }
    }

    /** 작품 설정은 이 휴대폰에 정한 것이 없을 때만 채운다 — 가져오기가 지금 손으로 고친 것을 덮지 않게(안드로이드 `mergeWorks`). */
    fun mergeWorks(works: List<BackupWork>) = locked {
        if (works.isEmpty()) return@locked
        fun put(kind: String, key: String, value: String?) {
            if (value != null && (kind to key) !in comicSettings) comicSettings[kind to key] = value
        }
        fun flag(b: Boolean?) = b?.let { if (it) "1" else "0" }
        for (w in works.groupBy { it.key }.values.map { it.reduce(BackupWork::filledFrom) }) {
            put(TITLE, w.key, w.title)
            put(RTL, w.key, flag(w.rightToLeft))
            put(VIEW, w.key, w.view)
            put(SHELF, w.key, w.shelf)
            put(COVER, w.key, flag(w.coverAlone))
            put(SPLIT, w.key, flag(w.splitSpreads))
        }
        persist()
    }

    // ── 작품 정리(구상안 40, 안드로이드 `Library.merge` · `split` · `rename` · `prefer`) ──────────────

    /**
     * 작품 [from] 의 모든 권(같은 권 사본까지)을 [into] 로 합친다. 합친 작품은 [into] 의 이름으로 — 적어 두지 않으면 가장 많이 나온 이름이
     * 이기는데, "One Piece" 1권과 "원피스" 1권처럼 동률이면 자연 순서로 엉뚱한 쪽 이름이 됐다(안드로이드).
     */
    fun mergeWork(from: Work, into: Work) = locked {
        for (e in from.entries) for (u in listOf(e.unit) + e.copies) comicSettings[WORK_OF to u.id] = into.key
        comicSettings[TITLE to into.key] = into.title
        persist()
    }

    /** 권 하나(와 그 사본들)를 작품에서 빼 따로 둔다. 빼낸 것은 제 이름으로 새 작품이 된다. */
    fun splitEntry(entry: WorkEntry) = locked {
        val own = OWN_PREFIX + entry.unit.id
        for (u in listOf(entry.unit) + entry.copies) comicSettings[WORK_OF to u.id] = own
        comicSettings[TITLE to own] = entry.name.series.ifBlank { entry.name.cleaned }
        persist()
    }

    /** 작품 이름. 빈 이름이면 고침을 지워 원래 이름으로 돌아간다. 파일 이름은 바꾸지 않는다. */
    fun renameWork(work: Work, title: String) = locked {
        val t = title.trim()
        if (t.isEmpty()) comicSettings.remove(TITLE to work.key) else comicSettings[TITLE to work.key] = t
        persist()
    }

    /** 같은 권 여러 곳 중 읽을 파일. 나머지는 목록에서 숨는다(파일은 그대로). */
    fun preferCopy(entry: WorkEntry, unitId: String) = locked {
        comicSettings[PREFERRED to entry.slot] = unitId
        persist()
    }

    // ── 읽은 시간 ─────────────────────────────────────────────────────────

    /**
     * 읽은 시간을 더한다. 음수는 버리고(시계가 뒤로 감), 한 줄이 하루(24시간)를 넘지 않게 자른다 — 잘못 잰 값 하나가 통계를 수십
     * 시간으로 부풀리면 기록 전체를 믿을 수 없게 된다(안드로이드 `ReadingTime.add` 와 같다).
     */
    fun addTime(bookId: String, day: Long, mode: String, millis: Long) = locked {
        if (millis <= 0) return@locked
        val key = Triple(bookId, day, mode)
        times[key] = ((times[key] ?: 0L) + millis).coerceAtMost(DAY_MS)
        persist()
    }

    /** 모든 시간 줄(통계가 한 번에 묻는다). */
    fun times(): List<TimeRecord> = locked { times.map { (k, v) -> TimeRecord(k.first, k.second, k.third, v) } }

    // ── 백업(구상안 31A) ──────────────────────────────────────────────────

    /** 이 책의 기록(백업에 담을 것). 아무 기록도 없으면 null — 빈 책을 백업에 늘어놓지 않는다(안드로이드 `recordOf`). */
    fun recordOf(bookId: String, name: String, size: Long?, title: String?, author: String?): BackupBook? = locked {
        val p = progress[bookId]
        val book = BackupBook(
            name, size, title, author,
            p?.let { BackupProgress(Locator.encode(it.locator), it.percent, it.updatedAtEpochMs) },
            opened[bookId], finished[bookId],
            bookmarks.filter { it.bookId.value == bookId }.map { BackupBookmark(Locator.encode(it.locator), it.snippet, it.createdAtEpochMs) },
            notes.filter { it.bookId.value == bookId }.map {
                BackupNote(Locator.encode(it.start), Locator.encode(it.end), it.color.name, it.note, it.snippet, it.createdAtEpochMs)
            },
            times.filter { it.key.first == bookId }.map { (k, v) -> BackupTime(k.second, k.third, v) }.sortedWith(compareBy({ it.day }, { it.mode })),
        )
        book.takeIf { it.hasRecords }
    }

    /**
     * 백업의 기록을 이 책에 합친다(안드로이드 `mergeInto`). 이 휴대폰의 기록은 지우지 않는다: 읽은 자리는 백업이 더 뒤쪽일 때만(나중에
     * 고쳤다는 것만으로는 이기지 않는다), 연 때는 나중 것, 다 읽은 때는 이 휴대폰에 없을 때만, 책갈피는 같은 자리가 없을 때만, 형광펜은
     * 같은 구간이 있으면 이 휴대폰의 색을 두고 메모만 비었으면 채운다, 읽은 시간은 날 · 방식마다 큰 쪽(두 번 가져와도 늘지 않는다).
     */
    fun merge(bookId: String, b: BackupBook, addTime: Boolean = false) = locked {
        val id = BookId(bookId)
        b.progress?.let { incoming ->
            val local = progress[bookId]
            val mine = local?.let { BackupProgress(Locator.encode(it.locator), it.percent, it.updatedAtEpochMs) }
            if (mine == null || isFurther(incoming, mine)) {
                Locator.decodeOrNull(incoming.locator)?.let { progress[bookId] = ReadingProgress(id, it, incoming.percent, incoming.updatedAt) }
            }
        }
        if (b.openedAt != null || b.finishedAt != null) {
            opened[bookId] = maxOf(opened[bookId] ?: Long.MIN_VALUE, b.openedAt ?: opened[bookId] ?: currentTimeMillis())
            if (bookId !in finished) b.finishedAt?.let { finished[bookId] = it }
        }
        val marks = bookmarks.filter { it.bookId == id }.map { Locator.encode(it.locator) }.toMutableSet()
        for (m in b.bookmarks) {
            val locator = Locator.decodeOrNull(m.locator) ?: continue
            if (!marks.add(Locator.encode(locator))) continue
            bookmarks.add(Bookmark(nextBookmarkId++, id, locator, m.snippet, m.createdAt))
        }
        for (a in b.annotations) {
            val start = Locator.decodeOrNull(a.start) as? Locator.Reflow ?: continue
            val end = Locator.decodeOrNull(a.end) as? Locator.Reflow ?: continue
            val i = notes.indexOfFirst { it.bookId == id && it.start == start && it.end == end }
            if (i >= 0) {
                if (notes[i].note == null && a.note != null) notes[i] = notes[i].copy(note = a.note)
                continue
            }
            // 모르는 색은 노랑으로 — iOS 의 칠 색은 넷뿐이다.
            val color = HighlightColor.entries.firstOrNull { it.name == a.color } ?: HighlightColor.Yellow
            runCatching { Annotation(nextNoteId, id, start, end, color, a.note, a.snippet, a.createdAt) }.getOrNull()?.let {
                notes.add(it)
                nextNoteId++
            }
        }
        for (t in b.time) addTime(bookId, t, addTime)
        persist()
    }

    /**
     * 읽은 시간 합치기. 백업은 큰 쪽(같은 백업을 두 번 가져와도 늘지 않게), 옮긴 파일은 더한다 — 옛 자리 · 새 자리에서 읽은 시간은 둘 다
     * 실제로 읽은 시간이고, 옛 줄은 지우므로 두 번 세지 않는다(안드로이드 `mergeTime`). 하루 한도는 그대로.
     */
    private fun addTime(id: String, t: BackupTime, add: Boolean) {
        val key = Triple(id, t.day, t.mode)
        val now = times[key] ?: 0L
        times[key] = (if (add) now + t.ms else maxOf(now, t.ms)).coerceAtMost(DAY_MS)
    }

    // ── 옮긴 파일(안드로이드 0.50.0) ──────────────────────────────────────

    private fun hasRecords(id: String): Boolean =
        id in progress || id in opened || id in finished || id in comics ||
            bookmarks.any { it.bookId.value == id } || notes.any { it.bookId.value == id } ||
            comicMarks.any { it.first == id } || times.keys.any { it.first == id }

    /** 같은 파일의 열쇠: 종류 · 이름(한글을 모아 쓴 꼴, 구상안 1A) · 크기(폴더는 그림 수). 크기도 그림 수도 모르면 null — 짝짓지 않는다. */
    private fun matchKey(f: KnownFile): Triple<String, String, Long>? = when {
        f.size != null && f.size >= 0 -> Triple(f.kind, nfc(f.name), f.size)
        f.pages != null && f.pages > 0 -> Triple(f.kind, nfc(f.name), -f.pages - 1L)
        else -> null
    }

    /**
     * 사라진 파일의 기록을 옮겨 간 파일로 잇는다. [visible] 은 이번에 훑은 모든 파일, [scanned] 는 이번에 실제로 읽은 폴더의 id 머리
     * ("app:", "link:폴더:"). 묻지 않고 옮기므로 백업 가져오기보다 엄격하다(안드로이드와 같다):
     * - 사라진 쪽: 읽은 폴더 안에 있었고 지금 보이지 않으며 기록이 있는 파일. 접근이 끊긴 폴더 · 서재에서 뺀 폴더의 파일은 사라진 것으로
     *   치지 않는다(구상안 2A) — 다시 추가하면 그 자리에서 그대로 이어져야 한다.
     * - 짝: 같은 열쇠의 사라진 파일도, 보이는 파일도 하나일 때만. 둘 중 무엇인지 고르지 않는다 — 잘못 붙인 기록은 되돌릴 수 없다.
     * - 옛 자리에 아직 보이면 복사본이라 각자 기록을 둔다(사라진 쪽이 아니다).
     * 합치기는 백업 가져오기와 같고 시간만 더한다. 옛 id 의 기록 · 작품 설정 열쇠를 새 id 로 옮긴다.
     */
    fun adoptMoved(visible: List<KnownFile>, scanned: List<String>): List<MovedPair> = locked {
        val now = visible.associateBy { it.id }
        val before = LinkedHashMap(known)
        for (f in visible) if (hasRecords(f.id)) known[f.id] = f
        // 기록도 파일도 없어진 것은 잊는다 — 남겨 두면 언젠가 같은 이름 · 크기의 다른 파일에 빈 기록을 붙이려 든다.
        known.keys.filter { it !in now && !hasRecords(it) }.forEach { known.remove(it) }
        val gone = known.values.filter { it.id !in now && scanned.any { p -> it.id.startsWith(p) } }
        val goneCount = gone.mapNotNull(::matchKey).groupingBy { it }.eachCount()
        val byKey = visible.groupBy(::matchKey)
        val pairs = ArrayList<MovedPair>()
        for (old in gone) {
            val key = matchKey(old) ?: continue
            if (goneCount[key] != 1) continue
            val target = byKey[key]?.singleOrNull() ?: continue
            if (target.id == old.id) continue
            val comic = old.kind != KnownFile.BOOK
            if (comic) {
                recordOfComic(old.id, old.name, old.size)?.let { mergeComic(target.id, it, addTime = true) }
            } else {
                recordOf(old.id, old.name, old.size, null, null)?.let { merge(target.id, it, addTime = true) }
            }
            forget(old.id)
            moveSettings(old.id, target.id)
            known.remove(old.id)
            known[target.id] = target
            pairs += MovedPair(old.id, target.id, comic)
        }
        // 훑을 때마다 쓰지 않는다 — 바뀐 것이 있을 때만.
        if (pairs.isNotEmpty() || known != before) persist()
        pairs
    }

    /** 옛 id 의 기록을 모두 지운다(옮긴 뒤). 남기면 파일이 옛 자리로 돌아왔을 때 기록이 둘로 갈린다. */
    private fun forget(id: String) {
        progress.remove(id)
        opened.remove(id)
        finished.remove(id)
        comics.remove(id)
        bookmarks.removeAll { it.bookId.value == id }
        notes.removeAll { it.bookId.value == id }
        comicMarks.removeAll { it.first == id }
        times.keys.filter { it.first == id }.forEach { times.remove(it) }
    }

    /**
     * 작품 설정 중 옛 id 를 가리키는 것(빼낸 권의 `#own:` 작품 열쇠 · 어느 작품에 묶였나 · 고른 사본)을 새 id 로. 새 쪽에 같은 설정이
     * 이미 있으면 그것을 둔다(안드로이드 `moveOverrides`).
     */
    private fun moveSettings(old: String, new: String) {
        fun swap(v: String) = when (v) {
            old -> new
            OWN_PREFIX + old -> OWN_PREFIX + new
            else -> v
        }
        for ((k, v) in comicSettings.map { it.key to it.value }) {
            val subject = swap(k.second)
            val value = swap(v)
            if (subject == k.second && value == v) continue
            comicSettings.remove(k)
            if ((k.first to subject) !in comicSettings) comicSettings[k.first to subject] = value
        }
    }

    private fun order(locator: Locator): Long = when (locator) {
        is Locator.Reflow -> locator.spine.toLong() * Int.MAX_VALUE + locator.charOffset
        is Locator.FixedPage -> locator.page.toLong() * 1_000_000 + locator.yPermille * 1000L + locator.xPermille
    }

    // ── 파일 ──────────────────────────────────────────────────────────────

    private fun load() {
        val text = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) ?: return
        for (line in text.split('\n')) {
            val raw = line.split('\t').map(::unescape)
            // 책 id 는 한글을 모아 쓴 꼴(NFC)로. iOS 가 폴더를 훑어 주는 경로는 풀어 쓴 꼴(NFD)이라 옛 판은 그 꼴로 적었다 — Swift 는 두
            // 꼴을 같은 글로 보지만 여기(Kotlin)는 다른 글로 봐, 같은 책의 기록이 둘로 갈라졌다.
            val idAt = when (raw.firstOrNull()) { "P", "R", "T", "C", "K", "F" -> 1; "B", "A" -> 2; else -> -1 }
            val f = if (idAt in raw.indices) raw.toMutableList().also { it[idAt] = nfc(it[idAt]) } else raw
            // 깨진 줄은 그 줄만 버린다 — 기록 하나 때문에 서재 전체가 비면 안 된다.
            runCatching {
                when (f.firstOrNull()) {
                    "P" -> {
                        val locator = Locator.decodeOrNull(f[2]) ?: return@runCatching
                        progress[f[1]] = ReadingProgress(BookId(f[1]), locator, f[3].toFloat().coerceIn(0f, 100f), f[4].toLong())
                    }
                    "B" -> {
                        val locator = Locator.decodeOrNull(f[3]) ?: return@runCatching
                        val id = f[1].toLong()
                        bookmarks.add(Bookmark(id, BookId(f[2]), locator, f.getOrNull(5)?.ifEmpty { null }, f[4].toLong()))
                        if (id >= nextBookmarkId) nextBookmarkId = id + 1
                    }
                    "A" -> {
                        val start = Locator.decodeOrNull(f[3]) as? Locator.Reflow ?: return@runCatching
                        val end = Locator.decodeOrNull(f[4]) as? Locator.Reflow ?: return@runCatching
                        val color = HighlightColor.entries.firstOrNull { it.name == f[5] } ?: HighlightColor.Yellow
                        val id = f[1].toLong()
                        // 빈 구간 · 뒤집힌 구간은 Annotation 이 거절한다 — 그 줄만 버린다.
                        notes.add(Annotation(id, BookId(f[2]), start, end, color, f.getOrNull(8), f[7], f[6].toLong()))
                        if (id >= nextNoteId) nextNoteId = id + 1
                    }
                    "C" -> {
                        val page = f[2].toInt()
                        val count = f[3].toInt()
                        if (page >= 0 && count >= 0) {
                            // 자리 · 반이 깨졌으면 그 칸만 버린다(쪽 전체로 연다).
                            val offset = f.getOrNull(6)?.toFloatOrNull()?.takeIf { it in 0f..1f }
                            val half = f.getOrNull(7)?.let { h -> SpreadSplit.Half.entries.firstOrNull { it.name == h && it != SpreadSplit.Half.WHOLE } }
                            comics[f[1]] = ComicProgress(page, count, f[4].toLong(), f.getOrNull(5)?.takeIf { it != "-" }?.toLong(), offset, half)
                        }
                    }
                    "K" -> comicMarks.add(Triple(f[1], f[2].toInt().also { require(it >= 0) }, f[3].toLong()))
                    "O" -> comicSettings[f[1] to f[2]] = f[3]
                    "F" -> known[f[1]] = KnownFile(f[1], f[2], f[3], f[4].takeIf { it != "-" }?.toLong(), f[5].takeIf { it != "-" }?.toInt())
                    "T" -> {
                        val ms = f[4].toLong()
                        if (ms > 0) times[Triple(f[1], f[2].toLong(), f[3])] = ms.coerceAtMost(DAY_MS)
                    }
                    "R" -> {
                        opened[f[1]] = f[2].toLong()
                        f[3].takeIf { it != "-" }?.let { finished[f[1]] = it.toLong() }
                    }
                }
            }
        }
    }

    private fun persist() {
        val out = StringBuilder()
        fun row(vararg values: String) {
            out.append(values.joinToString("\t", transform = ::escape)).append('\n')
        }
        for (p in progress.values) row("P", p.bookId.value, Locator.encode(p.locator), p.percent.toString(), p.updatedAtEpochMs.toString())
        for (b in bookmarks) row("B", b.id.toString(), b.bookId.value, Locator.encode(b.locator), b.createdAtEpochMs.toString(), b.snippet ?: "")
        for (a in notes) {
            val cells = mutableListOf("A", a.id.toString(), a.bookId.value, Locator.encode(a.start), Locator.encode(a.end), a.color.name,
                a.createdAtEpochMs.toString(), a.snippet)
            a.note?.let { cells += it }
            row(*cells.toTypedArray())
        }
        // 다 읽었지만 연 기록이 없는 책(기록을 옮겨 온 경우)도 남긴다.
        for (id in opened.keys + finished.keys.filter { it !in opened }) {
            row("R", id, (opened[id] ?: finished.getValue(id)).toString(), finished[id]?.toString() ?: "-")
        }
        for ((k, v) in times) row("T", k.first, k.second.toString(), k.third, v.toString())
        for ((id, p) in comics) row("C", id, p.page.toString(), p.pageCount.toString(), p.updatedAtEpochMs.toString(), p.finishedAtEpochMs?.toString() ?: "-",
            p.offset?.toString() ?: "-", p.half?.name ?: "-",
        )
        for ((id, page, at) in comicMarks) row("K", id, page.toString(), at.toString())
        for ((k, v) in comicSettings) row("O", k.first, k.second, v)
        for (f in known.values) row("F", f.id, f.kind, f.name, f.size?.toString() ?: "-", f.pages?.toString() ?: "-")
        val dir = path.substringBeforeLast('/')
        NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        @Suppress("CAST_NEVER_SUCCEEDS")
        (out.toString() as NSString).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }

    private companion object {
        const val FINISHED_PERCENT = 99.95f
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val RTL = "RTL"
        const val COVER = "COVER"
        const val SHELF = "SHELF"
        const val TITLE = "TITLE"
        const val VIEW = "VIEW"
        const val SPLIT = "SPLIT"
        const val OWN_PREFIX = "#own:"
        const val WORK_OF = "WORK_OF"
        const val PREFERRED = "PREFERRED"
        val WORK_KINDS = setOf(TITLE, RTL, VIEW, SHELF, COVER, SPLIT)

        fun escape(value: String): String = buildString {
            for (c in value) when (c) {
                '%' -> append("%25")
                '\t' -> append("%09")
                '\n' -> append("%0A")
                '\r' -> append("%0D")
                else -> append(c)
            }
        }

        fun unescape(value: String): String {
            if ('%' !in value) return value
            val out = StringBuilder(value.length)
            var i = 0
            while (i < value.length) {
                val c = value[i]
                val code = if (c == '%' && i + 2 < value.length) value.substring(i + 1, i + 3).toIntOrNull(16) else null
                if (code != null) {
                    out.append(code.toChar())
                    i += 3
                } else {
                    out.append(c)
                    i++
                }
            }
            return out.toString()
        }
    }
}
