package io.github.kgcaudit.reader.app

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.reflow.ACTION_BOOKMARK
import io.github.kgcaudit.reader.reflow.ACTION_MENU
import io.github.kgcaudit.reader.reflow.ACTION_NEXT_PAGE
import io.github.kgcaudit.reader.reflow.ACTION_PREVIOUS_PAGE
import io.github.kgcaudit.reader.reflow.READING_SURFACE
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertTrue

/**
 * 화면 읽기(TalkBack)로 읽기(전체 점검에서 찾음): 지면은 손짓만 받아, 화면 읽기를 켜 두면 쪽을 넘기거나 메뉴 · 책갈피에 갈 길이
 * 없었다. 지면의 사용자 지정 동작으로 넘기고 · 메뉴를 열고 · 책갈피를 꽂는다. 알림은 화면 읽기가 읽어 준다.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class ReaderAccessibilityTest {
    @get:Rule
    val compose = createEmptyComposeRule(StandardTestDispatcher())
    private var scenario: ActivityScenario<MainActivity>? = null
    private val app = ApplicationProvider.getApplicationContext<OloApp>()
    private lateinit var root: File

    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(60, 90, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Before
    fun setUp() {
        root = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        File(root, "소설").mkdirs()
        File(root, "소설/어린 왕자.epub").writeBytes(SampleBooks.epub())
        File(root, "만화").mkdirs()
        ZipOutputStream(File(root, "만화/별 01권.cbz").outputStream()).use { zip ->
            listOf(0xFFD03030, 0xFF3050D0, 0xFF2E7D6B).forEachIndexed { i, c ->
                zip.putNextEntry(ZipEntry("00${i + 1}.png"))
                zip.write(png(c.toInt()))
                zip.closeEntry()
            }
        }
        runBlocking {
            app.container.data.folders.register(FolderProvider.treeUri)
            app.container.data.rescanAll()
            app.container.data.probeComics()
        }
    }

    @After
    fun tearDown() {
        scenario?.onActivity { it.finish() }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun has(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 30_000) = compose.waitUntil(timeoutMs) { has(matcher) }

    /** [surface] 지면의 사용자 지정 동작 [label] 을 화면 읽기처럼 부른다. */
    private fun act(surface: SemanticsMatcher, label: String) {
        waitFor(surface)
        val node = compose.onAllNodes(surface, useUnmergedTree = true)[0].fetchSemanticsNode()
        val action = node.config[SemanticsActions.CustomActions].single { it.label == label }
        compose.runOnUiThread { action.action() }
        compose.waitForIdle()
    }

    @Test
    fun `a screen reader can turn pages open the menu and bookmark a book`() {
        scenario = ActivityScenario.launch(Intent(app, MainActivity::class.java))
        waitFor(hasText("어린 왕자", substring = true))
        compose.onAllNodes(hasText("어린 왕자", substring = true), useUnmergedTree = true)[0].performClick()
        waitFor(hasText("1 / ", substring = true))
        val surface = hasContentDescription(READING_SURFACE)
        act(surface, ACTION_NEXT_PAGE)
        waitFor(hasText("2 / ", substring = true))
        act(surface, ACTION_PREVIOUS_PAGE)
        waitFor(hasText("1 / ", substring = true))
        act(surface, ACTION_BOOKMARK)
        // 꽂았다는 알림은 화면 읽기가 읽는 곳(live region)에 뜬다 — 그 동작의 유일한 대답이다.
        compose.waitUntil(10_000) { runBlocking { app.container.data.bookmarks.forBook(app.container.data.library.books().first().single().id).isNotEmpty() } }
        waitFor(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        act(surface, ACTION_MENU)
        waitFor(hasText("목차", substring = true))
    }

    @Test
    fun `a screen reader can turn the pages of a comic`() {
        scenario = ActivityScenario.launch(Intent(app, MainActivity::class.java))
        val unit = runBlocking { app.container.data.comics.units().first().single().id }
        scenario!!.onActivity { it.onNewIntent(ContinueWidget.intentFor(app, ContinueTarget.Comic(unit))) }
        waitFor(hasContentDescription("만화 1쪽"))
        act(hasContentDescription("만화 1쪽"), "다음 쪽")
        waitFor(hasContentDescription("만화 2쪽"))
        act(hasContentDescription("만화 2쪽"), "앞 쪽")
        waitFor(hasContentDescription("만화 1쪽"))
        assertTrue(has(hasContentDescription("만화 1쪽")))
    }
}
