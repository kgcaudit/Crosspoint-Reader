package io.github.kgcaudit.reader.reflow

import io.github.kgcaudit.reader.document.InMemoryAnnotations
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.kgcaudit.reader.document.Annotation
import io.github.kgcaudit.reader.document.AnnotationRepository
import io.github.kgcaudit.reader.document.Bookmark
import io.github.kgcaudit.reader.document.HighlightColor
import io.github.kgcaudit.reader.document.BookmarkRepository
import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.document.ProgressRepository
import io.github.kgcaudit.reader.document.ReflowDocument
import io.github.kgcaudit.reader.document.TocEntry
import io.github.kgcaudit.reader.layout.LayoutSpec
import io.github.kgcaudit.reader.layout.Page
import io.github.kgcaudit.reader.layout.book.BookLayout
import io.github.kgcaudit.reader.layout.book.ReadingPosition
import io.github.kgcaudit.reader.layout.book.ReadingSession
import io.github.kgcaudit.reader.layout.cache.PageStore
import io.github.kgcaudit.reader.text.AndroidTextMeasurer
import io.github.kgcaudit.reader.text.BookTypefaces
import io.github.kgcaudit.reader.text.FontCatalog
import io.github.kgcaudit.reader.layout.book.BookFontTable
import io.github.kgcaudit.reader.layout.book.LinkTarget
import io.github.kgcaudit.reader.layout.book.SearchHit
import io.github.kgcaudit.reader.layout.book.Sentence
import io.github.kgcaudit.reader.listen.ListenSource
import io.github.kgcaudit.reader.listen.SpeechChapter
import io.github.kgcaudit.reader.layout.book.splitSentences
import io.github.kgcaudit.reader.layout.html.Link
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 화면이 그리는 데 필요한 전부. 바뀔 때마다 통째로 새로 낸다. */
data class ReaderState(
    val page: Page? = null,
    /** [page] 의 런이 가리키는 챕터 텍스트. */
    val text: String = "",
    val position: ReadingPosition? = null,
    val chapterCount: Int = 0,
    val percent: Float = 0f,
    val bookmarked: Boolean = false,
    /** 조판 중. 첫 페이지가 나오기 전까지만 켜진다. */
    val busy: Boolean = true,
    val error: String? = null,
    /** 이 상태를 만든 조판 설정. 그리는 쪽이 같은 글꼴·크기의 Paint 를 쓰는 근거다. */
    val spec: LayoutSpec? = null,
    /**
     * 두쪽보기의 오른쪽 쪽([page] 와 같은 장, 다음 쪽). 한 쪽 보기이거나 장이 홀수 쪽으로 끝나 비었으면 null.
     * 두쪽보기에서 [page] · [position] 은 왼쪽 쪽이다 — 진도 · 책갈피도 왼쪽 쪽으로 한다.
     */
    val rightPage: Page? = null,
    val spread: Boolean = false,
    /** 이 장의 링크(각주 표시 칠하기 · 누르기). */
    val links: List<Link> = emptyList(),
    /** 찾기 결과로 왔을 때 칠할 자리. 다른 장이면 칠하지 않는다. */
    val highlight: SearchHit? = null,
    /** 링크로 건너왔다 — "읽던 곳으로" 단추를 띄운다(F4 · F5). */
    val canReturn: Boolean = false,
    /** 이 장의 글자 수 · 뒤 장들의 글자 수(추정). 남은 시간을 센다(E5). */
    val chapterLength: Int = 0,
    val charsAfterChapter: Long = 0,
    /** 이 장의 형광펜(N2). 칠하기 · 누르기가 이 장 텍스트의 글자 구간으로 쓴다. */
    val annotations: List<Annotation> = emptyList(),
    /**
     * 형광펜을 고칠 때마다 는다. 독서노트 목록이 이 값으로 다시 모은다 — [annotations] 는 지금 장 것뿐이라, 다른
     * 장의 칠을 목록에서 지우거나 색을 바꾸면 값이 그대로여서 목록이 옛것으로 남는다.
     */
    val notesVersion: Int = 0,
    /** 이 장의 문단 시작 자리. 고른 글을 뜰 때 문단 사이를 한 칸 띄운다(붙으면 "삼킨다.어른들은"). */
    val paragraphStarts: Set<Int> = emptySet(),
)

