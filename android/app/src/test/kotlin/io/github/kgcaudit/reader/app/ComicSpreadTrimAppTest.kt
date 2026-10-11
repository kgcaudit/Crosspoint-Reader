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
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * 두 쪽 보기의 여백 자르기: 한 판의 쪽들은 같은 상자로 잘려 같은 크기로 나란히 놓인다. 먼작귀 컬러판(모든 쪽이 같은 픽셀
 * 크기)에서 흰 여백이 있는 쪽과 하늘이 끝까지 찬 짝이 따로 잘려, 판마다 크기가 다르고 가운데 이음매의 구름이 어긋났다.
 *
 * 쪽은 모두 600×900. 0쪽은 표지(혼자), 1–2 판은 흰 여백 쪽(A) + 하늘이 끝까지 찬 쪽(B), 3–4 판은 여백 폭이 다른 두 쪽(C · D).
 * 모든 쪽의 600–630 줄에 남색 띠를 그어, 두 쪽이 같은 배율로 그려졌는지 띠의 화면 줄로 본다.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-mdpi")
class ComicSpreadTrimAppTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>(StandardTestDispatcher())

    @After
    fun settleTurn() = compose.waitForIdle()

    private val app = ApplicationProvider.getApplicationContext<OloApp>()
    private val shots = File(System.getProperty("reader.screenshots") ?: "build/screenshots").apply { mkdirs() }

    private val white = 0xFFFFFFFF.toInt()
    private val sky = 0xFFD6EEF2.toInt()
    private val grass = 0xFFD2E4A8.toInt()
    private val orange = 0xFFE68C28.toInt()
    private val blue = 0xFF3C78C8.toInt()
    private val band = 0xFF1E2878.toInt()

    private fun png(paint: (Bitmap) -> Unit): ByteArray {
        val b = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)
        paint(b)
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun Bitmap.fill(l: Int, t: Int, r: Int, b: Int, color: Int) {
        val row = IntArray(r - l) { color }
        for (y in t until b) setPixels(row, 0, r - l, l, y, r - l, 1)
    }

    /** 흰 종이 위에 좌우 [side] · 위아래 90 픽셀 여백, 안은 [inner] 색, 600–630 줄에 띠. */
    private fun margined(side: Int, inner: Int) = png { b ->
        b.eraseColor(white)
        b.fill(side, 90, 600 - side, 810, inner)
        b.fill(side, 600, 600 - side, 630, band)
    }

    /** 위 1/3 이 연한 하늘, 아래가 풀밭인 그림이 끝까지 찬 쪽(먼작귀 컬러판의 하늘 쪽), 600–630 줄에 띠. */
    private fun skyPage() = png { b ->
        b.eraseColor(grass)
        b.fill(0, 0, 600, 300, sky)
        b.fill(0, 600, 600, 630, band)
    }

    @Before
    fun setUp() {
        val root = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        val file = File(root, "C/구름 01권.cbz").apply { parentFile.mkdirs() }
        val pages = listOf(margined(60, orange), margined(60, orange), skyPage(), margined(60, orange), margined(24, blue))
        ZipOutputStream(file.outputStream()).use { zip ->
            pages.forEachIndexed { i, bytes ->
                zip.putNextEntry(ZipEntry("%03d.png".format(i + 1)))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        File(root, "C/구름 02권.cbz").writeBytes(file.readBytes())
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

    private fun near(pixel: Int, color: Int, tolerance: Int = 24): Boolean =
        abs(android.graphics.Color.red(pixel) - android.graphics.Color.red(color)) <= tolerance &&
            abs(android.graphics.Color.green(pixel) - android.graphics.Color.green(color)) <= tolerance &&
            abs(android.graphics.Color.blue(pixel) - android.graphics.Color.blue(color)) <= tolerance

    /** 쪽 밖 바탕(COMIC_BACKDROP)이 아닌 점. */
    private fun drawn(pixel: Int) = !near(pixel, 0xFF141311.toInt(), 12)

    /** 화면에 그려진 판의 상자(쪽 밖 바탕이 아닌 곳): 가운데 줄 · 가운데 열에서 잰다. */
    private data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left + 1
        val height get() = bottom - top + 1
    }

    private fun spreadRect(s: Bitmap, column: Int = s.width / 2): Rect? {
        val y = s.height * 2 / 3
        val l = (0 until s.width).firstOrNull { drawn(s.getPixel(it, y)) } ?: return null
        val r = (s.width - 1 downTo 0).first { drawn(s.getPixel(it, y)) }
        val t = (0 until s.height).firstOrNull { drawn(s.getPixel(column, it)) } ?: return null
        val b = (s.height - 1 downTo 0).first { drawn(s.getPixel(column, it)) }
        return Rect(l, t, r, b)
    }

    /** 열 [x] 에서 띠가 시작하는 화면 줄. */
    private fun bandTop(s: Bitmap, x: Int): Int? = (0 until s.height).firstOrNull { near(s.getPixel(x, it), band) }

    /** [y] 줄에서 [color] 가 이어진 구간의 폭(처음 만난 구간, [from] 부터). */
    private fun runWidth(s: Bitmap, y: Int, color: Int, from: Int = 0): Int {
        val start = (from until s.width).firstOrNull { near(s.getPixel(it, y), color) } ?: return 0
        var end = start
        while (end + 1 < s.width && near(s.getPixel(end + 1, y), color)) end++
        return end - start + 1
    }

    private fun next() {
        compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.97f)) }
        compose.mainClock.advanceTimeBy(1_000)
    }

    private fun openSpread(label: String) {
        click(hasText("만화 1"))
        click(hasContentDescription("구름 작품"))
        click(hasText("1권"))
        waitFor(hasContentDescription("만화 1쪽"))
        next(); waitFor(hasContentDescription("만화 2–3쪽"))
        if (label == "4–5") { next(); waitFor(hasContentDescription("만화 4–5쪽")) }
    }

    /** 판이 [check] 를 만족할 때까지(쪽은 입출력 스레드에서 풀린다) 기다린다. 끝내 안 되면 화면을 남기고 실패. */
    private fun waitForScreen(name: String, message: () -> String, check: (Bitmap) -> Boolean) {
        var last: Bitmap? = null
        val ok = runCatching { compose.waitUntil(30_000) { screen().also { last = it }.let(check) } }.isSuccess
        last?.let { s -> File(shots, "$name.png").outputStream().use { s.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        assertTrue(ok, message())
    }

    @Test
    fun `a white-margin page beside a full-bleed sky page is drawn at the same size and scale`() {
        openSpread("2–3")
        var why = ""
        waitForScreen("comic-spread-trim-sky", { why }) { s ->
            val rect = spreadRect(s) ?: return@waitForScreen false.also { why = "판이 그려지지 않았다" }
            // 하늘 쪽(B)은 여백이 없으니 판 전체를 자르지 않는다 — 판의 비는 자르지 않은 두 쪽(600×900 둘)의 비 4:3.
            // 옛 판은 B 의 하늘을 여백으로 걷어 판이 1.44 로 넓어졌다(판마다 위 띠 높이가 달라진 까닭).
            val aspect = rect.width.toFloat() / rect.height
            // 두 쪽의 띠가 같은 화면 줄에 — 같은 배율로 그려졌다(구름이 이음매에서 이어진다).
            val a = bandTop(s, rect.left + rect.width / 4)
            val b = bandTop(s, rect.left + rect.width * 3 / 4)
            // 이음매: 가운데 줄에서 A 의 흰 오른쪽 여백이 끝나고 B 의 풀빛이 시작하는 곳. 두 쪽의 폭이 같아야 한다.
            val y = rect.top + rect.height / 2
            val seam = (rect.left until rect.right).firstOrNull { near(s.getPixel(it, y), grass, 10) } ?: -1
            val leftW = seam - rect.left
            val rightW = rect.right - seam + 1
            why = "판 $rect 비 $aspect, 띠 A=$a B=$b, 폭 $leftW / $rightW"
            abs(aspect - 4f / 3f) < 0.02f && a != null && b != null && abs(a - b) <= 1 && abs(leftW - rightW) <= 2
        }
    }

    @Test
    fun `two white-margin pages share the smaller trim on each side`() {
        openSpread("4–5")
        var why = ""
        waitForScreen("comic-spread-trim-shared", { why }) { s ->
            val rect = spreadRect(s) ?: return@waitForScreen false.also { why = "판이 그려지지 않았다" }
            // 함께 쓰는 상자: 좌우는 여백이 좁은 D 를 따라 3%(24 픽셀 − 여유 6), 위아래는 둘 다 9% — 쪽마다 564×738.
            // 쪽마다 따로 자르면 C 가 492 폭으로 좁아져 판의 비가 1.43 이 된다.
            val aspect = rect.width.toFloat() / rect.height
            val pageW = rect.width / 2f
            val y = rect.top + rect.height / 3
            val orangeW = runWidth(s, y, orange)
            val blueW = runWidth(s, y, blue)
            val a = bandTop(s, rect.left + rect.width / 4)
            val b = bandTop(s, rect.left + rect.width * 3 / 4)
            why = "판 $rect 비 $aspect, 주황 $orangeW 파랑 $blueW (쪽 폭 $pageW), 띠 $a / $b"
            abs(aspect - 2 * 564f / 738f) < 0.03f &&
                abs(orangeW - pageW * 480f / 564f) < 4f && abs(blueW - pageW * 552f / 564f) < 4f &&
                a != null && b != null && abs(a - b) <= 1
        }
    }

    @Test
    @Config(qualifiers = "w393dp-h851dp-xhdpi")
    fun `single-page view still trims each page by its own margins`() {
        // 한 쪽 보기(세로 휴대폰)는 쪽마다 제 여백대로 — 흰 여백 쪽(A)의 주황 칸이 화면 끝 가까이에서 시작한다(여백 그대로면 10%).
        click(hasText("만화 1"))
        click(hasContentDescription("구름 작품"))
        click(hasText("1권"))
        waitFor(hasContentDescription("만화 1쪽"))
        next(); waitFor(hasContentDescription("만화 2쪽"))
        var why = ""
        waitForScreen("comic-single-trim", { why }) { s ->
            val y = s.height / 3
            val x = (0 until s.width).firstOrNull { near(s.getPixel(it, y), orange) } ?: return@waitForScreen false
            why = "주황이 ${x.toFloat() / s.width} 에서 시작"
            x.toFloat() / s.width < 0.03f
        }
    }

    @Test
    @Config(qualifiers = "w393dp-h851dp-xhdpi")
    fun `a full-bleed page with a pale sky keeps its sky in single-page view`() {
        // 위 두 모서리가 연한 하늘이어도 종이 여백이 아니다 — 쪽은 원래 비(900 ÷ 600)대로 그려진다. 옛 판은 하늘을 14% 걷어
        // 1.29 로 납작해졌다.
        click(hasText("만화 1"))
        click(hasContentDescription("구름 작품"))
        click(hasText("1권"))
        waitFor(hasContentDescription("만화 1쪽"))
        next(); waitFor(hasContentDescription("만화 2쪽"))
        next(); waitFor(hasContentDescription("만화 3쪽"))
        var why = ""
        waitForScreen("comic-single-sky", { why }) { s ->
            val rect = spreadRect(s) ?: return@waitForScreen false.also { why = "쪽이 그려지지 않았다" }
            val ratio = rect.height.toFloat() / rect.width
            why = "쪽 $rect 세로/가로 $ratio"
            near(s.getPixel(s.width / 2, rect.top + 2), sky) && abs(ratio - 1.5f) < 0.03f
        }
    }
}
