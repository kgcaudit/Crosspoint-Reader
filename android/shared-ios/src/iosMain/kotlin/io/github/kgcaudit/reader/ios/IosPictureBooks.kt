package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.Bookmark
import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.document.ReadingProgress
import io.github.kgcaudit.reader.document.comic.SpreadSplit
import io.github.kgcaudit.reader.document.epub.EpubDocument
import io.github.kgcaudit.reader.document.epub.PictureBook
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** 만화 뷰어가 처음 열 자리. */
data class ComicStart(val page: Int, val half: SpreadSplit.Half)

/**
 * 만화 뷰어가 자리 · 책갈피를 적는 곳(구상안 46). 만화 권은 권의 기록([UnitComicPlace]), 만화로 보는 책은 책의 기록
 * ([BookComicPlace]) — 뷰어는 어느 쪽인지 모른다.
 */
interface IosComicPlace {
    /** 처음 열 쪽(다 읽고 끝에서 멈춘 것은 처음부터). null 이면 처음. */
    fun start(pageCount: Int): ComicStart?

    /** 판을 보였다: 앞 쪽 [first] ~ 끝 쪽 [last], 마지막 판이면 [atEnd]. 진도(0..1)를 준다. */
    fun save(first: Int, last: Int, pageCount: Int, atEnd: Boolean, half: SpreadSplit.Half): Float

    fun marks(): List<Int>

    /** 보이는 쪽들의 책갈피를 켜고 끈다. 하나라도 있으면 지우고(false), 없으면 앞 쪽에 꽂는다(true). */
    fun toggle(pages: List<Int>): Boolean
}

/** 만화 권: 권의 쪽 기록(`C` · `K` 줄) — 지금까지와 같다. */
class UnitComicPlace(private val store: ReadingStore, private val unitId: String) : IosComicPlace {
    override fun start(pageCount: Int): ComicStart? {
        val saved = store.comicProgress()[unitId] ?: return null
        // 다 읽고 마지막 쪽에서 멈춘 권을 다시 열면 처음부터(안드로이드와 같다).
        if (saved.finishedAtEpochMs != null && saved.page >= pageCount - 1) return null
        return ComicStart(saved.page.coerceIn(0, maxOf(pageCount - 1, 0)), saved.half ?: SpreadSplit.Half.WHOLE)
    }

    override fun save(first: Int, last: Int, pageCount: Int, atEnd: Boolean, half: SpreadSplit.Half): Float =
        store.saveComic(unitId, last, pageCount, atEnd, half.takeIf { it != SpreadSplit.Half.WHOLE }?.name, null).fraction

    override fun marks(): List<Int> = store.comicBookmarks(unitId)

    override fun toggle(pages: List<Int>): Boolean = store.toggleComicBookmark(unitId, pages)
}

/**
 * 책을 만화 뷰어로 본다(안드로이드 `BookComic`): 책의 자리 · 책갈피를 그대로 쓴다 — 책으로 보기와 만화로 보기를 오가도 읽던 곳 ·
 * 책갈피 · 다 읽음이 이어진다. 만화의 진도 표에는 쓰지 않는다. 쪽 ↔ 자리 짝만 책 종류마다 다르다.
 */
abstract class BookPlace(private val store: ReadingStore, private val bookId: String) : IosComicPlace {
    abstract fun locatorOf(page: Int): Locator

    /** 책 자리가 가리키는 쪽. 이 보기로 나타낼 수 없는 자리(다른 종류)면 null. */
    abstract fun pageOf(locator: Locator): Int?

    override fun start(pageCount: Int): ComicStart? {
        val p = store.progressOf(bookId) ?: return null
        val at = pageOf(p.locator)?.coerceIn(0, maxOf(pageCount - 1, 0)) ?: return null
        // 다 읽고 끝에 멈춘 책을 다시 열면 처음부터 — 만화와 같다.
        if (p.percent >= FINISHED && at >= pageCount - 1) return null
        return ComicStart(at, SpreadSplit.Half.WHOLE)
    }

