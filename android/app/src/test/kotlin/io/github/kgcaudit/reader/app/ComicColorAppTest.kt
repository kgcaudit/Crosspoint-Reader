package io.github.kgcaudit.reader.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
import io.github.kgcaudit.reader.reflow.ReaderPrefs
import io.github.kgcaudit.reader.ui.design.ComicColor
import io.github.kgcaudit.reader.ui.design.PageTurn
import io.github.kgcaudit.reader.ui.design.ScreenPrefs
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
 * 만화 색 보정(사용자 결정 2-1 · 2-2): 보기 판의 "색 보정" 줄이 화면에 그려지는 색을 바꾸고, 쪽 넘김과 웹툰이 같은 설정을
 * 따르며, 다시 열어도 남는다. 화면을 픽셀로 읽는다 — 설정 값만 보면 거르개를 그리기에 넘기지 않은 것을 못 잡는다.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class ComicColorAppTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>(StandardTestDispatcher())

    @After
    fun settleTurn() = compose.waitForIdle()

    private val app = ApplicationProvider.getApplicationContext<OloApp>()

    private val paper = 0xFFE6D9B5.toInt()
    private val red = 0xFFC03030.toInt()

    /** 오래된 스캔 한 쪽: 위 절반은 누런 종이, 아래 절반은 빨간 칠. 종이색 · 색 있는 곳을 한 쪽에서 잰다. */
    private fun scan(): ByteArray {
        val b = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)
        b.eraseColor(paper)
        for (y in 450 until 900) for (x in 0 until 600) b.setPixel(x, y, red)
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun png(w: Int, h: Int, color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Before
    fun setUp() {
        val root = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        val file = File(root, "Comics/옛날 만화/옛날 만화 01권.cbz").apply { parentFile.mkdirs() }
        ZipOutputStream(file.outputStream()).use { zip ->
            for (name in listOf("001.png", "002.png")) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(scan())
                zip.closeEntry()
            }
        }
        // 누런 종이색 웹툰 한 화.
        val dir = File(root, "Webtoon/전학생/001화").apply { mkdirs() }
        for (i in 1..3) File(dir, "$i.png").writeBytes(png(60, 400, paper))
        app.container.comicRegions = false
        runBlocking {
            val data = app.container.data
            data.folders.register(FolderProvider.treeUri)
            data.rescanAll()
            data.probeComics()
        }
        // 여백 자르기는 끈다 — 고른 종이 바탕은 여백으로 잘려 나가 종이색을 잴 곳이 없어진다. 넘김 효과도 없이(시험이 짧게).
        save(ScreenPrefs())
    }

    private fun save(screen: ScreenPrefs) {
        app.container.prefs.save(ReaderPrefs(screen = screen.copy(comicTrimMargins = false, pageTurn = PageTurn.None)))
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

    private fun rgb(pixel: Int) = Triple(android.graphics.Color.red(pixel), android.graphics.Color.green(pixel), android.graphics.Color.blue(pixel))

    /** 화면 [fy] 높이 가운데의 색이 [ok] 를 만족할 때까지 기다린다. 끝내 아니면 마지막 색을 알린다. */
    private fun waitForPixel(fy: Float, what: String, ok: (Triple<Int, Int, Int>) -> Boolean) {
        var last: Triple<Int, Int, Int>? = null
        val done = runCatching {
            compose.waitUntil(30_000) { screen().let { s -> rgb(s.getPixel(s.width / 2, (s.height * fy).toInt())).also { last = it }.let(ok) } }
        }.isSuccess
        assertTrue(done, "$what — 화면 색이 $last")
    }

    // 쪽은 화면 폭에 맞춰 위에서부터(휴대폰): 폭 393dp → 높이 589dp. 위 절반(종이)은 화면 높이의 0~35%, 아래 절반(빨강)은 35~69%.
    private val paperY = 0.18f
    private val redY = 0.52f

    private fun isPaper(c: Triple<Int, Int, Int>) = kotlin.math.abs(c.first - 0xE6) < 6 && kotlin.math.abs(c.second - 0xD9) < 6 && kotlin.math.abs(c.third - 0xB5) < 6
    private fun isCleared(c: Triple<Int, Int, Int>) = c.first >= 250 && c.second >= 245 && c.third in 0xB5 + 5..0xD0
    private fun isGray(c: Triple<Int, Int, Int>) = kotlin.math.abs(c.first - c.second) <= 3 && kotlin.math.abs(c.second - c.third) <= 3

    private fun openComic() {
        click(hasText("만화 2"))
        click(hasContentDescription("옛날 만화 작품"))
        click(hasText("1권"))
        waitFor(hasContentDescription("만화 1쪽"))
    }

    private fun openViewPanel() {
        compose.onRoot().performTouchInput { click(center) }
        click(hasText("보기"))
        waitFor(hasText("색 보정"))
    }

    /** 보기 판 → 뒤로(도구줄로) → 지면을 눌러 닫는다. 도구줄이 남아 있으면 다음에 가운데를 누를 때 열리지 않고 닫힌다. */
    private fun closePanel() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(1_000)
        if (has(hasText("보기"))) compose.onRoot().performTouchInput { click(center) }
        compose.waitUntil(30_000) { !has(hasText("보기")) }
    }

    @Test
    fun `the colour row clears yellowed paper, grays a coloured page, and is remembered after reopening`() {
        openComic()
        // 끔(기본): 푼 그대로의 색.
        waitForPixel(paperY, "끔인데 종이색이 바뀌었다", ::isPaper)
        waitForPixel(redY, "끔인데 빨강이 바뀌었다") { it.first > 170 && it.second < 70 }

        openViewPanel()
        // 자리: "펼침면 나누기" 바로 아래, 두 쪽 보기 줄 위(구상안).
        fun y(label: String) = compose.onAllNodes(hasText(label), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot.center.y
        assertTrue(y("펼침면 나누기") < y("색 보정") && y("색 보정") < y("가로에서 두 쪽 보기"), "색 보정 줄의 자리가 구상안과 다르다")
        click(hasText("선명하게"))
        compose.waitUntil(30_000) { app.container.prefs.load().screen.comicColor == ComicColor.Clear }
        closePanel()
        // 누런 종이 → 거의 흰색(빨강 · 초록은 흰 점 위, 파랑은 옅은 미색까지 — 구상안 그대로).
        waitForPixel(paperY, "선명하게인데 종이가 희어지지 않았다", ::isCleared)

        openViewPanel()
        click(hasText("흑백"))
        compose.waitUntil(30_000) { app.container.prefs.load().screen.comicColor == ComicColor.Gray }
        closePanel()
        waitForPixel(redY, "흑백인데 빨강이 회색이 되지 않았다", ::isGray)
        waitForPixel(paperY, "흑백인데 종이가 희지 않다") { c -> isGray(c) && c.first >= 245 }

        // 다시 열어도 흑백.
        // 화면을 다시 만들면 보던 만화가 그대로 다시 열린다(리더 자리를 기억한다). 서재로 돌아갔으면 다시 연다.
        compose.activityRule.scenario.recreate()
        compose.waitUntil(30_000) { has(hasContentDescription("만화 1쪽")) || has(hasText("만화 2")) }
        if (!has(hasContentDescription("만화 1쪽"))) openComic()
        waitForPixel(redY, "다시 열었더니 흑백이 풀렸다", ::isGray)

        // 끔으로 돌리면 원래 색 — 거르개가 남아 있지 않다.
        openViewPanel()
        click(hasText("끔") and hasAnyAncestorRow("색 보정"))
        compose.waitUntil(30_000) { app.container.prefs.load().screen.comicColor == ComicColor.Off }
        closePanel()
        waitForPixel(paperY, "끔으로 돌렸는데 종이색이 돌아오지 않았다", ::isPaper)
    }

    /** "끔" 은 다른 켬 · 끔 줄에도 있다 — [row] 줄과 같은 높이의 것만. */
    private fun hasAnyAncestorRow(row: String) = SemanticsMatcher("same row as $row") { n ->
        val rowY = compose.onAllNodes(hasText(row), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.center?.y
        rowY != null && kotlin.math.abs(n.boundsInRoot.center.y - rowY) < 30f
    }

    private val webtoon = SemanticsMatcher("webtoon view") { n ->
        n.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith("웹툰 ") && it.endsWith("%") } == true
    }

    @Test
    fun `webtoons follow the same colour setting and show the same row`() {
        save(ScreenPrefs(comicColor = ComicColor.Clear))
        click(hasText("만화 2"))
        click(hasContentDescription("전학생 작품"))
        click(hasText("1화"))
        waitFor(webtoon)
        // 띠가 모두 누런 종이색이라 화면 가운데가 곧 그림이다.
        waitForPixel(0.5f, "웹툰에 선명하게가 씌워지지 않았다", ::isCleared)

        compose.onRoot().performTouchInput { click(center) }
        click(hasText("보기"))
        waitFor(hasText("색 보정"))
        click(hasText("끔") and hasAnyAncestorRow("색 보정"))
        compose.waitUntil(30_000) { app.container.prefs.load().screen.comicColor == ComicColor.Off }
        closePanel()
        waitForPixel(0.5f, "웹툰에서 끔으로 돌렸는데 원래 색이 아니다", ::isPaper)
        assertEquals(ComicColor.Off, app.container.prefs.load().screen.comicColor)
    }
}
