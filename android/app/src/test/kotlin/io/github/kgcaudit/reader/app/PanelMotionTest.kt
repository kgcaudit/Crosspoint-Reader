package io.github.kgcaudit.reader.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import io.github.kgcaudit.reader.ui.design.CpBottomSheet
import io.github.kgcaudit.reader.ui.design.CpFullScreen
import io.github.kgcaudit.reader.ui.design.CpIcons
import io.github.kgcaudit.reader.ui.design.CpPanelHost
import io.github.kgcaudit.reader.ui.design.CpReaderBar
import io.github.kgcaudit.reader.ui.design.CpTheme
import io.github.kgcaudit.reader.ui.design.CpToolButton
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 시험이 판을 바꾸는 열쇠. 0 닫힘 · 1 읽기 메뉴 · 2 보기 판 · 3 목차(전체 화면) · 4 아래 판. */
internal enum class Shown { None, Bar, View, List, Sheet }

private fun group(s: Shown): Int = when (s) {
    Shown.None -> 0
    Shown.Bar, Shown.View -> 1
    Shown.List -> 2
    Shown.Sheet -> 3
}

/**
 * 판이 열리고 닫히는 움직임 · 태블릿 보기 카드 · 판과 지면의 경계(0.55.0, 2026-10-22 확정 구상안). 0.54 까지는 판이 그 자리에
 * "툭" 나타나 어디서 무엇이 열렸는지 눈이 따라가지 못했고, 태블릿의 보기 판은 화면 폭을 다 덮었다.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class PanelMotionBase {
    /** 부품만 띄우는 빈 화면은 시험용 매니페스트가 디버그에만 있다(ReaderBarTest 와 같다). */
    @get:Rule(order = 0)
    val debugOnly = org.junit.rules.TestRule { base, _ ->
        object : org.junit.runners.model.Statement() {
            override fun evaluate() {
                org.junit.Assume.assumeTrue(BuildConfig.DEBUG)
                base.evaluate()
            }
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    internal var shown by mutableStateOf(Shown.None)

    protected fun stage() {
        compose.setContent {
            CpTheme(dark = false) {
                // 쪽 자리는 판과 확 다른 색 — 경계의 그림자를 색으로 찾는다.
                Box(Modifier.fillMaxSize().background(Color(0xFF2050C0))) {
                    CpPanelHost(shown, ::group) { s ->
                        when (s) {
                            Shown.None -> Unit
                            Shown.Bar, Shown.View -> CpReaderBar(
                                "시티 오브 걸스", "2 / 39장", false, {}, {}, {}, 0f, { "0%" }, {},
                                viewOpen = s == Shown.View,
                                above = { Box(Modifier.fillMaxWidth().height(120.dp).testTag("view")) },
                            ) {
                                CpToolButton(CpIcons.Toc, "목차", {}); CpToolButton(CpIcons.Note, "독서노트", {}); CpToolButton(CpIcons.View, "보기", {})
                            }
                            Shown.List -> CpFullScreen { Box(Modifier.fillMaxSize().testTag("list")) }
                            Shown.Sheet -> CpBottomSheet({}) { Box(Modifier.fillMaxWidth().height(200.dp).testTag("sheet")) }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    protected val dp get() = compose.activity.resources.displayMetrics.density

    protected fun bounds(m: SemanticsMatcher): Rect? =
        compose.onAllNodes(m, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInRoot

    /** 시계를 멈추고 [to] 로 바꾼 뒤 [ms] 만큼만 흘린다. */
    internal fun switchAndPeek(to: Shown, ms: Long) {
        compose.mainClock.autoAdvance = false
        // 시험 스레드에서 바꾼 값은 알려야 다음 그림에 들어간다 — 알리지 않으면 시계만 흐르고 판은 그대로였다.
        compose.runOnUiThread {
            shown = to
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(ms)
    }

    protected fun settle() {
        compose.mainClock.advanceTimeBy(1_000)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    protected fun bitmap(): Bitmap {
        val view = compose.activity.window.decorView
        val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(b)) }
        return b
    }
}

@Config(sdk = [35], qualifiers = "w400dp-h800dp-xhdpi")
class PanelMotionPhoneTest : PanelMotionBase() {

    @Test
    fun `the reading menu slides in from the screen edges instead of popping up`() {
        stage()
        switchAndPeek(Shown.Bar, 60)
        val backEarly = bounds(hasContentDescription("뒤로"))!!
        val viewEarly = bounds(hasText("보기"))!!
        settle()
        val back = bounds(hasContentDescription("뒤로"))!!
        val view = bounds(hasText("보기"))!!
        // 위 막대는 위에서 내려오는 중이라 아직 제자리보다 위, 아래 막대는 아래에서 올라오는 중이라 아직 아래.
        assertTrue(backEarly.top < back.top - 4 * dp, "위 막대가 위에서 내려오지 않았다: ${backEarly.top} → ${back.top}")
        assertTrue(viewEarly.top > view.top + 4 * dp, "아래 막대가 아래에서 올라오지 않았다: ${viewEarly.top} → ${view.top}")
    }

    @Test
    fun `closing the menu slides it away before it disappears`() {
        stage()
        shown = Shown.Bar
        compose.waitForIdle()
        val back = bounds(hasContentDescription("뒤로"))!!
        switchAndPeek(Shown.None, 80)
        // 닫는 중: 아직 화면에 있고, 위로 빠지는 중이다. 바로 사라지면 무엇이 닫혔는지 눈이 따라가지 못한다.
        val leaving = bounds(hasContentDescription("뒤로"))
        assertTrue(leaving != null && leaving.top < back.top - 2 * dp, "위 막대가 빠지며 사라지지 않았다: $leaving")
        settle()
        assertEquals(null, bounds(hasContentDescription("뒤로")), "다 닫힌 뒤에도 메뉴가 남았다")
    }

    @Test
    fun `on a phone the view settings unfold full width above the progress bar`() {
        stage()
        shown = Shown.View
        compose.waitForIdle()
        val view = bounds(hasTestTag("view"))!!
        val tools = bounds(hasText("목차"))!!
        assertEquals(400f, view.width / dp, 2f, "휴대폰의 보기 판은 화면 폭 그대로")
        assertTrue(view.bottom <= tools.top, "보기 판이 도구 줄 위에 있어야 한다")
    }

    @Test
    fun `a full screen list slides in from the right on a phone`() {
        stage()
        switchAndPeek(Shown.List, 60)
        val early = bounds(hasTestTag("list"))!!
        settle()
        val done = bounds(hasTestTag("list"))!!
        assertTrue(early.left > done.left + 20 * dp, "목록이 오른쪽에서 들어오지 않았다: ${early.left} → ${done.left}")
    }

    @Test
    fun `a bottom sheet rises from the bottom edge`() {
        stage()
        switchAndPeek(Shown.Sheet, 60)
        val early = bounds(hasTestTag("sheet"))!!
        settle()
        val done = bounds(hasTestTag("sheet"))!!
        assertTrue(early.top > done.top + 20 * dp, "아래 판이 아래에서 올라오지 않았다: ${early.top} → ${done.top}")
    }

    @Test
    fun `a shadow below the top bar and above the bottom bar separates the menu from the page`() {
        stage()
        shown = Shown.Bar
        compose.waitForIdle()
        val b = bitmap()
        val x = b.width / 2
        val topEdge = (bounds(hasContentDescription("뒤로"))!!.bottom + 0).toInt()
        // 위 막대 바로 아래(1~3px)의 쪽 색이 멀리 있는 쪽 색보다 어둡다. 경계가 없으면 같은 파랑이다.
        val bar = (0 until b.height).first { android.graphics.Color.blue(b.getPixel(x, it)) > 150 && android.graphics.Color.red(b.getPixel(x, it)) < 80 }
        val far = android.graphics.Color.blue(b.getPixel(x, bar + (40 * dp).toInt()))
        val near = android.graphics.Color.blue(b.getPixel(x, bar + 1))
        assertTrue(near < far - 10, "위 막대 아래에 그림자가 없다: 가까이 $near · 멀리 $far (막대 끝 $bar, 단추 끝 $topEdge)")
        val bottomBar = (b.height - 1 downTo 0).first { android.graphics.Color.blue(b.getPixel(x, it)) > 150 && android.graphics.Color.red(b.getPixel(x, it)) < 80 }
        val nearBottom = android.graphics.Color.blue(b.getPixel(x, bottomBar - 1))
        val farBottom = android.graphics.Color.blue(b.getPixel(x, bottomBar - (40 * dp).toInt()))
        assertTrue(nearBottom < farBottom - 10, "아래 막대 위에 그림자가 없다: 가까이 $nearBottom · 멀리 $farBottom")
    }
}

@Config(sdk = [35], qualifiers = "w1000dp-h755dp-xhdpi")
class PanelMotionTabletTest : PanelMotionBase() {

    @Test
    fun `on a tablet the view settings float as a card above the view button and leave the page beside it`() {
        stage()
        shown = Shown.View
        compose.waitForIdle()
        val view = bounds(hasTestTag("view"))!!
        val button = bounds(hasText("보기"))!!
        // 카드 폭 420dp 안쪽(좌우 16dp 여백) — 화면 폭 전체로 늘지 않는다.
        assertEquals(388f, view.width / dp, 2f, "보기 카드 안쪽 폭")
        // 오른쪽 끝, 보기 단추 위. 카드 왼쪽으로 쪽이 절반 넘게 보인다.
        assertTrue(view.left / dp > 500f, "카드가 화면 가운데까지 덮었다: ${view.left / dp}dp")
        assertTrue(view.bottom <= button.top, "카드가 보기 단추를 덮었다")
        assertTrue(view.right >= button.left, "카드가 보기 단추 쪽에 있지 않다: 카드 ${view.right / dp} · 단추 ${button.left / dp}")
    }

    @Test
    fun `the view card grows out of the view button`() {
        stage()
        shown = Shown.Bar
        compose.waitForIdle()
        switchAndPeek(Shown.View, 40)
        val early = bounds(hasTestTag("view"))!!
        settle()
        val done = bounds(hasTestTag("view"))!!
        // 오른쪽 아래(보기 단추 쪽)를 기준으로 커진다 — 처음엔 작고, 그 작은 카드의 오른쪽 아래가 제자리 가까이에 있다.
        assertTrue(early.width < done.width - 4 * dp, "카드가 커지며 나오지 않았다: ${early.width} → ${done.width}")
        // 단추 쪽(오른쪽)을 붙잡고 커지므로 왼쪽 끝은 많이, 오른쪽 끝은 조금만 움직인다. 가운데서 커지면 두 끝이 같이 움직인다.
        val leftMove = early.left - done.left
        val rightMove = done.right - early.right
        assertTrue(leftMove > 3 * rightMove, "카드가 보기 단추 쪽에서 커지지 않았다: 왼쪽 ${leftMove / dp}dp · 오른쪽 ${rightMove / dp}dp")
    }

    @Test
    fun `on a tablet the contents slide in as a side panel from the right edge`() {
        stage()
        switchAndPeek(Shown.List, 60)
        val early = bounds(hasTestTag("list"))!!
        settle()
        val done = bounds(hasTestTag("list"))!!
        assertEquals(400f, done.width / dp, 2f, "옆 판 폭")
        assertTrue(early.left > done.left + 20 * dp, "옆 판이 오른쪽에서 들어오지 않았다: ${early.left / dp} → ${done.left / dp}")
    }
}
