package io.github.kgcaudit.reader.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.data.TimeMode
import io.github.kgcaudit.reader.reflow.ReaderPrefs
import io.github.kgcaudit.reader.ui.design.AutoTurn
import io.github.kgcaudit.reader.ui.design.PageTurn
import io.github.kgcaudit.reader.ui.design.ScreenPrefs
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
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 독서 기록(0.51.0)이 리더에서 재는 시간. 손으로 넘기며 읽은 시간만 "읽은 시간" 이고, 자동 넘김은 따로, 건너뛴 쪽 · 화면을
 * 끈 동안은 들어가지 않는다. 시계는 손으로 돌린다(앱의 독서 기록 시계를 화면 시험 시계에 묶는다).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class ReadingTimeAppTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>(StandardTestDispatcher())

    private val app get() = ApplicationProvider.getApplicationContext<OloApp>()

    @After
    fun settle() {
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    @Before
    fun setUp() {
        val folder = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        File(folder, "소설").mkdirs()
        File(folder, "소설/어린 왕자.epub").writeBytes(SampleBooks.epub())
        app.container.readingClock = { compose.mainClock.currentTime }
        runBlocking { app.container.data.folders.register(FolderProvider.treeUri) }
    }

    private fun open(prefs: ScreenPrefs = ScreenPrefs()) {
        app.container.prefs.save(ReaderPrefs(screen = prefs.copy(pageTurn = PageTurn.None)))
        compose.activityRule.scenario.recreate()
        waitFor(hasText("어린 왕자.epub"))
        node(hasText("어린 왕자.epub")).performClick()
        waitFor(hasText("1 / ", substring = true))
        compose.mainClock.autoAdvance = false
        tick { true }
    }

    private fun millis(mode: TimeMode): Long = runBlocking { app.container.data.readingTime.all() }.filter { it.mode == mode }.sumOf { it.millis }

    @Test
    fun `pages turned by hand are recorded as reading time`() {
        open()
        read(seconds = 20)
        next()
        tick { hasNode(hasText("2 / ", substring = true)) }
        tick { millis(TimeMode.READ) > 0 }
        // 넘김을 기다리며 돌린 시계(수백 ms)만큼 길 수 있다.
        assertTrue(millis(TimeMode.READ) in 20_000L..23_000L, "읽은 시간 ${millis(TimeMode.READ)}")
        // 앞 쪽으로 넘긴 것도 읽은 것이다(속도와 다르다).
        read(seconds = 10)
        compose.onRoot().performTouchInput { click(centerLeft.copy(x = width * 0.1f)) }
        tick { hasNode(hasText("1 / ", substring = true)) }
        tick { millis(TimeMode.READ) >= 30_000L }
    }

    @Test
    fun `auto turned pages are recorded apart and not as reading`() {
        open(ScreenPrefs(autoTurn = AutoTurn.S15))
        compose.mainClock.advanceTimeBy(16_000)
        tick { hasNode(hasText("2 / ", substring = true)) }
        tick { millis(TimeMode.AUTO) > 0 }
        tick(500) { false }
        assertEquals(0L, millis(TimeMode.READ), "자동 넘김이 넘긴 쪽을 읽은 시간에 넣었다")
        // 기다리는 동안 몇 쪽 더 넘어갔을 수 있다 — 넘어간 쪽마다 15초 남짓이다.
        assertTrue(millis(TimeMode.AUTO) >= 14_000L, "자동 넘김 시간 ${millis(TimeMode.AUTO)}")
    }

    @Test
    fun `jumping by the contents does not record the page left behind`() {
        open()
        read(seconds = 20)
        compose.onRoot().performTouchInput { click(center) }
        tick { hasNode(hasText("목차")) }
        node(hasText("목차")).performClick()
        tick { hasNode(hasText("제2장 사막의 아침")) }
        node(hasText("제2장 사막의 아침")).performClick()
        tick { !hasNode(hasText("독서노트", substring = true)) }
        tick(500) { false }
        assertEquals(0L, millis(TimeMode.READ), "목차로 건너뛴 쪽의 시간을 넣었다")
    }

    @Test
    fun `the clock stops while the app is in the background`() {
        open()
        read(seconds = 30)
        // 홈으로 나갔다가(화면을 껐다가) 10분 뒤 돌아왔다.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        tick { millis(TimeMode.READ) > 0 }
        compose.mainClock.advanceTimeBy(10 * 60_000L)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        tick { true }
        read(seconds = 10)
        next()
        tick { hasNode(hasText("2 / ", substring = true)) }
        tick { millis(TimeMode.READ) >= 40_000L }
        tick(500) { false }
        // 나가 있던 10분이 들어갔으면 한 쪽의 끝(5분)까지 찼을 것이다.
        assertTrue(millis(TimeMode.READ) < 45_000L, "나가 있던 시간을 셌다: ${millis(TimeMode.READ)}")
    }

    // ── 도구 ────────────────────────────────────────────────────────

    private fun read(seconds: Int) {
        compose.mainClock.advanceTimeBy(seconds * 1_000L)
        compose.waitForIdle()
    }

    private fun next() {
        compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.93f)) }
        compose.mainClock.advanceTimeBy(400)
    }

    private fun tick(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > until) {
                if (timeoutMs <= 500) return
                throw AssertionError("조건이 끝내 맞지 않았다: read=${millis(TimeMode.READ)} auto=${millis(TimeMode.AUTO)}")
            }
            Thread.sleep(20)
            compose.mainClock.advanceTimeBy(50)
            compose.waitForIdle()
        }
    }

    private fun hasNode(matcher: SemanticsMatcher) =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun node(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true)[0]

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 30_000) {
        compose.waitUntil(timeoutMs) { hasNode(matcher) }
    }
}
