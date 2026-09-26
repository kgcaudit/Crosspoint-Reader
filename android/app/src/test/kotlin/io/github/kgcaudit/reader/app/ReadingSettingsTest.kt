package io.github.kgcaudit.reader.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.filterToOne
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.reflow.ReaderPrefs
import io.github.kgcaudit.reader.ui.design.FooterItem
import io.github.kgcaudit.reader.ui.design.KeepScreenOn
import io.github.kgcaudit.reader.ui.design.PaperTheme
import io.github.kgcaudit.reader.ui.design.ScreenPrefs
import io.github.kgcaudit.reader.ui.design.TouchZones
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 1단계(0.12.0) 읽기 환경: 배경 · 밝기 · 여백 · 문단 · 화면 켜짐 · 볼륨키 · 터치 영역 · 하단 정보 · 책갈피 리본 ·
 * 모서리 눌러 책갈피. 사람이 누르는 순서 그대로 고르고, 고른 것이 **화면에서** 달라지는지 본다.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class ReadingSettingsTest {

    /** 효과를 UI 스레드 하나에서 돌린다 — 실제 앱과 같게(AppWalkthroughTest 의 설명 참고). */
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>(StandardTestDispatcher())

    private val shots = File(System.getProperty("reader.screenshots") ?: "build/screenshots").apply { mkdirs() }
    private val app get() = ApplicationProvider.getApplicationContext<OloApp>()
    private val density get() = compose.activity.resources.displayMetrics.density

    @Before
    fun setUp() {
        val folder = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        File(folder, "소설").mkdirs()
        File(folder, "소설/어린 왕자.epub").writeBytes(SampleBooks.epub())
        kotlinx.coroutines.runBlocking { app.container.data.folders.register(FolderProvider.treeUri) }
    }

    /** 저장된 설정으로 앱을 다시 띄워 책을 연다. */
    private fun openWith(prefs: ReaderPrefs = ReaderPrefs()) {
        app.container.prefs.save(prefs)
        compose.activityRule.scenario.recreate()
        waitFor(hasText("어린 왕자.epub"))
        node(hasText("어린 왕자.epub")).performClick()
        waitFor(hasText("1 / ", substring = true))
    }

    private fun openMenu() = compose.onRoot().performTouchInput { click(center) }

    private fun openView() {
        openMenu()
        node(hasText("보기")).performClick()
        waitFor(hasText("모든 보기 설정"))
    }

    /**
     * [text] 줄이 화면 안에 들 때까지 설정 화면을 천천히 밀어 올린다. `performScrollTo` 는 시험 디스패처(StandardTestDispatcher)
     * 에서 스크롤 코루틴이 돌기 전에 다시 요청하기를 되풀이해, 전체 검사에서 코루틴 127만 개를 쌓고 메모리가 바닥났다.
     */
    private fun scrollToShow(text: String) {
        repeat(10) {
            val b = compose.onAllNodes(hasText(text), useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot
            // 목록 끝의 줄은 화면 맨 아래까지만 올라온다 — "화면 안에 온전히" 면 된다(잘린 줄은 높이가 준다).
            if (b.height > 20 * density && b.bottom <= compose.activity.window.decorView.height) {
                // 목록 끝을 넘겨 끌면 늘어남(overscroll) 효과가 되돌아오는 동안 누름을 받지 않는다. waitForIdle 은 그것을
                // 기다리지 않아, 시계를 직접 돌린다.
                compose.mainClock.advanceTimeBy(2_000)
                compose.waitForIdle()
                return
            }
            // 설정 목록 자체를 민다. 화면 전체(onRoot)를 밀면 목록 밑의 읽기 화면이 끌기를 받아 목록은 그대로다.
            val list = compose.onAllNodes(hasScrollAction(), useUnmergedTree = true).fetchSemanticsNodes()
                .maxBy { it.boundsInRoot.height }.id
            compose.onAllNodes(hasScrollAction(), useUnmergedTree = true).filterToOne(SemanticsMatcher("목록") { it.id == list })
                .performTouchInput {
                    // 끌다가 멈춘 뒤 뗀다 — 그냥 밀면 관성으로 계속 흘러, 바로 다음 누름이 "흐름 멈춤" 으로 먹힌다.
                    down(Offset(centerX, height * 0.7f))
                    repeat(10) { moveBy(Offset(0f, -height * 0.03f), delayMillis = 50) }
                    advanceEventTime(400)
                    up()
                }
            compose.waitForIdle()
        }
        error("$text 줄이 화면에 오지 않는다")
    }

    private fun openAllSettings() {
        openView()
        node(hasText("모든 보기 설정")).performClick()
        waitFor(hasText("문단"))
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /** 메뉴 · 판을 모두 닫고 지면으로. */
    private fun backToPage() {
        repeat(4) {
            if (!hasNode(hasText("보기")) && !hasNode(hasText("보기 설정"))) return
            back()
        }
    }

    @Test
    fun `a paper colour paints the page and darkens the menus with it`() {
        openWith()
        openView()
        shot("40-view-panel")
        node(hasContentDescription("배경 검정")).performClick()
        compose.waitForIdle()
        // 검정을 고르면 메뉴 · 도구줄도 어둡다(D3). 지면만 검정이고 메뉴가 흰색이면 누를 때마다 눈이 부시다.
        val bar = page().getPixel(4, (30 * density).toInt())
        assertTrue(android.graphics.Color.red(bar) < 60, "검정 배경인데 메뉴가 밝다: ${Integer.toHexString(bar)}")
        backToPage()
        compose.waitForIdle()
        assertEquals(0xFF121110.toInt(), page().getPixel(4, page().height / 2), "지면이 검정 지면색이 아니다")
        assertEquals(PaperTheme.Black, app.container.prefs.load().screen.theme)
        shot("41-theme-black")

        // 아이보리로 바꾸면 곧바로 아이보리 지면. 저장돼 다음에 열 때도 같다.
        openView()
        node(hasContentDescription("배경 아이보리")).performClick()
        backToPage()
        compose.waitForIdle()
        assertEquals(0xFFF4ECD8.toInt(), page().getPixel(4, page().height / 2))
    }

    @Test
    fun `the margin setting moves where the lines start`() {
        openWith()
        val normal = leftmostInk(page())
        assertEquals(36 * density, normal.toFloat(), 3f, "보통 여백(36dp)에서 글자가 시작하지 않는다")

        openView()
        // "넓게" 는 줄 간격에도 있다 — 판에서 둘째 것이 여백이다.
        compose.onAllNodes(hasText("넓게"), useUnmergedTree = true)[1].performClick()
        backToPage()
        compose.waitUntil(10_000) { leftmostInk(page()) > normal + 10 }
        assertEquals(48 * density, leftmostInk(page()).toFloat(), 3f, "넓게(48dp)에서 글자가 시작하지 않는다")
        assertEquals(ReaderPrefs.Margin.Wide, app.container.prefs.load().margin)
    }

    @Test
    fun `all view settings are grouped with their rows one step inside the group title`() {
        openWith()
        openAllSettings()
        shot("42-view-settings")
        // 위계 규칙: 묶음 제목(글자만) 아래 설정 줄은 제목 글자가 시작하는 자리에서 한 단(16dp) 안쪽.
        fun left(text: String) = node(hasText(text)).fetchSemanticsNode().boundsInRoot.left / density
        assertEquals(left("문단") + 16f, left("정렬"), 1f)
        assertEquals(left("넘기기") + 16f, left("터치 영역"), 1f)

        // 문단 설정은 저장되고 조판 설정에 들어간다(규칙 4 — 빠지면 캐시 키가 같아 옛 쪽이 보인다).
        node(hasText("왼쪽")).performClick()
        compose.onAllNodes(hasText("끔"), useUnmergedTree = true)[0].performClick()
        compose.onAllNodes(hasText("넓게"), useUnmergedTree = true)[0].performClick()
        compose.waitForIdle()
        val saved = app.container.prefs.load()
        assertEquals(ReaderPrefs.ParagraphAlign.Left, saved.align)
        assertEquals(ReaderPrefs.Indent.Off, saved.indent)
        assertEquals(ReaderPrefs.ParagraphSpacing.Loose, saved.paragraphSpacing)

        // 화면 묶음은 줄이 많아 아래쪽이 첫 화면 밖이다(밖에 있는 줄은 폭 0 으로 잰다) — 문단 줄을 다 누른 뒤 내려 본다.
        scrollToShow("하단 정보")
        assertEquals(left("화면") + 16f, left("하단 정보"), 1f)
        assertEquals(left("화면") + 16f, left("형광펜 · 메모"), 1f)
    }

    @Test
    fun `tapping the top right corner bookmarks the page with a ribbon and tapping it again removes it`() {
        openWith()
        val corner = Offset(compose.activity.window.decorView.width - 20 * density, 20 * density)
        compose.onRoot().performTouchInput { click(corner) }
        waitFor(hasContentDescription("책갈피 꽂힌 쪽"))
        shot("43-ribbon")
        // 넘기지 않는다 — 여전히 첫 쪽.
        waitFor(hasText("1 / ", substring = true))
        val bookId = kotlinx.coroutines.runBlocking { app.container.data.library.books().first() }.single { it.displayName == "어린 왕자.epub" }.id
        kotlinx.coroutines.runBlocking { assertEquals(1, app.container.data.bookmarks.forBook(bookId).size) }

        compose.onRoot().performTouchInput { click(corner) }
        compose.waitUntil(10_000) { !hasNode(hasContentDescription("책갈피 꽂힌 쪽")) }
        kotlinx.coroutines.runBlocking { assertEquals(0, app.container.data.bookmarks.forBook(bookId).size) }

        // 네모 바로 아래(오른쪽 가장자리)는 여전히 다음 쪽이다.
        compose.onRoot().performTouchInput { click(Offset(corner.x, 120 * density)) }
        waitFor(hasText("2 / ", substring = true))
        assertFalse(hasNode(hasContentDescription("책갈피 꽂힌 쪽")))
    }

    @Test
    fun `swapped touch zones turn forward on the left`() {
        openWith()
        openAllSettings()
        node(hasText("터치 영역")).performClick()
        waitFor(hasText("좌우 바꾸기"))
        shot("44-touch-zones")
        node(hasText("좌우 바꾸기")).performClick()
        waitFor(hasText("문단"))
        assertEquals(TouchZones.Reversed, app.container.prefs.load().screen.touch)
        backToPage()
        compose.onRoot().performTouchInput { click(centerLeft.copy(x = width * 0.1f)) }
        waitFor(hasText("2 / ", substring = true))
    }

    @Test
    fun `volume keys turn pages only when switched on`() {
        openWith()
        // 끈 동안은 음량 단추를 가져가지 않는다(시스템 음량이 바뀐다).
        assertFalse(volumeDown(), "볼륨키 넘김을 켜지 않았는데 음량 단추를 가져갔다")

        openAllSettings()
        node(hasText("켬")).performClick()
        assertTrue(app.container.prefs.load().screen.volumeKeys)
        backToPage()
        assertTrue(volumeDown())
        waitFor(hasText("2 / ", substring = true))
        compose.runOnUiThread { compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)) }
        waitFor(hasText("1 / ", substring = true))
    }

    @Test
    fun `the footer shows what was chosen for each place`() {
        openWith()
        openAllSettings()
        scrollToShow("하단 정보")
        node(hasText("하단 정보")).performClick()
        waitFor(hasText("미리 보기"))
        node(hasText("왼쪽")).performClick()
        waitFor(hasText("왼쪽에 보일 것"))
        shot("45-footer-picker")
        node(hasText("시계")).performClick()
        node(hasText("오른쪽")).performClick()
        node(hasText("이 장 남은 쪽")).performClick()
        waitFor(hasText("미리 보기"))
        shot("46-footer-settings")
        val saved = app.container.prefs.load().screen.footer
        assertEquals(FooterItem.Clock, saved.left)
        assertEquals(FooterItem.ChapterLeft, saved.right)

        backToPage()
        backToPage()
        // 시계는 "10:42" 모양, 오른쪽은 이 장에서 남은 쪽.
        waitFor(SemanticsMatcher("시각") { n ->
            n.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { Regex("""\d{1,2}:\d{2}""").containsMatchIn(it.text) }
        })
        waitFor(hasText("이 장", substring = true))
        shot("47-footer-clock")
    }

    @Test
    fun `brightness applies only while reading and system hands it back`() {
        openWith(ReaderPrefs(screen = ScreenPrefs(brightness = 0.3f)))
        compose.waitUntil(5_000) { brightness() == 0.3f }
        openView()
        node(hasContentDescription("시스템 밝기")).performClick()
        compose.waitUntil(5_000) { brightness() < 0f }
        assertEquals(null, app.container.prefs.load().screen.brightness)

        // 리더를 떠나면 라이브러리는 휴대폰 밝기다 — 앱 전체가 어두우면 고장 난 것처럼 보인다.
        // 막대 왼쪽 끝을 누르면 어둡게. 그 채로 책을 닫는다.
        compose.onAllNodes(hasContentDescription("밝기"), useUnmergedTree = true)[0].performTouchInput { click(centerLeft) }
        compose.waitUntil(5_000) { brightness() in 0f..0.2f }
        backToPage()
        back()
        compose.waitUntil(5_000) { !hasNode(hasText("1 / ", substring = true)) }
        compose.waitUntil(5_000) { brightness() < 0f }
    }

    @Test
    fun `keep screen on for ten minutes stops after ten quiet minutes and starts again on a page turn`() {
        openWith(ReaderPrefs(screen = ScreenPrefs(keepScreenOn = KeepScreenOn.TenMinutes)))
        compose.waitUntil(5_000) { keptOn() }
        // 읽다 잠들어도 밤새 켜져 있지 않다.
        compose.mainClock.advanceTimeBy(10 * 60_000L + 1_000)
        compose.waitUntil(5_000) { !keptOn() }
        compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.9f)) }
        compose.waitUntil(5_000) { keptOn() }
    }

    // ── 도구 ────────────────────────────────────────────────────────

    private fun volumeDown(): Boolean {
        var eaten = false
        compose.runOnUiThread { eaten = compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)) }
        compose.waitForIdle()
        return eaten
    }

    private fun brightness(): Float = compose.activity.window.attributes.screenBrightness

    private fun keptOn(): Boolean {
        fun any(v: View): Boolean = v.keepScreenOn || (v is ViewGroup && (0 until v.childCount).any { any(v.getChildAt(it)) })
        var on = false
        compose.runOnUiThread { on = any(compose.activity.window.decorView) }
        return on
    }

    /** 본문 자리에서 가장 왼쪽의 글자(어두운 점) x. 여백이 곧 이 값이다. */
    private fun leftmostInk(bitmap: Bitmap): Int {
        var best = bitmap.width
        for (y in (bitmap.height * 0.08).toInt() until (bitmap.height * 0.8).toInt() step 2) {
            for (x in 0 until minOf(best, bitmap.width / 2)) {
                val c = bitmap.getPixel(x, y)
                if (android.graphics.Color.red(c) + android.graphics.Color.green(c) + android.graphics.Color.blue(c) < 300) {
                    best = x
                    break
                }
            }
        }
        return best
    }

    private fun hasNode(matcher: SemanticsMatcher) =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun node(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true)[0]

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 30_000) {
        compose.waitUntil(timeoutMs) { hasNode(matcher) }
    }

    private fun page(): Bitmap {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        return bitmap
    }

    private fun shot(name: String) {
        File(shots, "$name.png").outputStream().use { page().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
