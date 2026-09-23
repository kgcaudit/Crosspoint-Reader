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
import org.junit.Before
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

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val shots = File(System.getProperty("reader.screenshots") ?: "build/screenshots").apply { mkdirs() }

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<OloApp>()
        val folder = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        File(folder, "문서").mkdirs()
        File(folder, "문서/설명서.pdf").writeBytes(ByteArray(64))
        app.container.pdfEngine = { DrawnPdf(it, pageCount = 6) }
        app.container.data.folders.register(FolderProvider.treeUri)
        compose.activityRule.scenario.recreate()
        waitFor(hasText("설명서.pdf"))
    }

    @Test
    fun `a pdf opens, turns, zooms, keeps a bookmark and reopens where it was left`() {
        // 목록에서 "준비 중" 이 사라지고 눌러 열린다. 제목은 확장자를 뗀 파일 이름.
        node(hasText("설명서.pdf")).performClick()
        waitFor(hasText("1 / 6"))
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

        // 가운데를 누르면 EPUB 과 같은 도구줄. 목차 단추는 없다(읽을 수 없는 목차).
        compose.onRoot().performTouchInput { click(center) }
        waitFor(hasContentDescription("책갈피 꽂기"))
        check(compose.onAllNodes(hasText("목차"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        shot("22-pdf-bar")
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
        waitFor(hasText("설명서.pdf"))
        node(hasText("설명서.pdf")).performClick()
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
