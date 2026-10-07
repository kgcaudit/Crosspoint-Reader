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
 * 펼침면 나누기(0.50.0): 휴대폰 세로 한 쪽 보기에서 가로로 긴 쪽은 반씩 넘긴다. 읽던 자리 · 책갈피는 책의 쪽으로 적혀, 나누기를
 * 끄고 켜도 같은 곳이다.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class SpreadSplitAppTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>(StandardTestDispatcher())

    @After
    fun settleTurn() = compose.waitForIdle()

    private val app = ApplicationProvider.getApplicationContext<OloApp>()
    private val red = 0xFFD03030.toInt()
    private val blue = 0xFF3050D0.toInt()
    private val green = 0xFF30A050.toInt()

    private fun png(w: Int, h: Int, paint: (Bitmap) -> Unit): ByteArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply(paint)
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** 1쪽 빨강, 2쪽 펼침면(왼쪽 반 파랑 · 오른쪽 반 초록), 3쪽 빨강. */
    private fun cbz(file: File) = ZipOutputStream(file.outputStream()).use { zip ->
        fun put(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        put("001.png", png(60, 90) { it.eraseColor(red) })
        put("002.png", png(120, 90) { b -> for (x in 0 until 120) for (y in 0 until 90) b.setPixel(x, y, if (x < 60) blue else green) })
        put("003.png", png(60, 90) { it.eraseColor(red) })
    }

    @Before
    fun setUp() {
        val root = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        File(root, "C").mkdirs()
        cbz(File(root, "C/별 01권.cbz"))
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

    private fun center(): Int {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(b)) }
        return b.getPixel(b.width / 2, b.height / 2)
    }

    private fun near(pixel: Int, color: Int): Boolean {
        fun d(c: Int) = listOf(android.graphics.Color::red, android.graphics.Color::green, android.graphics.Color::blue).sumOf { f -> (f(pixel) - f(c)).let { it * it } }
        return d(color) < 40 * 40 * 3
    }

    private fun waitForColor(color: Int, message: String) =
        assertTrue(runCatching { compose.waitUntil(30_000) { near(center(), color) } }.isSuccess, message)

    private fun next(rtl: Boolean = false) {
        compose.onRoot().performTouchInput { click(if (rtl) centerLeft.copy(x = width * 0.1f) else centerRight.copy(x = width * 0.9f)) }
        compose.mainClock.advanceTimeBy(1_000)
    }

    private fun open() {
        click(hasText("만화 1"))
        click(hasContentDescription("별 작품"))
        click(hasText("1권"))
        waitFor(hasContentDescription("만화 1쪽"))
    }

    private suspend fun savedPage() = app.container.data.comics.progress().first().values.single().page

    @Test
    fun `a spread is read half by half on a phone and the place stays the book's page`() {
        open()
        waitForColor(red, "1쪽이 아니다")
        next()
        waitFor(hasContentDescription("만화 2쪽"))
        // 펼침면의 왼쪽 반이 화면을 채운다 — 나누지 않으면 화면 가운데는 쪽 밖 바탕이다.
        waitForColor(blue, "펼침면의 왼쪽 반(파랑)이 화면을 채우지 않는다")
        next()
        waitForColor(green, "다음은 오른쪽 반(초록)이어야 한다")
        // 반쪽을 넘겨도 쪽 번호와 자리는 책의 2쪽이다.
        assertTrue(has(hasContentDescription("만화 2쪽")))
        compose.waitUntil(10_000) { runBlocking { savedPage() } == 1 }
        next()
        waitFor(hasContentDescription("만화 3쪽"))
        compose.waitUntil(10_000) { runBlocking { savedPage() } == 2 }
    }

    @Test
    fun `a right-to-left book shows the right half of a spread first`() {
        // 이 작품은 오→왼(보기 판에서 고른 것과 같다).
        runBlocking { app.container.data.comics.let { c -> c.setRightToLeft(c.works().first().single(), true) } }
        open()
        next(rtl = true)
        waitFor(hasContentDescription("만화 2쪽"))
        waitForColor(green, "오→왼 책은 오른쪽 반(초록)이 먼저여야 한다")
        next(rtl = true)
        waitForColor(blue, "그다음 왼쪽 반(파랑)")
    }

    @Test
    fun `turning splitting off shows the whole spread at the same page and a half bookmark is the book's page`() {
        open()
        next(); next()
        waitForColor(green, "오른쪽 반에 오지 않았다")
        // 반쪽에서 꽂은 책갈피는 책의 2쪽(번호 1)이다.
        compose.onRoot().performTouchInput { click(topRight.copy(x = width - 20f, y = 40f)) }
        compose.waitUntil(10_000) { runBlocking { app.container.data.comics.bookmarks(app.container.data.comics.units().first().single().id).first() } == listOf(1) }
        compose.onRoot().performTouchInput { click(center) }
        click(hasText("보기"))
        waitFor(hasText("펼침면 나누기"))
        val y = node(hasText("펼침면 나누기")).fetchSemanticsNode().boundsInRoot.center.y
        compose.onAllNodes(hasText("끔"), useUnmergedTree = true).let { nodes ->
            val k = nodes.fetchSemanticsNodes().indexOfFirst { kotlin.math.abs(it.boundsInRoot.center.y - y) < 30f }
            nodes[k].performClick()
        }
        compose.waitUntil(10_000) { !app.container.prefs.load().screen.comicSplitSpreads }
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        // 같은 2쪽 — 펼침면 전체라 화면 가운데는 쪽 밖 바탕이다(위에 작게 선다).
        waitFor(hasContentDescription("만화 2쪽"))
        assertTrue(runCatching { compose.waitUntil(10_000) { center().let { !near(it, blue) && !near(it, green) } } }.isSuccess, "나누기를 껐는데 반쪽이 화면을 채운다")
        assertEquals(1, runBlocking { savedPage() })
    }
}