/**
 * 책 한 권을 읽는 동안의 상태와 동작.
 *
 * 조판과 페이지 읽기는 **한 스레드**([layoutThread])에서만 한다. `AndroidTextMeasurer`
 * 가 스레드 안전하지 않고, 두 조판이 같은 캐시 파일을 동시에 쓰면 안 되기 때문이다.
 * 화면은 [state] 만 본다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookReader(
    /** 본문 글꼴 목록. 화면이 설정 목록과 그리기 측정기를 만들 때도 같은 것을 쓴다. */
    val fonts: FontCatalog,
    val document: ReflowDocument,
    /** 책에 든 글꼴의 목록. 조판(글꼴 번호)과 측정기(서체)가 같은 표를 봐야 한다. */
    private val bookFonts: BookFontTable = BookFontTable.EMPTY,
    /** 책 글꼴을 꺼내 둘 곳. null 이면 책 글꼴을 쓰지 않는다. */
    private val bookFontsDir: File? = null,
    private val store: PageStore,
    private val bookmarkRepository: BookmarkRepository,
    private val progressRepository: ProgressRepository,
    private val annotationRepository: AnnotationRepository = InMemoryAnnotations(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val layoutThread: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) : ListenSource {
    private val _state = MutableStateFlow(ReaderState())
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var layout: BookLayout? = null
    private var session: ReadingSession? = null
    private var spec: LayoutSpec? = null
    private var spread = false

    /**
     * 지금 화면에 보이는 페이지의 첫 글자. 설정을 바꿀 때 여기로 돌아온다.
     *
     * 화면 상태의 페이지 번호에서 매번 되구하지 않는 이유: 조판이 도중에 취소되면(글자 크기를 빠르게
     * 두 번 누름) [layout] 은 새것인데 화면의 페이지 번호는 옛 조판의 것이라, 새 조판에서 엉뚱한
     * 페이지를 가리킨다 — 읽던 자리가 튄다. 실제로 보여 준 순간의 글자를 들고 있으면 그런 일이 없다.
     */
    private var shownLocator: Locator.Reflow? = null
    private val texts = HashMap<Int, String>()
    private var highlight: SearchHit? = null
    private var returnTo: Locator.Reflow? = null

    /** 이 책의 형광펜 전부(읽는 순서). 처음 쪽을 보일 때 한 번 읽고, 고칠 때마다 보관소와 함께 고친다. */
    private var notes: List<Annotation>? = null

    /** 목차 · 책갈피 · 진행 막대 · 찾기로 다른 곳에 가면 "읽던 곳으로" 는 뜻을 잃는다. */
    private fun forgetReturn() {
        returnTo = null
    }
    private val images = LruCache<String, ImageBitmap>(8)

    override val title: String get() = document.meta.title

    /**
     * 책에 **쓸 수 있는** 글꼴이 들어 있다. 글꼴 목록에 "출판사 글꼴" 을 내놓는다.
     *
     * 꺼내 보기 전에는 선언만 보고 판단하고, 꺼내 본 뒤에는 읽힌 것이 있는지를 본다. 모두 WOFF 이거나
     * 깨진 책에서 "출판사 글꼴" 이 켜진 채 휴대폰 글꼴로 그려지면 무엇을 고른 것인지 알 수 없다.
     */
    val hasBookFonts: Boolean get() = !bookFonts.isEmpty && bookFontsDir != null && typefaces?.isEmpty != true

    /** 꺼내 읽은 책 글꼴. 처음으로 책 글꼴로 조판할 때 준비한다. */
    @Volatile
    private var typefaces: BookTypefaces? = null

    /** "출판사 글꼴" 이름을 그릴 서체. 아직 준비 전이면 null. */
    val bookFontPreview: android.graphics.Typeface? get() = typefaces?.preview

    /**
     * [spec] 으로 재고 그리는 측정기. 조판과 그리기가 **같은 서체**를 쓰게 하는 유일한 길이다 —
     * 따로 만들면 책 글꼴로 잰 줄을 본문 글꼴로 그려 줄 끝이 어긋난다.
     */
    fun measurer(spec: LayoutSpec): AndroidTextMeasurer = AndroidTextMeasurer.forSpec(fonts, spec, typefaces)

    /**
     * 조판 설정을 정한다(처음 열 때, 화면 크기나 보기 설정이 바뀔 때).
     *
     * 설정이 바뀌면 **읽던 글자**로 돌아간다. 페이지 번호로 돌아가면 글자 크기를 키운
     * 순간 몇 장 앞으로 튄다.
     */
    suspend fun layOut(newSpec: LayoutSpec, twoPages: Boolean = false) = run {
        if (newSpec == spec && twoPages == spread) return@run
        spread = twoPages
        if (newSpec == spec) {
            // 조판은 그대로이고 한 쪽 ↔ 두 쪽만 바뀌었다(같은 폭). 펼침만 다시 맞춘다.
            _state.value.position?.let { show(it) }
            return@run
        }
        val anchor = shownLocator
        _state.value = _state.value.copy(busy = _state.value.page == null)

        if (newSpec.useBookFonts && typefaces == null && bookFontsDir != null) {
            // 처음 한 번. 수 MB 짜리 글꼴을 꺼내므로 입출력 스레드에서 한다.
            typefaces = withContext(Dispatchers.IO) {
                try {
                    BookTypefaces.prepare(document, bookFonts, bookFontsDir)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    BookTypefaces.EMPTY
                }
            }
        }
        val built = BookLayout(document, newSpec, store, measurer(newSpec), fonts = bookFonts)
        val newSession = ReadingSession(built, bookmarkRepository, progressRepository)
        layout = built
        session = newSession
        spec = newSpec
        texts.clear()

        val position = if (anchor != null) built.resolve(anchor) else newSession.restore()
        show(position)
        // 보인 쪽(두쪽이면 펼침)에는 읽던 글자가 들어 있다. 기준을 그 글자로 남긴다 — 펼침의 왼쪽 쪽 시작으로
        // 바꾸면 가로(두 쪽)로 돌렸다 세로로 돌아올 때마다 한 쪽씩 뒤로 밀린다(ScreenRotationTest 에서 발견).
        if (anchor != null) shownLocator = anchor
        // 옛 설정의 캐시는 다시 쓰일 일이 드물다. 남겨 두면 글자 크기를 바꿀 때마다 쌓인다.
        built.pruneStaleCaches()
    }

    /** 다음 쪽. 두쪽보기면 다음 펼침(두 쪽). */
    suspend fun next() = run {
        move { if (spread) it.nextSpread(requireNotNull(_state.value.position)) else it.next(requireNotNull(_state.value.position)) }
    }

    suspend fun previous() = run {
        move { if (spread) it.previousSpread(requireNotNull(_state.value.position)) else it.previous(requireNotNull(_state.value.position)) }
    }

    suspend fun goTo(bookmark: Bookmark) = run {
        val position = requireSession().goTo(bookmark) ?: return@run
        forgetReturn()
        show(position)
    }

    suspend fun goTo(entry: TocEntry) = run {
        val spine = (entry.locator as? Locator.Reflow)?.spine ?: return@run
        val l = requireLayout()
        forgetReturn()
        show(l.resolve(l.locatorForAnchor(spine, entry.anchor)))
    }

    /** 진행 막대로 옮긴다. [fraction] 은 0~1. */
    suspend fun seek(fraction: Float) = run {
        val l = requireLayout()
        forgetReturn()
        show(l.resolve(l.locatorAtPercent(fraction * 100f)))
    }

    // ── 찾기(E2 · E3) ───────────────────────────────────────────────

    /** 장 수. 찾기가 "2 / 3 장" 을 보이는 데 쓴다. */
    suspend fun chapterCount(): Int = run { requireLayout().spine().size }

    /** 장 하나에서 찾는다. 장마다 따로 조판 스레드를 잡았다 놓는다 — 찾는 동안에도 쪽을 넘길 수 있다. */
    suspend fun searchChapter(spineIndex: Int, query: String): List<SearchHit> =
        run { requireLayout().searchChapter(spineIndex, query) }

    /** 찾은 자리가 책의 몇 %. */
    suspend fun percentOf(hit: SearchHit): Float = run { requireLayout().percentAt(hit.spine, hit.start) }

    /** 찾은 자리로 가서 칠한다. 두쪽이면 그 자리가 든 펼침. */
    suspend fun goTo(hit: SearchHit) = run {
        val l = requireLayout()
        forgetReturn()
        highlight = hit
        show(l.resolve(Locator.Reflow(hit.spine, hit.start)))
    }

    /** 찾기를 끝냈다 — 칠한 것을 지운다. */
    suspend fun clearHighlight() = run {
        highlight = null
        _state.value = _state.value.copy(highlight = null)
    }

    // ── 링크 · 각주(F1–F6) ──────────────────────────────────────────

    /** 누른 링크가 어디로 가는가. 각주면 내용까지 뽑아 준다. */
    suspend fun resolve(link: Link): LinkTarget = run {
        val spine = _state.value.position?.spineIndex ?: return@run LinkTarget.Missing
        requireLayout().resolveLink(spine, link)
    }

    /**
     * 링크가 가리키는 자리로 건너간다(각주 자리로 가기 · 본문 속 링크). 지금 보던 글자를 기억해 두어
     * [returnBack] 으로 돌아온다 — 건너뛴 뒤 쪽을 몇 장 넘겨도 "읽던 곳" 은 건너뛰기 전 그 자리다.
     */
    suspend fun jumpTo(spineIndex: Int, anchor: String?, markLength: Int = 0) = run {
        val l = requireLayout()
        if (returnTo == null) returnTo = shownLocator
        // 각주 자리로 가면 그 각주를 칠한다(F4) — 주석 장에는 비슷한 문단이 줄지어 있어 어느 것인지 찾아야 한다.
        if (markLength > 0) {
            val at = l.anchorOffset(spineIndex, anchor)
            highlight = SearchHit(spineIndex, at, at + markLength, "", 0)
        }
        show(l.resolve(l.locatorForAnchor(spineIndex, anchor)))
    }

    /** 건너뛰기 전 읽던 곳으로. */
    suspend fun returnBack() = run {
        val back = returnTo ?: return@run
        returnTo = null
        highlight = null
        show(requireLayout().resolve(back))
    }

    /** 이 페이지의 책갈피를 꽂거나 뺀다. */
    suspend fun toggleBookmark() = run {
        val position = _state.value.position ?: return@run
        val s = requireSession()
        val nowMarked = if (s.isBookmarked(position)) {
            s.removeBookmarkAt(position)
            false
        } else {
            s.addBookmark(position)
            true
        }
        _state.value = _state.value.copy(bookmarked = nowMarked)
    }

    suspend fun bookmarks(): List<Bookmark> = run { requireSession().bookmarks() }

    suspend fun removeBookmark(bookmark: Bookmark) = run {
        bookmarkRepository.remove(bookmark.id)
        _state.value.position?.let { _state.value = _state.value.copy(bookmarked = requireSession().isBookmarked(it)) }
    }

    // ── 형광펜 · 메모(N1–N4) ────────────────────────────────────────

    /** 이 책의 형광펜 전부. 독서노트가 쓴다. */
    suspend fun annotations(): List<Annotation> = run { loadNotes() }

    /**
     * 지금 장의 [start]..[end] 를 칠한다. 칠한 글은 장 텍스트에서 떠 둔다(목록 · 내보내기용).
     *
     * 똑같은 구간이 이미 칠해져 있으면 새로 만들지 않고 색 · 메모만 바꾼다 — 같은 곳을 두 번 칠하면 목록에
     * 똑같은 줄이 둘 생기고, 하나를 지워도 칠이 남아 "지웠는데 안 지워진다" 로 보인다.
     */
    suspend fun highlight(start: Int, end: Int, color: HighlightColor, note: String? = null): Annotation? = run {
        val spine = _state.value.position?.spineIndex ?: return@run null
        val text = _state.value.text
        val from = start.coerceIn(0, text.length)
        val to = end.coerceIn(from, text.length)
        if (from >= to) return@run null
        val same = loadNotes().firstOrNull { it.start == Locator.Reflow(spine, from) && it.end == Locator.Reflow(spine, to) }
        val saved = if (same != null) {
            val changed = same.copy(color = color).withNote(note ?: same.note)
            annotationRepository.update(changed)
            changed
        } else {
            annotationRepository.add(
                Annotation(
                    id = Annotation.NO_ID,
                    bookId = requireLayout().bookId,
                    start = Locator.Reflow(spine, from),
                    end = Locator.Reflow(spine, to),
                    color = color,
                    note = note,
                    snippet = snippetOf(text, from, to, _state.value.paragraphStarts),
                    createdAtEpochMs = clock(),
                ).withNote(note),
            )
        }
        notes = null
        refreshNotes()
        saved
    }

    /** 색 · 메모를 바꾼다. */
    suspend fun update(annotation: Annotation) = run {
        annotationRepository.update(annotation)
        notes = null
        refreshNotes()
    }

    suspend fun remove(annotation: Annotation) = run {
        annotationRepository.remove(annotation.id)
        notes = null
        refreshNotes()
    }

    /** 칠한 곳으로 간다(독서노트에서 누름). */
    suspend fun goTo(annotation: Annotation) = run {
        forgetReturn()
        show(requireLayout().resolve(annotation.start))
    }

    /**
     * 칠이 여러 쪽에 걸치면 그 쪽들(장 안의 1부터 센 번호, 화면 아래 "5 / 12" 와 같은 수). 한 쪽 안이면 null.
     * 쪽을 넘어 이어 고른 칠을 독서노트에서 눌렀을 때 첫 쪽만 보고 "잘렸다" 고 오해하지 않게 알린다.
     */
    suspend fun pagesOf(annotation: Annotation): IntRange? = run {
        val l = requireLayout()
        // 끝은 칠 바깥의 첫 글자라, 그대로 찾으면 쪽 끝에서 멈춘 칠이 다음 쪽에 걸친 것으로 보인다.
        val last = Locator.Reflow(annotation.end.spine, (annotation.end.charOffset - 1).coerceAtLeast(0))
        val a = l.resolve(annotation.start)
        val b = l.resolve(last)
        if (a.spineIndex != b.spineIndex || a.pageIndex == b.pageIndex) null else (a.pageIndex + 1)..(b.pageIndex + 1)
    }

    /** 이 자리가 책의 몇 %(독서노트의 "3%"). */
    suspend fun percentOf(locator: Locator.Reflow): Float = run { requireLayout().percentAt(locator.spine, locator.charOffset) }

    private suspend fun loadNotes(): List<Annotation> =
        notes ?: runCatching { annotationRepository.forBook(requireLayout().bookId) }.getOrDefault(emptyList()).also { notes = it }

    private suspend fun refreshNotes() {
        val spine = _state.value.position?.spineIndex ?: return
        _state.value = _state.value.copy(
            annotations = loadNotes().filter { it.start.spine == spine },
            notesVersion = _state.value.notesVersion + 1,
        )
    }

    // ── 듣기(4단계) ──────────────────────────────────────────────────

    /** 장 하나의 문장들. 듣기가 장을 넘어갈 때 부른다. 장이 깨졌으면 빈 목록(그 장을 건너뛴다). */
    override suspend fun speech(unit: Int): SpeechChapter = run {
        val spineIndex = unit
        val l = requireLayout()
        if (spineIndex !in 0 until l.spine().size) return@run SpeechChapter(spineIndex, "", emptyList())
        val text = runCatching { l.chapterText(spineIndex).orEmpty() }.getOrDefault("")
        val sentences = splitSentences(text, l.paragraphStarts(spineIndex))
        SpeechChapter(spineIndex, text, sentences)
    }

    override suspend fun unitCount(): Int = chapterCount()

    /**
     * 글자 [offset] 이 든 쪽을 보인다(듣는 문장을 쪽이 따라간다). 이미 보이는 쪽(두쪽이면 펼침)이면 아무것도 하지
     * 않는다 — 문장마다 다시 그리면 화면이 깜빡이고 진도를 쓸데없이 저장한다.
     */
    override suspend fun follow(unit: Int, offset: Int): Unit = run {
        val spineIndex = unit
        val st = _state.value
        val shownEnd = (st.rightPage ?: st.page)?.endCharExclusive ?: -1
        val shownStart = st.page?.startChar ?: -1
        if (st.position?.spineIndex == spineIndex && offset in shownStart until shownEnd) return@run
        show(requireLayout().resolve(Locator.Reflow(spineIndex, offset)))
    }

    suspend fun outline(): List<TocEntry> = run { document.outline() }

    /**
     * 페이지에 놓인 그림을 지면 크기에 맞춰 디코드한다.
     *
     * 원본 해상도로 디코드하지 않는다(가벼움 규칙 5). 스캔본 EPUB 의 그림은 한 장에
     * 수십 MB 가 되어, 몇 장만 넘겨도 메모리가 바닥난다.
     */
    suspend fun image(spineIndex: Int, href: String, widthPx: Int, heightPx: Int): ImageBitmap? {
        val key = "$spineIndex|$href|$widthPx"
        images.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val bytes = runCatching { document.openChapterResource(spineIndex, href)?.use { it.readBytes() } }
                .getOrNull() ?: return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= widthPx && bounds.outHeight / (sample * 2) >= heightPx) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()?.also { images.put(key, it) }
        }
    }

    /** 문서를 닫는다. EPUB 은 파일 디스크립터를 들고 있다. */
    fun close() {
        (document as? AutoCloseable)?.runCatching { close() }
    }

    // ── 내부 ────────────────────────────────────────────────────────

    private suspend fun <T> run(block: suspend () -> T): T = withContext(layoutThread) {
        mutex.withLock {
            try {
                block()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // 한 챕터가 깨졌다고 책 전체를 닫지 않는다. 화면이 알리고 사용자가 넘기거나
                // 목차로 건너뛸 수 있게 둔다.
                _state.value = _state.value.copy(busy = false, error = e.message ?: e.javaClass.simpleName)
                throw e
            }
        }
    }

    private suspend fun move(step: suspend (BookLayout) -> ReadingPosition?) {
        val next = step(requireLayout()) ?: return
        show(next)
    }

    private suspend fun show(target: ReadingPosition) {
        val l = requireLayout()
        // 두쪽보기면 이 쪽이 든 펼침의 왼쪽부터 — 목차 · 책갈피가 오른쪽 쪽을 가리켜도 그 펼침이 보인다.
        val position = if (spread) l.spreadStart(target) else target
        val page = l.page(position.spineIndex, position.pageIndex)
        val right = if (spread) l.spreadRight(position)?.let { l.page(it.spineIndex, it.pageIndex) } else null
        val text = texts.getOrPut(position.spineIndex) { l.chapterText(position.spineIndex).orEmpty() }
        // 앞뒤 챕터 캐시가 너무 쌓이지 않게 지금 챕터 근처만 들고 있는다.
        texts.keys.retainAll { kotlin.math.abs(it - position.spineIndex) <= 1 }

        val s = requireSession()
        val links = l.links(position.spineIndex)
        val length = l.chapterLength(position.spineIndex)
        val after = l.charsAfterChapter(position.spineIndex)
        val saved = s.saveProgress(position)
        shownLocator = l.locatorAt(position.spineIndex, position.pageIndex)
        _state.value = ReaderState(
            page = page,
            text = text,
            position = position,
            chapterCount = l.spine().size,
            percent = saved.percent,
            bookmarked = s.isBookmarked(position),
            busy = false,
            error = null,
            spec = spec,
            rightPage = right,
            spread = spread,
            links = links,
            highlight = highlight?.takeIf { it.spine == position.spineIndex },
            canReturn = returnTo != null,
            chapterLength = length,
            charsAfterChapter = after,
            annotations = loadNotes().filter { it.start.spine == position.spineIndex },
            notesVersion = _state.value.notesVersion,
            paragraphStarts = l.paragraphStarts(position.spineIndex),
        )
    }

    private fun requireLayout() = checkNotNull(layout) { "layOut() first" }
    private fun requireSession() = checkNotNull(session) { "layOut() first" }
}

/**
 * 칠한 글 토막. 문단 사이는 한 칸([paragraphStarts]), 줄바꿈 · 겹친 공백도 한 칸으로(목록에서 한 줄로 읽히게),
 * 너무 길면 자른다 — 한 쪽 전체를 칠해도 목록 한 줄이 화면을 덮지 않게.
 */
internal fun snippetOf(text: String, start: Int, end: Int, paragraphStarts: Set<Int> = emptySet(), max: Int = 400): String {
    val from = start.coerceIn(0, text.length)
    val to = end.coerceIn(from, text.length)
    val raw = StringBuilder(to - from + 8)
    for (i in from until to) {
        if (i > from && i in paragraphStarts) raw.append(' ')
        raw.append(text[i])
    }
    val flat = raw.replace(Regex("\\s+"), " ").trim()
    return if (flat.length <= max) flat else flat.take(max).trimEnd() + "…"
}


