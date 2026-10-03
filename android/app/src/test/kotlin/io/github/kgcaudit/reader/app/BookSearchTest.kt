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
import org.junit.After
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

    /**
     * 넘김 효과(0.32.0 부터 기본 말림)가 도는 채로 시험이 끝나면, 같은 JVM 의 다음 시험 화면이 60초 동안 쉬지 못하고
     * 줄줄이 실패했다(AppNotIdleException). 쪽을 넘기고 끝나는 시험이 많아 시험마다 붙이지 않고 여기서 기다린다.
     */
    @After
    fun settleTurn() = compose.waitForIdle()

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

    /** [pixel] 이 [a] 쪽에 더 가까운가(RGB 거리). 모서리 안팎을 화면에서 직접 뜬 색과 견준다. */
    private fun closer(pixel: Int, a: Int, b: Int): Boolean {
        fun d(c: Int) = listOf(android.graphics.Color::red, android.graphics.Color::green, android.graphics.Color::blue)
            .sumOf { f -> (f(pixel) - f(c)).let { it * it } }
        return d(a) < d(b)
    }
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
        // EPUB 의 대신 표지는 계열 EBOOK — 문서 회청 위의 두 톤 펼친 책(0.32.2). 0.32.1 까지의 청록(계열에서는 소스 코드
        // 색)이면 걸린다. 가운데 책장은 흰색이다.
        run {
            // 찾기 화면 밑의 홈 화면도 그 책의 큰 대신 표지를 품고 있다 — 결과 줄의 작은 표지를 고른다.
            val cover = compose.onAllNodes(hasContentDescription("어린 왕자 대신 표지"), useUnmergedTree = true).fetchSemanticsNodes()
                .map { it.boundsInRoot }.minBy { it.width }
            val view = compose.activity.window.decorView
            val shot = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            compose.runOnUiThread { view.draw(android.graphics.Canvas(shot)) }
            val density = compose.activity.resources.displayMetrics.density
            val fill = shot.getPixel((cover.left + 4 * density).toInt(), (cover.top + 4 * density).toInt())
            assertTrue(closer(fill, 0xFF55606B.toInt(), 0xFF3E7F80.toInt()), "EPUB 대신 표지가 문서 회청이 아니다: #${Integer.toHexString(fill)}")
            // 그림은 채운 두 쪽이라 가운데 20dp 네모의 3분의 1 남짓이 흰색이다. 선 아이콘(0.32.1 까지)은 15% 쯤이다.
            val half = (10 * density).toInt()
            var white = 0
            var all = 0
            for (y in cover.center.y.toInt() - half until cover.center.y.toInt() + half) for (x in cover.center.x.toInt() - half until cover.center.x.toInt() + half) {
                val p = shot.getPixel(x, y)
                all++
                if (android.graphics.Color.red(p) > 220 && android.graphics.Color.blue(p) > 220) white++
            }
            assertTrue(white > all / 4, "펼친 책 그림(채운 두 쪽)이 아니다: 흰 점 ${white * 100 / all}%")
        }
        // 결과를 누르면 그 책이 열린다. 찾기 화면 밑의 홈 격자에도 같은 제목의 칸이 있다 — 화면 폭을 다 쓰는 결과 줄을 누른다.
        val rows = compose.onAllNodes(androidx.compose.ui.test.hasClickAction() and hasText("어린 왕자"))
        rows[rows.fetchSemanticsNodes().withIndex().maxBy { it.value.boundsInRoot.width }.index].performClick()
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
