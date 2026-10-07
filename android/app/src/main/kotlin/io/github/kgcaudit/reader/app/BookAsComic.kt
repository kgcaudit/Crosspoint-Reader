package io.github.kgcaudit.reader.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.Bookmark
import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.document.ReadingProgress
import io.github.kgcaudit.reader.document.comic.ComicView
import io.github.kgcaudit.reader.document.comic.Work
import io.github.kgcaudit.reader.document.epub.PictureBook
import io.github.kgcaudit.reader.document.image.ImageSize
import io.github.kgcaudit.reader.ui.design.ScreenPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 책을 만화 뷰어로 본 것(0.50.0): 쪽 그림과, 쪽 번호 ↔ 책 자리(Locator)의 짝. 자리 · 책갈피는 책의 것을 그대로 쓴다 — 책으로
 * 보기와 만화로 보기를 오가도 읽던 곳 · 책갈피가 이어지게. 만화의 진도 표에는 쓰지 않는다.
 */
class BookComic private constructor(
    val comic: ComicBook,
    private val locators: (Int) -> Locator,
    private val pages: (Locator) -> Int?,
    /** 책에 적힌 넘기는 방향(EPUB 의 spine). null 은 적혀 있지 않음. */
    val rightToLeft: Boolean?,
) : AutoCloseable {

    fun locatorOf(page: Int): Locator = locators(page.coerceIn(0, (comic.pageCount - 1).coerceAtLeast(0)))

    /** 책 자리가 가리키는 쪽. 이 보기로 나타낼 수 없는 자리(다른 종류의 자리)면 null. */
    fun pageOf(locator: Locator): Int? = pages(locator)?.coerceIn(0, (comic.pageCount - 1).coerceAtLeast(0))

    override fun close() = comic.close()

    companion object {
        /**
         * 그림책 EPUB: 쪽 = 그림. 자리는 (그 그림이 든 장, 장 안에서 몇 번째 그림). 책으로 보기에서 고른 자리(장 안의 글자 자리)는
         * 그 장의 첫 그림으로 — 글자 자리를 그림 순번으로 읽으면 엉뚱한 쪽으로 갔다. 그림 없는 장(판권)을 가리키면 그 뒤의 첫 쪽.
         */
        fun ofPictures(comic: ComicBook, book: PictureBook): BookComic {
            val ordinal = IntArray(book.pages.size)
            val seen = HashMap<Int, Int>()
            book.pages.forEachIndexed { i, p -> ordinal[i] = seen.merge(p.spineIndex, 1, Int::plus)!! - 1 }
            return BookComic(
                comic,
                locators = { i -> Locator.Reflow(book.pages[i].spineIndex, ordinal[i]) },
                pages = { loc ->
                    (loc as? Locator.Reflow)?.let { r ->
                        val first = book.pages.indexOfFirst { it.spineIndex >= r.spine }
                        when {
                            first < 0 -> book.pages.lastIndex
                            book.pages[first].spineIndex != r.spine -> first
                            // 같은 장 안: 만화로 보기가 적은 순번이면 그 그림, 글자 자리(책으로 보기)면 장의 첫 그림.
                            else -> (first + r.charOffset).takeIf { r.charOffset < seen.getValue(r.spine) } ?: first
                        }
                    }
                },
                rightToLeft = book.rightToLeft,
            )
        }

        /** PDF: 쪽 = 쪽. */
        fun ofPdf(comic: ComicBook): BookComic =
            BookComic(comic, locators = { Locator.FixedPage(it) }, pages = { (it as? Locator.FixedPage)?.page }, rightToLeft = null)

        /** 이 쪽까지 읽었을 때의 진도(%). 마지막 쪽이면 100 — 책장이 "다 읽음" 으로 옮긴다. */
        fun percentAt(page: Int, count: Int): Float = if (count <= 1 || page >= count - 1) 100f else page * 100f / (count - 1)
    }
}

/**
 * 책을 만화 뷰어로 연다(0.50.0, 사용자 결정 2 권고안). 보는 방식은 쪽 넘김뿐이다 — 보기 판에 그 줄을 두지 않는다. 넘기는
 * 방향은 사람이 고른 것 → 책에 적힌 것 → 왼→오. 못 열면 [onFail] — 부르는 쪽이 책 뷰어로 연다.
 */
