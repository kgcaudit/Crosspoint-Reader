package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.document.Annotation
import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.HighlightColor
import io.github.kgcaudit.reader.document.Bookmark
import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.document.ReadingProgress
import io.github.kgcaudit.reader.document.io.currentTimeMillis
import io.github.kgcaudit.reader.document.pdf.PdfStructure
import io.github.kgcaudit.reader.document.pdf.PdfStructureReader

/** PDF 목차 한 줄. [page] 는 0 부터, [pageName] 은 줄 끝에 보이는 쪽(인쇄된 번호가 있으면 그것). */
class PdfContentsRow(val label: String, val page: Int, val depth: Int, val pageName: String)

/** PDF 책갈피 한 줄. [snippet] 은 "4쪽"(인쇄된 번호가 있으면 그것). */
class PdfBookmarkRow(val id: Long, val page: Int, val snippet: String, val createdAtEpochMs: Long)

/** 다시 열 자리: 쪽과 그 쪽 안 세로 자리(천분율, 폭 맞춤 · 이어서). */
class PdfPlace(val page: Int, val yPermille: Int)

/**
 * PDF 한 권의 구조 · 기록(구상안 32A · 33, 안드로이드 `PdfReader` 의 기록 쪽). 쪽 그리기는 iOS PDFKit(화면)이 하고, 여기는 기기 없이
 * 시험할 수 있는 것만 — 목차 · 제목 · 쪽 이름표(공통 `PdfStructureReader`), 읽은 자리 · 진도, 책갈피, 이 장 남은 쪽.
 *
 * 자리는 `Locator.FixedPage(page, 0, y)` — 안드로이드와 같은 `p:<page>:0:<y>` 라 백업으로 두 앱이 주고받는다.
 */
