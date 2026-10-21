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
import io.github.kgcaudit.reader.document.io.currentTimeMillis
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/** 서재의 한 줄에 필요한 읽기 기록. [openedAtEpochMs] 가 null 이면 아직 열지 않은 책(읽을 책). */
data class ShelfRecord(
    val bookId: String,
    val openedAtEpochMs: Long?,
    val finishedAtEpochMs: Long?,
    val percent: Float?,
    val bookmarkCount: Int,
)

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

    override suspend fun get(bookId: BookId): ReadingProgress? = locked { progress[bookId.value] }

    override suspend fun save(progress: ReadingProgress) = locked {
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

    override suspend fun forBook(bookId: BookId): List<Bookmark> = locked {
        bookmarks.filter { it.bookId == bookId }.sortedWith(compareBy({ order(it.locator) }, { it.id }))
    }

    override suspend fun add(bookmark: Bookmark): Bookmark = locked {
        val saved = bookmark.copy(id = nextBookmarkId++)
        bookmarks.add(saved)
        persist()
        saved
    }

    override suspend fun remove(id: Long) = locked {
        bookmarks.removeAll { it.id == id }
        persist()
    }

    // ── 서재 ──────────────────────────────────────────────────────────────

    /** 책을 열었다(읽는 중으로 올라간다). */
    fun markOpened(bookId: String) = locked {
        opened[bookId] = currentTimeMillis()
        persist()
    }

    /** [ids] 각각의 기록. 서재가 한 번에 묻는다 — 책마다 잠그면 수백 권에서 느려진다. */
    fun shelf(ids: List<String>): List<ShelfRecord> = locked {
        val counts = bookmarks.groupingBy { it.bookId.value }.eachCount()
        ids.map { id ->
            ShelfRecord(id, opened[id], finished[id], progress[id]?.percent, counts[id] ?: 0)
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
            val f = line.split('\t').map(::unescape)
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
        val dir = path.substringBeforeLast('/')
        NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        @Suppress("CAST_NEVER_SUCCEEDS")
        (out.toString() as NSString).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }

    private companion object {
        const val FINISHED_PERCENT = 99.95f

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
