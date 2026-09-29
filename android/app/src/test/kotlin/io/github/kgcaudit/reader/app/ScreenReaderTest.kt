package io.github.kgcaudit.reader.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 화면 읽기(TalkBack, 0.25.1). 판 안을 눌러도 닫히지 않게 빈 누르기로 막던 틀(과 판을 감싼 바깥 막)이 "누를 수 있는 한
 * 덩어리" 가 되어, 화면 읽기가 판의 제목 · 안내를 하나씩 읽지 못하고 화면 전체를 한 단추로 읽었다.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class ScreenReaderTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>(StandardTestDispatcher())

    private val app = ApplicationProvider.getApplicationContext<OloApp>()
    private val message = "폴더를 빼도 그 책들의 읽은 자리와 책갈피는 남습니다. 다시 추가하면 이어집니다."

    @Before
    fun setUp() {
        val folder = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        File(folder, "어린 왕자.epub").writeBytes(SampleBooks.epub())
        runBlocking { app.container.data.folders.register(FolderProvider.treeUri) }
        compose.activityRule.scenario.recreate()
    }

    private fun has(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `a popup's message is read on its own and tapping inside still does not close it`() {
        compose.waitUntil(30_000) { has(hasContentDescription("책 폴더")) }
        compose.onAllNodes(hasContentDescription("책 폴더"), useUnmergedTree = true)[0].performClick()
        compose.waitUntil(10_000) { has(hasText(message)) }
        // 합친 의미 나무(화면 읽기가 보는 것)에서 안내 글이 누를 수 있는 덩어리에 묶이지 않는다.
        compose.onNode(hasText(message), useUnmergedTree = false).assertHasNoClickAction()
        // 판 안(안내 글)을 눌러도 닫히지 않고, 판 밖을 누르면 닫힌다.
        compose.onNode(hasText(message), useUnmergedTree = true).performTouchInput { click(center) }
        compose.waitForIdle()
        assertTrue(has(hasText(message)), "판 안을 눌렀는데 닫혔다")
        compose.onRoot().performTouchInput { click(topLeft.copy(x = 10f, y = height - 10f)) }
        compose.waitUntil(5_000) { !has(hasText(message)) }
        assertFalse(has(hasText(message)))
    }
}
