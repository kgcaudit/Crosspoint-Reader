package io.github.kgcaudit.reader.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 만화 두 쪽 보기(0.36.0, docs/COMIC_PLAN.md C3 · 구상안 ⑥). 가로 화면에서 연다.
 *
 * 쪽마다 색이 다르다 — 어느 쪽이 화면 왼쪽 · 오른쪽에 놓였는지 화소로 본다. 4쪽은 가로가 긴 펼침면 그림이다.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w851dp-h393dp-xhdpi")
class ComicSpreadAppTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>(StandardTestDispatcher())

    @After
    fun settleTurn() = compose.waitForIdle()

    private val app = ApplicationProvider.getApplicationContext<OloApp>()
    private val shots = File(System.getProperty("reader.screenshots") ?: "build/screenshots").apply { mkdirs() }

    /** 쪽 색(0쪽부터). */
    private val colors = listOf(0xFF303030, 0xFFD03030, 0xFF3050D0, 0xFF30A050, 0xFFE0A020, 0xFF9040C0, 0xFF20B0B0).map { it.toInt() }

    private fun png(w: Int, h: Int, color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Before
    fun setUp() {
        val root = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        File(root, "C").mkdirs()
        ZipOutputStream(File(root, "C/별 01권.cbz").outputStream()).use { zip ->
            colors.forEachIndexed { i, c ->
                zip.putNextEntry(ZipEntry("%03d.png".format(i + 1)))
                // 4쪽(3번)은 펼침면: 가로가 길다.
                zip.write(if (i == 3) png(120, 90, c) else png(60, 90, c))
                zip.closeEntry()
            }
        }
        File(root, "C/별 02권.cbz").writeBytes(File(root, "C/별 01권.cbz").readBytes())
        runBlocking {
            val data = app.container.data
            data.folders.register(FolderProvider.treeUri)
            data.rescanAll()
            data.probeComics()
        }
        compose.activityRule.scenario.recreate()
    }

    private fun has(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(30_000) { has(matcher) }
    private fun node(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true).let { it[it.fetchSemanticsNodes().size - 1] }
    private fun click(matcher: SemanticsMatcher) {
        waitFor(matcher)
        node(matcher).performClick()
    }

    private fun screen(): Bitmap {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        return bitmap
    }

    private fun near(pixel: Int, color: Int): Boolean {
        fun d(c: Int) = listOf(android.graphics.Color::red, android.graphics.Color::green, android.graphics.Color::blue).sumOf { f -> (f(pixel) - f(c)).let { it * it } }
        return d(color) < 30 * 30 * 3
    }

    /** 화면 가운데 줄에서 [fx] 자리의 색이 [page] 쪽 색이 될 때까지 기다린다(쪽은 입출력 스레드에서 풀린다). */
    private fun waitForPageAt(fx: Float, page: Int, message: String) {
        val ok = runCatching {
            compose.waitUntil(30_000) { screen().let { near(it.getPixel((it.width * fx).toInt(), it.height / 2), colors[page]) } }
        }.isSuccess
        if (!ok) File(shots, "spread-fail.png").outputStream().use { screen().compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(ok, message)
    }

    private fun page(label: String) = hasContentDescription("만화 ${label}쪽")
    private fun next(rtl: Boolean = false) {
        compose.onRoot().performTouchInput { click(if (rtl) centerLeft.copy(x = width * 0.1f) else centerRight.copy(x = width * 0.9f)) }
        compose.mainClock.advanceTimeBy(1_000)
    }

    private fun openVolume() {
        click(hasText("만화 1"))
        click(hasContentDescription("별 작품"))
        click(hasText("1권"))
    }

    @Test
    fun `landscape opens the cover alone, then pages face each other left to right`() {
        openVolume()
        waitFor(page("1"))
        assertTrue(has(hasText("1 / 7")))
        next()
        waitFor(page("2–3"))
        assertTrue(has(hasText("2–3 / 7")))
        // 왼→오: 앞 쪽(2쪽 · 빨강)이 왼쪽, 3쪽(파랑)이 오른쪽.
        waitForPageAt(0.4f, 1, "2쪽이 왼쪽에 없다")
        waitForPageAt(0.6f, 2, "3쪽이 오른쪽에 없다")
        File(shots, "comic-spread.png").outputStream().use { screen().compress(Bitmap.CompressFormat.PNG, 100, it) }
        // 자리는 판의 마지막 쪽으로 적는다.
        compose.waitUntil(30_000) { runBlocking { app.container.data.comics.progress().first().values.any { it.page == 2 } } }
    }

    @Test
    fun `right to left puts the earlier page on the right`() {
        runBlocking { app.container.data.comics.setRightToLeft(app.container.data.comics.works().first().single(), true) }
        openVolume()
        waitFor(page("1"))
        next(rtl = true)
        waitFor(page("2–3"))
        assertTrue(has(hasText("2–3 / 7  ← 오→왼")))
        waitForPageAt(0.6f, 1, "오→왼인데 앞 쪽(2쪽)이 오른쪽에 없다")
        waitForPageAt(0.4f, 2, "오→왼인데 3쪽이 왼쪽에 없다")
        File(shots, "comic-spread-rtl.png").outputStream().use { screen().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun `a wide spread picture stands alone and the pairs after it stay in step`() {
        openVolume()
        waitFor(page("1"))
        next(); waitFor(page("2–3"))
        next(); waitFor(page("4"))
        assertTrue(has(hasText("4 / 7")))
        waitForPageAt(0.5f, 3, "펼침면 그림이 가운데에 없다")
        next(); waitFor(page("5–6"))
        next(); waitFor(page("7"))
        next(); waitFor(hasText("1권을 다 읽었습니다"))
    }

    @Test
    fun `two pages can be turned off from the view panel`() {
        openVolume()
        waitFor(page("1"))
        next(); waitFor(page("2–3"))
        compose.onRoot().performTouchInput { click(center) }
        click(hasText("보기"))
        click(hasText("끔"))
        // 보던 판의 첫 쪽이 한 쪽으로.
        waitFor(page("2"))
        assertEquals(io.github.kgcaudit.reader.ui.design.ComicSpread.Off, app.container.prefs.load().screen.comicSpread)
    }
}
