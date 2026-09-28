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
    fun `a view setting written by a newer version falls back to grid and name order`() {
        // 망가뜨린 입력: 모르는 이름. 앱이 닫히거나 빈 화면이 되지 않고 기본 보기로 연다.
        app.getSharedPreferences("library", Context.MODE_PRIVATE).edit().putString("layout", "Carousel").putString("sort", "Rating").commit()
        val store = LibraryViewStore(app)
        assertEquals(LibraryLayout.Grid, store.layout.value)
        assertEquals(LibrarySort.Name, store.sort.value)
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
    fun `sizes read short`() {
        assertEquals("9.2MB", sizeLabel(9_646_899))
        assertEquals("84KB", sizeLabel(86_016))
        assertEquals("512B", sizeLabel(512))
    }
}
