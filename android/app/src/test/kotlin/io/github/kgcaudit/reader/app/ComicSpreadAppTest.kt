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
import androidx.compose.ui.test.performScrollTo
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
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        // 책과 같은 "가로에서 두 쪽 보기" 줄(0.42.0). 끔이 둘(가로 · 세로) — 위의 것이 가로다.
        waitFor(hasText("가로에서 두 쪽 보기"))
        compose.onAllNodes(hasText("끔"), useUnmergedTree = true)[0].performClick()
        // 보던 판의 첫 쪽이 한 쪽으로.
        waitFor(page("2"))
        assertEquals(false, app.container.prefs.load().screen.twoPagesLandscape)
    }

    @Test
    @Config(qualifiers = "w673dp-h841dp-xhdpi")
    fun `turning off two pages in all view settings applies to comics too`() {
        // 0.41 의 고장: 모든 보기 설정의 두 쪽 줄을 꺼도 만화는 두 쪽 그대로였다(만화만의 설정을 따로 읽었다).
        // 세로 태블릿에서 잰다 — 가로 휴대폰은 보기 판이 굴러야 줄이 보이고, 그 굴리기가 시험 힙을 넘겼다.
        openVolume()
        waitFor(page("1"))
        next(); waitFor(page("2–3"))
        compose.onRoot().performTouchInput { click(center) }
        click(hasText("보기"))
        click(hasText("모든 보기 설정"))
        waitFor(hasText("세로에서 두 쪽 보기"))
        // 만화가 따르지 않는 넘김 효과 줄은 없다(줄 구성은 ViewSettingsRowsTest 가 따로 잰다).
        assertFalse(has(hasText("넘김 효과")), "만화는 붙은 밀기로 넘기는데 넘김 효과 줄이 보인다")
        val y = node(hasText("세로에서 두 쪽 보기")).fetchSemanticsNode().boundsInRoot.center.y
        compose.onAllNodes(hasText("끔"), useUnmergedTree = true).let { nodes ->
            val i = nodes.fetchSemanticsNodes().indexOfFirst { kotlin.math.abs(it.boundsInRoot.center.y - y) < 40f }
            nodes[i].performClick()
        }
        compose.waitUntil(10_000) { !app.container.prefs.load().screen.twoPagesPortrait }
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitFor(page("2"))
    }

    @Test
    fun `an old comic-only two page setting is ignored`() {
        // 망가뜨린 입력: 0.41 이 남긴 만화 전용 값 "끔". 이제 책과 같은 설정(가로 켬)을 따른다.
        app.getSharedPreferences("reader", android.content.Context.MODE_PRIVATE).edit().putString("comicSpread", "Off").commit()
        compose.activityRule.scenario.recreate()
        openVolume()
        waitFor(page("1"))
        next(); waitFor(page("2–3"))
    }

    @Test
    fun `the footer follows the reading footer setting and auto turn works in comics`() {
        runBlocking {
            val p = app.container.prefs.load()
            app.container.prefs.save(
                p.copy(
                    screen = p.screen.copy(
                        footer = io.github.kgcaudit.reader.ui.design.Footer(
                            io.github.kgcaudit.reader.ui.design.FooterItem.ChapterTitle,
                            io.github.kgcaudit.reader.ui.design.FooterItem.None,
                            io.github.kgcaudit.reader.ui.design.FooterItem.Percent,
                        ),
                        autoTurn = io.github.kgcaudit.reader.ui.design.AutoTurn.entries.first { it.seconds != null },
                    ),
                ),
            )
        }
        compose.activityRule.scenario.recreate()
        openVolume()
        waitFor(page("1"))
        // 장 이름 자리에 권 이름, 오른쪽에 %. 0.41 까지는 "제목 · 쪽" 으로 박혀 있었다.
        waitFor(hasText("1권"))
        assertTrue(has(hasText("0%")))
        assertFalse(has(hasText("1 / 7")), "꺼 둔 쪽 자리가 보인다")
        // 자동 넘김: 손대지 않아도 다음 판으로.
        val seconds = io.github.kgcaudit.reader.ui.design.AutoTurn.entries.first { it.seconds != null }.seconds!!
        compose.mainClock.advanceTimeBy((seconds + 2) * 1_000L)
        waitFor(page("2–3"))
    }

    /** 화면 가운데 줄 [fx] 자리가 [page] 쪽 색인가(기다리지 않는다 — 끄는 중의 한 순간을 잰다). */
    private fun isPageAt(shot: Bitmap, fx: Float, page: Int) = near(shot.getPixel((shot.width * fx).toInt(), shot.height / 2), colors[page])

    @Test
    @Config(qualifiers = "w393dp-h851dp-xhdpi")
    fun `while dragging, the next page is attached right beside the current one`() {
        // 0.40.0 사용자 결정 7: 만화는 두 쪽에 걸친 그림이 있다 — 넘기는 동안 다음 쪽이 바로 옆에 붙어 함께 와야 이어 보인다.
        openVolume()
        waitFor(page("1"))
        waitForPageAt(0.5f, 0, "첫 쪽이 그려지지 않았다")
        compose.onRoot().performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(-width * 0.2f, 0f)); moveBy(androidx.compose.ui.geometry.Offset(-width * 0.2f, 0f)) }
        compose.mainClock.advanceTimeBy(100)
        val mid = screen()
        File(shots, "comic-drag.png").outputStream().use { mid.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // 지금 쪽(검정)은 왼쪽으로 밀려 왼편에, 다음 쪽(빨강)은 그 오른쪽에 붙어 들어온다.
        assertTrue(isPageAt(mid, 0.3f, 0), "끄는 동안 지금 쪽이 손가락을 따라오지 않았다")
        assertTrue(isPageAt(mid, 0.8f, 1), "끄는 동안 다음 쪽이 옆에 붙어 있지 않다")
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1_000)
        waitFor(page("2"))
        waitForPageAt(0.5f, 1, "놓은 뒤 다음 쪽으로 자리 잡지 않았다")
    }

    @Test
    @Config(qualifiers = "w393dp-h851dp-xhdpi")
    fun `right to left brings the next page in from the left`() {
        runBlocking { app.container.data.comics.setRightToLeft(app.container.data.comics.works().first().single(), true) }
        openVolume()
        waitFor(page("1"))
        waitForPageAt(0.5f, 0, "첫 쪽이 그려지지 않았다")
        compose.onRoot().performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(width * 0.2f, 0f)); moveBy(androidx.compose.ui.geometry.Offset(width * 0.2f, 0f)) }
        compose.mainClock.advanceTimeBy(100)
        val mid = screen()
        assertTrue(isPageAt(mid, 0.7f, 0) && isPageAt(mid, 0.2f, 1), "오→왼인데 다음 쪽이 왼쪽에 붙어 오지 않았다")
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1_000)
        waitFor(page("2"))
    }

    @Test
    @Config(qualifiers = "w393dp-h851dp-xhdpi")
    fun `a short drag springs back and the first page cannot be pulled away backwards`() {
        openVolume()
        waitFor(page("1"))
        waitForPageAt(0.5f, 0, "첫 쪽이 그려지지 않았다")
        // 덜 민 끌기: 제자리로.
        compose.onRoot().performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(-30f, 0f)); moveBy(androidx.compose.ui.geometry.Offset(-20f, 0f)); up() }
        compose.mainClock.advanceTimeBy(1_000)
        assertTrue(has(page("1")))
        waitForPageAt(0.5f, 0, "덜 민 끌기 뒤 제자리로 돌아오지 않았다")
        // 첫 쪽에서 앞으로(오른쪽으로) 끌면 덜 따라온다 — 손가락만큼 오면 빈 자리가 화면 절반을 덮는다.
        compose.onRoot().performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(width * 0.25f, 0f)); moveBy(androidx.compose.ui.geometry.Offset(width * 0.25f, 0f)) }
        compose.mainClock.advanceTimeBy(100)
        val mid = screen()
        // 첫 쪽(#303030)은 바탕(#141311)과 가까워 느슨한 색 비교로는 가리지 못한다 — 거의 같은 색인지 본다.
        val p = mid.getPixel((mid.width * 0.4f).toInt(), mid.height / 2)
        assertTrue(listOf(android.graphics.Color::red, android.graphics.Color::green, android.graphics.Color::blue).all { f -> abs(f(p) - 0x30) <= 6 }, "앞 쪽이 없는데 첫 쪽이 손가락만큼 끌려갔다: #${Integer.toHexString(p)}")
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1_000)
        assertTrue(has(page("1")))
    }

    @Test
    @Config(qualifiers = "w673dp-h841dp-xhdpi")
    fun `a tablet held upright shows two pages by default`() {
        // 0.40.0 사용자 결정 8: 태블릿은 세로로 들어도 두 쪽. 0.42.0 부터 책과 같은 "세로에서 두 쪽 보기"(기본 켬)를 따른다.
        assertEquals(true, app.container.prefs.load().screen.twoPagesPortrait)
        openVolume()
        waitFor(page("1"))
        next(); waitFor(page("2–3"))
    }

    @Test
    @Config(qualifiers = "w393dp-h851dp-xhdpi")
    fun `a phone held upright keeps one page by default`() {
        openVolume()
        waitFor(page("1"))
        next(); waitFor(page("2"))
    }

    @Test
    @Config(qualifiers = "w393dp-h851dp-xhdpi")
    fun `right to left fills the top slider from the right like the bottom bar, and a tap on its left goes toward the end`() {
        // 0.41.0 사용자 결정 9: 오→왼이면 위 막대 · 아래 줄 모두 오른쪽에서 차오른다.
        runBlocking { app.container.data.comics.setRightToLeft(app.container.data.comics.works().first().single(), true) }
        openVolume()
        waitFor(page("1"))
        next(rtl = true); waitFor(page("2"))
        next(rtl = true); waitFor(page("3"))
        assertTrue(has(hasContentDescription("진행 오른쪽부터")), "아래 줄이 오른쪽부터가 아니다")
        compose.onRoot().performTouchInput { click(center) }
        waitFor(hasContentDescription("지금 위치"))
        compose.mainClock.advanceTimeBy(1_000)
        val bar = node(hasContentDescription("지금 위치")).fetchSemanticsNode().boundsInRoot
        val shot = screen()
        File(shots, "comic-rtl-bar.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // 3쪽(1/3 지점): 오른쪽 끝 가까이는 칠해졌고 왼쪽 끝 가까이는 비었다.
        val y = bar.center.y.toInt()
        val accent = shot.getPixel((bar.right - bar.width * 0.08f).toInt(), y)
        val empty = shot.getPixel((bar.left + bar.width * 0.08f).toInt(), y)
        assertTrue(!near(accent, empty), "막대 양 끝이 같은 색이다 — 채움이 보이지 않는다")
        assertTrue(android.graphics.Color.red(accent) > android.graphics.Color.blue(accent) + 40, "오른쪽 끝이 강조색이 아니다(왼쪽부터 찼다): #${Integer.toHexString(accent)}")
        // 막대 왼쪽 끝을 누르면 끝 쪽으로 — 오른쪽이 처음, 왼쪽이 끝이다.
        compose.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(bar.left + 4f, bar.center.y)) }
        compose.mainClock.advanceTimeBy(1_000)
        waitFor(page("7"))
    }
}
