package io.github.kgcaudit.reader.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.reflow.ReaderPrefs
import io.github.kgcaudit.reader.ui.design.ImageBlend
import io.github.kgcaudit.reader.ui.design.PaperTheme
import io.github.kgcaudit.reader.ui.design.ScreenPrefs
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 그림 흰 바탕을 지면색으로(0.24.0, 구상안 확정). 책 속 그림과 PDF 쪽의 흰 바탕이 아이보리 · 세피아 지면 위에서 흰 네모로
 * 떠 보였다. 밝은 지면은 곱하기, 검정은 조금 어둡게, "그대로" 는 원래 그림.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class ImageBlendTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>(StandardTestDispatcher())

    private val app = ApplicationProvider.getApplicationContext<OloApp>()
    private val shots = File(System.getProperty("reader.screenshots") ?: "build/screenshots").apply { mkdirs() }

    @Before
    fun setUp() {
        val folder = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        // 흰 바탕 가운데 파란 네모 — 책 속 차례 · 간지 그림처럼 흰 바탕으로 만든 그림.
        val png = ByteArrayOutputStream().also {
            Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.WHITE)
                Canvas(this).drawRect(100f, 200f, 300f, 400f, android.graphics.Paint().apply { color = Color.BLUE })
            }.compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        File(folder, "그림책.epub").writeBytes(SampleBooks.pictureBook(png))
        File(folder, "계약서.pdf").writeBytes(ByteArray(64))
        app.container.pdfEngine = { DrawnPdf(it, pageCount = 3) }
        runBlocking { app.container.data.folders.register(FolderProvider.treeUri) }
    }

    private fun has(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 30_000) = compose.waitUntil(timeoutMs) { has(matcher) }

    private fun screen(): Bitmap {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        return bitmap
    }

    private fun shot(name: String) = File(shots, "$name.png").outputStream().use { screen().compress(Bitmap.CompressFormat.PNG, 100, it) }

    private fun open(name: String, theme: PaperTheme, blend: ImageBlend = ImageBlend.Paper) {
        app.container.prefs.save(ReaderPrefs(screen = ScreenPrefs(theme = theme, imageBlend = blend)))
        compose.activityRule.scenario.recreate()
        waitFor(hasText(name))
        compose.onAllNodes(hasText(name), useUnmergedTree = true)[0].performClick()
        waitFor(hasText("1 / ", substring = true))
    }

    /** 파란 네모의 자리. 지면색에 곱해져도 파랑은 파랑이다. */
    private fun blueBox(bitmap: Bitmap): android.graphics.Rect? {
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = -1; var b = -1
        for (y in 0 until bitmap.height step 2) for (x in 0 until bitmap.width step 2) {
            val p = bitmap.getPixel(x, y)
            if (Color.blue(p) > 150 && Color.red(p) < 60 && Color.green(p) < 60) {
                l = minOf(l, x); t = minOf(t, y); r = maxOf(r, x); b = maxOf(b, y)
            }
        }
        return if (r < 0) null else android.graphics.Rect(l, t, r, b)
    }

    /** 그림 속 흰 바탕 한 점: 파란 네모 왼쪽, 그림 안(네모 폭의 1/4 만큼 떨어진 곳). */
    private fun pictureWhite(): Int {
        compose.waitUntil(15_000) { blueBox(screen()) != null }
        val bitmap = screen()
        val box = blueBox(bitmap)!!
        return bitmap.getPixel(box.left - box.width() / 4, box.centerY())
    }

    private fun assertColor(expected: Int, actual: Int, what: String) {
        val near = abs(Color.red(expected) - Color.red(actual)) <= 3 &&
            abs(Color.green(expected) - Color.green(actual)) <= 3 && abs(Color.blue(expected) - Color.blue(actual)) <= 3
        assertTrue(near, "$what: #${Integer.toHexString(expected)} 이어야 하는데 #${Integer.toHexString(actual)}")
    }

    @Test
    fun `a picture's white background takes the sepia paper colour`() {
        open("그림책.epub", PaperTheme.Sepia)
        assertColor(0xFFE9DCC0.toInt(), pictureWhite(), "세피아 지면 위 그림의 흰 바탕")
        shot("98-picture-sepia")
    }

    @Test
    fun `on black paper a picture is only dimmed so its dark lines stay visible`() {
        // 흰색만 빼면 그림 속 검은 글자가 검정 지면에 묻힌다(구상안 "다"). 곱하면 통째로 검다. 78% 로만 낮춘다.
        open("그림책.epub", PaperTheme.Black)
        assertColor(Color.rgb(199, 199, 199), pictureWhite(), "검정 지면 위 그림의 흰 바탕")
    }

    @Test
    fun `choosing as-is in the view panel shows the original white and is remembered`() {
        open("그림책.epub", PaperTheme.Sepia)
        compose.onRoot().performTouchInput { click(center) }
        compose.onAllNodes(hasText("보기"), useUnmergedTree = true)[0].performClick()
        waitFor(hasText("그림 흰 바탕"))
        compose.onAllNodes(hasText("그대로"), useUnmergedTree = true)[0].performClick()
        compose.waitUntil(10_000) { app.container.prefs.load().screen.imageBlend == ImageBlend.Original }
        // 판을 닫고 그림을 본다.
        compose.onRoot().performTouchInput { click(center.copy(y = height * 0.15f)) }
        compose.waitForIdle()
        assertColor(Color.WHITE, pictureWhite(), "그대로를 고른 그림의 흰 바탕")
    }

    @Test
    fun `a pdf page on sepia paper has no white left`() {
        // PDF 쪽은 통째로 그림이라 쪽 전체의 흰 바탕이 지면색이 된다(결정 3).
        open("계약서.pdf", PaperTheme.Sepia)
        compose.waitUntil(15_000) { blueOrInk(screen()) }
        assertEquals(0, countWhite(screen()), "세피아 지면 위 PDF 쪽에 흰 곳이 남았다")
        shot("99-pdf-sepia")
    }

    @Test
    fun `with as-is chosen a pdf page stays white on sepia paper`() {
        // 거르개가 설정을 따르는지 — 설정을 무시하고 늘 거르면 위 시험만으로는 모른다.
        open("계약서.pdf", PaperTheme.Sepia, ImageBlend.Original)
        compose.waitUntil(15_000) { countWhite(screen()) > 1000 }
    }

    /** 쪽이 그려졌는가: 글자(짙은 점)가 보인다. */
    private fun blueOrInk(bitmap: Bitmap): Boolean {
        for (y in bitmap.height / 4 until bitmap.height * 3 / 4 step 4) for (x in 0 until bitmap.width step 4) {
            val p = bitmap.getPixel(x, y)
            if (Color.red(p) < 80 && Color.green(p) < 80) return true
        }
        return false
    }

    private fun countWhite(bitmap: Bitmap): Int {
        var n = 0
        for (y in 0 until bitmap.height step 3) for (x in 0 until bitmap.width step 3) {
            if (bitmap.getPixel(x, y) == Color.WHITE) n++
        }
        return n
    }
}
