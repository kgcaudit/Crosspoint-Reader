package io.github.kgcaudit.reader.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.kgcaudit.reader.ui.design.AutoTurnState
import io.github.kgcaudit.reader.ui.design.CpAutoTurnPill
import io.github.kgcaudit.reader.ui.design.CpBottomSheet
import io.github.kgcaudit.reader.ui.design.CpButton
import io.github.kgcaudit.reader.ui.design.CpChoice
import io.github.kgcaudit.reader.ui.design.CpIconToggle
import io.github.kgcaudit.reader.ui.design.CpIcons
import io.github.kgcaudit.reader.ui.design.CpPopup
import io.github.kgcaudit.reader.ui.design.CpPopupButtons
import io.github.kgcaudit.reader.ui.design.CpSearchResultBar
import io.github.kgcaudit.reader.ui.design.CpTextButton
import io.github.kgcaudit.reader.ui.design.CpTheme
import io.github.kgcaudit.reader.ui.design.CpThemeSwatches
import io.github.kgcaudit.reader.ui.design.PaperTheme
import io.github.kgcaudit.reader.listen.ListenPlayer
import io.github.kgcaudit.reader.listen.ListenState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 0.29.0 디자인 점검(구상안 가안 확정)의 규칙: 좁은 폰(360dp) · 큰 글자에서 이름이 잘리지 않고, 누르는 곳은 48dp,
 * 팝업 단추는 오른쪽 끝. 모두 360dp 폰에서 잰다 — 393dp 에서만 돌던 시험은 잘림을 보지 못했다.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
class DesignRulesTest {

    /**
     * 부품만 띄우는 빈 화면(ComponentActivity)은 시험용 매니페스트가 디버그에만 들어 있어 릴리스 시험에서는 뜨지 않는다.
     * 규칙은 디버그 시험이 모두 본다. 빈 화면을 띄우는 규칙보다 먼저 건너뛴다.
     */
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

    private val density get() = compose.activity.resources.displayMetrics.density