    /** 펼침면의 반은 버린다 — 책의 자리는 Locator 하나라 반을 담을 칸이 없다(안드로이드와 같다). 진도는 끝 쪽으로. */
    @OptIn(ExperimentalTime::class)
    override fun save(first: Int, last: Int, pageCount: Int, atEnd: Boolean, half: SpreadSplit.Half): Float {
        val percent = percentAt(last, pageCount, atEnd)
        store.saveProgress(ReadingProgress(BookId(bookId), locatorOf(first), percent, Clock.System.now().toEpochMilliseconds()))
        return percent / 100f
    }

    override fun marks(): List<Int> = store.bookmarksOf(bookId).mapNotNull { pageOf(it.locator) }.distinct().sorted()

    @OptIn(ExperimentalTime::class)
    override fun toggle(pages: List<Int>): Boolean {
        val here = store.bookmarksOf(bookId).filter { b -> pageOf(b.locator) in pages }
        if (here.isNotEmpty()) {
            here.forEach { store.removeBookmark(it.id) }
            return false
        }
        val p = pages.firstOrNull() ?: return false
        store.addBookmark(Bookmark(Bookmark.NO_ID, BookId(bookId), locatorOf(p), "${p + 1}쪽", Clock.System.now().toEpochMilliseconds()))
        return true
    }

    companion object {
        private const val FINISHED = 99.95f

        /** 끝이 아닌 자리의 진도 한도 — 다 읽음 문턱(99.95)보다 낮다. 펼침면 첫 반만 보고 다 읽은 책이 되면 안 된다. */
        private const val NOT_YET_FINISHED = 99.9f

        /** 이 쪽까지 읽었을 때의 진도(%, 안드로이드 `BookComic.percentAt`). 끝(마지막 조각)이면 100. */
        fun percentAt(page: Int, count: Int, atEnd: Boolean): Float = when {
            atEnd -> 100f
            count <= 1 -> 0f
            else -> minOf(page * 100f / (count - 1), NOT_YET_FINISHED)
        }
    }
}

/** PDF 를 만화로(안드로이드 `BookComic.ofPdf`, 구상안 46 ②): 쪽 = 쪽. PDF 보기가 적은 쪽 안 세로 자리는 버린다(쪽 머리부터). */
class PdfComicPlace(store: ReadingStore, bookId: String, private val pageCount: Int) : BookPlace(store, bookId) {
    override fun locatorOf(page: Int): Locator = Locator.FixedPage(page.coerceIn(0, maxOf(pageCount - 1, 0)))

    override fun pageOf(locator: Locator): Int? = (locator as? Locator.FixedPage)?.page?.coerceIn(0, maxOf(pageCount - 1, 0))
}

/**
 * 그림책을 만화 뷰어로(안드로이드 `BookComic.ofPictures`): 쪽 = 그림, 자리는 (그림이 든 장, 장 안에서 몇 번째 그림).
 */
class BookComicPlace(store: ReadingStore, bookId: String, private val pages: List<PictureBook.Page>) : BookPlace(store, bookId) {
    private val ordinal = IntArray(pages.size)
    private val perChapter = HashMap<Int, Int>()

    init {
        pages.forEachIndexed { i, p ->
            ordinal[i] = perChapter[p.spineIndex] ?: 0
            perChapter[p.spineIndex] = ordinal[i] + 1
        }
    }

    override fun locatorOf(page: Int): Locator {
        val i = page.coerceIn(0, maxOf(pages.size - 1, 0))
        return Locator.Reflow(pages[i].spineIndex, ordinal[i])
    }

