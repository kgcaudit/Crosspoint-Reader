package io.github.kgcaudit.reader.app

import android.graphics.Bitmap
import android.graphics.Canvas
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
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 폴더블(0.30.0, 구상안 가안): 덮개 화면(466dp)은 휴대폰 그대로, 펼친 본 화면(732dp)은 태블릿 모드 — 세로 두 쪽,
 * 오른쪽 옆 판, 큰 표지, 폭을 줄인 정보 화면. 접고 펼쳐도 읽던 자리에 있다.
 * 크기는 갤럭시 폴드 실기의 dp(덮개 1972×1248 · 본 화면 2448×1848, 400dpi 근처)다.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = MAIN_PORTRAIT)
class FoldTest {

    /** 효과를 UI 스레드 하나에서 돌린다 — 실제 앱과 같게(AppWalkthroughTest 의 설명 참고). */
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>(StandardTestDispatcher())

    private val shots = File(System.getProperty("reader.screenshots") ?: "build/screenshots").apply { mkdirs() }
    private val app get() = ApplicationProvider.getApplicationContext<OloApp>()
    private val density get() = compose.activity.resources.displayMetrics.density
    private val screenDp get() = compose.activity.window.decorView.width / density

    @Before
    fun setUp() {
        val folder = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        File(folder, "소설").mkdirs()
        File(folder, "소설/어린 왕자.epub").writeBytes(SampleBooks.epub())
        kotlinx.coroutines.runBlocking { app.container.data.folders.register(FolderProvider.treeUri) }
        compose.activityRule.scenario.recreate()
        waitFor(hasText("어린 왕자.epub"))
    }

    @Test
    fun `the unfolded main screen shows two upright pages without being asked`() {
        // 0.29 까지는 설정에서 켜야 했다 — 732dp 한 쪽은 한 줄이 40자를 넘었다.
        openBook()
        waitFor(hasText("1–2 / ", substring = true))
        compose.waitUntil(10_000) { ink(page(), 0.05f, 0.45f) && ink(page(), 0.55f, 0.95f) }
        shot("f1-main-portrait-two-pages")
    }

    @Test
    @Config(qualifiers = COVER)
    fun `the cover screen reads one page and panels fill the whole screen like a phone`() {
        openBook()
        waitFor(hasText("1 / ", substring = true))
        assertFalse(hasNode(hasText("–", substring = true)), "덮개 화면(466dp)에서 두 쪽이 됐다")
        openContents()
        // 판이 화면 왼쪽 끝부터 덮는다 — 466dp 에 400dp 옆 판을 두면 뒤의 66dp 는 누르기에도 좁은 띠다.
        assertTrue(dp(contentsTab().left) < 20f, "덮개 화면인데 옆 판이다: ${dp(contentsTab().left)}dp")
        // 목차(LazyColumn)를 연 채 끝내면 Robolectric 에서 다음 시험의 화면이 쉬지 못한다(시험 환경 문제 — 0.29.0 의
        // 스크롤 시험과 같은 증상). 닫고 끝낸다.
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(10_000) { !hasNode(CONTENTS_TAB) }
    }

    @Test
    fun `on the main screen the contents open as a side panel and a tap on the page behind closes it`() {
        openBook()
        waitFor(hasText("1–2 / ", substring = true))
        openContents()
        shot("f2-main-contents-side-panel")
        // 판은 오른쪽 400dp. 판 맨 왼쪽의 "목차" 탭이 화면 오른쪽 끝에서 400dp 안쪽에서 시작한다.
        val left = dp(contentsTab().left)
        assertTrue(left in screenDp - 400f - 1f..screenDp - 400f + 20f, "옆 판이 아니다: 목차 탭 ${left}dp / 화면 ${screenDp}dp")
        // 뒤의 쪽이 보인다(흐리게) — 왼쪽 절반에 글자가 남아 있다.
        assertTrue(ink(page(), 0.05f, 0.4f, threshold = 400), "옆 판 뒤의 쪽이 가려졌다")

        // 판 밖(뒤의 쪽)을 누르면 판만 닫힌다. 쪽은 넘어가지 않는다 — 왼쪽을 눌렀으니 넘어가면 앞 쪽이다.
        compose.onRoot().performTouchInput { click(centerLeft.copy(x = width * 0.15f)) }
        compose.waitUntil(10_000) { !hasNode(CONTENTS_TAB) }
        assertTrue(hasNode(hasText("1–2 / ", substring = true)), "판 밖을 눌렀더니 쪽이 바뀌었다")
    }

