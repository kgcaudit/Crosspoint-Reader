package io.github.kgcaudit.reader.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.document.BookId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 책장(0.23.0, 구상안 가안 확정): 열어 본 책 모두 · 다 읽은 책 따로 · 길게 눌러 다 읽음 표시. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h1400dp-xhdpi")
class ShelfTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>(StandardTestDispatcher())

    /**
     * 넘김 효과(0.32.0 부터 기본 말림)가 도는 채로 시험이 끝나면, 같은 JVM 의 다음 시험 화면이 60초 동안 쉬지 못하고
     * 줄줄이 실패했다(AppNotIdleException). 쪽을 넘기고 끝나는 시험이 많아 시험마다 붙이지 않고 여기서 기다린다.
     */
    @After
    fun settleTurn() = compose.waitForIdle()

    private val app = ApplicationProvider.getApplicationContext<OloApp>()
    private val shots = File(System.getProperty("reader.screenshots") ?: "build/screenshots").apply { mkdirs() }
    private val names = listOf("소설/어린 왕자.epub", "소설/소년.epub", "메모/옛 일기.txt", "메모/새 일기.txt", "소설/데미안.epub")

    @Before
    fun setUp() {
        val folder = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        File(app.filesDir, "covers").deleteRecursively()
        names.forEach { path ->
            File(folder, path).apply { parentFile?.mkdirs() }.writeBytes(if (path.endsWith(".txt")) SampleBooks.txt() else SampleBooks.epub())
        }
        runBlocking {
            val data = app.container.data
            data.folders.register(FolderProvider.treeUri)
            data.rescanAll()
            // 네 권을 열어 둔 사람. 세 권을 넘으니 옆으로 넘겨 본다.
            val books = data.library.books().first()
            names.take(4).forEachIndexed { i, path ->
                val book = books.first { it.displayName == path.substringAfter('/') }
                data.library.markOpened(book.id, 100L + i)
            }
        }
        compose.activityRule.scenario.recreate()
    }

    private fun has(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(30_000) { has(matcher) }

    private fun shot(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        File(shots, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun idOf(name: String): BookId = runBlocking { app.container.data.library.books().first().first { it.displayName == name }.id }

    @Test
    fun `every opened book sits on the reading shelf and more than three scroll sideways`() {
        // 0.22 까지는 최근 세 권만 보였다.
        waitFor(hasText("읽는 중 · 4권"))
        assertTrue(has(hasText("옆으로 넘겨 보기 ›")))
        // 열지 않은 책(데미안)은 책장에 없고 모든 책 목록에만 있다.
        assertFalse(has(hasContentDescription("데미안.epub 대신 표지").and(hasText("데미안.epub"))))
        shot("96-shelf-reading")
    }

    @Test
    fun `marking a book finished moves it to its own shelf and back`() {
        waitFor(hasText("읽는 중 · 4권"))
        // 연 책은 책장에만 있다 — 읽을 책에 한 번 더 보이지 않는다(0.25.0, 모든 책 목록을 없앰).
        // 대신 표지는 제목을 얹어 글자로 세면 둘이다 — 표지로 센다.
        assertEquals(1, compose.onAllNodes(hasContentDescription("옛 일기.txt 대신 표지"), useUnmergedTree = true).fetchSemanticsNodes().size, "연 책이 두 곳에 있다")
        compose.onAllNodes(hasText("옛 일기.txt"), useUnmergedTree = true)[0].performTouchInput { longClick() }
        waitFor(hasText("읽은 책으로 옮기기"))
        compose.onAllNodes(hasText("읽은 책으로 옮기기"), useUnmergedTree = true)[0].performClick()
        waitFor(hasText("읽은 책 · 1권"))
        waitFor(hasText("읽는 중 · 3권"))
        assertTrue(has(hasText("다 읽음 · ", substring = true)), "끝낸 날이 없다")
        shot("97-shelf-finished")
        assertNotNull(runBlocking { app.container.data.library.shelf().first() }.first { it.book.id == idOf("옛 일기.txt") }.finishedAtEpochMs)

        compose.onAllNodes(hasText("옛 일기.txt"), useUnmergedTree = true)[0].performTouchInput { longClick() }
        waitFor(hasText("읽는 중으로 되돌리기"))
        compose.onAllNodes(hasText("읽는 중으로 되돌리기"), useUnmergedTree = true)[0].performClick()
        waitFor(hasText("읽는 중 · 4권"))
        assertFalse(has(hasText("읽은 책 · ", substring = true)))
        assertNull(runBlocking { app.container.data.library.shelf().first() }.first { it.book.id == idOf("옛 일기.txt") }.finishedAtEpochMs)
    }

    @Test
    fun `a book never opened can be marked finished from the list`() {
        // 종이책으로 읽은 책을 정리하는 경우. 한 번도 열지 않은 데미안이 다 읽은 책 줄에 올라간다.
        waitFor(hasText("데미안.epub"))
        compose.onAllNodes(hasText("데미안.epub"), useUnmergedTree = true)[0].performTouchInput { longClick() }
        waitFor(hasText("읽은 책으로 옮기기"))
        compose.onAllNodes(hasText("읽은 책으로 옮기기"), useUnmergedTree = true)[0].performClick()
        waitFor(hasText("읽은 책 · 1권"))
        assertTrue(has(hasText("읽는 중 · 4권")))
    }
}
