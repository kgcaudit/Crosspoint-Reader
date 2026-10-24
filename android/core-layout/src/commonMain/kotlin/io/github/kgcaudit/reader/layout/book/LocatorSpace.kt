package io.github.kgcaudit.reader.layout.book

import io.github.kgcaudit.reader.document.Annotation
import io.github.kgcaudit.reader.document.AnnotationRepository
import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.Bookmark
import io.github.kgcaudit.reader.document.BookmarkRepository
import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.document.PiecedDocument
import io.github.kgcaudit.reader.document.ProgressRepository
import io.github.kgcaudit.reader.document.ReadingProgress
import io.github.kgcaudit.reader.document.ReflowDocument

/**
 * 저장하는 자리(책 자리)와 조판하는 자리(조각 자리)를 오간다 — 조각으로 나눈 TXT([PiecedDocument]) 때문에 있다.
 *
 * 책 자리는 나누기 전 한 장의 글자 번호(`r:0:N`)라, 옛 기록 · 백업 · 다른 기기 · 옛 판 앱과 그대로 맞는다. 조판 · 화면은
 * 조각 자리(`r:k:M`)로 돈다. 바꾸는 곳은 저장소의 문턱 하나([layoutRepositories])라, 리더 안쪽은 EPUB 과 같은 코드를 탄다.
 * 나누지 않은 문서는 [IDENTITY] — 두 자리가 같다.
 */
class LocatorSpace private constructor(private val starts: IntArray, private val length: Int) {

    /** 조각이 둘 이상이다. 아니면 모든 변환이 그대로다. */
    val isSplit: Boolean get() = starts.size > 1

    /** 조각 [piece] 가 한 장 안에서 시작하는 글자 자리. */
    fun pieceStart(piece: Int): Int = if (isSplit) starts[piece.coerceIn(0, starts.size - 1)] else 0

    /** 한 장의 글자 수(나눈 문서). 나누지 않았으면 0. */
    val textLength: Int get() = if (isSplit) length else 0

    /** 책 자리 → 조각 자리. 글자 [N] 이 든 조각(경계 글자는 뒤 조각의 처음). */
    fun toLayout(locator: Locator.Reflow): Locator.Reflow {
        if (!isSplit) return locator
        val offset = bookOffset(locator)
        val piece = pieceOf(offset, endExclusive = false)
        return Locator.Reflow(piece, offset - starts[piece])
    }

    /**
     * 범위 **끝**(그 글자는 들지 않는다)의 책 자리 → 조각 자리. 경계에서 끝나는 칠은 앞 조각의 끝으로 둔다 — [toLayout] 처럼
     * 뒤 조각의 0 으로 두면 칠이 뒤 조각까지 걸친 것으로 보여 뒤 조각 첫 쪽에도 그 칠을 찾았다.
     */
    fun toLayoutEnd(locator: Locator.Reflow): Locator.Reflow {
        if (!isSplit) return locator
        val offset = bookOffset(locator)
        val piece = pieceOf(offset, endExclusive = true)
        return Locator.Reflow(piece, offset - starts[piece])
    }

    /** 조각 자리 → 책 자리. */
    fun toBook(locator: Locator.Reflow): Locator.Reflow {
        if (!isSplit) return locator
        val piece = locator.spine.coerceIn(0, starts.size - 1)
        return Locator.Reflow(0, (starts[piece] + locator.charOffset).coerceIn(0, length))
    }

    // 나눈 문서의 책 자리는 늘 0 장이다. 다른 장 번호가 오면(있을 수 없지만 깨진 기록) 글자 번호만 믿는다 — 버리면 처음으로 간다.
    private fun bookOffset(locator: Locator.Reflow): Int = locator.charOffset.coerceIn(0, length)

    private fun pieceOf(offset: Int, endExclusive: Boolean): Int {
        // 마지막으로 offset 앞(끝이면 offset 보다 작은)에서 시작하는 조각.
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            val inside = if (endExclusive) starts[mid] < offset else starts[mid] <= offset
            if (inside) lo = mid else hi = mid - 1
        }
        return lo
    }

    companion object {
        /** 나누지 않은 문서. */
        val IDENTITY: LocatorSpace = LocatorSpace(intArrayOf(0), 0)

        fun of(pieceStarts: IntArray, textLength: Int): LocatorSpace =
            if (pieceStarts.size <= 1) IDENTITY else LocatorSpace(pieceStarts.copyOf(), textLength)

        /** [document] 의 자리 체계. 조각으로 나누는 문서가 아니면 [IDENTITY]. */
        suspend fun of(document: ReflowDocument): LocatorSpace =
            (document as? PiecedDocument)?.let { of(it.pieceStarts(), it.textLength()) } ?: IDENTITY
    }
}

/**
 * 리더가 쓸 저장소 셋을 조각 자리로 감싼다. 읽어 올 때 책 자리 → 조각 자리, 쓸 때 거꾸로. 나누지 않은 문서면 그대로 돌려준다.
 *
 * [space] 는 처음 쓸 때 한 번 구한다(TXT 는 파일 전체를 글자로 풀어야 알 수 있어, 저장소를 만들 때는 아직 모른다).
 */
class LayoutRepositories(
    private val bookmarkStore: BookmarkRepository,
    private val progressStore: ProgressRepository,
    private val annotationStore: AnnotationRepository,
    private val space: suspend () -> LocatorSpace,
) {
    val bookmarks: BookmarkRepository = object : BookmarkRepository {
        override suspend fun forBook(bookId: BookId): List<Bookmark> {
            val s = space()
            return bookmarkStore.forBook(bookId).map { it.copy(locator = s.layout(it.locator)) }
        }

        override suspend fun add(bookmark: Bookmark): Bookmark {
            val s = space()
            val saved = bookmarkStore.add(bookmark.copy(locator = s.book(bookmark.locator)))
            return saved.copy(locator = s.layout(saved.locator))
        }

        override suspend fun remove(id: Long) = bookmarkStore.remove(id)
    }

    val progress: ProgressRepository = object : ProgressRepository {
        override suspend fun get(bookId: BookId): ReadingProgress? {
            val s = space()
            return progressStore.get(bookId)?.let { it.copy(locator = s.layout(it.locator)) }
        }

        override suspend fun save(progress: ReadingProgress) {
            val s = space()
            progressStore.save(progress.copy(locator = s.book(progress.locator)))
        }

        override suspend fun remove(bookId: BookId) = progressStore.remove(bookId)
    }

    val annotations: AnnotationRepository = object : AnnotationRepository {
        override suspend fun forBook(bookId: BookId): List<Annotation> {
            val s = space()
            return annotationStore.forBook(bookId).map { s.layout(it) }
        }

        override suspend fun add(annotation: Annotation): Annotation {
            val s = space()
            return s.layout(annotationStore.add(s.book(annotation)))
        }

        override suspend fun update(annotation: Annotation) = annotationStore.update(space().book(annotation))

        override suspend fun remove(id: Long) = annotationStore.remove(id)
    }
}

private fun LocatorSpace.layout(locator: Locator): Locator = (locator as? Locator.Reflow)?.let(::toLayout) ?: locator
private fun LocatorSpace.book(locator: Locator): Locator = (locator as? Locator.Reflow)?.let(::toBook) ?: locator
private fun LocatorSpace.layout(a: Annotation): Annotation = if (!isSplit) a else a.copy(start = toLayout(a.start), end = toLayoutEnd(a.end))
private fun LocatorSpace.book(a: Annotation): Annotation = if (!isSplit) a else a.copy(start = toBook(a.start), end = toBook(a.end))
