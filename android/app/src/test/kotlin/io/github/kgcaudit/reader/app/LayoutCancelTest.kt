package io.github.kgcaudit.reader.app

import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.Bookmark
import io.github.kgcaudit.reader.document.BookmarkRepository
import io.github.kgcaudit.reader.document.ProgressRepository
import io.github.kgcaudit.reader.document.ReadingProgress
import io.github.kgcaudit.reader.document.SeekableSource
import io.github.kgcaudit.reader.document.epub.EpubDocument
import io.github.kgcaudit.reader.layout.Insets
import io.github.kgcaudit.reader.layout.cache.PageStore
import io.github.kgcaudit.reader.reflow.BookReader
import io.github.kgcaudit.reader.reflow.ReaderPrefs
import io.github.kgcaudit.reader.reflow.toSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 조판이 도중에 취소될 때(0.30.0). 폴더블을 빨리 접었다 펴면 화면이 새 크기로 조판을 청하면서 앞 조판을 취소한다.
 * 취소 지점을 책갈피 조회에 고정해(문을 닫아 둔 가짜 보관소) 매번 같은 자리에서 끊는다 — 기기에서는 가끔만 난다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LayoutCancelTest {

    private val app get() = ApplicationProvider.getApplicationContext<OloApp>()

    /** 책갈피 조회를 [hold] 가 열릴 때까지 세운다. 멈췄음은 [reached] 로 알린다. */
    private class GatedBookmarks : BookmarkRepository {
        @Volatile var hold: CompletableDeferred<Unit>? = null
        @Volatile var reached = CompletableDeferred<Unit>()
        fun arm() { reached = CompletableDeferred(); hold = CompletableDeferred() }
        fun release() { hold?.complete(Unit); hold = null }
        override suspend fun forBook(bookId: BookId): List<Bookmark> {
            hold?.let { reached.complete(Unit); it.await() }
            return emptyList()
        }
        override suspend fun add(bookmark: Bookmark) = bookmark
        override suspend fun remove(id: Long) = Unit
    }

    private class MemoryProgress : ProgressRepository {
        private val rows = HashMap<BookId, ReadingProgress>()
        override suspend fun get(bookId: BookId) = rows[bookId]
        override suspend fun save(progress: ReadingProgress) { rows[progress.bookId] = progress }
        override suspend fun remove(bookId: BookId) { rows.remove(bookId) }
    }

    @Test
    fun `a layout cancelled halfway leaves the reader on the page they were reading`() = runBlocking {
        val bookmarks = GatedBookmarks()
        val reader = BookReader(
            fonts = app.container.fonts,
            document = EpubDocument.open(BookId("cancel"), "어린 왕자", SeekableSource.of(SampleBooks.epub())),
            store = PageStore(File(app.cacheDir, "cancel-pages").apply { deleteRecursively() }),
            bookmarkRepository = bookmarks,
            progressRepository = MemoryProgress(),
        )
        val prefs = ReaderPrefs()
        val fontId = app.container.fonts.layoutFontId(null)
        fun spec(w: Float, h: Float) = prefs.toSpec(w, h, Insets.all(60f), 3f, 2.5f, fontId)
        // 작은 쪽(한 장이 여러 쪽)과, 쪽이 훨씬 큰 두 쪽(한 장이 한 펼침에 든다).
        val cover = spec(700f, 1000f)
        val main = spec(1400f, 2422f)

        reader.layOut(cover)
        reader.next()
        val reading = reader.state.value.position!!
        assertTrue(reading.spineIndex == 0 && reading.pageIndex >= 1, "첫 장 둘째 쪽이 아니다: $reading")

        // 펼친다 — 두 쪽 조판이 책갈피 조회에서 멈춘 사이 다시 접는다(취소).
        bookmarks.arm()
        val unfolding = launch(Dispatchers.Default) { reader.layOut(main, twoPages = true) }
        withTimeout(30_000) { bookmarks.reached.await() }
        unfolding.cancelAndJoin()
        bookmarks.release()

        // 덮개 화면으로 돌아온다. 취소된 조판이 기준 글자를 펼침 첫머리(1쪽)로 바꿔 두면 여기서 처음으로 간다.
        reader.layOut(cover)
        assertEquals(reading, reader.state.value.position, "도중에 취소된 조판 뒤 읽던 쪽을 잃었다")
        assertEquals(false, reader.state.value.spread, "덮개 화면이 두 쪽으로 남았다")

        // 다시 펼치면(이번에는 끝까지) 두 쪽이 보인다 — 취소된 요청과 같은 설정이라고 건너뛰면 한 쪽이 남는다.
        reader.layOut(main, twoPages = true)
        assertEquals(true, reader.state.value.spread, "다시 펼쳤는데 두 쪽이 아니다")

        // 접는 조판이 취소된 뒤 같은 크기를 곧바로 다시 청한다(화면 크기와 설정이 따로 도착하면 이렇게 된다).
        // 취소된 요청이 설정을 남겨 두면 "이미 같은 설정" 으로 보고 돌아가 펼친 두 쪽이 덮개 화면에 남는다.
        bookmarks.arm()
        val folding = launch(Dispatchers.Default) { reader.layOut(cover) }
        withTimeout(30_000) { bookmarks.reached.await() }
        folding.cancelAndJoin()
        bookmarks.release()
        reader.layOut(cover)
        assertEquals(false, reader.state.value.spread, "접었는데 두 쪽이 남았다")
        assertEquals(reading, reader.state.value.position, "접었는데 읽던 쪽이 아니다")
        reader.close()
    }
}
