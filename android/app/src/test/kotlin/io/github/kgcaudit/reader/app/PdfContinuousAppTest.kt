package io.github.kgcaudit.reader.app

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.document.pdf.TestPdf
import io.github.kgcaudit.reader.document.pdf.TestPdf.Companion.pages
import io.github.kgcaudit.reader.ui.design.PdfFit
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PDF 이어서 보기를 앱에서(사용자 결정 5-1 · 5-2): 보기 판에서 고르고, 한 화면씩 내려 읽다 닫았다 다시 열면 같은 자리 · 같은
 * 보기로 열린다. 자리는 앱의 진도 저장소(데이터베이스)를 거친다 — 리더 모듈 시험의 가짜 저장소가 놓치는 길이다.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class PdfContinuousAppTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>(StandardTestDispatcher())

    @After
    fun settleTurn() = compose.waitForIdle()

    private val app = ApplicationProvider.getApplicationContext<OloApp>()

    @Before
    fun setUp() {
        val folder = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        File(folder, "문서").mkdirs()
        File(folder, "문서/보고서.pdf").writeBytes(report())
        app.container.pdfEngine = { DrawnPdf(it, pageCount = 6) }
        app.container.data.folders.register(FolderProvider.treeUri)
        compose.activityRule.scenario.recreate()
        waitFor(hasText("보고서.pdf"))
    }

    private fun report(): ByteArray = TestPdf().run {
        pages(10, 6)
        obj(1, "<< /Type /Catalog /Pages 10 0 R >>")
        classic()
    }

    private fun has(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(30_000) { has(matcher) }
    private fun click(matcher: SemanticsMatcher) {
        waitFor(matcher)
        compose.onAllNodes(matcher, useUnmergedTree = true).let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
    }

    /** 자르지 않은 화면 자리(위로 밀려난 쪽의 머리는 화면 위 음수). */
    private fun bounds(matcher: SemanticsMatcher): Rect =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().first().let { n ->
            Rect(n.positionInRoot, Size(n.size.width.toFloat(), n.size.height.toFloat()))
        }

    @Test
    fun `continuous is chosen in the view panel and a reopened pdf comes back to the same spot`() {
        click(hasText("보고서.pdf"))
        waitFor(hasText("1 / 6"))
        compose.onRoot().performTouchInput { click(center) }
        click(hasText("보기"))
        click(hasText("이어서"))
        compose.waitUntil(30_000) { app.container.prefs.load().screen.pdfFit == PdfFit.Continuous }
        waitFor(hasText("이어서: 쪽을 폭에 맞춰", substring = true))
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        waitFor(hasContentDescription("2쪽"))

        // 두 번 눌러 한 화면씩 두 번 내려간다. 어느 쪽에 멈추는지는 화면 높이에 달려 있어 아래 정보 줄에서 읽는다.
        repeat(2) {
            compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.9f)) }
            compose.waitForIdle()
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val label = compose.onAllNodes(hasText(" / 6", substring = true), useUnmergedTree = true).fetchSemanticsNodes().first()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text].first().text
        val page = label.substringBefore(" / ").trim().toInt()
        assertTrue(page >= 2, "두 화면 내렸는데 아직 $label")
        val pageTop = bounds(hasContentDescription("${page}쪽")).top - bounds(hasScrollAction()).top

        // 닫았다 다시 연다.
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitFor(hasText("보고서.pdf"))
        click(hasText("보고서.pdf"))
        waitFor(hasContentDescription("${page}쪽"))
        compose.waitForIdle()
        waitFor(hasText(label))
        val again = bounds(hasContentDescription("${page}쪽")).top - bounds(hasScrollAction()).top
        assertTrue(abs(again - pageTop) < 4f, "다시 연 자리가 다르다: $pageTop → $again")
        assertEquals(PdfFit.Continuous, app.container.prefs.load().screen.pdfFit)
    }
}
