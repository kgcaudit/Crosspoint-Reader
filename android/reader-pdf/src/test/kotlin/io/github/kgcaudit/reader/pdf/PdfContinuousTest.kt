package io.github.kgcaudit.reader.pdf

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import io.github.kgcaudit.reader.document.BookFormat
import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.BookMeta
import io.github.kgcaudit.reader.document.Bookmark
import io.github.kgcaudit.reader.document.BookmarkRepository
import io.github.kgcaudit.reader.document.HighlightColor
import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.document.ProgressRepository
import io.github.kgcaudit.reader.document.ReadingProgress
import io.github.kgcaudit.reader.document.TocEntry
import io.github.kgcaudit.reader.listen.ListenHub
import io.github.kgcaudit.reader.ui.design.CpTheme
import io.github.kgcaudit.reader.ui.design.PageTurn
import io.github.kgcaudit.reader.ui.design.PdfFit
import io.github.kgcaudit.reader.ui.design.ScreenPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PDF 이어서 보기(사용자 결정 5-1 · 5-2). 앱 없이 PDF 화면만 띄워 기둥의 움직임 · 지금 쪽 · 저장하는 자리 · 그리는 쪽 수를 잰다.
 * 기본은 휴대폰 세로(A4 한 쪽이 화면 높이의 3분의 2쯤 — 한 화면에 두 쪽이 걸친다).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class PdfContinuousTest {

    @get:Rule
    val compose = createComposeRule()

    private val id = BookId("content://books/continuous.pdf")
    private val progress = Place()
    private val continuous = ScreenPrefs(pdfFit = PdfFit.Continuous, pageTurn = PageTurn.None)

    @After
    fun stopListening() = compose.runOnIdle { ListenHub.detach() }

    private fun reader(source: PdfSource = Stack(6), contents: List<TocEntry> = emptyList()) =
        PdfReader(PdfBook(BookMeta(id, BookFormat.PDF, "보고서"), source, contents), NoMarks(), progress, Dispatchers.Unconfined) { 1_000L }
            .also { runBlocking { it.open() } }

    private fun has(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(5_000) { has(matcher) }
    /** 화면에 놓인 네모. boundsInRoot 는 화면 밖으로 나간 부분을 잘라 내 위로 밀려난 쪽의 머리가 늘 화면 맨 위로 읽힌다 — 자르지 않은 자리로 잰다. */
    private fun bounds(matcher: SemanticsMatcher): Rect =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().first().let { n ->
            Rect(n.positionInRoot, androidx.compose.ui.geometry.Size(n.size.width.toFloat(), n.size.height.toFloat()))
        }

    /** 기둥(목록)의 화면 네모. 화면 높이(viewH)가 이것의 높이다. */
    private fun column() = bounds(hasScrollAction())
    /** [page] 쪽(0부터)의 화면 네모. 보이지 않으면 실패. */
    private fun page(page: Int) = bounds(hasContentDescription("${page + 1}쪽"))

    private fun saved(): Pair<Int, Int> = (progress.place as? Locator.FixedPage)?.let { it.page to it.yPermille } ?: (0 to 0)
    private fun tapNext() {
        compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.9f)) }
        compose.waitForIdle()
    }
    private fun show(r: PdfReader, prefs: ScreenPrefs = continuous) =
        compose.setContent { CpTheme(dark = false) { PdfScreen(r, onClose = {}, onChrome = {}, prefs = prefs) } }

    @Test
    fun `pages are stacked with a gap and a tap moves down nine tenths of the screen`() {
        val r = reader()
        show(r)
        waitFor(hasText("1 / 6"))
        // 쪽 사이 틈: 1쪽 끝과 2쪽 머리 사이가 비어 있다(쪽이 바뀌는 곳이 보인다).
        val gap = page(1).top - page(0).bottom
        assertTrue(gap > 20f && gap < 40f, "쪽 사이 틈이 ${gap}px")
        val view = column().height
        val pageH = page(0).height
        // 2쪽 머리의 자리로 잰다 — 한 화면 내리면 1쪽은 화면 밖으로 나간다.
        val before = page(1).top
        tapNext()
        // 한 화면의 90% — 앞 화면 끝 10% 가 새 화면 머리에 다시 보여 끝에 걸린 줄을 잃지 않는다.
        compose.waitUntil(5_000) { has(hasContentDescription("2쪽")) && page(1).top < before - 10f }
        compose.waitForIdle()
        val moved = before - page(1).top
        check(pageH > 0f)
        assertTrue(abs(moved - view * 0.9f) < 3f, "한 번 누르니 ${moved}px(화면 ${view}px)")
    }

    @Test
    @Config(qualifiers = "w851dp-h393dp-xhdpi")
    fun `a tall page stays the current page until its middle has scrolled past`() {
        // 가로 휴대폰: A4 한 쪽이 화면 서너 개. 누를 때마다 같은 걸음으로 쪽 안을 내려가고, 쪽은 그 끝이 화면 가운데를 지나야 바뀐다.
        val r = reader()
        show(r)
        waitFor(hasText("1 / 6"))
        tapNext()
        compose.waitUntil(5_000) { saved().second > 0 }
        val (p1, y1) = saved()
        tapNext()
        compose.waitUntil(5_000) { saved().second > y1 }
        val (p2, y2) = saved()
        assertEquals(0, p1)
        assertEquals(0, p2)
        assertTrue(abs((y2 - y1) - y1) <= 3, "걸음이 고르지 않다: $y1 → $y2")
        assertEquals(0, r.state.value.page, "쪽 안을 내려가는 중인데 쪽이 바뀌었다")
        // 쪽 끝이 화면 가운데를 지날 때까지 누른다 — 그때 2쪽.
        repeat(6) { if (r.state.value.page == 0) tapNext() }
        compose.waitUntil(5_000) { r.state.value.page == 1 }
        waitFor(hasText("2 / 6"))
    }

    @Test
    fun `the label follows the page in the middle while the place keeps the top of the screen`() {
        val r = reader()
        var shown by mutableStateOf(r)
        compose.setContent { CpTheme(dark = false) { key(shown) { PdfScreen(shown, onClose = {}, onChrome = {}, prefs = continuous) } } }
        waitFor(hasText("1 / 6"))
        val pageH = page(0).height
        val view = column().height
        // 1쪽의 60% 까지 내린다: 화면 맨 위는 1쪽, 가운데는 2쪽(구상안 1번 그림과 같은 모양).
        val by = pageH * 0.6f
        check(by + view / 2 > pageH + 40f) { "화면 가운데가 2쪽에 닿지 않는 배치" }
        compose.onAllNodes(hasScrollAction(), useUnmergedTree = true)[0].performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, by) }
        compose.waitForIdle()
        waitFor(hasText("2 / 6"))
        assertEquals(1, r.state.value.page)
        // 저장하는 자리는 화면 맨 위(1쪽 600‰) — 2쪽으로 저장하면 다시 열 때 1쪽 끝(아직 읽지 않았을 수 있다)을 건너뛴다.
        compose.waitUntil(5_000) { saved().first == 0 && saved().second > 0 }
        assertTrue(abs(saved().second - 600) <= 3, "저장한 자리 ${saved()}")
        r.close()

        // 다시 열면 같은 자리 · 같은 쪽 번호.
        shown = reader()
        compose.waitForIdle()
        waitFor(hasContentDescription("1쪽"))
        waitFor(hasText("2 / 6"))
        assertTrue(abs(page(0).top + pageH * 0.6f - column().top) < 3f, "다시 연 자리가 다르다: ${page(0).top}")
    }

    @Test
    fun `the page number shows while scrolling and goes away after a moment`() {
        val r = reader()
        show(r)
        waitFor(hasText("1 / 6"))
        compose.waitForIdle()
        // 시계를 손으로 돌린다 — 저절로 돌면 사라지기까지 기다리는 1.5초가 한 번에 지나가 보이는 순간을 잡지 못한다.
        compose.mainClock.autoAdvance = false
        compose.onRoot().performTouchInput { swipeUp(startY = centerY + height * 0.3f, endY = centerY - height * 0.3f, durationMillis = 400) }
        compose.mainClock.advanceTimeBy(100)
        assertTrue(has(hasContentDescription("쪽 위치")), "굴리는데 쪽 번호가 보이지 않는다")
        assertTrue(has(hasText("쪽 · 6쪽 중", substring = true)))
        compose.mainClock.advanceTimeBy(5_000)
        assertTrue(!has(hasContentDescription("쪽 위치")), "멈춘 뒤에도 쪽 번호가 남아 글을 가린다")
        compose.mainClock.autoAdvance = true
    }

    @Test
    @Config(qualifiers = "w851dp-h393dp-xhdpi")
    fun `auto turn keeps moving screen by screen inside a tall page`() {
        // 가로 화면: 한 화면 내려가도 쪽 번호가 그대로다. 쪽 번호로 세면 첫 걸음 뒤 "책 끝" 으로 읽고 멈췄다.
        val r = reader()
        show(r, continuous.copy(autoTurn = io.github.kgcaudit.reader.ui.design.AutoTurn.S15))
        waitFor(hasText("1 / 6"))
        compose.mainClock.advanceTimeBy(16_000)
        compose.waitUntil(5_000) { compose.waitForIdle(); saved().second > 0 }
        val first = saved().second
        compose.mainClock.advanceTimeBy(16_000)
        compose.waitUntil(5_000) { compose.waitForIdle(); saved().second > first }
        assertEquals(0, saved().first)
    }

    @Test
    fun `a sideways swipe turns one whole page to its top`() {
        val r = reader()
        show(r)
        waitFor(hasText("1 / 6"))
        compose.onRoot().performTouchInput { swipeLeft() }
        waitFor(hasText("2 / 6"))
        compose.waitUntil(5_000) { saved() == (1 to 0) }
        assertTrue(abs(page(1).top - column().top) < 2f, "2쪽 머리가 화면 맨 위가 아니다")
        compose.onRoot().performTouchInput { swipeRight() }
        waitFor(hasText("1 / 6"))
        compose.waitUntil(5_000) { saved() == (0 to 0) }
    }

    @Test
    @Config(qualifiers = "w851dp-h393dp-xhdpi")
    fun `switching between fit width and continuous keeps the place in the page`() {
        progress.place = Locator.FixedPage(1, yPermille = 500)
        val r = reader()
        var prefs by mutableStateOf(ScreenPrefs(pdfFit = PdfFit.Width, pageTurn = PageTurn.None))
        compose.setContent { CpTheme(dark = false) { PdfScreen(r, onClose = {}, onChrome = {}, prefs = prefs) } }
        waitFor(hasText("2 / 6"))
        compose.runOnIdle { prefs = continuous }
        waitFor(hasContentDescription("2쪽"))
        // 이어서에서도 2쪽 한가운데가 화면 맨 위.
        compose.waitUntil(5_000) { abs(page(1).top + page(1).height * 0.5f - column().top) < 3f }
        tapNext()
        compose.waitUntil(5_000) { saved().first == 1 && saved().second > 520 }
        val (_, y) = saved()
        // 폭으로 돌아가도 같은 자리: 폭의 쪽 안 위치와 저장한 천분율이 그대로.
        compose.runOnIdle { prefs = ScreenPrefs(pdfFit = PdfFit.Width, pageTurn = PageTurn.None) }
        waitFor(hasText("2 / 6"))
        compose.waitForIdle()
        assertEquals(1, saved().first)
        assertTrue(abs(saved().second - y) <= 2, "폭으로 바꾸니 자리가 $y → ${saved().second}")
        assertEquals(y, r.scrollPermille(1))
    }

    @Test
    fun `a contents jump lands on the top of that page`() {
        val r = reader(contents = listOf(TocEntry("1장", Locator.FixedPage(0)), TocEntry("5장", Locator.FixedPage(4))))
        show(r)
        waitFor(hasText("1 / 6"))
        compose.runOnIdle { runBlocking { r.goTo(TocEntry("5장", Locator.FixedPage(4))) } }
        waitFor(hasContentDescription("5쪽"))
        compose.waitUntil(5_000) { abs(page(4).top - column().top) < 2f }
        waitFor(hasText("5 / 6"))
        assertEquals(4 to 0, saved())
        // 지금 쪽을 목차에서 다시 골라도 그 쪽 머리로 간다(쪽 번호가 같다고 가만있지 않는다).
        compose.onAllNodes(hasScrollAction(), useUnmergedTree = true)[0].performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100f) }
        compose.waitForIdle()
        compose.runOnIdle { runBlocking { r.goTo(TocEntry("5장", Locator.FixedPage(4))) } }
        compose.waitUntil(5_000) { abs(page(4).top - column().top) < 2f }
    }

    @Test
    @Config(qualifiers = "w851dp-h393dp-xhdpi")
    fun `continuous never shows two pages side by side`() {
        // 가로 두 쪽 보기는 기본 켬이다. 쪽 전체에서는 두 쪽(2–3쪽 펼침), 이어서로 바꾸면 한 기둥.
        progress.place = Locator.FixedPage(2)
        val r = reader()
        var prefs by mutableStateOf(ScreenPrefs(pageTurn = PageTurn.None))
        compose.setContent { CpTheme(dark = false) { PdfScreen(r, onClose = {}, onChrome = {}, prefs = prefs) } }
        // 리더의 상태만 보는 조건은 화면을 돌리지 않는다 — 화면이 쉴 때까지 돌린 뒤에 본다.
        compose.waitForIdle()
        assertEquals(listOf(1, 2), r.state.value.shown)
        compose.runOnIdle { prefs = continuous }
        compose.waitForIdle()
        assertEquals(1, r.state.value.shown.size)
        waitFor(hasContentDescription("2쪽"))
        compose.waitForIdle()
        // 기둥은 화면 폭을 다 쓴다(두 쪽이면 반).
        assertTrue(page(1).width > column().width * 0.95f)
    }

    @Test
    fun `a page that fails to draw shows the usual note and the column keeps scrolling`() {
        val r = reader(Stack(6, broken = setOf(1)))
        show(r)
        waitFor(hasText("1 / 6"))
        compose.onRoot().performTouchInput { swipeLeft() }
        waitFor(hasText("2 / 6"))
        waitFor(hasText("이 쪽을 그리지 못했습니다"))
        compose.onRoot().performTouchInput { swipeLeft() }
        waitFor(hasText("3 / 6"))
        compose.waitUntil(5_000) { saved() == (2 to 0) }
    }

    @Test
    fun `a thousand page pdf draws only the pages near the screen`() {
        val source = Stack(1000)
        progress.place = Locator.FixedPage(500)
        val r = reader(source)
        show(r)
        waitFor(hasText("501 / 1000"))
        repeat(3) { tapNext() }
        compose.waitUntil(5_000) { saved().first > 500 }
        compose.waitForIdle()
        // 보이는 쪽 · 미리 짓는 쪽만. 1000쪽을 다 그리거나 다 재면 열 때 수 초 · 수백 MB 다.
        assertTrue(source.renders.get() <= 10, "그린 쪽 ${source.renders.get()}번")
        assertTrue(source.sizes.get() <= 20, "잰 쪽 ${source.sizes.get()}번")
    }

    @Test
    fun `the view panel offers continuous as a third fit and explains it`() {
        val r = reader()
        var prefs by mutableStateOf(ScreenPrefs(pageTurn = PageTurn.None))
        compose.setContent { CpTheme(dark = false) { PdfScreen(r, onClose = {}, onChrome = {}, prefs = prefs, onPrefsChange = { prefs = it }) } }
        waitFor(hasText("1 / 6"))
        compose.onRoot().performTouchInput { click(center) }
        waitFor(hasText("보기"))
        compose.onAllNodes(hasText("보기"), useUnmergedTree = true)[0].performClick()
        waitFor(hasText("쪽 맞춤"))
        assertTrue(!has(hasText(CONTINUOUS_NOTE)), "이어서를 고르기 전에 설명이 보인다")
        compose.onAllNodes(hasText("이어서"), useUnmergedTree = true)[0].performClick()
        compose.waitUntil(5_000) { prefs.pdfFit == PdfFit.Continuous }
        waitFor(hasText(CONTINUOUS_NOTE))
    }

    @Test
    @Config(qualifiers = "w851dp-h393dp-xhdpi")
    fun `a search hit low on a page is scrolled into view`() {
        // 3쪽 아래쪽(쪽 높이의 92%)에 "코끼리". 쪽 머리로만 가면 가로 화면에서는 화면 셋 아래라 보이지 않는다.
        val r = reader(Stack(6, words = mapOf(2 to "코끼리")))
        show(r)
        waitFor(hasText("1 / 6"))
        compose.onRoot().performTouchInput { click(center) }
        waitFor(hasContentDescription("책에서 찾기"))
        compose.onAllNodes(hasContentDescription("책에서 찾기"), useUnmergedTree = true)[0].performClick()
        waitFor(hasContentDescription("찾을 말"))
        compose.onAllNodes(hasContentDescription("찾을 말"), useUnmergedTree = true)[0].performTextInput("코끼리")
        compose.onAllNodes(hasContentDescription("찾을 말"), useUnmergedTree = true)[0].performImeAction()
        waitFor(hasText("3쪽"))
        // 머리(쪽 이름)가 아니라 결과 줄(누를 수 있는 것)의 "3쪽".
        compose.onAllNodes(hasText("3쪽"), useUnmergedTree = true).let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        waitFor(hasText("3 / 6"))
        compose.waitUntil(5_000) {
            has(hasContentDescription("3쪽")) && page(2).let { p -> (p.top + p.height * Stack.WORD_TOP).let { y -> y > column().top && y < column().bottom } }
        }
    }

    @Test
    fun `highlights are drawn on every visible page, not only the current one`() {
        // 2쪽 머리의 칠. 화면 가운데는 1쪽이지만 2쪽 머리도 화면 아래에 보인다 — 거기에도 칠이 있어야 한다.
        val r = reader(Stack(6, words = mapOf(1 to "형광펜칠", 0 to "첫쪽"), wordTop = 0.05f))
        runBlocking { r.highlight(1, 0, 4, HighlightColor.entries.first()) }
        var view: android.view.View? = null
        compose.setContent {
            view = androidx.compose.ui.platform.LocalView.current
            CpTheme(dark = false) { PdfScreen(r, onClose = {}, onChrome = {}, prefs = continuous) }
        }
        waitFor(hasText("1 / 6"))
        compose.waitForIdle()
        check(page(1).top + page(1).height * 0.06f < column().bottom) { "2쪽 머리가 화면에 없다" }
        compose.waitUntil(5_000) {
            val v = view!!
            val shot = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            compose.runOnUiThread { v.draw(android.graphics.Canvas(shot)) }
            val p = page(1)
            val c = shot.getPixel((p.left + p.width * 0.15f).toInt(), (p.top + p.height * 0.065f).toInt())
            // 흰 쪽 위의 노란 칠: 파랑이 빨강 · 초록보다 뚜렷이 낮다.
            Color.blue(c) < Color.red(c) - 20 && Color.blue(c) < Color.green(c) - 20
        }
    }
}

