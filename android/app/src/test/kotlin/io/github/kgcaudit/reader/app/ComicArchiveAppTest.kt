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
import io.github.kgcaudit.reader.document.archive.StoredArchives
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
import kotlin.test.assertTrue

/**
 * cbr · cb7 · cbt 를 서재에서 열어 읽는다(0.37.0, docs/COMIC_PLAN.md C4). RAR · 7z 는 C++ 해제기(PC 빌드), tar 는 Kotlin.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class ComicArchiveAppTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>(StandardTestDispatcher())

    @After
    fun settleTurn() = compose.waitForIdle()

    private val app = ApplicationProvider.getApplicationContext<OloApp>()
    private val red = 0xFFD03030.toInt()
    private val blue = 0xFF3050D0.toInt()

    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(60, 90, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Before
    fun setUp() {
        val root = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        val pages = listOf("001.png" to png(red), "002.png" to png(blue))
        File(root, "C").mkdirs()
        File(root, "C/별 01권.cbr").writeBytes(StoredArchives.rar4(pages))
        File(root, "C/별 02권.cbt").writeBytes(StoredArchives.tar(pages))
        File(root, "C/칠지.cb7").writeBytes(javaClass.getResource("/comic.cb7")!!.readBytes())
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
        return d(color) < 40 * 40 * 3
    }

    private fun waitForColor(color: Int, message: String) {
        val ok = runCatching {
            compose.waitUntil(30_000) { screen().let { near(it.getPixel(it.width / 2, it.height / 2), color) } }
        }.isSuccess
        assertTrue(ok, message)
    }

    private fun openVolume(work: String, label: String) {
        click(hasText("만화 2"))
        click(hasContentDescription("$work 작품"))
        click(hasText(label))
        waitFor(hasContentDescription("만화 1쪽"))
    }

    @Test
    fun `a cbr opens and its pages are drawn`() {
        openVolume("별", "1권")
        waitForColor(red, "cbr 1쪽이 그려지지 않았다")
        compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.9f)) }
        compose.mainClock.advanceTimeBy(1_000)
        waitFor(hasContentDescription("만화 2쪽"))
        waitForColor(blue, "cbr 2쪽이 그려지지 않았다")
    }

    @Test
    fun `a cbt opens and its pages are drawn`() {
        openVolume("별", "2권")
        waitForColor(red, "cbt 1쪽이 그려지지 않았다")
    }

    @Test
    fun `a cb7 opens with the direction its comic info gives`() {
        openVolume("칠지", "2권")
        // ComicInfo 의 Manga=YesAndRightToLeft — 아래 줄에 오→왼.
        waitFor(hasText("1 / 3  ← 오→왼"))
        waitForColor(0xFFD03030.toInt(), "cb7 1쪽(빨강)이 그려지지 않았다")
    }
}
