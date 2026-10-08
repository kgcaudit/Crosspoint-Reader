package io.github.kgcaudit.reader.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.Bookmark
import io.github.kgcaudit.reader.document.BookmarkRepository
import io.github.kgcaudit.reader.document.ByteSource
import io.github.kgcaudit.reader.document.ProgressRepository
import io.github.kgcaudit.reader.document.ReadingProgress
import io.github.kgcaudit.reader.document.ReflowDocument
import io.github.kgcaudit.reader.document.SeekableSource
import io.github.kgcaudit.reader.document.TocEntry
import io.github.kgcaudit.reader.document.TxtDocument
import io.github.kgcaudit.reader.document.epub.EpubDocument
import io.github.kgcaudit.reader.layout.cache.PageStore
import io.github.kgcaudit.reader.listen.ListenHub
import io.github.kgcaudit.reader.reflow.BookReader
import io.github.kgcaudit.reader.reflow.ReaderPrefs
import io.github.kgcaudit.reader.reflow.ReaderScreen
import io.github.kgcaudit.reader.ui.design.CpReaderTheme
import io.github.kgcaudit.reader.ui.design.PageTurn
import io.github.kgcaudit.reader.ui.design.PaperTheme
import io.github.kgcaudit.reader.ui.design.ScreenPrefs
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 리플로우 리더 화면을 그대로 둔 채 다른 책이 들어올 때. 화면이 책마다 따로 들고 있어야 하는 것(목차)이 앞 책의 것으로 남으면
 * 새 책의 목차 화면 · 하단 장 제목에 앞 책의 장이 보인다.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class ReaderSwitchTest {

    @get:Rule
    val compose = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<OloApp>()

    @After
    fun tearDown() = compose.runOnIdle { ListenHub.detach() }

    /** [gate] 가 열릴 때까지 목차를 내주지 않는 책 — 큰 NCX 를 푸는 동안(몇 초)을 붙잡아 둔다. */
    private class SlowOutline(private val inner: ReflowDocument, val gate: CompletableDeferred<Unit>) : ReflowDocument by inner {
        override suspend fun outline(): List<TocEntry> {
            gate.await()
            return inner.outline()
        }
    }

    private class NoBookmarks : BookmarkRepository {
        override suspend fun forBook(bookId: BookId): List<Bookmark> = emptyList()
        override suspend fun add(bookmark: Bookmark) = bookmark
        override suspend fun remove(id: Long) = Unit
    }

    private class MemoryProgress : ProgressRepository {
        private val rows = HashMap<BookId, ReadingProgress>()
        override suspend fun get(bookId: BookId) = rows[bookId]
        override suspend fun save(progress: ReadingProgress) { rows[progress.bookId] = progress }
        override suspend fun remove(bookId: BookId) { rows.remove(bookId) }
    }

    private fun reader(name: String, document: ReflowDocument) = BookReader(
        fonts = app.container.fonts,
        document = document,
        store = PageStore(File(app.cacheDir, "switch-$name").apply { deleteRecursively() }),
        bookmarkRepository = NoBookmarks(),
        progressRepository = MemoryProgress(),
    )

    @Test
    fun `a book opened in place of another never shows the previous book's contents`() {
        val prince = reader("prince", EpubDocument.open(BookId("prince"), "어린 왕자", SeekableSource.of(SampleBooks.epub())))
        val gate = CompletableDeferred<Unit>()
        val notes = reader("notes", SlowOutline(TxtDocument.open(BookId("notes"), "메모장", ByteSource.of(SampleBooks.txt())), gate))
        var current by mutableStateOf(prince)
        val prefs = ReaderPrefs(screen = ScreenPrefs(pageTurn = PageTurn.None))
        compose.setContent {
            CpReaderTheme(PaperTheme.White) {
                ReaderScreen(reader = current, prefs = prefs, onPrefsChange = {}, onClose = {}, onChrome = {})
            }
        }
        waitFor(hasText("1 / ", substring = true))
        // 앞 책의 목차는 보인다(이 화면이 목차를 읽어 둔 뒤에 바꾼다).
        compose.onRoot().performTouchInput { click(center) }
        waitFor(hasText("목차"))
        node(hasText("목차")).performClick()
        waitFor(hasText("제1장 보아 구렁이"))

        // 목차 화면을 연 채 같은 화면에 다른 책이 들어온다. 새 책의 목차는 아직 읽는 중이다.
        compose.runOnIdle { current = notes }
        compose.waitForIdle()
        assertTrue(hasNode(hasText("메모장")), "새 책의 목차 화면이 아니다")
        assertFalse(hasNode(hasText("제1장 보아 구렁이")), "새 책의 목차 화면에 앞 책의 목차가 보인다")

        // 목차를 다 읽으면 새 책을 짠다 — 설정이 같아도 새 책은 새로 짜야 한다(안 짜면 "책을 여는 중…" 에 멈춘다).
        gate.complete(Unit)
        compose.waitUntil(30_000) { notes.state.value.position != null }
        prince.close()
        notes.close()
    }

    private fun hasNode(matcher: SemanticsMatcher) =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun node(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true)[0]

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 30_000) {
        compose.waitUntil(timeoutMs) { hasNode(matcher) }
    }
}
