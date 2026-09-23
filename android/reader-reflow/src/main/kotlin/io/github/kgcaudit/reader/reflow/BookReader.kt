package io.github.kgcaudit.reader.reflow

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.kgcaudit.reader.document.Bookmark
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
    private val layoutThread: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) {
    private val _state = MutableStateFlow(ReaderState())
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var layout: BookLayout? = null
    private var session: ReadingSession? = null
    private var spec: LayoutSpec? = null
    private val texts = HashMap<Int, String>()
    private val images = LruCache<String, ImageBitmap>(8)

    val title: String get() = document.meta.title

    /** 책에 글꼴이 들어 있다. 글꼴 목록에 "출판사 글꼴" 을 내놓는다. */
    val hasBookFonts: Boolean get() = !bookFonts.isEmpty && bookFontsDir != null

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
    suspend fun layOut(newSpec: LayoutSpec) = run {
        if (newSpec == spec) return@run
        val anchor = currentLocator()
        _state.value = _state.value.copy(busy = _state.value.page == null)

        if (newSpec.useBookFonts && typefaces == null && bookFontsDir != null) {
            // 처음 한 번. 수 MB 짜리 글꼴을 꺼내므로 입출력 스레드에서 한다.
            typefaces = withContext(Dispatchers.IO) {
                runCatching { BookTypefaces.prepare(document, bookFonts, bookFontsDir) }.getOrDefault(BookTypefaces.EMPTY)
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
        // 옛 설정의 캐시는 다시 쓰일 일이 드물다. 남겨 두면 글자 크기를 바꿀 때마다 쌓인다.
        built.pruneStaleCaches()
    }

    suspend fun next() = run { move { it.next(requireNotNull(_state.value.position)) } }

    suspend fun previous() = run { move { it.previous(requireNotNull(_state.value.position)) } }

    suspend fun goTo(bookmark: Bookmark) = run {
        val position = requireSession().goTo(bookmark) ?: return@run
        show(position)
    }

    suspend fun goTo(entry: TocEntry) = run {
        val spine = (entry.locator as? Locator.Reflow)?.spine ?: return@run
        val l = requireLayout()
        show(l.resolve(l.locatorForAnchor(spine, entry.anchor)))
    }

    /** 진행 막대로 옮긴다. [fraction] 은 0~1. */
    suspend fun seek(fraction: Float) = run {
        val l = requireLayout()
        show(l.resolve(l.locatorAtPercent(fraction * 100f)))
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

    private suspend fun show(position: ReadingPosition) {
        val l = requireLayout()
        val page = l.page(position.spineIndex, position.pageIndex)
        val text = texts.getOrPut(position.spineIndex) { l.chapterText(position.spineIndex).orEmpty() }
        // 앞뒤 챕터 캐시가 너무 쌓이지 않게 지금 챕터 근처만 들고 있는다.
        texts.keys.retainAll { kotlin.math.abs(it - position.spineIndex) <= 1 }

        val s = requireSession()
        val saved = s.saveProgress(position)
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
        )
    }

    private suspend fun currentLocator(): Locator.Reflow? {
        val l = layout ?: return null
        val position = _state.value.position ?: return null
        return l.locatorAt(position.spineIndex, position.pageIndex)
    }

    private fun requireLayout() = checkNotNull(layout) { "layOut() first" }
    private fun requireSession() = checkNotNull(session) { "layOut() first" }
}