class IosPdfBook private constructor(
    val bookId: String,
    private val structure: PdfStructure,
    private val store: ReadingStore,
    /** PDFKit 이 센 쪽 수. 목차 · 기록이 이보다 뒤를 가리키면 버리거나 마지막 쪽으로 — 파일이 짧아졌을 수 있다. */
    val pageCount: Int,
    val title: String,
) {
    val author: String? get() = structure.author

    /** 목차. 쪽 수를 넘는 항목은 뺀다(안드로이드와 같다). */
    val contents: List<PdfContentsRow> = structure.outline.filter { it.pageIndex in 0 until pageCount }
        .map { PdfContentsRow(it.label, it.pageIndex, it.depth, pageName(it.pageIndex)) }

    /** 인쇄된 쪽 번호가 파일 순서와 다를 때만 그 이름("iv"). 같으면 null — 보일 까닭이 없다. */
    fun label(page: Int): String? = structure.pageLabels?.label(page)

    /** 사람이 부르는 쪽 이름: 인쇄된 번호, 없으면 1 부터 센 번호. */
    fun pageName(page: Int): String = label(page) ?: "${page + 1}"

    /** 저장된 자리. 처음이면 0쪽, 파일이 짧아졌으면 마지막 쪽. */
    suspend fun restore(): PdfPlace {
        val at = store.get(BookId(bookId))?.locator as? Locator.FixedPage ?: return PdfPlace(0, 0)
        val page = at.page.coerceIn(0, pageCount - 1)
        return PdfPlace(page, if (page == at.page) at.yPermille else 0)
    }

    /**
     * 자리를 저장하고 진도(0~100)를 돌려준다. 진도는 보이는 마지막 쪽까지 읽은 것으로 센다 — 두 쪽이면 오른쪽 쪽까지(안드로이드와 같다).
     * 마지막 쪽이 보이면 100 이라 "다 읽음" 이 된다.
     */
    suspend fun save(page: Int, yPermille: Int, lastShown: Int): Float {
        val p = page.coerceIn(0, pageCount - 1)
        val percent = ((lastShown.coerceIn(p, pageCount - 1) + 1) * 100f / pageCount).coerceIn(0f, 100f)
        store.save(ReadingProgress(BookId(bookId), Locator.FixedPage(p, 0, yPermille.coerceIn(0, 1000)), percent, currentTimeMillis()))
        return percent
    }

    /** [page] 를 담은 목차 항목(그 쪽 이전의 마지막 항목). 없으면 −1. */
    fun currentContentsIndex(page: Int): Int = contents.indexOfLast { it.page <= page }

    /** 아래 줄의 장 제목. 목차가 없거나 첫 항목 앞이면 빈 글. */
    fun section(page: Int): String = contents.getOrNull(currentContentsIndex(page))?.label ?: ""

    /**
     * 이 장 남은 쪽(안드로이드 `pagesLeftInSection`): 보이는 마지막 쪽 뒤에서 시작하는 가장 가까운 목차 항목까지. 없으면 책 끝까지.
     */
    fun pagesLeftInSection(lastShown: Int): Int {
        val next = contents.firstOrNull { it.page > lastShown }?.page ?: pageCount
        return (next - lastShown - 1).coerceAtLeast(0)
    }

    /** [first]..[last] 쪽(두 쪽이면 펼침)에 책갈피가 있는가. */
    suspend fun isBookmarked(first: Int, last: Int): Boolean =
        store.forBook(BookId(bookId)).any { (it.locator as? Locator.FixedPage)?.page in first..last }

    /**
     * 보이는 쪽에 책갈피가 있으면 모두 빼고, 없으면 왼쪽 쪽에 꽂는다. 꽂았으면 true. 펼침의 오른쪽 쪽 책갈피도 빠져야 한다 — 리본이
     * 보이는데 눌러도 안 빠지면 하나 더 꽂힌다.
     */
    suspend fun toggleBookmark(first: Int, last: Int): Boolean {
        val id = BookId(bookId)
        val here = store.forBook(id).filter { (it.locator as? Locator.FixedPage)?.page in first..last }
        if (here.isNotEmpty()) {
            for (b in here) store.remove(b.id)
            return false
        }
        store.add(Bookmark(Bookmark.NO_ID, id, Locator.FixedPage(first), "${pageName(first)}쪽", currentTimeMillis()))
        return true
    }

    suspend fun bookmarks(): List<PdfBookmarkRow> = store.forBook(BookId(bookId)).mapNotNull { b ->
        val page = (b.locator as? Locator.FixedPage)?.page ?: return@mapNotNull null
        PdfBookmarkRow(b.id, page.coerceIn(0, pageCount - 1), b.snippet ?: "${pageName(page)}쪽", b.createdAtEpochMs)
    }

    suspend fun removeBookmark(id: Long) = store.remove(id)

    // ── 형광펜(구상안 34A) ────────────────────────────────────────────────

    /**
     * 이 쪽에 앉은 칠들. 자리는 [Locator.Reflow] 에 쪽 번호와 그 쪽 글자 층의 번호(안드로이드 `PdfReader.highlight` 와 같다 — PDF 는 글이
     * 다시 흐르지 않아 (쪽, 글자) 가 늘 같은 글자다). 한 쪽 안의 칠만.
     */
    suspend fun tintsOn(page: Int): List<PdfTint> = notes().filter { it.start.spine == page && it.end.spine == page }
        .map { PdfTint(it.start.charOffset, it.end.charOffset, it.color.ordinal, it.note != null) }

    suspend fun notesOn(page: Int): List<NoteRow> = notes().filter { it.start.spine == page && it.end.spine == page }.map(::row)

    /** 칠한다. 같은 구간이 이미 있으면 색 · 메모만 바꾼다(둘이면 지울 때 하나가 남는다). 빈 구간은 무시. */
    suspend fun highlight(page: Int, start: Int, end: Int, color: Int, note: String?, snippet: String) {
        if (start >= end || page !in 0 until pageCount) return
        val pen = HighlightColor.entries.getOrElse(color) { HighlightColor.Yellow }
        val from = Locator.Reflow(page, start)
        val to = Locator.Reflow(page, end)
        val same = notes().firstOrNull { it.start == from && it.end == to }
        if (same != null) {
            store.annotations.update(same.copy(color = pen).withNote(note ?: same.note))
        } else {
            store.annotations.add(Annotation(Annotation.NO_ID, BookId(bookId), from, to, pen, null, snippet.takeWhole(200), currentTimeMillis()).withNote(note))
        }
    }

    suspend fun recolor(id: Long, color: Int) {
        notes().firstOrNull { it.id == id }?.let { a -> store.annotations.update(a.copy(color = HighlightColor.entries.getOrElse(color) { a.color })) }
    }

    suspend fun setNote(id: Long, note: String?) {
        notes().firstOrNull { it.id == id }?.let { store.annotations.update(it.withNote(note)) }
    }

    suspend fun removeNote(id: Long) = store.annotations.remove(id)

    /** 독서노트의 줄들(쪽 차례). 시작 · 끝은 화면의 묶은 번호([IosPdfText.encode]). */
    suspend fun noteRows(): List<NoteRow> = notes().sortedWith(compareBy({ it.start.spine }, { it.start.charOffset })).map(::row)

    suspend fun notePage(id: Long): Int? = notes().firstOrNull { it.id == id }?.start?.spine

    private suspend fun notes(): List<Annotation> = store.annotations.forBook(BookId(bookId))

    private fun row(a: Annotation): NoteRow {
        val page = a.start.spine
        return NoteRow(
            a.id, page, IosPdfText.encode(page, a.start.charOffset), IosPdfText.encode(page, a.end.charOffset), a.color.ordinal,
            a.snippet, a.note, ((page + 1) * 100f / pageCount).coerceIn(0f, 100f), section(page).ifEmpty { "${pageName(page)}쪽" },
            a.createdAtEpochMs,
        )
    }

    companion object {
        /**
         * 연다(기록 쪽만). [pageCount] 는 PDFKit 이 센 쪽 수. 구조를 읽지 못해도(깨진 목차 · 사용자 암호) 쪽은 보여야 하므로 빈 구조로
         * 연다. 제목은 파일에 적힌 것(공통 정리 규칙 — 'Microsoft Word - ' · '제목 없음' 은 뺀다), 없으면 확장자 뗀 파일 이름.
         */
        fun open(path: String, bookId: String, displayName: String, pageCount: Int, store: ReadingStore): IosPdfBook {
            val structure = structure(path)
            store.markOpened(bookId)
            return IosPdfBook(bookId, structure, store, pageCount.coerceAtLeast(1),
                structure.title ?: displayName.substringBeforeLast('.', displayName))
        }

        internal fun structure(path: String): PdfStructure = runCatching {
            FileSeekableSource(path).use { PdfStructureReader.read(it) }
        }.getOrDefault(PdfStructure.EMPTY)
    }
}
