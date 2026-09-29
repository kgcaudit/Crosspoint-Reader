package io.github.kgcaudit.reader.app

import androidx.test.core.app.ApplicationProvider
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 쓸 수 없는 책 글꼴(WOFF 뿐 · 깨진 파일)만 든 책(0.25.1). 책 글꼴 키로 한 번 조판한 뒤, 화면이 "글꼴 없음" 을 알고 휴대폰
 * 글꼴로 다시 요청해 열 때마다 두 번 조판했다 — 첫 쪽이 늦게 뜨고 캐시가 쓰이지 않았다.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class UnusableBookFontTest {

    private val app = ApplicationProvider.getApplicationContext<OloApp>()
    private val spec = LayoutSpec(viewportWidthPx = 1080f, viewportHeightPx = 2000f, margin = Insets(60f, 60f, 60f, 60f), baseSizePx = 42f)

    private fun layouts(): Set<String> =
        File(app.filesDir, "pages").walk().filter { it.isDirectory && it.parentFile?.parentFile == File(app.filesDir, "pages") }.map { it.name }.toSet()

    @Test
    fun `a book whose only font cannot be used is laid out once in the phone font`() = runBlocking {
        val folder = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        File(app.filesDir, "pages").deleteRecursively()
        File(app.cacheDir, "book-fonts").deleteRecursively()
        // 망가뜨린 입력: 글꼴 자리에 글꼴이 아닌 바이트(WOFF 처럼 읽을 수 없는 것).
        File(folder, "깨진 글꼴.epub").writeBytes(SampleBooks.fontBook("wOFF 읽을 수 없는 글꼴".toByteArray()))
        app.container.data.folders.register(FolderProvider.treeUri)
        app.container.data.rescanAll()
        val book = app.container.data.library.books().first().single { it.displayName == "깨진 글꼴.epub" }
        val opened = app.container.open(book) as OpenedBook.Reflow
        try {
            val reader = opened.reader
            // 화면은 글꼴을 꺼내기 전에는 "책 글꼴 있음" 으로 보고 책 글꼴로 청한다.
            reader.layOut(spec.copy(useBookFonts = true))
            val first = layouts()
            assertFalse(reader.hasBookFonts, "쓸 수 없는 글꼴을 출판사 글꼴로 내놓는다")
            // 꺼내 보니 없었으므로 화면이 휴대폰 글꼴로 다시 청한다. 이미 그 키로 조판했으면 다시 하지 않는다.
            reader.layOut(spec.copy(useBookFonts = false))
            assertEquals(first, layouts(), "휴대폰 글꼴로 한 번 더 조판했다")
            assertTrue(reader.state.value.text.isNotEmpty())
        } finally {
            opened.close()
        }
    }
}
