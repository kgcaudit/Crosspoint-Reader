package io.github.kgcaudit.reader.app

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.ui.design.ScreenRotation
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
import kotlin.test.assertNotEquals

/** 화면 회전: 휴대폰의 회전 잠금과 상관없이 돌고(자동), 앱 안에서 고정할 수 있고, 돌려도 읽던 곳에 있다. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class ScreenRotationTest {

    /** 효과를 UI 스레드 하나에서 돌린다 — 실제 앱과 같게(AppWalkthroughTest 의 설명 참고). */
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>(StandardTestDispatcher())

    private val shots = File(System.getProperty("reader.screenshots") ?: "build/screenshots").apply { mkdirs() }

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<OloApp>()
        val folder = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        File(folder, "소설").mkdirs()
        File(folder, "소설/어린 왕자.epub").writeBytes(SampleBooks.epub())
        app.container.data.let { kotlinx.coroutines.runBlocking { it.folders.register(FolderProvider.treeUri) } }
        compose.activityRule.scenario.recreate()
        waitFor(hasText("어린 왕자.epub"))
    }

    private fun orientation() = compose.activity.requestedOrientation

    @Test
    fun `the app turns with the phone even when the phone's rotation lock is on, unless locked in the app`() {
        // 기본은 FULL_SENSOR: 휴대폰의 잠금을 따르는 UNSPECIFIED 였을 때는 잠금을 켠 사람에게 앱이 돌지 않았다.
        compose.waitUntil(5_000) { orientation() == ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR }

        // 보기 › 모든 보기 설정 › 화면 회전 › 세로: 누워서 읽을 때 돌지 않게. 창에 곧바로 걸리고 저장된다.
        // (0.12.0 부터 EPUB 보기 판은 자주 바꾸는 것만 둔다 — 회전은 모든 보기 설정의 "화면" 묶음에 있다.)
        openBook()
        compose.onRoot().performTouchInput { click(center) }
        node(hasText("보기")).performClick()
        node(hasText("모든 보기 설정")).performClick()
        node(hasText("세로")).performClick()
        compose.waitUntil(5_000) { orientation() == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT }
        assertEquals(ScreenRotation.Portrait, compose.activity.container.prefs.load().screen.rotation)
        node(hasText("가로")).performClick()
        compose.waitUntil(5_000) { orientation() == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE }
        node(hasText("자동")).performClick()
        compose.waitUntil(5_000) { orientation() == ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR }
    }

    @Test
    fun `turning the phone keeps the reader where they were reading`() {
        openBook()
        // 셋째 쪽까지 넘긴다.
        repeat(2) { compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.9f)) } }
        waitFor(hasText("3 / ", substring = true))

        // 가로로 돌린다(액티비티는 다시 만들지 않는다 — configChanges). 새 폭으로 다시 조판되고, 읽던 글자가
        // 들어 있는 쪽이 보여야 한다. 처음(1쪽)으로 돌아가면 안 된다.
        turn("+land")
        // 새 폭으로 다시 조판됐다 = 글자가 오른쪽 절반까지 찬다. 세로 폭 조판을 그대로 그리면 왼쪽에만 있다.
        compose.waitUntil(15_000) { inkOnRightHalf(page()) && hasNode(hasText(" / ", substring = true)) }
        compose.waitForIdle()
        shot("27-reader-landscape")
        val status = compose.onAllNodes(hasText(" / ", substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes().first().config.toString()
        assertNotEquals(true, status.contains("[1 / "), "가로로 돌렸더니 처음으로 돌아갔다: $status")
        assertEquals(true, compose.activity.window.decorView.width > compose.activity.window.decorView.height)

        // 다시 세로로. 같은 자리(셋째 쪽)로 돌아온다 — 세로 조판은 캐시에 그대로 있다.
        turn("+port")
        waitFor(hasText("3 / ", substring = true))
    }

    /**
     * 휴대폰을 돌린다. 기기에서는 시스템이 창 크기를 바꾸고 onConfigurationChanged 를 보낸다(앱이 회전을
     * 스스로 처리한다 — configChanges). Robolectric 은 설정만 바꾸므로 그 둘을 여기서 한다.
     */
    private fun turn(qualifier: String) {
        RuntimeEnvironment.setQualifiers(qualifier)
        compose.runOnUiThread {
            val activity = compose.activity
            val config = activity.resources.configuration
            activity.onConfigurationChanged(config)
            activity.window.decorView.dispatchConfigurationChanged(config)
            // 창 크기: 시스템이 창을 새 화면 크기로 다시 잡는 것(dispatchResized)을 흉내 낸다.
            val root = android.view.View::class.java.getMethod("getViewRootImpl").invoke(activity.window.decorView)
            org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowViewRootImpl>(root).callDispatchResized()
        }
        compose.waitForIdle()
    }

    private fun openBook() {
        node(hasText("어린 왕자.epub")).performClick()
        waitFor(hasText("1 / ", substring = true))
    }

    private fun hasNode(matcher: SemanticsMatcher) =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun node(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true)[0]

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 30_000) {
        compose.waitUntil(timeoutMs) { hasNode(matcher) }
    }

    /** 본문 자리(위아래 여백·상태 막대 제외)의 오른쪽 절반에 글자(어두운 점)가 있는가. */
    private fun inkOnRightHalf(bitmap: Bitmap): Boolean {
        for (y in (bitmap.height * 0.1).toInt() until (bitmap.height * 0.75).toInt() step 3) {
            for (x in (bitmap.width * 0.6).toInt() until (bitmap.width * 0.95).toInt() step 2) {
                val c = bitmap.getPixel(x, y)
                if (android.graphics.Color.red(c) + android.graphics.Color.green(c) + android.graphics.Color.blue(c) < 250) return true
            }
        }
        return false
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
