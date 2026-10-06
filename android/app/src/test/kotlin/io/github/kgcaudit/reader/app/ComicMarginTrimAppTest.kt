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
import kotlin.test.assertTrue

/**
 * 만화 여백 자르기(0.49.0, 사용자 결정 3-가~라): 스캔본의 종이 여백을 걷어 그림이 화면 폭을 채우고, 보기 판의 "여백 자르기"
 * 를 끄면 여백이 돌아온다.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class ComicMarginTrimAppTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>(StandardTestDispatcher())

    @After
    fun settleTurn() = compose.waitForIdle()

    private val app = ApplicationProvider.getApplicationContext<OloApp>()
    private val shots = File(System.getProperty("reader.screenshots") ?: "build/screenshots").apply { mkdirs() }

    /**
     * 스캔한 쪽: 누런 종이 위에 좌우 12% · 위아래 5% 여백, 안에 검은 칸(456×810). 여백이 고르지 않아 자르면 쪽의 비가
     * 바뀐다 — 자른 그림을 원래 쪽의 비로 그리면 칸이 옆으로 늘어나는 것까지 잡는다.
     */
    private fun scanned(): ByteArray {
        val b = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)
        b.eraseColor(0xFFF4F0E6.toInt())
        for (y in 45 until 855) for (x in 72 until 528) b.setPixel(x, y, 0xFF141414.toInt())
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Before
    fun setUp() {
        val root = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        val file = File(root, "Comics/옛날 만화/옛날 만화 01권.cbz").apply { parentFile.mkdirs() }
        ZipOutputStream(file.outputStream()).use { zip ->
            for (name in listOf("001.png", "002.png")) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(scanned())
                zip.closeEntry()
            }
        }
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
    private fun click(matcher: SemanticsMatcher) {
        waitFor(matcher)
        compose.onAllNodes(matcher, useUnmergedTree = true).let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
    }

    private fun screen(): Bitmap {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        return bitmap
    }

    /** 화면 가운데 줄에서 검은 칸이 시작하는 x 의 화면 폭 비율. 칸이 아직 없으면 null. */
    private fun inkStart(): Float? {
        val s = screen()
        val y = s.height / 2
        val x = (0 until s.width).firstOrNull { android.graphics.Color.red(s.getPixel(it, y)) < 60 } ?: return null
        return x.toFloat() / s.width
    }

    /**
     * 검은 칸의 세로 ÷ 가로(화면에서). 화면 가운데에서 이어진 검은 구간만 잰다 — 쪽 밖 바탕도 어두워서, 줄 전체에서 찾으면
     * 바탕까지 칸으로 쳤다.
     */
    private fun inkRatio(): Float? {
        val s = screen()
        fun dark(x: Int, y: Int) = android.graphics.Color.red(s.getPixel(x, y)) < 60
        val cx = s.width / 2
        val cy = s.height / 2
        if (!dark(cx, cy)) return null
        var l = cx; while (l > 0 && dark(l - 1, cy)) l--
        var r = cx; while (r < s.width - 1 && dark(r + 1, cy)) r++
        var t = cy; while (t > 0 && dark(cx, t - 1)) t--
        var b = cy; while (b < s.height - 1 && dark(cx, b + 1)) b++
        return (b - t + 1).toFloat() / (r - l + 1)
    }

    @Test
    fun `scan margins are trimmed so the panels reach the screen edge until turned off`() {
        click(hasText("만화 1"))
        click(hasContentDescription("옛날 만화 작품"))
        click(hasText("1권"))
        waitFor(hasContentDescription("만화 1쪽"))
        // 켬(기본): 칸이 화면 끝 가까이에서 시작한다. 여백 그대로면 12%.
        compose.waitUntil(30_000) { inkStart()?.let { it < 0.05f } == true }
        // 칸은 원래 비(810 ÷ 456)대로 — 자른 쪽을 머리의 비로 그리면 1.5 정도로 눌린다.
        val ratio = inkRatio()
        assertTrue(ratio != null && kotlin.math.abs(ratio - 810f / 456f) < 0.05f, "칸의 비가 $ratio")
        File(shots, "comic-trim-on.png").outputStream().use { screen().compress(Bitmap.CompressFormat.PNG, 100, it) }

        compose.onRoot().performTouchInput { click(center) }
        click(hasText("보기"))
        waitFor(hasText("여백 자르기"))
        File(shots, "comic-trim-panel.png").outputStream().use { screen().compress(Bitmap.CompressFormat.PNG, 100, it) }
        // "끔" 은 두 쪽 보기 줄에도 있다 — 여백 자르기 줄과 같은 높이의 것을 누른다.
        val rowTop = compose.onAllNodes(hasText("여백 자르기"), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot.center.y
        val offs = compose.onAllNodes(hasText("끔"), useUnmergedTree = true)
        val k = offs.fetchSemanticsNodes().indexOfFirst { kotlin.math.abs(it.boundsInRoot.center.y - rowTop) < 30f }
        assertTrue(k >= 0, "여백 자르기 줄에 끔이 없다")
        // 다른 켬 · 끔 줄(두 쪽 보기)과 같은 차례: 켬이 왼쪽. 0.49.0 은 이 줄만 "끔 · 켬" 이었다.
        val on = compose.onAllNodes(hasText("켬"), useUnmergedTree = true).fetchSemanticsNodes()
            .first { kotlin.math.abs(it.boundsInRoot.center.y - rowTop) < 30f }
        assertTrue(on.boundsInRoot.center.x < offs.fetchSemanticsNodes()[k].boundsInRoot.center.x, "여백 자르기 줄의 켬이 끔보다 뒤에 있다")
        offs[k].performClick()
        compose.waitUntil(30_000) { runBlocking { !app.container.prefs.load().screen.comicTrimMargins } }
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(1_000)
        // 끔: 종이 여백이 돌아온다.
        compose.waitUntil(30_000) { inkStart()?.let { it > 0.09f } == true }
    }
}
