package io.github.kgcaudit.reader.app

import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.layout.Insets
import io.github.kgcaudit.reader.layout.LayoutSpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertTrue

/**
 * 같은 이름으로 덮어쓴 개정판(0.24.1). 조판 캐시가 책 경로와 설정으로만 이름 붙어, 덮어쓴 파일을 열면 옛 판의 쪽이
 * 그대로 보였다 — 링크 · 찾기 · 형광펜은 새 파일로 다시 읽어 엉뚱한 글자에 놓였다.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class BookEditionTest {

    private val app = ApplicationProvider.getApplicationContext<OloApp>()
    private val spec = LayoutSpec(viewportWidthPx = 1080f, viewportHeightPx = 2000f, margin = Insets(60f, 60f, 60f, 60f), baseSizePx = 42f)
    private lateinit var folder: File

    @Before
    fun setUp() {
        folder = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        runBlocking { app.container.data.folders.register(FolderProvider.treeUri) }
    }

    /** 목록에서 [name] 을 찾아 열고, 첫 쪽의 글을 준다. */
    private fun firstPage(name: String): String = runBlocking {
        app.container.data.rescanAll()
        val book = app.container.data.library.books().first().single { it.displayName == name }
        val opened = app.container.open(book) as OpenedBook.Reflow
        try {
            opened.reader.layOut(spec)
            opened.reader.state.value.text
        } finally {
            opened.close()
        }
    }

    @Test
    fun `an epub overwritten by a new edition shows the new text`() {
        File(folder, "개정판.epub").writeBytes(SampleBooks.epub())
        assertTrue("보아 구렁이" in firstPage("개정판.epub"))
        File(folder, "개정판.epub").writeBytes(CoverTest.coverEpub(CoverTest.png(android.graphics.Color.RED)))
        val text = firstPage("개정판.epub")
        assertTrue("표지가 있는 책" in text, "옛 판의 쪽이 보인다: ${text.take(40)}")
    }

    @Test
    fun `a txt overwritten by a new edition shows the new text`() {
        File(folder, "메모.txt").writeText("첫 판의 글입니다.\n")
        assertTrue("첫 판" in firstPage("메모.txt"))
        File(folder, "메모.txt").writeText("고쳐 쓴 둘째 판의 글입니다. 조금 더 길어졌습니다.\n")
        val text = firstPage("메모.txt")
        assertTrue("둘째 판" in text, "옛 판의 쪽이 보인다: ${text.take(40)}")
    }
}
