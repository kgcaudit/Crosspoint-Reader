package io.github.kgcaudit.reader.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.reflow.ReaderPrefs
import io.github.kgcaudit.reader.text.UserFonts
import org.junit.Before
import kotlinx.coroutines.test.StandardTestDispatcher
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
 * 출판사 글꼴(책에 든 글꼴). 글꼴이 든 책은 그 글꼴로 열리고, 목록에서 끄고 켤 수 있다.
 * 올려 받은 책 세 권이 모두 KoPub 을 넣어 두었는데 0.5 까지는 전부 무시했다.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class PublisherFontsTest {

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
    private val app = ApplicationProvider.getApplicationContext<OloApp>()

    @Before
    fun setUp() {
        val folder = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        val font = File(app.cacheDir, "f.ttf").also { TestFonts.copy("olo-test-regular.ttf", it) }.readBytes()
        File(folder, "글꼴 책.epub").writeBytes(SampleBooks.fontBook(font))
        File(folder, "어린 왕자.epub").writeBytes(SampleBooks.epub())
        File(app.cacheDir, "book-fonts").deleteRecursively()
        compose.activity.container.data.let { kotlinx.coroutines.runBlocking { it.folders.register(FolderProvider.treeUri) } }
        compose.activityRule.scenario.recreate()
        waitFor(hasText("글꼴 책.epub"))
    }

    @Test
    fun `a book with its own font opens in it and the reader can switch it off and back on`() {
        node(hasText("글꼴 책.epub")).performClick()
        waitFor(hasText("1 / ", substring = true), timeoutMs = 30_000)
        // 책 글꼴을 꺼내 읽었다(기본값이 "켜짐" 이다).
        assertTrue(File(app.cacheDir, "book-fonts").walk().any { it.name.endsWith(".font") }, "출판사 글꼴을 꺼내지 않았다")
        shot("18-publisher-font-page")
        val withBookFont = page()

        compose.onRoot().performTouchInput { click(center) }
        node(hasText("보기")).performClick()
        waitFor(hasText("출판사 글꼴"))
        node(hasText("출판사 글꼴")).performClick()
        waitFor(hasText("이 책에 든 글꼴", substring = true))
        shot("19-fonts-publisher")

        // 휴대폰 글꼴을 고르면 출판사 글꼴이 꺼지고, 페이지가 실제로 다시 그려진다.
        node(hasText("휴대폰 글꼴")).performClick()
        compose.waitUntil(5_000) { !compose.activity.container.prefs.load().publisherFonts }
        node(hasContentDescription("뒤로")).performClick()
        waitFor(hasText("휴대폰 글꼴"))
        compose.onRoot().performTouchInput { click(topCenter.copy(y = height * 0.3f)) }
        compose.waitUntil(15_000) { !page().sameAs(withBookFont) }
        shot("20-phone-font-page")

        // 다시 켜면 같은 모양으로 돌아온다(캐시가 갈려 있으므로 옛 페이지가 섞이지 않는다).
        compose.onRoot().performTouchInput { click(center) }
        node(hasText("보기")).performClick()
        node(hasText("휴대폰 글꼴")).performClick()
        node(hasText("출판사 글꼴")).performClick()
        compose.waitUntil(5_000) { compose.activity.container.prefs.load().publisherFonts }
        node(hasContentDescription("뒤로")).performClick()
        compose.onRoot().performTouchInput { click(topCenter.copy(y = height * 0.3f)) }
        compose.waitUntil(15_000) { page().sameAs(withBookFont) }
    }

    @Test
    fun `with publisher fonts on, text the book leaves unstyled is in the phone font even after picking a user font`() {
        // 글꼴 목록의 약속: "책이 정하지 않은 곳은 휴대폰 글꼴". 이 책은 문단(p)만 글꼴을 정하고 제목(h1)은
        // 정하지 않는다. 사용자 글꼴을 골라 둔 채 출판사 글꼴로 열어도 제목은 휴대폰 글꼴이어야 한다.
        val container = compose.activity.container
        val bold = File(app.cacheDir, "b.ttf").also { TestFonts.copy("olo-test-bold.ttf", it) }
        val added = bold.inputStream().use { container.fonts.user!!.import(it) } as UserFonts.ImportResult.Added
        container.prefs.save(ReaderPrefs(font = added.families.first().key, publisherFonts = true))
        compose.activityRule.scenario.recreate()
        waitFor(hasText("글꼴 책.epub"))
        node(hasText("글꼴 책.epub")).performClick()
        waitFor(hasText("1 / ", substring = true), timeoutMs = 30_000)
        val withUserFontChosen = top()

        // 휴대폰 글꼴을 고른 뒤 출판사 글꼴을 다시 켠다. 제목의 글꼴이 같으니 쪽도 같아야 한다.
        compose.onRoot().performTouchInput { click(center) }
        node(hasText("보기")).performClick()
        node(hasText("출판사 글꼴")).performClick()
        node(hasText("휴대폰 글꼴")).performClick()
        node(hasText("출판사 글꼴")).performClick()
        compose.waitUntil(5_000) { container.prefs.load().let { it.publisherFonts && it.font != added.families.first().key } }
        node(hasContentDescription("뒤로")).performClick()
        compose.onRoot().performTouchInput { click(topCenter.copy(y = height * 0.3f)) }
        compose.waitUntil(15_000) { top().sameAs(withUserFontChosen) }

        // 헛통과가 아니라는 확인: 사용자 글꼴로 바꾸면 쪽이 실제로 달라진다(이 폰트는 휴대폰 글꼴과 모양이 다르다).
        compose.onRoot().performTouchInput { click(center) }
        node(hasText("보기")).performClick()
        node(hasText("출판사 글꼴")).performClick()
        node(hasText(added.families.first().label)).performClick()
        node(hasContentDescription("뒤로")).performClick()
        compose.onRoot().performTouchInput { click(topCenter.copy(y = height * 0.3f)) }
        compose.waitUntil(15_000) { !top().sameAs(withUserFontChosen) }
    }

    @Test
    fun `a book without fonts does not offer the publisher font`() {
        // 골라도 아무것도 바뀌지 않는 줄은 두지 않는다.
        node(hasText("어린 왕자.epub")).performClick()
        waitFor(hasText("1 / ", substring = true), timeoutMs = 30_000)
        compose.onRoot().performTouchInput { click(center) }
        node(hasText("보기")).performClick()
        node(hasText("휴대폰 글꼴")).performClick()
        waitFor(hasText("사용자 글꼴"))
        assertTrue(compose.onAllNodes(hasText("출판사 글꼴"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        // 글꼴 없는 책을 여는 것만으로 설정이 꺼지지는 않는다 — 다음 글꼴 책에서는 다시 출판사 글꼴이다.
        assertTrue(compose.activity.container.prefs.load().publisherFonts)
        assertFalse(File(app.cacheDir, "book-fonts").walk().any { it.isFile })
        assertEquals(0, compose.activity.container.fonts.user!!.families().size)
    }

    // ── 도우미 ──────────────────────────────────────────────────────

    private fun node(matcher: androidx.compose.ui.test.SemanticsMatcher) =
        compose.onAllNodes(matcher, useUnmergedTree = true)[0]

    private fun waitFor(matcher: androidx.compose.ui.test.SemanticsMatcher, timeoutMs: Long = 15_000) {
        compose.waitUntil(timeoutMs) { compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    /** 지면의 위쪽 절반. 제목(h1 — 책이 글꼴을 정하지 않은 곳)이 여기 있다. */
    private fun top(): Bitmap {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        return Bitmap.createBitmap(bitmap, 0, 0, view.width, view.height / 2)
    }

    /** 지면(상태바 위쪽 본문)만 잘라 온다. */
    private fun page(): Bitmap {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        return Bitmap.createBitmap(bitmap, 0, view.height / 5, view.width, view.height / 2)
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        File(shots, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
