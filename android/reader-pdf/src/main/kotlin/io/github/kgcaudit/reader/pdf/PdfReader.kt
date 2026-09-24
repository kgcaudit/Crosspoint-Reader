package io.github.kgcaudit.reader.pdf

import android.graphics.Bitmap
import android.util.LruCache
import io.github.kgcaudit.reader.document.Bookmark
import io.github.kgcaudit.reader.document.BookmarkRepository
import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.document.ProgressRepository
import io.github.kgcaudit.reader.document.ReadingProgress
import io.github.kgcaudit.reader.document.TocEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 지금 쪽이 속한 목차 항목의 번호: 지금 쪽 이전에 시작하는 항목 중 가장 늦게 시작하는 것(같으면 뒤의 것 —
 * 더 깊은 절). 첫 항목보다 앞(표지)이면 -1.
 */
fun currentContentsIndex(entries: List<TocEntry>, page: Int): Int {
    var best = -1
    var bestPage = -1
    entries.forEachIndexed { i, entry ->
        val start = (entry.locator as? Locator.FixedPage)?.page ?: return@forEachIndexed
        if (start in bestPage..page) {
            best = i
            bestPage = start
        }
    }
    return best
}

/**
 * 지금 목차 항목 안에서 [page] 뒤로 남은 쪽 수(하단 정보의 "이 장 남은 쪽"). 다음 항목이 시작하는 쪽 앞까지다.
 * 목차가 없거나 마지막 항목이면 파일 끝까지.
 *
 * 다음 항목을 "목록에서 바로 다음 것" 으로 잡으면 안 된다 — 잡지 목차는 쪽 순서가 뒤섞여 있어(특집을 앞에
 * 적는다) 남은 쪽이 음수나 수십 쪽으로 튄다. 지금 쪽보다 뒤에서 시작하는 항목 중 가장 가까운 것을 쓴다.
 */
fun pagesLeftInSection(entries: List<TocEntry>, page: Int, pageCount: Int): Int {
    val next = entries.mapNotNull { (it.locator as? Locator.FixedPage)?.page }.filter { it > page }.minOrNull() ?: pageCount
    return (next - page - 1).coerceAtLeast(0)
}

/**
 * 두쪽보기에서 [page] 가 든 펼침의 첫(왼쪽) 쪽.
 *
 * [coverAlone] 이면 표지(0)는 혼자이고 1–2 · 3–4 … 가 짝이다(T4) — 잡지는 표지 다음 쪽부터 양면을 한 판으로
 * 짠다. 0–1 · 2–3 으로 짝지으면 양면 기사 · 광고가 모두 한 쪽씩 어긋나 반쪽끼리 붙는다.
 */
fun spreadStart(page: Int, coverAlone: Boolean): Int = when {
    page <= 0 -> 0
    coverAlone -> page - (page - 1) % 2
    else -> page - page % 2
}

/** [left] 에서 시작하는 펼침의 쪽들(한 쪽 또는 두 쪽). 마지막 쪽이 혼자 남으면 한 쪽이다. */
fun spreadPages(left: Int, pageCount: Int, coverAlone: Boolean): List<Int> =
    if (coverAlone && left == 0) listOf(0) else listOf(left, left + 1).filter { it < pageCount }

/** 화면이 그리는 데 필요한 전부. */
data class PdfState(
    /** 0부터 센 쪽. */
    val page: Int = 0,
    val pageCount: Int = 0,
    val bookmarked: Boolean = false,
    /** 처음 위치를 되찾기 전. 그동안은 첫 쪽을 잠깐 그렸다 옮기는 깜빡임을 막으려고 그리지 않는다. */
    val ready: Boolean = false,
    /** 보이는 쪽들. 한 쪽 보기면 [page] 하나, 두쪽보기면 펼침(왼쪽이 [page]). */
    val shown: List<Int> = listOf(page),
) {
    /** 진도(0~100). 마지막 쪽을 펴면 100 — 두쪽이면 오른쪽 쪽까지 읽은 것으로 센다. */
    val percent: Float get() = if (pageCount <= 0) 0f else ((shown.maxOrNull() ?: page) + 1) * 100f / pageCount
}

