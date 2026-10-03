package io.github.kgcaudit.reader.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.data.library.LibraryBook
import io.github.kgcaudit.reader.document.BookFormat
import io.github.kgcaudit.reader.document.BookId
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryViewTest {

    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun `a view setting written by a newer version falls back to grid and recent order`() {
        // 망가뜨린 입력: 모르는 이름. 앱이 닫히거나 빈 화면이 되지 않고 기본 보기로 연다.
        app.getSharedPreferences("library", Context.MODE_PRIVATE).edit().putString("layout", "Carousel").putString("sort", "Rating").commit()
        val store = LibraryViewStore(app)
        assertEquals(LibraryLayout.Grid, store.layout.value)
        assertEquals(LibrarySort.Recent, store.sort.value)
    }

    @Test
    fun `name order is Korean dictionary order ignoring case and unknown sizes go last`() {
        fun book(name: String, size: Long?, added: Long? = null) =
            LibraryBook(BookId(name), BookFormat.EPUB, name, null, null, size, added)
        val books = listOf(book("zoo", 10), book("Apple", null), book("하늘", 30), book("가을", 20))
        assertEquals(listOf("Apple", "zoo", "가을", "하늘"), LibrarySort.Name.sort(books).map { it.displayName })
        assertEquals(listOf("하늘", "가을", "zoo", "Apple"), LibrarySort.Size.sort(books).map { it.displayName })
    }

    @Test
    fun `recent order puts the latest read first and falls back to names where nothing was read`() {
        fun book(name: String) = LibraryBook(BookId(name), BookFormat.EPUB, name, null, null, null, null)
        val books = listOf(book("가을"), book("하늘"), book("나무"), book("zoo"))
        val readAt = mapOf("하늘" to 30L, "나무" to 10L)
        // 읽은 때가 있는 것이 앞(최근 먼저), 없는 것은 그 뒤에 이름순.
        assertEquals(listOf("하늘", "나무", "zoo", "가을"), LibrarySort.Recent.sort(books) { readAt[it.displayName] }.map { it.displayName })
        // 읽을 갈래(읽은 때가 없다)는 이름순 — 차례가 뒤죽박죽이면 찾을 수 없다.
        assertEquals(listOf("zoo", "가을", "나무", "하늘"), LibrarySort.Recent.sort(books).map { it.displayName })
    }

    @Test
    fun `sizes read short`() {
        assertEquals("9.2MB", sizeLabel(9_646_899))
        assertEquals("84KB", sizeLabel(86_016))
        assertEquals("512B", sizeLabel(512))
    }

    private fun b(name: String, title: String? = null, author: String? = null) =
        LibraryBook(BookId(name), BookFormat.EPUB, name, title, author, null)

    @Test
    fun `search matches title, author and file name ignoring case and spacing, reading books first`() {
        val books = listOf(
            b("작별.epub", "작별하지 않는다", "한강"),
            b("veg.epub", "채식주의자", "한강"),
            b("boy.epub", "소년이 온다", "한강"),
            b("prince.epub", "어린 왕자", "생텍쥐페리"),
            b("한국 단편선.txt"),
        )
        val reading = setOf(BookId("boy.epub"))
        val read = setOf(BookId("veg.epub"))
        val hits = findBooks(books, reading, read, "한")
        assertEquals(listOf("소년이 온다", "채식주의자", "작별하지 않는다", "한국 단편선.txt"), hits.map { it.book.label })
        assertEquals(listOf(Shelf.Reading, Shelf.Read, Shelf.ToRead, Shelf.ToRead), hits.map { it.shelf })
        // 띄어쓰기 · 대소문자 · 파일 이름.
        assertEquals(listOf("어린 왕자"), findBooks(books, reading, read, "어린왕자").map { it.book.label })
        assertEquals(listOf("어린 왕자"), findBooks(books, reading, read, "PRINCE").map { it.book.label })
    }

    @Test
    fun `search characters that mean something to a regex are just characters`() {
        // 망가뜨린 입력: 정규식으로 찾으면 "(" · "[" 에서 오류가 나 찾기 화면이 닫혔다.
        val books = listOf(b("시집 (개정판).epub"), b("[단편] 모음.txt"))
        assertEquals(1, findBooks(books, emptySet(), emptySet(), "(개정").size)
        assertEquals(1, findBooks(books, emptySet(), emptySet(), "[단편]").size)
        assertEquals(0, findBooks(books, emptySet(), emptySet(), "   ").size, "빈 말로는 아무것도 찾지 않는다")
    }

    @Test
    fun `the painted range covers the spaced original of a squashed query`() {
        assertEquals(0 until 5, matchRange("어린 왕자", "어린왕자"))
        assertEquals(4 until 6, matchRange("소년이 온다", "온다"))
        assertEquals(null, matchRange("채식주의자", "한강"))
    }
}
