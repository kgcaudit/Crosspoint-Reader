package io.github.kgcaudit.reader.app

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
import io.github.kgcaudit.reader.document.pdf.TestPdf
import io.github.kgcaudit.reader.document.pdf.TestPdf.Companion.pages
import io.github.kgcaudit.reader.document.pdf.TestPdf.Companion.utf16
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

/**
 * 하단의 "남은 시간" 이 쓰는 읽는 속도(E5)는 사람이 읽고 한 쪽 넘긴 것만으로 잰다. 자동 넘김이 넘긴 쪽 · 목차로 건너뛴 쪽이
 * 섞이면 속도가 고른 초나 목차를 고른 시간이 된다. EPUB 과 PDF 가 같은 표본기를 쓴다 — 둘 다 본다.
 *
 * 시계는 손으로 돌린다(쪽에 머문 시간을 정해 두려고). 저장된 표본 수로 셌는지 본다.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class ReadingSpeedAppTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>(StandardTestDispatcher())

    @After
    fun settle() {
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    private val app get() = ApplicationProvider.getApplicationContext<OloApp>()

    @Before
    fun setUp() {
        val folder = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        File(folder, "소설").mkdirs()
        File(folder, "소설/어린 왕자.epub").writeBytes(SampleBooks.epub())
        File(folder, "소설/설명서.pdf").writeBytes(manual())
        app.container.pdfEngine = { DrawnPdf(it, pageCount = 6) }
        runBlocking { app.container.data.folders.register(FolderProvider.treeUri) }
    }

    // 넘김 효과 없이 — 효과가 도는 동안의 프레임까지 손으로 돌리면 시험이 길어진다. 표본기는 효과와 상관없다.
    private fun open(name: String, prefs: ScreenPrefs = ScreenPrefs()) {
        app.container.prefs.save(ReaderPrefs(screen = prefs.copy(pageTurn = PageTurn.None)))
        compose.activityRule.scenario.recreate()
        waitFor(hasText(name))
        node(hasText(name)).performClick()
        waitFor(hasText("1 / ", substring = true))
        // 여기부터 시계는 손으로만 돈다.
        compose.mainClock.autoAdvance = false
        tick { true }
    }

    private fun samples(kind: String) = app.container.prefs.loadSpeed(kind).samples

    @Test
    fun `a page read by hand and turned forward is counted`() {
        open("어린 왕자.epub")
        read(seconds = 20)
        next()
        tick { hasNode(hasText("2 / ", substring = true)) }
        tick { samples("chars") == 1 }
        // 뒤로 넘긴 것은 세지 않는다(다시 본 쪽은 처음 읽은 속도가 아니다).
        read(seconds = 20)
        compose.onRoot().performTouchInput { click(centerLeft.copy(x = width * 0.1f)) }
        tick { hasNode(hasText("1 / ", substring = true)) }
        tick(500) { false }
        assertEquals(1, samples("chars"), "뒤로 넘긴 쪽을 읽는 속도에 넣었다")
    }

    @Test
    fun `pages turned by auto turn are not counted as reading`() {
        open("어린 왕자.epub", ScreenPrefs(autoTurn = AutoTurn.S15))
        // 15초마다 저절로 넘어간다 — 머문 15초는 사람이 읽은 시간이 아니라 고른 초다.
        compose.mainClock.advanceTimeBy(16_000)
        tick { hasNode(hasText("2 / ", substring = true)) }
        compose.mainClock.advanceTimeBy(16_000)
        tick { hasNode(hasText("3 / ", substring = true)) }
        tick(500) { false }
        assertEquals(0, samples("chars"), "자동 넘김이 넘긴 쪽을 읽는 속도에 넣었다")

        // ✕ 로 자동 넘김을 끄면 그때부터 사람이 읽은 시간이다.
        node(hasContentDescription("자동 넘김 끄기")).performClick()
        read(seconds = 20)
        next()
        // 3쪽이 장의 끝이면 다음 장 첫 쪽으로 간다 — 그것도 한 쪽 넘김이다.
        tick { !hasNode(hasText("3 / ", substring = true)) }
        tick { samples("chars") == 1 }
    }

    @Test
    fun `jumping by the contents is not counted as reading the page left behind`() {
        open("어린 왕자.epub")
        read(seconds = 20)
        // 20초 뒤 목차로 다음 장에 간다 — 앞으로 갔지만 1쪽을 다 읽고 넘긴 것이 아니다.
        compose.onRoot().performTouchInput { click(center) }
        tick { hasNode(hasText("목차")) }
        node(hasText("목차")).performClick()
        tick { hasNode(hasText("제2장 사막의 아침")) }
        node(hasText("제2장 사막의 아침")).performClick()
        tick { !hasNode(hasText("독서노트", substring = true)) }
        // 둘째 장 첫 쪽에 왔다(도구줄의 "2 / 3장"). 쪽 번호는 1쪽 그대로라 도구줄로 본다.
        compose.onRoot().performTouchInput { click(center) }
        tick { hasNode(hasText("2 / 3장")) }
        compose.onRoot().performTouchInput { click(center) }
        tick { !hasNode(hasText("2 / 3장")) }
        tick(500) { false }
        assertEquals(0, samples("chars"), "목차로 건너뛴 것을 읽는 속도에 넣었다")
        // 건너간 곳에서 손으로 넘기면 센다.
        read(seconds = 20)
        next()
        tick { hasNode(hasText("2 / ", substring = true)) }
        tick { samples("chars") == 1 }
    }

    @Test
    fun `a pdf counts pages turned by hand but not a jump by the contents or auto turn`() {
        open("설명서.pdf")
        read(seconds = 20)
        next()
        tick { hasNode(hasText("2 / 6")) }
        tick { samples("pages") == 1 }
        // 목차로 3장(6쪽)에 간다 — 건너뛴 것.
        read(seconds = 20)
        compose.onRoot().performTouchInput { click(center) }
        tick { hasNode(hasText("목차")) }
        node(hasText("목차")).performClick()
        tick { hasNode(hasText("3장 문제 해결")) }
        node(hasText("3장 문제 해결")).performClick()
        tick { hasNode(hasText("6 / 6")) }
        tick(500) { false }
        assertEquals(1, samples("pages"), "목차로 건너뛴 것을 읽는 속도에 넣었다")
    }

    @Test
    fun `a pdf turned by auto turn is not counted`() {
        open("설명서.pdf", ScreenPrefs(autoTurn = AutoTurn.S15))
        compose.mainClock.advanceTimeBy(16_000)
        tick { hasNode(hasText("2 / 6")) }
        compose.mainClock.advanceTimeBy(16_000)
        tick { hasNode(hasText("3 / 6")) }
        tick(500) { false }
        assertEquals(0, samples("pages"), "자동 넘김이 넘긴 쪽을 읽는 속도에 넣었다")
    }

    // ── 도구 ────────────────────────────────────────────────────────

    /** 지금 쪽을 [seconds] 초 동안 읽는다(화면 시계만 흐른다). */
    private fun read(seconds: Int) {
        compose.mainClock.advanceTimeBy(seconds * 1_000L)
        compose.waitForIdle()
    }

    /** 다음 쪽 자리를 누른다. PDF 는 두 번 누르기(확대)를 기다려 한 번 누르기가 조금 늦다 — 그만큼 시계를 돌린다. */
    private fun next() {
        compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.93f)) }
        compose.mainClock.advanceTimeBy(400)
    }

    /**
     * 시계를 조금씩 돌리며 [condition] 을 기다린다. 조판 스레드가 끝낸 일(쪽 넘김)이 화면에 오려면 시계가 돌아야 한다.
     * [timeoutMs] 를 넘기면 실패 — 단, 늘 거짓인 조건은 "그만큼 돌려 둔다" 는 뜻으로 쓴다.
     */
    private fun tick(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > until) {
                if (timeoutMs <= 500) return
                throw AssertionError("조건이 끝내 맞지 않았다: chars=${samples("chars")} pages=${samples("pages")}")
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

    /** 쪽 6장, 목차 셋(1쪽 · 3쪽 · 6쪽). */
    private fun manual(): ByteArray = TestPdf().run {
        val p = pages(10, 6)
        obj(1, "<< /Type /Catalog /Pages 10 0 R /Outlines 2 0 R >>")
        obj(2, "<< /Type /Outlines /First 50 0 R >>")
        obj(50, "<< /Title ${utf16("1장 시작하기")} /Next 51 0 R /Dest [${p[0]} 0 R /Fit] >>")
        obj(51, "<< /Title ${utf16("2장 넘기기")} /Next 52 0 R /Dest [${p[2]} 0 R /Fit] >>")
        obj(52, "<< /Title ${utf16("3장 문제 해결")} /Dest [${p[5]} 0 R /Fit] >>")
        obj(30, "<< /Title ${utf16("설명서")} >>")
        classic(trailerExtra = "/Info 30 0 R")
    }
}
