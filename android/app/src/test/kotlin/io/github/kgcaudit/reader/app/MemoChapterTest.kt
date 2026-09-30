package io.github.kgcaudit.reader.app

import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.document.Bookmark
import io.github.kgcaudit.reader.document.HighlightColor
import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.layout.Insets
import io.github.kgcaudit.reader.layout.LayoutSpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * 메모 판을 여는 사이 장이 바뀌어도(듣기가 다음 장으로 넘김 · 볼륨키) 칠과 메모는 고른 장의 고른 글에 붙는다(0.28.1).
 * 저장하는 순간의 "지금 장" 으로 칠하던 때는 다음 장의 같은 번호 자리, 전혀 다른 문장에 붙었다.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MemoChapterTest {

    private val app = ApplicationProvider.getApplicationContext<OloApp>()
    private val spec = LayoutSpec(viewportWidthPx = 1080f, viewportHeightPx = 2000f, margin = Insets(60f, 60f, 60f, 60f), baseSizePx = 42f)

    @Test
    fun `a memo written while the chapter changes is saved on the chapter it was picked in`() = runBlocking {
        val folder = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        File(folder, "메모 책.epub").writeBytes(SampleBooks.epub())
        app.container.data.folders.register(FolderProvider.treeUri)
        app.container.data.rescanAll()
        val book = app.container.data.library.books().first().single()
        val opened = app.container.open(book) as OpenedBook.Reflow
        try {
            val reader = opened.reader
            reader.layOut(spec)
            val picked = reader.state.value.position!!.spineIndex
            val text = reader.state.value.text
            val word = text.substring(0, text.indexOf(' ').coerceAtLeast(2))
            val start = text.indexOf(word)

            // 메모 판이 떠 있는 사이 다음 장으로 넘어간다.
            reader.goTo(Bookmark(Bookmark.NO_ID, book.id, Locator.Reflow(picked + 1, 0), null, 0))
            assertNotEquals(picked, reader.state.value.position!!.spineIndex, "장이 바뀌지 않았다")

            val saved = assertNotNull(reader.highlight(start, start + word.length, HighlightColor.Green, "메모", spineIndex = picked))
            assertEquals(Locator.Reflow(picked, start), saved.start)
            assertEquals(word, saved.snippet)
            assertEquals("메모", saved.note)
        } finally {
            opened.close()
        }
    }
}