    @Test
    fun `a tap outside the settings side panel steps back one layer, not all the way out`() {
        openBook()
        waitFor(hasText(" / ", substring = true))
        compose.onRoot().performTouchInput { click(center) }
        node(hasText("보기")).performClick()
        node(hasText("모든 보기 설정")).performClick()
        waitFor(hasText("세로에서 두 쪽 보기"))
        compose.onRoot().performTouchInput { click(centerLeft.copy(x = width * 0.15f)) }
        // 설정 → 보기 판. 한 번에 책으로 나가면 다른 설정을 고르려고 다시 세 번 눌러야 한다.
        waitFor(hasText("모든 보기 설정"))
        assertFalse(hasNode(hasText("세로에서 두 쪽 보기")))
    }

    @Test
    @Config(qualifiers = MAIN_LANDSCAPE)
    fun `the view panel card does not stretch across the unfolded landscape screen`() {
        openBook()
        waitFor(hasText(" / ", substring = true))
        compose.onRoot().performTouchInput { click(center) }
        node(hasText("보기")).performClick()
        waitFor(hasText("모든 보기 설정"))
        shot("f2-main-landscape-view-panel")
        val card = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange), useUnmergedTree = true)
            .fetchSemanticsNodes().maxBy { it.boundsInRoot.width }.boundsInRoot
        assertTrue(dp(card.width) <= 561f, "보기 판이 ${dp(card.width)}dp 로 늘어났다")
        // 가운데에 놓인다 — 한쪽에 붙으면 반대편 손가락이 멀다.
        assertTrue(kotlin.math.abs(dp(card.center.x) - screenDp / 2) < 2f, "보기 판이 가운데가 아니다: ${dp(card.left)}..${dp(card.right)}")
    }

    @Test
    fun `library covers grow on the unfolded screen`() {
        // 102dp 로 채우면 732dp 에 여섯 권 — 표지 그림을 알아볼 수 없었다. 130dp 기준이면 네 권, 권당 160dp 남짓.
        waitFor(hasContentDescription("어린 왕자.epub 대신 표지"))
        shot("f3-main-shelf")
        val cover = compose.onAllNodes(hasContentDescription("어린 왕자.epub 대신 표지"), useUnmergedTree = true)
            .fetchSemanticsNodes().first().boundsInRoot
        assertTrue(dp(cover.width) >= 130f, "표지가 ${dp(cover.width)}dp 다")
    }

    @Test
    @Config(qualifiers = COVER_LANDSCAPE)
    fun `library covers on the cover screen keep the phone size`() {
        // 덮개 화면은 휴대폰 규칙 그대로(가로로 돌려도 가장 짧은 폭은 466dp). 폭만 보고 키우면 덮개 화면을 가로로
        // 돌리는 순간 표지 크기가 바뀌어 서재가 출렁인다.
        waitFor(hasContentDescription("어린 왕자.epub 대신 표지"))
        val cover = compose.onAllNodes(hasContentDescription("어린 왕자.epub 대신 표지"), useUnmergedTree = true)
            .fetchSemanticsNodes().first().boundsInRoot
        // 휴대폰 규칙이면 737dp 에 여섯 권(106dp), 태블릿 규칙이면 네 권(165dp).
        assertTrue(dp(cover.width) < 130f, "가로로 돌린 덮개 화면 표지가 ${dp(cover.width)}dp 다")
    }

    @Test
    @Config(qualifiers = MAIN_LANDSCAPE)
    fun `about and library search keep a readable width on the unfolded landscape screen`() {
        // 969dp 한 줄로 늘면 라이선스 문단 · 기록 행의 이름과 값이 화면 양 끝으로 갈라졌다.
        node(hasContentDescription("앱 정보")).performClick()
        waitFor(hasText("OLO eBook"))
        shot("f4-main-landscape-about")
        val gutter = (screenDp - 600f) / 2
        assertTrue(dp(node(hasText("OLO eBook")).fetchSemanticsNode().boundsInRoot.left) >= gutter, "앱 정보가 600dp 보다 넓다")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitFor(hasContentDescription("책 찾기"))
        node(hasContentDescription("책 찾기")).performClick()
        waitFor(hasText("책 제목 · 저자 · 파일 이름"))
        // 서재 찾기는 옆 판이 아니다(뒤에 읽던 쪽이 없다) — 가운데 600dp.
        val back = dp(backButton().left)
        assertTrue(back in gutter - 1f..gutter + 16f, "서재 찾기의 뒤로 단추가 ${back}dp 다(가운데 600dp 의 왼쪽 끝 ${gutter}dp)")
    }

    @Test
    @Config(qualifiers = COVER)
    fun `folding and unfolding keeps the reader on the page they were reading`() {
        openBook()
        repeat(2) { compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.9f)) } }
        waitFor(hasText("3 / ", substring = true))

        // 펼친다: 두 쪽으로 다시 조판된다. 본 화면 한 쪽은 덮개 쪽보다 크므로 덮개 3쪽의 글자가 "1–2" 에 들어갈 수
        // 있다 — 쪽 번호로는 가를 수 없다.
        fold(MAIN_PORTRAIT)
        waitFor(hasText("–", substring = true))
        compose.waitForIdle()
        shot("f5-unfolded-keeps-place")

        // 다시 접으면 같은 쪽. 펼친 화면이 자리를 그 펼침의 첫머리(1쪽)로 바꿔 저장했다면 여기서 1쪽이 된다.
        fold(COVER)
        runCatching { waitFor(hasText("3 / ", substring = true)) }
            .onFailure { throw AssertionError("다시 접었더니 3쪽이 아니다: ${status()}", it) }
    }

    private fun status(): String =
        compose.onAllNodes(hasText(" / ", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
            .joinToString { n -> n.config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString() }

    private fun openBook() {
        node(hasText("어린 왕자.epub")).performClick()
        waitFor(hasText(" / ", substring = true))
    }

    private fun openContents() {
        compose.onRoot().performTouchInput { click(center) }
        node(hasText("목차")).performClick()
        waitFor(CONTENTS_TAB)
        compose.waitForIdle()
    }

    private fun backButton() = node(hasContentDescription("뒤로")).fetchSemanticsNode().boundsInRoot

    /** 목차 · 독서노트 판의 "목차" 탭(리더 막대의 "목차" 단추와 가르려고 탭 — 고를 수 있는 것 — 만). */
    private fun contentsTab() = node(CONTENTS_TAB).fetchSemanticsNode().boundsInRoot

    private fun dp(px: Float) = px / density

    /**
     * 접거나 펼친다. 기기에서는 화면이 바뀌고 onConfigurationChanged 가 온다(configChanges 에 smallestScreenSize ·
     * screenLayout 이 있어 다시 만들지 않는다). Robolectric 은 설정만 바꾸므로 창 크기는 여기서 알린다.
     */
    private fun fold(qualifiers: String) {
        RuntimeEnvironment.setQualifiers(qualifiers)
        compose.runOnUiThread {
            val activity = compose.activity
            val config = activity.resources.configuration
            activity.onConfigurationChanged(config)
            activity.window.decorView.dispatchConfigurationChanged(config)
            val root = android.view.View::class.java.getMethod("getViewRootImpl").invoke(activity.window.decorView)
            org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowViewRootImpl>(root).callDispatchResized()
        }
        compose.waitForIdle()
    }

    /** 가로 [from]..[to] 비율 구간(본문 높이)에 글자(어두운 점)가 있는가. [threshold] 는 RGB 합 — 막 아래는 옅다. */
    private fun ink(bitmap: Bitmap, from: Float, to: Float, threshold: Int = 250): Boolean {
        for (y in (bitmap.height * 0.1).toInt() until (bitmap.height * 0.75).toInt() step 3) {
            for (x in (bitmap.width * from).toInt() until (bitmap.width * to).toInt() step 2) {
                val c = bitmap.getPixel(x, y)
                if (android.graphics.Color.red(c) + android.graphics.Color.green(c) + android.graphics.Color.blue(c) < threshold) return true
            }
        }
        return false
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
        compose.waitForIdle()
        File(shots, "$name.png").outputStream().use { page().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

/** 갤럭시 폴드 덮개 화면(5.5", 1972×1248) — 가장 짧은 폭 466dp, 휴대폰 모드. */
private const val COVER = "w466dp-h737dp-400dpi"

/** 덮개 화면을 가로로. 가장 짧은 폭은 그대로 466dp — 휴대폰 모드여야 한다. */
private const val COVER_LANDSCAPE = "w737dp-h466dp-400dpi"

private val CONTENTS_TAB = androidx.compose.ui.test.isSelectable() and androidx.compose.ui.test.hasAnyDescendant(hasText("목차"))

/** 펼친 본 화면(7.6", 2448×1848) 세로 · 가로 — 가장 짧은 폭 732dp, 태블릿 모드. */
private const val MAIN_PORTRAIT = "w732dp-h969dp-400dpi"
private const val MAIN_LANDSCAPE = "w969dp-h732dp-400dpi"