@Composable
fun BookComicHost(
    bookId: String,
    prefs: ScreenPrefs,
    onPrefsChange: (ScreenPrefs) -> Unit,
    onClose: () -> Unit,
    onChrome: (Boolean) -> Unit,
    onFail: () -> Unit,
) {
    val container = LocalContext.current.container
    val data = container.data
    val scope = rememberCoroutineScope()
    val id = BookId(bookId)
    var opened by remember(bookId) { mutableStateOf<BookComic?>(null) }
    var sizes by remember(bookId) { mutableStateOf<List<ImageSize?>>(emptyList()) }
    var start by remember(bookId) { mutableIntStateOf(0) }
    var title by remember(bookId) { mutableStateOf("") }
    var chosenRtl by remember(bookId) { mutableStateOf<Boolean?>(null) }
    var marks by remember(bookId) { mutableStateOf<List<Bookmark>>(emptyList()) }
    LaunchedEffect(bookId) {
        var made: BookComic? = null
        runCatching {
            withContext(Dispatchers.IO) {
                val book = data.library.get(id) ?: throw java.io.FileNotFoundException(bookId)
                val c = container.openAsComic(book).also { made = it }
                sizes = c.comic.sizes()
                val p = data.progress.get(id)
                val at = p?.let { c.pageOf(it.locator) }
                // 다 읽고 끝에 멈춘 책을 다시 열면 처음부터 — 만화와 같다(끝에서 열면 곧바로 "다 읽었습니다" 판이 뜬다).
                start = if (at == null || (p.percent >= 99.95f && at >= c.comic.pageCount - 1)) 0 else at
                // 여는 동안 책 제목을 적었으니 다시 읽는다.
                title = data.library.get(id)?.label ?: book.label
                chosenRtl = data.library.bookRightToLeft(id)
                marks = data.bookmarks.forBook(id)
                c
            }
        }.onSuccess { opened = it }.onFailure {
            made?.close()
            if (it is kotlinx.coroutines.CancellationException) throw it
            android.util.Log.w("OloComic", "cannot open $bookId as a comic", it)
            onFail()
        }
    }
    DisposableEffect(bookId) { onDispose { opened?.close() } }
    val c = opened
    if (c == null) {
        Box(Modifier.fillMaxSize().background(COMIC_BACKDROP))
        return
    }
    val now = { System.currentTimeMillis() }
    val failed: (Exception) -> Unit = { android.util.Log.w("OloComic", "cannot save $bookId", it) }
    val rtl = chosenRtl ?: c.rightToLeft ?: false
    // 만화 뷰어는 작품으로 방향 · 다음 권을 안다. 책은 한 권짜리 작품이다(다음 권이 없다).
    val work = remember(title, rtl) { Work("book|$bookId", title, emptyList(), webtoon = false, complete = false, places = emptyList(), rightToLeft = rtl) }
    ComicReader(
        book = c.comic,
        title = title,
        work = work,
        startPage = start,
        bookmarks = marks.mapNotNull { c.pageOf(it.locator) }.distinct(),
        prefs = prefs,
        onPrefsChange = onPrefsChange,
        onPage = { p ->
            scope.launchWrite(failed) {
                data.progress.save(ReadingProgress(id, c.locatorOf(p), BookComic.percentAt(p, c.comic.pageCount), now()))
            }
        },
        onBookmark = { p ->
            scope.launchWrite(failed) {
                val here = data.bookmarks.forBook(id).filter { c.pageOf(it.locator) == p }
                if (here.isEmpty()) data.bookmarks.add(Bookmark(Bookmark.NO_ID, id, c.locatorOf(p), "${p + 1}쪽", now()))
                else here.forEach { data.bookmarks.remove(it.id) }
                marks = data.bookmarks.forBook(id)
            }
        },
        onDirection = { r ->
            chosenRtl = r
            scope.launchWrite(failed) { data.library.setBookRightToLeft(id, r) }
        },
        onNext = {},
        onClose = onClose,
        onChrome = onChrome,
        view = ComicView.PAGE,
        onView = null,
        sizes = sizes,
    )
}