    /**
     * 책 자리가 가리키는 쪽. 같은 장 안: 만화로 보기가 적은 순번이면 그 그림, 글자 자리(책으로 보기)면 장의 첫 그림 — 글자 자리를
     * 그림 순번으로 읽으면 엉뚱한 쪽으로 갔다. 그림 없는 장(판권)이면 그 뒤의 첫 쪽, 끝 너머면 마지막. 다른 종류의 자리면 null.
     */
    override fun pageOf(locator: Locator): Int? {
        val r = locator as? Locator.Reflow ?: return null
        if (pages.isEmpty()) return null
        val first = pages.indexOfFirst { it.spineIndex >= r.spine }
        return when {
            first < 0 -> pages.lastIndex
            pages[first].spineIndex != r.spine -> first
            r.charOffset < (perChapter[r.spine] ?: 0) -> first + r.charOffset
            else -> first
        }
    }
}

/**
 * 그림 위주 책(구상안 46, 안드로이드 0.50.0): 판별(공통 `PictureBooks`) · 보기 설정. 설정은 작품 설정 표에 책 id 로 —
 * `BOOK_VIEW`(COMIC · BOOK, 없으면 판별대로) · `BOOK_RTL`(1 · 0) · `PICTURES`("크기:1|0:책에 적힌 방향 1|0|-", 파일 크기가 바뀌면
 * 다시 판별). 적힌 방향까지 두는 것은 서재 표지(펼침면의 앞 반) 때문이다 — 서재를 그릴 때마다 책을 열어 볼 수는 없다.
 */
object IosPictureBooks {
    const val VIEW = "BOOK_VIEW"
    const val RTL = "BOOK_RTL"
    const val PICTURES = "PICTURES"

    /** 그림책이면 쪽들과 적힌 방향. 아니거나 열 수 없으면 null — 열 수 없는 책을 그림책으로 쳐서 만화로 열면 아무것도 보이지 않는다. */
    suspend fun detect(path: String): PictureBook? = runCatching {
        val document = EpubDocument.open(BookId("pictures"), "pictures.epub", FileSeekableSource(path))
        try {
            document.pictureBook()
        } finally {
            document.close()
        }
    }.getOrNull()

    /** 판별해 둔 값(파일 크기가 같을 때만). 모르면 null. */
    fun cached(store: ReadingStore, bookId: String, size: Long): Boolean? {
        val v = store.comicSetting(PICTURES, bookId) ?: return null
        val parts = v.split(':').takeIf { it.size >= 2 } ?: return null
        if (parts[0].toLongOrNull() != size) return null
        return when (parts[1]) { "1" -> true; "0" -> false; else -> null }
    }

    /** 판별할 때 읽어 둔 책의 넘기는 방향(EPUB 의 `page-progression-direction`). 모르면 null. */
    fun cachedRightToLeft(store: ReadingStore, bookId: String): Boolean? =
        when (store.comicSetting(PICTURES, bookId)?.split(':')?.getOrNull(2)) { "1" -> true; "0" -> false; else -> null }

    fun remember(store: ReadingStore, bookId: String, size: Long, picture: Boolean, rightToLeft: Boolean? = null) =
        store.setComicSetting(PICTURES, bookId, "$size:${if (picture) 1 else 0}:${rightToLeft?.let { if (it) 1 else 0 } ?: "-"}")

    /**
     * 만화 뷰어로 여는가(안드로이드 `opensAsComic`). 사람이 고른 것이 먼저: COMIC 이면 PDF · 그림책, BOOK 이면 아니다. 고르지
     * 않았으면 그림책인가. [pdf] 는 판별하지 않는다 — 고를 때만(46 · 1A).
     */
    fun opensAsComic(view: String?, pdf: Boolean, picture: Boolean): Boolean = when (view) {
        "COMIC" -> pdf || picture
        "BOOK" -> false
        else -> picture
    }

    /** 넘기는 방향: 사람이 고른 것 → 책에 적힌 것 → 왼→오. */
    fun rightToLeft(chosen: String?, book: Boolean?): Boolean = when (chosen) {
        "1" -> true
        "0" -> false
        else -> book ?: false
    }
}
