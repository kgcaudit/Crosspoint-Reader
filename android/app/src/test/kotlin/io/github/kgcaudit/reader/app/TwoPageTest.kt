package io.github.kgcaudit.reader.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.document.pdf.TestPdf
import io.github.kgcaudit.reader.document.pdf.TestPdf.Companion.pages
import io.github.kgcaudit.reader.reflow.ReaderPrefs
import io.github.kgcaudit.reader.ui.design.ScreenPrefs
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 두쪽보기(T1–T5): 가로에서 기본으로 두 쪽, 세로는 넓은 화면에서만, 하단 정보는 "5–6 / 12", PDF 는 표지를 따로.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class TwoPageTest {

    /** 효과를 UI 스레드 하나에서 돌린다 — 실제 앱과 같게(AppWalkthroughTest 의 설명 참고). */
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>(StandardTestDispatcher())

    private val shots = File(System.getProperty("reader.screenshots") ?: "build/screenshots").apply { mkdirs() }
    private val app get() = ApplicationProvider.getApplicationContext<OloApp>()

    @Before
    fun setUp() {
        org.robolectric.shadows.ShadowLog.stream = System.out
        val folder = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        File(folder, "소설").mkdirs()
        File(folder, "소설/어린 왕자.epub").writeBytes(SampleBooks.epub())
        File(folder, "문서").mkdirs()
        File(folder, "문서/잡지.pdf").writeBytes(TestPdf().run { pages(10, 6); obj(1, "<< /Type /Catalog /Pages 10 0 R >>"); classic() })
        app.container.pdfEngine = { DrawnPdf(it, pageCount = 6) }
        kotlinx.coroutines.runBlocking { app.container.data.folders.register(FolderProvider.treeUri) }
    }

    private fun open(name: String, prefs: ReaderPrefs = ReaderPrefs()) {
        app.container.prefs.save(prefs)
        compose.activityRule.scenario.recreate()
        waitFor(hasText(name))
        node(hasText(name)).performClick()
        waitFor(hasText(" / ", substring = true))
    }

    @Test
    fun `turning an epub sideways shows two pages and turns two at a time`() {
        open("어린 왕자.epub")
        waitFor(hasText("1 / ", substring = true))
        turn("+land")
        // 두 쪽이 나란히: 하단은 "1–2 / n", 글자가 양쪽 절반에 모두 있다.
        waitFor(hasText("1–2 / ", substring = true))
        compose.waitUntil(10_000) { ink(page(), 0.05f, 0.45f) && ink(page(), 0.55f, 0.95f) }
        shot("50-epub-two-pages")
        // 다음 펼침: 3쪽부터(3–4, 또는 장이 3쪽으로 끝나면 "3 / 3").
        compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.9f)) }
        compose.waitUntil(10_000) { statusText().startsWith("3") }
        compose.onRoot().performTouchInput { click(centerLeft.copy(x = width * 0.1f)) }
        waitFor(hasText("1–2 / ", substring = true))

        // 가로(높이 393dp)에서 보기 판은 일곱 줄이 다 들어가지 않는다 — 판이 스크롤돼야 "모든 보기 설정" 에 닿는다
        // (처음 쓴 판에서는 아래 줄이 잘려 누를 수 없었다).
        compose.onRoot().performTouchInput { click(center) }
        node(hasText("보기")).performClick()
        waitFor(hasText("글꼴"))
        val scrollable = compose.onAllNodes(
            SemanticsMatcher("세로 스크롤") { n ->
                n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange) { null }
                    ?.let { it.maxValue() > 0f } == true
            },
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        assertTrue(scrollable.isNotEmpty(), "가로에서 보기 판이 스크롤되지 않는다")
        shot("54-view-panel-landscape")

        // 가로 두쪽을 끄면 가로에서도 한 쪽(0.11.0 과 같다). 설정은 세로에서 바꾼다.
        repeat(2) { compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() } }
        turn("+port")
        waitFor(hasText(" / ", substring = true))
        compose.onRoot().performTouchInput { click(center) }
        node(hasText("보기")).performClick()
        node(hasText("모든 보기 설정")).performClick()
        waitFor(hasText("가로에서 두쪽보기"))
        // "끔" 은 들여쓰기 · 볼륨키에도 있다 — 가로 두쪽 줄의 것을 누른다.
        clickInRow("가로에서 두쪽보기", "끔")
        assertFalse(app.container.prefs.load().screen.twoPagesLandscape)
        repeat(3) { compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() } }
        turn("+land")
        waitFor(hasText("1 / ", substring = true))
        compose.waitUntil(10_000) { !hasNode(hasText("–", substring = true)) }
    }

    @Test
    fun `an odd chapter ends with an empty right page and the next chapter starts on the left`() {
        open("어린 왕자.epub")
        turn("+land")
        waitFor(hasText("1–2 / ", substring = true))
        // 장 끝까지 넘긴다. 마지막 펼침이 홀수면 "n / n"(오른쪽 빈 쪽), 다음 장은 다시 "1–2" 또는 "1 / 1" 로 시작.
        var guard = 0
        var sawChapterStart = false
        while (guard++ < 40) {
            compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.9f)) }
            compose.waitForIdle()
            val status = statusText()
            if (status.startsWith("1–2 /") || status.startsWith("1 / 1")) { sawChapterStart = true; break }
            // 왼쪽 쪽 번호는 언제나 홀수(1, 3, 5 …) — 장마다 짝을 새로 센다.
            val left = status.substringBefore(' ').substringBefore('–').toIntOrNull() ?: continue
            assertEquals(1, left % 2, "왼쪽 쪽이 짝수다: $status")
        }
        assertTrue(sawChapterStart, "다음 장이 왼쪽에서 시작하지 않았다")
    }

    @Test
    fun `portrait two pages are offered only on a wide screen`() {
        // 휴대폰 세로: 줄은 흐리고 까닭이 적혀 있으며, 눌러도 바뀌지 않는다.
        open("어린 왕자.epub")
        compose.onRoot().performTouchInput { click(center) }
        node(hasText("보기")).performClick()
        node(hasText("모든 보기 설정")).performClick()
        waitFor(hasText("넓은 화면(태블릿 · 폴더블)에서만 쓸 수 있습니다"))
        shot("55-two-page-settings-phone")
        clickInRow("세로에서 두쪽보기", "켬")
        assertFalse(app.container.prefs.load().screen.twoPagesPortrait)
        // EPUB 설정에는 PDF 묶음이 없다.
        assertFalse(hasNode(hasText("두쪽보기에서 표지")))
    }

    @Test
    @Config(qualifiers = "w820dp-h1180dp-mdpi")
    fun `a tablet held upright shows two pages when asked`() {
        open("어린 왕자.epub", ReaderPrefs(screen = ScreenPrefs(twoPagesPortrait = true)))
        waitFor(hasText("1–2 / ", substring = true))
        compose.waitUntil(10_000) { ink(page(), 0.05f, 0.45f) && ink(page(), 0.55f, 0.95f) }
        shot("53-tablet-two-pages")
    }

    @Test
    fun `a pdf spread keeps the cover alone and pairs the facing pages after it`() {
        open("잡지.pdf")
        waitFor(hasText("1 / 6"))
        turn("+land")
        compose.mainClock.advanceTimeBy(1_000)
        // 표지 혼자 → 2–3 → 4–5 → 6(T4).
        waitFor(hasText("1 / 6"))
        shot("51-pdf-cover-alone")
        for (expected in listOf("2–3 / 6", "4–5 / 6", "6 / 6")) {
            compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.9f)) }
            compose.mainClock.advanceTimeBy(1_000)
            waitFor(hasText(expected))
            if (expected == "2–3 / 6") shot("52-pdf-spread")
        }
        // "함께" 로 바꾸면 5–6 펼침(6쪽이 든 펼침)으로. 설정은 화면에 다 들어오는 세로에서 바꾼다.
        turn("+port")
        waitFor(hasText("6 / 6"))
        compose.onRoot().performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(1_000)
        waitFor(hasText("보기"))
        node(hasText("보기")).performClick()
        node(hasText("모든 보기 설정")).performClick()
        waitFor(hasText("두쪽보기에서 표지"))
        node(hasText("함께")).performClick()
        assertFalse(app.container.prefs.load().screen.pdfCoverAlone)
        repeat(3) { compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() } }
        turn("+land")
        compose.mainClock.advanceTimeBy(1_000)
        waitFor(hasText("5–6 / 6"))
    }

    // ── 도구 ────────────────────────────────────────────────────────

    /** [label] 줄에 있는 [option] 단추를 누른다. 같은 글자의 단추가 여러 줄에 있다. */
    private fun clickInRow(label: String, option: String) {
        val row = node(hasText(label)).fetchSemanticsNode().boundsInRoot
        val candidates = compose.onAllNodes(hasText(option), useUnmergedTree = true).fetchSemanticsNodes()
        val index = candidates.indexOfFirst { n -> n.boundsInRoot.center.y in row.top - 20f..row.bottom + 20f }
        check(index >= 0) { "$label 줄에 $option 이 없다" }
        compose.onAllNodes(hasText(option), useUnmergedTree = true)[index].performClick()
        compose.waitForIdle()
    }

    private fun statusText(): String =
        compose.onAllNodes(hasText(" / ", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
            .firstNotNullOfOrNull { n ->
                n.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }.firstOrNull()?.text
            }.orEmpty()

    /** 휴대폰을 돌린다(ScreenRotationTest.turn 과 같다 — Robolectric 이 창 크기 · 설정 변경을 보내지 않는다). */
    private fun turn(qualifier: String) {
        RuntimeEnvironment.setQualifiers(qualifier)
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

    /** 가로 [from]..[to] 비율 구간(본문 높이)에 글자(어두운 점)가 있는가. */
    private fun ink(bitmap: Bitmap, from: Float, to: Float): Boolean {
        for (y in (bitmap.height * 0.1).toInt() until (bitmap.height * 0.75).toInt() step 3) {
            for (x in (bitmap.width * from).toInt() until (bitmap.width * to).toInt() step 2) {
                val c = bitmap.getPixel(x, y)
                if (android.graphics.Color.red(c) + android.graphics.Color.green(c) + android.graphics.Color.blue(c) < 250) return true
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