/**
 * PDF 한 권을 읽는 동안의 상태와 동작. EPUB 의 `BookReader` 와 같은 역할이다.
 *
 * 위치는 [Locator.FixedPage] 로 저장한다(쪽 + 쪽 안의 세로 위치 천분율). 확대해서 보던 자리까지는
 * 저장하지 않는다 — 다시 열면 그 쪽의 전체가 보이는 편이 어디였는지 알아보기 쉽다.
 *
 * 그리기는 **한 스레드**([renderThread])에서만 한다. PdfRenderer 는 한 번에 한 쪽만 열 수 있다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PdfReader(
    val book: PdfBook,
    private val bookmarkRepository: BookmarkRepository,
    private val progressRepository: ProgressRepository,
    private val renderThread: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(PdfState(pageCount = book.pageCount))
    val state: StateFlow<PdfState> = _state.asStateFlow()

    val title: String get() = book.meta.title

    /**
     * 화면 크기로 그린 쪽. 지금 쪽과 앞뒤 한 쪽씩이면 넘길 때 기다리지 않는다. 쪽 하나가 화면 크기
     * 비트맵(1080×2400 이면 약 10MB)이라 여섯 장까지만 둔다 — 두쪽보기의 지금 · 다음 · 앞 펼침(쪽마다 화면의
     * 절반이라 합이 한 쪽 보기의 세 장과 비슷하다).
     */
    private val pages = LruCache<Triple<Int, Int, Int>, Bitmap>(6)

    /** 저장된 자리로 간다. 없거나 범위를 벗어났으면(파일이 바뀜) 첫 쪽. */
    suspend fun open() {
        val saved = runCatching { progressRepository.get(book.meta.id)?.locator as? Locator.FixedPage }.getOrNull()
        show(saved?.page ?: 0, save = false)
    }

    /** 두쪽보기. null 이면 한 쪽. 값은 표지를 따로 둘지(T4). */
    private var spread: Boolean? = null

    /** 한 쪽 ↔ 두 쪽을 바꾼다(가로로 돌리거나 설정을 바꿀 때). 보던 쪽이 든 펼침으로 간다. */
    suspend fun setSpread(coverAlone: Boolean?) {
        if (spread == coverAlone) return
        spread = coverAlone
        if (_state.value.ready) show(_state.value.page, save = false)
    }

    suspend fun next() {
        val last = _state.value.shown.maxOrNull() ?: _state.value.page
        if (last + 1 < book.pageCount) show(last + 1)
    }

    suspend fun previous() {
        val first = _state.value.page
        if (first > 0) show(first - 1)
    }

    suspend fun goTo(page: Int) = show(page)

    /** 진행 막대. 0..1 → 쪽. 1 은 마지막 쪽이다. */
    suspend fun seek(fraction: Float) {
        val count = _state.value.pageCount
        if (count <= 0) return
        show(pageAt(fraction, count))
    }

    /** 목차. 파일에 없으면 빈 목록. */
    suspend fun outline(): List<TocEntry> = book.outline()

    suspend fun goTo(entry: TocEntry) {
        (entry.locator as? Locator.FixedPage)?.let { show(it.page) }
    }

    suspend fun goTo(bookmark: Bookmark) {
        (bookmark.locator as? Locator.FixedPage)?.let { show(it.page) }
    }

    suspend fun bookmarks(): List<Bookmark> = bookmarkRepository.forBook(book.meta.id)

    suspend fun removeBookmark(bookmark: Bookmark) {
        bookmarkRepository.remove(bookmark.id)
        refreshBookmarked()
    }

    /** 이 쪽에 책갈피가 있으면 빼고, 없으면 꽂는다. */
    suspend fun toggleBookmark() {
        val page = _state.value.page
        val here = bookmarks().filter { (it.locator as? Locator.FixedPage)?.page == page }
        if (here.isEmpty()) {
            bookmarkRepository.add(
                Bookmark(Bookmark.NO_ID, book.meta.id, Locator.FixedPage(page), snippet = "${book.pageLabel(page) ?: (page + 1)}쪽", createdAtEpochMs = clock()),
            )
        } else {
            here.forEach { bookmarkRepository.remove(it.id) }
        }
        refreshBookmarked()
    }

    /**
     * [page] 쪽 전체를 [widthPx]×[heightPx] 로 그린 것. 같은 크기면 다시 그리지 않는다. 깨진 쪽은 null —
     * 화면은 빈 종이를 보여 주고 다른 쪽은 계속 넘길 수 있다.
     */
    suspend fun page(page: Int, widthPx: Int, heightPx: Int): Bitmap? {
        if (widthPx <= 0 || heightPx <= 0 || page !in 0 until book.pageCount) return null
        val key = Triple(page, widthPx, heightPx)
        pages.get(key)?.let { return it }
        return withContext(renderThread) {
            pages.get(key) ?: draw(page, widthPx, heightPx, PageRegion.WHOLE)?.also { pages.put(key, it) }
        }
    }

    /** 이미 그려 둔 쪽이면 기다리지 않고 준다. 없으면 null — [page] 로 그린다. */
    fun cachedPage(page: Int, widthPx: Int, heightPx: Int): Bitmap? = pages.get(Triple(page, widthPx, heightPx))

    /** 확대했을 때 보이는 부분만 화면 해상도로. 캐시하지 않는다 — 조금만 움직여도 다른 구역이다. */
    suspend fun region(page: Int, region: PageRegion, widthPx: Int, heightPx: Int): Bitmap? {
        if (widthPx <= 0 || heightPx <= 0 || region.width <= 0f || region.height <= 0f) return null
        if (page !in 0 until book.pageCount) return null
        return withContext(renderThread) { draw(page, widthPx, heightPx, region) }
    }

    fun close() {
        pages.evictAll()
        book.close()
    }

    private fun draw(page: Int, widthPx: Int, heightPx: Int, region: PageRegion): Bitmap? = runCatching {
        Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888).also { book.source.render(page, it, region) }
    }.getOrNull()

    private suspend fun show(page: Int, save: Boolean = true) {
        val count = book.pageCount
        if (count <= 0) {
            _state.value = _state.value.copy(ready = true)
            return
        }
        val cover = spread
        val wanted = page.coerceIn(0, count - 1)
        // 두쪽이면 이 쪽이 든 펼침의 왼쪽부터 — 목차 · 책갈피가 오른쪽 쪽을 가리켜도 그 펼침이 보인다.
        val target = if (cover != null) spreadStart(wanted, cover) else wanted
        val shown = if (cover != null) spreadPages(target, count, cover) else listOf(target)
        _state.value = _state.value.copy(page = target, pageCount = count, ready = true, shown = shown)
        refreshBookmarked()
        if (save) {
            runCatching {
                progressRepository.save(
                    ReadingProgress(book.meta.id, Locator.FixedPage(target), _state.value.percent.coerceIn(0f, 100f), clock()),
                )
            }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        }
    }

    private suspend fun refreshBookmarked() {
        val page = _state.value.page
        val marked = runCatching { bookmarks().any { (it.locator as? Locator.FixedPage)?.page == page } }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .getOrDefault(false)
        _state.value = _state.value.copy(bookmarked = marked)
    }
}