/**
 * 흰 A4 쪽들. [broken] 쪽은 그리다 실패한다(엔진이 던짐). [words] 쪽에는 그 말이 쪽 높이의 [wordTop] 자리에 한 줄로 있다
 * (글자 층 — 안드로이드 15+ 흉내). 그린 횟수 · 잰 횟수를 센다.
 */
private class Stack(
    override val pageCount: Int,
    private val broken: Set<Int> = emptySet(),
    private val words: Map<Int, String> = emptyMap(),
    private val wordTop: Float = WORD_TOP,
) : PdfSource {
    val renders = AtomicInteger()
    val sizes = AtomicInteger()
    override fun pageSize(index: Int): Pair<Int, Int> = (595 to 842).also { sizes.incrementAndGet() }
    override fun render(index: Int, target: Bitmap, region: PageRegion) {
        renders.incrementAndGet()
        if (index in broken) error("broken page $index")
        target.eraseColor(Color.WHITE)
    }
    override val readsText: Boolean get() = words.isNotEmpty()
    override fun pageText(index: Int): String? = words[index].orEmpty()
    override fun textLayer(index: Int): PageText? {
        val word = words[index] ?: return PageText.EMPTY
        val boxes = FloatArray(word.length * 4)
        for (i in word.indices) {
            boxes[i * 4] = 0.1f + i * 0.05f
            boxes[i * 4 + 1] = wordTop
            boxes[i * 4 + 2] = 0.1f + (i + 1) * 0.05f
            boxes[i * 4 + 3] = wordTop + 0.03f
        }
        return PageText(word, boxes)
    }
    override fun close() = Unit

    companion object {
        const val WORD_TOP = 0.92f
    }
}

private class Place : ProgressRepository {
    var place: Locator? = null
    override suspend fun get(bookId: BookId): ReadingProgress? = place?.let { ReadingProgress(bookId, it, 0f, 0L) }
    override suspend fun save(progress: ReadingProgress) {
        place = progress.locator
    }
    override suspend fun remove(bookId: BookId) {
        place = null
    }
}

private class NoMarks : BookmarkRepository {
    override suspend fun forBook(bookId: BookId): List<Bookmark> = emptyList()
    override suspend fun add(bookmark: Bookmark): Bookmark = bookmark
    override suspend fun remove(id: Long) = Unit
}
