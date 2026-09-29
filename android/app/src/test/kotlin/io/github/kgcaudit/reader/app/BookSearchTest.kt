package io.github.kgcaudit.reader.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 라이브러리 책 찾기(0.26.0, 구상안 가 확정): 제목 · 저자 · 파일 이름, 결과마다 갈래, 찾은 글자 칠. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class BookSearchTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>(StandardTestDispatcher())

    private val app = ApplicationProvider.getApplicationContext<OloApp>()
    private val shots = File(System.getProperty("reader.screenshots") ?: "build/screenshots").apply { mkdirs() }

    @Before
    fun setUp() {
        val folder = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        File(folder, "소설").mkdirs()
        File(folder, "소설/어린 왕자.epub").writeBytes(SampleBooks.epub())
        File(folder, "소설/데미안.epub").writeBytes(SampleBooks.epub())
        File(folder, "옛 일기.txt").writeBytes(SampleBooks.txt())
        runBlocking {
            val data = app.container.data
            data.folders.register(FolderProvider.treeUri)
            data.rescanAll()
            // 어린 왕자는 한 번 열어 제목 · 저자를 읽은 책(읽는 중).
            val prince = data.library.books().first().first { it.displayName == "어린 왕자.epub" }
            data.library.updateMetadata(prince.id, "어린 왕자", "생텍쥐페리")
            data.library.markOpened(prince.id, 100L)
        }
        compose.activityRule.scenario.recreate()
    }

    private fun has(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(30_000) { has(matcher) }
    private fun node(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true)[0]

    private fun shot(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        File(shots, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun openSearch() {
        waitFor(hasContentDescription("책 찾기"))
        node(hasContentDescription("책 찾기")).performClick()
        waitFor(hasText("책 3권에서 찾습니다"))
    }

    @Test
    fun `a book is found by its author and shows which shelf it is on`() {
        openSearch()
        shot("102-search-empty")
        node(hasContentDescription("찾을 말")).performTextInput("생텍")
        waitFor(hasText("1권 · 제목 · 저자 · 파일 이름에서"))
        assertTrue(has(hasText("읽는 중 0%")), "갈래 표시가 없다")
        assertTrue(has(hasText("어린 왕자")))
        shot("103-search-results")
        // 결과를 누르면 그 책이 열린다.
        node(hasText("어린 왕자")).performClick()
        waitFor(hasText("1 / ", substring = true))
    }

    @Test
    fun `spacing and file names find books and nothing found is said so`() {
        openSearch()
        node(hasContentDescription("찾을 말")).performTextInput("옛일기")
        waitFor(hasText("읽을 책"))
        assertTrue(has(hasText("옛 일기.txt")))
        // 망가뜨린 입력: 정규식 글자. 오류 없이 "맞는 책이 없습니다".
        node(hasContentDescription("찾을 말")).performTextReplacement("[(")
        waitFor(hasText("‘[(’ — 맞는 책이 없습니다"))
    }

    @Test
    fun `back closes the search and returns to the shelves`() {
        openSearch()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(10_000) { !has(hasText("책 3권에서 찾습니다")) }
        waitFor(hasText("읽는 중 · 1권"))
        assertFalse(compose.activity.isFinishing, "뒤로가 찾기가 아니라 앱을 닫았다")
    }
}