    private fun show(fontScale: Float = 1f, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                CpTheme(dark = false) { Box(Modifier.fillMaxSize()) { content() } }
            }
        }
        compose.waitForIdle()
    }

    private fun node(matcher: SemanticsMatcher): SemanticsNode =
        compose.onAllNodes(matcher, useUnmergedTree = true)[0].fetchSemanticsNode()

    /** 글자가 "…" 로 잘렸는가. */
    private fun clipped(text: String): Boolean {
        val results = ArrayList<TextLayoutResult>()
        node(hasText(text)).config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        // "…" 로 줄였는가만 본다. didOverflowWidth 는 Robolectric 에서 폭이 남아도 참을 돌려줬다.
        val r = results.single()
        return (0 until r.lineCount).any { r.isLineEllipsized(it) }
    }

    private fun dp(px: Float) = px / density

    @Test
    fun `a four option setting keeps its whole name on a 360dp phone by putting the options below`() {
        // 한 줄에 우겨 넣던 때는 "자동 넘…" 으로 잘렸다. 선택지는 이름 아래 줄, 이름 글자 시작선에서.
        show { Column { CpChoice("자동 넘김", listOf("끔", "15초", "30초", "60초"), 2, {}, Modifier.padding(start = 16.dp)) } }
        assertFalse(clipped("자동 넘김"), "이름이 잘렸다")
        val label = node(hasText("자동 넘김")).boundsInRoot
        val first = node(hasText("끔")).boundsInRoot
        assertTrue(first.top >= label.bottom - 1, "선택지가 이름 아래 줄로 내려가지 않았다: $label / $first")
        assertTrue(abs(dp(first.left) - dp(label.left) - 16f) < 2f, "선택지가 이름 글자 시작선에서 시작하지 않는다(단추 안쪽 16dp): $label / $first")
    }

    @Test
    fun `a short setting stays on one line`() {
        // 들어가는 줄은 그대로 나란히 — 모든 줄을 두 줄로 늘리면 설정 화면이 두 배로 길어진다.
        show { Column { CpChoice("음량 단추로 넘기기", listOf("켬", "끔"), 0, {}) } }
        val label = node(hasText("음량 단추로 넘기기")).boundsInRoot
        val on = node(hasText("켬")).boundsInRoot
        assertTrue(abs(label.center.y - on.center.y) < 2 * density, "짧은 줄이 두 줄로 나뉘었다: $label / $on")
    }

    @Test
    fun `at 150 percent text the setting name and every option are still whole`() {
        show(fontScale = 1.5f) { Column { CpChoice("화면 켜짐 유지", listOf("휴대폰", "10분", "항상"), 1, {}, Modifier.padding(start = 16.dp)) } }
        assertFalse(clipped("화면 켜짐 유지"), "이름이 잘렸다")
        for (o in listOf("휴대폰", "10분", "항상")) {
            assertFalse(clipped(o), "$o 가 잘렸다")
            assertTrue(node(hasText(o)).boundsInRoot.right <= 360 * density + 1, "$o 가 화면 밖이다")
        }
    }

    @Test
    fun `small controls still give a finger 48dp`() {
        show {
            Column {
                CpChoice("음량 단추로 넘기기", listOf("켬", "끔"), 0, {})
                CpIconToggle(listOf(CpIcons.Grid, CpIcons.Rows), listOf("격자", "목록"), 0, {})
                CpTextButton("내보내기", {})
            }
        }
        // 선택지는 누르는 칸(글자의 부모 — 선택할 수 있는 노드)이 48dp.
        val option = compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Selected), useUnmergedTree = true)
            .fetchSemanticsNodes().filter { !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription) }
        assertTrue(option.isNotEmpty() && option.all { dp(it.boundsInRoot.height) >= 47.5f }, "선택지 누르는 곳: ${option.map { dp(it.boundsInRoot.height) }}")
        for (cell in listOf("격자", "목록")) {
            val b = node(hasContentDescription(cell)).boundsInRoot
            assertTrue(dp(b.height) >= 47.5f && dp(b.width) >= 47.5f, "$cell 칸: ${dp(b.width)}×${dp(b.height)}")
        }
        assertTrue(dp(node(hasText("내보내기")).boundsInRoot.height) >= 47.5f, "글자 단추가 48dp 보다 작다")
    }

    @Test
    fun `the background row names only the chosen paper and keeps every swatch on screen at 130 percent`() {
        // 견본마다 이름을 달던 때는 130% 에서 "배경" 이 사라지고 마지막 견본이 찌그러졌다.
        show(fontScale = 1.3f) { CpThemeSwatches(PaperTheme.Ivory, {}) }
        assertFalse(clipped("배경"), "\"배경\" 이 잘렸다")
        assertTrue(compose.onAllNodes(hasText("아이보리"), useUnmergedTree = true).fetchSemanticsNodes().size == 1, "고른 이름이 한 번만 보여야 한다")
        assertTrue(compose.onAllNodes(hasText("세피아"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty(), "고르지 않은 견본에 이름이 남았다")
        for (theme in PaperTheme.entries) {
            val name = if (theme == PaperTheme.System) "배경 휴대폰 설정" else "배경 ${theme.label}"
            val b = node(hasContentDescription(name)).boundsInRoot
            assertTrue(b.right <= 360 * density + 1 && dp(b.width) >= 39f, "$name 견본: ${dp(b.left)}..${dp(b.right)}")
        }
    }

    @Test
    fun `popup buttons sit at the right edge of the popup`() {
        // 팝업마다 왼쪽 · 오른쪽 · 반반으로 달라 확인 단추를 찾아 손이 헤맸다.
        show { CpPopup(title = "책 폴더", message = "폴더를 빼도 기록은 남습니다.", onDismiss = {}) { CpPopupButtons { CpButton("확인", {}) } } }
        val message = node(hasText("폴더를 빼도 기록은 남습니다.")).boundsInRoot
        val ok = compose.onAllNodes(hasText("확인"), useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot
        // 단추 오른쪽 끝(글자 + 안쪽 여백 22dp)이 판 글자 오른쪽 끝 근처에 있다 — 판 폭의 오른쪽 절반.
        val popupRight = 360 * density - 32 * density - 20 * density
        assertTrue(ok.right + 22 * density >= popupRight - 2 * density, "확인 단추가 오른쪽 끝에 있지 않다: $ok (판 글자 끝 $popupRight)")
        assertTrue(ok.left > message.left + 100 * density, "확인 단추가 왼쪽에 있다")
    }

    // ---------- 모서리(0.31.0, OLO-Design: 알약 · 완전 둥근 모양을 쓰지 않는다) ----------

    /** 화면을 찍어 [tag] 부품의 왼쪽 위 모서리에서 대각선으로 [inset] 들어간 점의 색. */
    private fun cornerPixel(tag: String, inset: Float): Int {
        compose.waitForIdle()
        val b = node(hasTestTag(tag)).boundsInRoot
        val view = compose.activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(android.graphics.Canvas(bitmap)) }
        return bitmap.getPixel((b.left + inset * density).toInt(), (b.top + inset * density).toInt())
    }

    /** [pixel] 이 [inside](부품 색) 쪽에 더 가까운가 — 모서리 안이면 부품 색, 밖이면 뒤의 색. */
    private fun closerTo(pixel: Int, inside: androidx.compose.ui.graphics.Color, outside: androidx.compose.ui.graphics.Color): Boolean {
        fun d(c: androidx.compose.ui.graphics.Color): Float {
            val r = android.graphics.Color.red(pixel) / 255f - c.red
            val g = android.graphics.Color.green(pixel) / 255f - c.green
            val bl = android.graphics.Color.blue(pixel) / 255f - c.blue
            return r * r + g * g + bl * bl
        }
        return d(inside) < d(outside)
    }

    @Test
    fun `buttons have the family's 10dp corners, not pill ends`() {
        // 알약(반지름 24dp)이면 모서리에서 4dp 들어간 점이 아직 바깥(뒤 색), 10dp 면 안(단추 색)이다. 1dp 점은 어느 쪽이든
        // 바깥 — 각진 네모로 되돌아간 것도 잡는다.
        var accent = androidx.compose.ui.graphics.Color.Unspecified
        var paper = androidx.compose.ui.graphics.Color.Unspecified
        show {
            accent = CpTheme.colors.accent
            paper = CpTheme.colors.background
            Box(Modifier.fillMaxSize().background(paper)) { CpButton("빼기", {}, Modifier.padding(40.dp).testTag("button")) }
        }
        assertTrue(closerTo(cornerPixel("button", 4f), accent, paper), "단추 모서리가 10dp 보다 둥글다(알약)")
        assertTrue(closerTo(cornerPixel("button", 1f), paper, accent), "단추 모서리가 각졌다")
    }

    @Test
    fun `the auto turn notice is a 14dp card, not a pill`() {
        // 14dp 면 5.5dp 들어간 점은 안, 24dp 알약이면 바깥.
        var surface = androidx.compose.ui.graphics.Color.Unspecified
        var paper = androidx.compose.ui.graphics.Color.Unspecified
        show {
            surface = CpTheme.colors.surface
            paper = CpTheme.colors.background
            Box(Modifier.fillMaxSize().background(paper)) {
                CpAutoTurnPill(AutoTurnState().apply { remaining = 18 }, Modifier.padding(40.dp).testTag("notice"))
            }
        }
        assertTrue(closerTo(cornerPixel("notice", 5.5f), surface, paper), "자동 넘김 알림 모서리가 14dp 보다 둥글다")
        assertTrue(closerTo(cornerPixel("notice", 1.5f), paper, surface), "자동 넘김 알림 모서리가 각졌다")
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xxxhdpi")
    fun `a bottom sheet rounds its top at 18dp, one step below a popup`() {
        // 18dp 와 20dp 는 0.3dp 차이라 촘촘한 화면(xxxhdpi)에서 잰다. 5.6dp 들어간 점: 18dp 면 안, 20dp 면 바깥.
        var surface = androidx.compose.ui.graphics.Color.Unspecified
        var behind = androidx.compose.ui.graphics.Color.Unspecified
        show {
            surface = CpTheme.colors.surface
            behind = CpTheme.colors.background
            Box(Modifier.fillMaxSize().background(behind)) {
                CpBottomSheet({}, Modifier.testTag("sheet")) { androidx.compose.foundation.layout.Spacer(Modifier.padding(60.dp)) }
            }
        }
        // 뒤는 막(검정 40%, 0x66)에 덮여 바탕이 60% 밝기로 보인다.
        val scrimmed = androidx.compose.ui.graphics.Color(behind.red * 0.6f, behind.green * 0.6f, behind.blue * 0.6f)
        assertTrue(closerTo(cornerPixel("sheet", 5.6f), surface, scrimmed), "아래 판 모서리가 18dp 보다 둥글다")
        assertTrue(closerTo(cornerPixel("sheet", 1.5f), scrimmed, surface), "아래 판 모서리가 각졌다")
    }

    // ---------- 떠 있는 막대(0.32.2): 알약 → 14dp ----------

    /** 떠 있는 막대 [content] 의 모서리를 잰다. 14dp 면 5.5dp 들어간 점은 안, 24 · 28dp 알약이면 바깥. 1.5dp 점은 늘 바깥. */
    private fun assertCard14(what: String, content: @androidx.compose.runtime.Composable (Modifier) -> Unit) {
        var surface = androidx.compose.ui.graphics.Color.Unspecified
        var paper = androidx.compose.ui.graphics.Color.Unspecified
        show {
            surface = CpTheme.colors.surface
            paper = CpTheme.colors.background
            Box(Modifier.fillMaxSize().background(paper)) { content(Modifier.padding(40.dp).testTag("bar")) }
        }
        assertTrue(closerTo(cornerPixel("bar", 5.5f), surface, paper), "$what 모서리가 14dp 보다 둥글다(알약)")
        assertTrue(closerTo(cornerPixel("bar", 1.5f), paper, surface), "$what 모서리가 각졌다")
    }

    @Test
    fun `the search result bar is a 14dp card like the auto turn notice`() =
        assertCard14("찾기 결과 막대") { CpSearchResultBar(0, 16, {}, {}, {}, {}, it) }

    @Test
    fun `the listening controller is a 14dp card like the other floating bars`() =
        assertCard14("듣기 조종판") { ListenPlayer(ListenState(active = true), {}, {}, {}, {}, {}, it) }
}
