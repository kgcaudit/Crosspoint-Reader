package io.github.kgcaudit.reader.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.document.pdf.TestPdf
import io.github.kgcaudit.reader.document.pdf.TestPdf.Companion.pages
import io.github.kgcaudit.reader.document.pdf.TestPdf.Companion.utf16
import org.junit.Before
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * PDF 를 라이브러리에서 열어 넘기고 · 확대하고 · 책갈피를 꽂고 · 다시 열어 이어 읽는다.
 * PDF 엔진만 가짜([DrawnPdf])이고 나머지(폴더 · 디스크립터 · 저장 · 화면)는 실제 앱이다.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class PdfAppTest {

    /**
     * 효과(LaunchedEffect · collectAsState)를 **UI 스레드 하나**에서 돌린다 — 실제 앱(AndroidUiDispatcher)과 같게.
     *
     * 기본값은 UnconfinedTestDispatcher 라, 백그라운드에서 값이 온 코루틴(조판 스레드의 리더 상태, Room 의
     * 질의 결과, `withContext(IO)` 에서 돌아온 효과)이 그 스레드에서 그대로 이어졌다. 화면 상태 쓰기와 프레임이
     * 메인 밖에서 돌아 "잘못된 스레드" 예외가 나거나 깨움을 놓쳐 "첫 쪽을 30초 기다리다 실패" 했다(0.5.1 부터
     * 있던 간헐 실패). StandardTestDispatcher 는 돌아온 코루틴을 대기열에 넣고 시험 스레드에서 차례로 돌린다.
     */
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>(StandardTestDispatcher())

    private val shots = File(System.getProperty("reader.screenshots") ?: "build/screenshots").apply { mkdirs() }

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<OloApp>()
        val folder = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        File(folder, "문서").mkdirs()
        File(folder, "문서/설명서.pdf").writeBytes(manual())
        app.container.pdfEngine = { DrawnPdf(it, pageCount = 6) }
        app.container.data.folders.register(FolderProvider.treeUri)
        compose.activityRule.scenario.recreate()
        waitFor(hasText("설명서.pdf"))
    }

    @Test
    fun `a pdf opens, turns, zooms, keeps a bookmark and reopens where it was left`() {
        // 목록에서 "준비 중" 이 사라지고 눌러 열린다. 제목은 파일에 적힌 것(문서 정보).
        node(hasText("설명서.pdf")).performClick()
        waitFor(hasText("1 / 6"))
        waitFor(hasText("OLO 사용 설명서"))
        shot("20-pdf-first-page")

        // 넘기기는 EPUB 과 같다: 오른쪽을 누르거나 왼쪽으로 민다.
        compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.9f)) }
        waitFor(hasText("2 / 6"))
        compose.onRoot().performTouchInput { swipeLeft() }
        waitFor(hasText("3 / 6"))

        // 두 번 누르면 확대. 확대한 동안 밀면 쪽 안을 움직일 뿐 넘어가지 않는다.
        compose.onRoot().performTouchInput { doubleClick(center) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        shot("21-pdf-zoomed")
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitForIdle()
        waitFor(hasText("3 / 6"))
        check(compose.onAllNodes(hasText("4 / 6"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            "확대한 채 밀었는데 쪽이 넘어갔다"
        }
        // 다시 두 번 누르면 전체로 돌아오고, 그때는 밀면 넘어간다.
        compose.onRoot().performTouchInput { doubleClick(center) }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { swipeLeft() }
        waitFor(hasText("4 / 6"))

        // 가운데를 누르면 EPUB 과 같은 도구줄: 목차 · 책갈피.
        compose.onRoot().performTouchInput { click(center) }
        waitFor(hasContentDescription("책갈피 꽂기"))
        shot("22-pdf-bar")

        // 목차: 파일에 적힌 목차(장 · 절)가 쪽 번호와 함께 나온다. 누르면 그 쪽으로.
        node(hasText("목차")).performClick()
        waitFor(hasText("3장 문제 해결"))
        check(compose.onAllNodes(hasText("2-1 확대하기"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        // 위계 규칙(CLAUDE.md "UI 규칙"): 절은 장의 글자보다 한 단(16dp) 안쪽, 같은 급의 장끼리는 나란히.
        val density = compose.activity.resources.displayMetrics.density
        fun left(text: String) = node(hasText(text)).fetchSemanticsNode().boundsInRoot.left / density
        kotlin.test.assertEquals(left("2장 넘기기") + 16f, left("2-1 확대하기"), 1f)
        kotlin.test.assertEquals(left("2장 넘기기"), left("3장 문제 해결"), 1f)
        shot("25-pdf-contents")
        node(hasText("3장 문제 해결")).performClick()
        waitFor(hasText("6 / 6"))
        // 책갈피를 꽂으러 4쪽으로 돌아간다.
        compose.onRoot().performTouchInput { click(center) }
        waitFor(hasText("목차"))
        node(hasText("목차")).performClick()
        waitFor(hasText("2장 넘기기"))
        node(hasText("2장 넘기기")).performClick()
        waitFor(hasText("3 / 6"))
        compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.9f)) }
        waitFor(hasText("4 / 6"))
        compose.onRoot().performTouchInput { click(center) }
        waitFor(hasContentDescription("책갈피 꽂기"))
        node(hasContentDescription("책갈피 꽂기")).performClick()
        waitFor(hasContentDescription("책갈피 빼기"))
        node(hasText("책갈피")).performClick()
        waitFor(hasText("4쪽"))
        shot("23-pdf-bookmarks")

        // 목록에서 책갈피를 누르면 그 쪽으로. 한 쪽 더 넘긴 뒤 닫고 다시 열면 넘긴 쪽(5쪽)이다.
        node(hasText("4쪽")).performClick()
        waitFor(hasText("4 / 6"))
        compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.9f)) }
        waitFor(hasText("5 / 6"))
        compose.activity.onBackPressedDispatcher.onBackPressed()
        // 라이브러리도 이제 파일 이름 대신 파일에 적힌 제목 · 저자를 보인다.
        waitFor(hasText("OLO 사용 설명서"))
        waitFor(hasText("OLO 팀"))
        shot("26-pdf-library-title")
        node(hasText("OLO 사용 설명서")).performClick()
        waitFor(hasText("5 / 6"))
    }

    @Test
    fun `a locked or broken pdf says why instead of opening a blank page`() {
        val container = compose.activity.container
        container.pdfEngine = { it.close(); throw SecurityException("password required or incorrect password") }
        node(hasText("설명서.pdf")).performClick()
        waitFor(hasText("암호가 걸린 PDF", substring = true))
        shot("24-pdf-locked")
        node(hasText("확인")).performClick()

        container.pdfEngine = { it.close(); throw java.io.IOException("file not in PDF format or corrupted") }
        node(hasText("설명서.pdf")).performClick()
        waitFor(hasText("PDF 파일이 손상됐거나", substring = true))
    }

    /** 쪽 6장, 목차(장 · 절), 문서 정보(제목 · 저자)가 있는 PDF. 쪽 그림은 가짜 엔진([DrawnPdf])이 그린다. */
    private fun manual(): ByteArray = TestPdf().run {
        val p = pages(10, 6)
        obj(1, "<< /Type /Catalog /Pages 10 0 R /Outlines 2 0 R >>")
        obj(2, "<< /Type /Outlines /First 50 0 R >>")
        obj(50, "<< /Title ${utf16("1장 시작하기")} /Next 51 0 R /Dest [${p[0]} 0 R /Fit] >>")
        obj(51, "<< /Title ${utf16("2장 넘기기")} /Next 52 0 R /First 53 0 R /Dest [${p[2]} 0 R /Fit] >>")
        obj(53, "<< /Title ${utf16("2-1 확대하기")} /Dest [${p[4]} 0 R /Fit] >>")
        obj(52, "<< /Title ${utf16("3장 문제 해결")} /Dest [${p[5]} 0 R /Fit] >>")
        obj(30, "<< /Title ${utf16("OLO 사용 설명서")} /Author ${utf16("OLO 팀")} >>")
        classic(trailerExtra = "/Info 30 0 R")
    }

    private fun node(matcher: SemanticsMatcher) =
        compose.onAllNodes(matcher, useUnmergedTree = true)[0]

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 30_000) {
        compose.waitUntil(timeoutMs) {
            compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        File(shots, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
