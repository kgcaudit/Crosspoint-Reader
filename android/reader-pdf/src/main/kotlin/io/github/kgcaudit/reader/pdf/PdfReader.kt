package io.github.kgcaudit.reader.pdf

import android.graphics.Bitmap
import android.util.LruCache
import io.github.kgcaudit.reader.document.Annotation
import io.github.kgcaudit.reader.document.AnnotationRepository
import io.github.kgcaudit.reader.document.Bookmark
import io.github.kgcaudit.reader.document.HighlightColor
import io.github.kgcaudit.reader.document.InMemoryAnnotations
import io.github.kgcaudit.reader.layout.book.SearchHit
import io.github.kgcaudit.reader.layout.book.findAll
import io.github.kgcaudit.reader.listen.ListenSource
import io.github.kgcaudit.reader.listen.SpeechChapter
import kotlinx.coroutines.ensureActive
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
    /** 이 책의 형광펜 전부(쪽마다 걸러 그린다). */
    val notes: List<Annotation> = emptyList(),
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
    private val annotationRepository: AnnotationRepository = InMemoryAnnotations(),
    private val clock: () -> Long = System::currentTimeMillis,
) : ListenSource {
    private val _state = MutableStateFlow(PdfState(pageCount = book.pageCount))
    val state: StateFlow<PdfState> = _state.asStateFlow()

    override val title: String get() = book.meta.title

    /**
     * 화면 크기로 그린 쪽. 지금 쪽과 앞뒤 한 쪽씩이면 넘길 때 기다리지 않는다. 장 수가 아니라 **바이트**로 센다 —
     * 쪽 전체는 한 장 약 7–10MB 라 여섯 장 남짓이지만, 가로 화면의 폭 맞춤은 한 장이 30MB 를 넘어 여섯 장이면
     * 180MB 다(0.17.0 시험에서 메모리가 모자랐다). 폭 맞춤에서는 지금 · 다음 두 장이 남고, 앞 쪽을 미리 그리면
     * 가장 오래된 것부터 내린다.
     */
    private val pages = object : LruCache<Triple<Int, Int, Int>, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: Triple<Int, Int, Int>, value: Bitmap): Int = value.allocationByteCount
    }

    /** 저장된 자리로 간다. 없거나 범위를 벗어났으면(파일이 바뀜) 첫 쪽. */
    suspend fun open() {
        val saved = runCatching { progressRepository.get(book.meta.id)?.locator as? Locator.FixedPage }.getOrNull()
        show(saved?.page ?: 0, save = false)
        refreshNotes()
    }

    // ── 글자 층(안드로이드 15+) ────────────────────────────────────────

    /** 이 휴대폰의 엔진이 글자를 꺼낼 수 있는가. 아니면 찾기 · 형광펜 · 듣기 단추가 없다. */
    val readsText: Boolean get() = book.source.readsText

    /** 쪽마다의 글(찾기 · 스캔본 판정). 글은 작아 모두 둔다 — 찾을 때마다 엔진을 다시 부르면 수백 쪽이 느리다. */
    private val texts = HashMap<Int, String>()

    /** 글자 네모까지 든 층(고르기 · 칠 · 문장 칠). 비싸서 최근 몇 쪽만 둔다. */
    private val layers = LruCache<Int, PageText>(LAYER_PAGES)

    private var readable: Boolean? = null

    /** [page] 쪽의 글. 없거나 못 꺼내면 빈 글. */
    suspend fun plainText(page: Int): String {
        if (!readsText || page !in 0 until book.pageCount) return ""
        synchronized(texts) { texts[page] }?.let { return it }
        val text = withContext(renderThread) { runCatching { book.source.pageText(page) }.getOrNull() }.orEmpty()
            .let(BrokenHangul::repair)
        synchronized(texts) { texts[page] = text }
        return text
    }

    /** [page] 쪽의 글자 층. 없거나 못 꺼내면 빈 층. */
    suspend fun textLayer(page: Int): PageText {
        if (!readsText || page !in 0 until book.pageCount) return PageText.EMPTY
        layers.get(page)?.let { return it }
        val layer = withContext(renderThread) { runCatching { book.source.textLayer(page) }.getOrNull() }
            ?.let { it.repaired().inVisualOrder() } ?: PageText.EMPTY
        layers.put(page, layer)
        return layer
    }

    /** 이미 꺼내 둔 층이면 기다리지 않고 준다(그리기 중에 부른다). */
    fun cachedLayer(page: Int): PageText? = layers.get(page)

    /**
     * 사람이 읽을 글이 있는 PDF 인가(결정 1: 스캔본이면 단추를 누를 때 알린다). 앞에서부터 [SCAN_PAGES] 쪽까지
     * 보고 하나라도 읽을 글이 있으면 그렇다 — 표지 · 속표지는 그림뿐인 책이 많다. 한 번 보면 기억한다.
     */
    suspend fun hasText(): Boolean {
        readable?.let { return it }
        if (!readsText) return false.also { readable = it }
        val here = _state.value.page
        val pages = (listOf(here) + (0 until book.pageCount).take(SCAN_PAGES)).distinct()
        return pages.any { PageText.isReadable(plainText(it)) }.also { readable = it }
    }

    /**
     * 찾기(4-1): 앞 쪽부터 차례로 찾아 쪽마다 [onPage] 로 넘긴다(찾는 대로 목록에 쌓인다). 부르는 쪽이 취소하면
     * 멈춘다. [findAll] 은 EPUB 과 같다 — 대소문자 · 공백 개수를 가리지 않아 줄에서 끊긴 말("확대\r\n합니다")도 찾는다.
     */
    suspend fun search(query: String, onPage: suspend (page: Int, hits: List<SearchHit>) -> Unit) {
        for (page in 0 until book.pageCount) {
            kotlin.coroutines.coroutineContext.ensureActive()
            // 줄 끝의 "\r" 을 한 칸으로(자리 수는 그대로) — 두면 목록의 문맥에 보이지 않는 글자가 끼어 줄이 깨진다.
            val text = plainText(page).replace('\r', ' ')
            onPage(page, if (PageText.isReadable(text)) findAll(text, query, page) else emptyList())
        }
    }

    // ── 형광펜(4-2) ──────────────────────────────────────────────────

    /**
     * 칠한다. 자리는 [Locator.Reflow] 에 쪽 번호와 그 쪽 글자 층의 오프셋을 담는다 — PDF 는 글자 크기를 바꿔도
     * 글이 다시 흐르지 않아 (쪽, 글자) 가 늘 같은 글자다. 한 쪽 안에서만 칠한다(결정 2).
     */
    suspend fun highlight(page: Int, start: Int, endExclusive: Int, color: HighlightColor, note: String? = null): Annotation? {
        if (start >= endExclusive) return null
        val layer = textLayer(page)
        val a = Annotation(
            Annotation.NO_ID, book.meta.id, Locator.Reflow(page, start), Locator.Reflow(page, endExclusive), color, null,
            snippet = layer.quote(start, endExclusive), createdAtEpochMs = clock(),
        ).withNote(note)
        return runCatching { annotationRepository.add(a) }.getOrNull().also { refreshNotes() }
    }

    suspend fun update(annotation: Annotation) {
        runCatching { annotationRepository.update(annotation) }
        refreshNotes()
    }

    suspend fun remove(annotation: Annotation) {
        runCatching { annotationRepository.remove(annotation.id) }
        refreshNotes()
    }

    suspend fun annotations(): List<Annotation> =
        runCatching { annotationRepository.forBook(book.meta.id) }.getOrDefault(emptyList())

    /** 칠한 곳으로 간다(독서노트에서 누름). */
    suspend fun goTo(annotation: Annotation) = show(annotation.start.spine)

    private suspend fun refreshNotes() {
        _state.value = _state.value.copy(notes = annotations())
    }

    // ── 듣기(4-3) — 쪽 하나가 듣기의 한 단위다 ───────────────────────────

    override suspend fun unitCount(): Int = book.pageCount

    /** [unit] 쪽의 문장들. 머리말 · 쪽 번호는 뺀다(결정 4). 글이 없는 쪽(그림 · 스캔)은 문장 없음 — 듣기가 건너뛴다. */
    override suspend fun speech(unit: Int): SpeechChapter {
        val layer = textLayer(unit)
        if (!PageText.isReadable(layer.text)) return SpeechChapter(unit, layer.text, emptyList())
        val speech = layer.speech()
        return SpeechChapter(unit, speech.text, speech.sentences)
    }

    /** 읽는 쪽을 따라 넘긴다. 이미 보이면(두쪽이면 펼침) 그대로 — 문장마다 진도를 쓰지 않는다. */
    override suspend fun follow(unit: Int, offset: Int) {
        if (unit !in _state.value.shown) show(unit)
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

/** 쪽 그림 캐시의 크기. 예전 "화면 크기 여섯 장"(1080×2400 × 6 ≈ 60MB)과 같은 몫. */
private const val CACHE_BYTES = 64 * 1024 * 1024

/** 글자 층을 둘 쪽 수. 지금 · 앞뒤 · 두쪽 펼침 · 찾은 결과 몇 쪽이면 충분하다(쪽마다 네모 수천 개). */
private const val LAYER_PAGES = 8

/** 스캔본인지 볼 앞쪽 쪽 수. 표지 · 속표지 · 목차 그림을 넘어 본문이 나올 만큼. */
private const val SCAN_PAGES = 12
