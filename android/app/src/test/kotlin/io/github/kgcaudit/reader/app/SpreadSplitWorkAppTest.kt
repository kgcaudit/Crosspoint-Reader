package io.github.kgcaudit.reader.app

import io.github.kgcaudit.reader.ui.design.WIDE_SKETCH_TOGETHER
import io.github.kgcaudit.reader.ui.design.WIDE_SKETCH_HALVES
import io.github.kgcaudit.reader.ui.design.WIDE_TOGETHER
import io.github.kgcaudit.reader.ui.design.WIDE_HALVES
import androidx.compose.ui.test.isSelected
import io.github.kgcaudit.reader.ui.design.WIDE_CHOSEN_NOTE
import io.github.kgcaudit.reader.ui.design.WIDE_AUTO_NOTE
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.document.comic.SpreadSplit
import io.github.kgcaudit.reader.ui.design.CpMetrics
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
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 펼침면 나누기를 작품마다(확정 구상안 4) · 위 막대의 반 표시(5) · 쪽 목록의 펼침면 줄(6) · 보던 반으로 다시 열기(7).
 *
 * 두 작품(별 · 달)이 같은 권을 가진다. 쪽: 1쪽 빨강(세로), 2쪽 펼침면(왼쪽 파랑 · 오른쪽 초록), 3쪽 펼침면(왼쪽 노랑 · 오른쪽
 * 보라), 4쪽 회색(세로).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
class SpreadSplitWorkAppTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>(StandardTestDispatcher())

    @After
    fun settle() = compose.waitForIdle()

    private val app = ApplicationProvider.getApplicationContext<OloApp>()
    private val comics get() = app.container.data.comics
    private val shots = File(System.getProperty("reader.screenshots") ?: "build/screenshots").apply { mkdirs() }
    private val red = 0xFFD03030.toInt()
    private val blue = 0xFF3050D0.toInt()
    private val green = 0xFF30A050.toInt()
    private val yellow = 0xFFE0C020.toInt()
    private val purple = 0xFF9040C0.toInt()
    private val gray = 0xFF808080.toInt()

    private fun png(w: Int, h: Int, left: Int, right: Int): ByteArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(b).apply {
            drawRect(0f, 0f, w / 2f, h.toFloat(), Paint().apply { color = left })
            drawRect(w / 2f, 0f, w.toFloat(), h.toFloat(), Paint().apply { color = right })
        }
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray().also { b.recycle() }
    }

    private fun volume(file: File) = ZipOutputStream(file.outputStream()).use { zip ->
        fun put(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        put("001.png", png(600, 840, red, red))
        put("002.png", png(1200, 840, blue, green))
        put("003.png", png(1200, 840, yellow, purple))
        put("004.png", png(600, 840, gray, gray))
    }

    @Before
    fun setUp() {
        val root = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        File(root, "C").mkdirs()
        volume(File(root, "C/별 01권.cbz"))
        volume(File(root, "C/달 01권.cbz"))
        runBlocking {
            val data = app.container.data
            data.folders.register(FolderProvider.treeUri)
            data.rescanAll()
            data.probeComics()
        }
    }

    /** [option] 단추가 골라져 있는가. 고름 표시는 글자를 감싼 단추에 있어 합친 나무에서 본다. */
    private fun picked(option: String) = compose.onAllNodes(hasText(option) and isSelected()).fetchSemanticsNodes().isNotEmpty()

    /** 작품의 넓은 그림을 한 장으로(나누지 않음). 0.55.1 부터 모든 만화의 켬 · 끔은 없고 작품마다만 고른다. */
    private fun oneSheet(title: String) = runBlocking { comics.setSplitSpreads(work(title), false) }

    private fun has(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(30_000) { has(matcher) }
    private fun node(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true).let { it[it.fetchSemanticsNodes().size - 1] }
    private fun click(matcher: SemanticsMatcher) {
        waitFor(matcher)
        node(matcher).performClick()
    }
    private fun back() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }
    private fun bounds(matcher: SemanticsMatcher): Rect = node(matcher).fetchSemanticsNode().boundsInRoot
    private fun startX(text: String) = node(hasText(text)).fetchSemanticsNode().positionInRoot.x
    private fun middleY(n: androidx.compose.ui.semantics.SemanticsNode) = n.positionInRoot.y + n.size.height / 2f
    private val density get() = compose.activity.resources.displayMetrics.density

    private fun work(title: String) = runBlocking { comics.works().first().single { it.title == title } }
    private fun saved(title: String) = runBlocking { comics.progressOf(work(title).entries.single().unit.id) }

    private fun screen(): Bitmap {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(b)) }
        return b
    }

    private fun save(shot: Bitmap, name: String) = File(shots, name).outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }

    private fun near(pixel: Int, color: Int): Boolean {
        fun d(c: Int) = listOf(android.graphics.Color::red, android.graphics.Color::green, android.graphics.Color::blue).sumOf { f -> (f(pixel) - f(c)).let { it * it } }
        return d(color) < 40 * 40 * 3
    }

    /** 화면 가운데 위쪽의 색이 [color] 가 될 때까지. 휴대폰은 남는 높이를 아래에 두므로(그림이 위에 붙는다) 위쪽을 본다. */
    private fun waitForColor(color: Int, message: String) {
        val ok = runCatching {
            compose.waitUntil(30_000) { screen().let { near(it.getPixel(it.width / 2, (it.height * 0.15f).toInt()), color) } }
        }.isSuccess
        if (!ok) save(screen(), "split-work-fail.png")
        assertTrue(ok, message)
    }

    private fun next() {
        compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.9f)) }
        compose.mainClock.advanceTimeBy(1_000)
    }

    /** 서재(처음) → 작품 → 1권. [fresh] 면 화면을 새로 띄워 바꿔 둔 설정을 읽게 한다. */
    private fun open(title: String, fresh: Boolean = true) {
        if (fresh) {
            compose.activityRule.scenario.recreate()
            click(hasText("만화 2"))
        }
        enter(title)
    }

    /**
     * 지금 화면에서 [title] 1권을 저장한 자리로 연다: 작품 화면이면 "1권 이어 읽기" 단추, 서재면 읽는 중 칸 또는 작품 칸 → 단추.
     * 작품 화면이 떠 있는 동안 아래 서재의 칸도 의미 나무에 남아 있다 — 그 칸을 누르면 가려진 것을 눌러 아무 일이 없다.
     */
    private fun enter(title: String) {
        val button = hasText("1권 이어 읽기", substring = true) and hasClickAction()
        val resume = hasContentDescription("$title 1권 이어 읽기")
        val work = hasContentDescription("$title 작품")
        // 읽은 자리가 없는 권은 단추가 없다 — 권 줄을 누른다.
        fun pressButton() {
            compose.waitUntil(30_000) { compose.onAllNodes(button).fetchSemanticsNodes().isNotEmpty() || has(hasText("1권")) }
            if (compose.onAllNodes(button).fetchSemanticsNodes().isNotEmpty()) {
                compose.onAllNodes(button).let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
            } else {
                click(hasText("1권"))
            }
        }
        compose.waitUntil(30_000) { compose.onAllNodes(button).fetchSemanticsNodes().isNotEmpty() || has(resume) || has(work) }
        when {
            compose.onAllNodes(button).fetchSemanticsNodes().isNotEmpty() -> pressButton()
            has(resume) -> click(resume)
            else -> {
                click(work)
                compose.mainClock.advanceTimeBy(1_000)
                pressButton()
            }
        }
        if (runCatching { waitFor(hasContentDescription("만화", substring = true)) }.isFailure) {
            save(screen(), "split-work-enter-fail.png")
            error("$title 1권이 열리지 않았다")
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    /** 만화를 닫고 같은 권을 다시 연다(화면을 새로 띄우지 않는다 — 기억한 자리가 아니라 저장한 자리로 열려야 한다). */
    private fun reopen(title: String = "별") {
        close()
        enter(title)
    }

    /** 만화를 닫는다(연 곳 — 작품 화면 · 서재 — 으로 돌아간다). */
    private fun close() {
        back()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    /** 위 막대를 띄워 부제(쪽 · 반 · 방향)가 [subtitle] 인지 보고 닫는다. 방향이 막 바뀐 때는 누름이 사라질 수 있어 다시 누른다. */
    private fun assertSubtitle(subtitle: String, message: String) {
        val seen = (1..10).any {
            compose.onRoot().performTouchInput { click(center) }
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            has(hasContentDescription("지금 위치"))
        }
        assertTrue(seen, "위 막대가 뜨지 않았다")
        val ok = runCatching { compose.waitUntil(10_000) { has(hasText(subtitle)) } }.isSuccess
        val shown = compose.onAllNodes(hasText("쪽", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
            .mapNotNull { n -> n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }?.joinToString() }
        assertTrue(ok, "$message — 부제 \"$subtitle\" 가 없다. 보인 것: $shown")
        back()
    }

    /** [label] 줄의 [option] 을 고른다. 같은 글자의 단추가 여러 줄에 있다 — 그 줄과 같은 높이의 것. */
    private fun choose(label: String, option: String) {
        waitFor(hasText(label))
        val y = middleY(node(hasText(label)).fetchSemanticsNode())
        val button = hasText(option) and hasClickAction()
        val k = compose.onAllNodes(button).fetchSemanticsNodes().indexOfFirst { abs(middleY(it) - y) < 30f }
        check(k >= 0) { "$label 줄에 $option 이 없다" }
        compose.onAllNodes(button)[k].performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun openViewPanel() {
        compose.onRoot().performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(1_000)
        click(hasText("보기"))
        waitFor(hasText("넓은 그림"))
    }

    private fun openPageList() {
        compose.onRoot().performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(1_000)
        click(hasText("쪽 목록"))
        waitFor(hasContentDescription("1쪽으로"))
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    @Test
    fun `a wide picture shows one page at a time by itself and choosing two pages keeps only that work together until set back to automatic`() {
        // 아무것도 고르지 않은 작품: 가로로 긴 2쪽은 자동으로 두 쪽 그림 — 한 쪽 보기에서 반씩 넘어간다.
        open("별")
        waitForColor(red, "1쪽이 아니다")
        next()
        waitForColor(blue, "자동인데 2쪽이 왼쪽 반(파랑)으로 나뉘지 않았다")
        assertSubtitle("2 / 4쪽 · 왼쪽 반", "자동인데 2쪽이 반으로 나뉘지 않았다")

        openViewPanel()
        // 넓은 그림은 두 쪽 보기 줄 바로 아래(확정 구상안) — 둘이 함께 "어떻게 보이나" 를 정한다. 같은 급이라 들이지 않는다.
        assertTrue(middleY(node(hasText("넓은 그림")).fetchSemanticsNode()) > middleY(node(hasText("세로에서 두 쪽 보기")).fetchSemanticsNode()))
        assertEquals(startX("세로에서 두 쪽 보기"), startX("넓은 그림"), 1f, "넓은 그림 줄이 두 쪽 보기 줄과 다른 자리에서 시작한다")
        // 자동 판단이 고른 칸이 처음부터 골라져 있고, 자동이라고 말한다. 없어진 말("펼침면 · 따름 · 이 작품")은 판에 없다.
        assertTrue(picked(WIDE_HALVES), "자동 판단(한 쪽)이 골라져 있지 않다")
        assertTrue(has(hasText(WIDE_AUTO_NOTE)), "자동으로 판단했다는 설명이 없다")
        for (gone in listOf("펼침면 나누기", "이 작품", "따름")) assertFalse(has(hasText(gone)), "없앤 말 \"$gone\" 이 남았다")
        // 단추 이름이 보이는 모습이다(0.55.3): 고른 "한 쪽" 아래 그림 줄도 한 쪽씩. 0.55.1 은 "두 쪽" 이 골라진 채 한 쪽씩 보였다.
        assertTrue(has(hasText(WIDE_SKETCH_HALVES)), "한 쪽을 골랐는데 그림 줄이 한 쪽씩이라고 하지 않는다")
        assertFalse(has(hasText(WIDE_SKETCH_TOGETHER)))

        choose("넓은 그림", WIDE_TOGETHER)
        compose.waitUntil(10_000) { work("별").splitSpreads == false }
        // 보던 2쪽이 곧바로 통째로 서고, 그림 줄도 한 장으로 바뀐다. 고른 값이라고 말하고 되돌릴 길을 준다.
        waitFor(hasText(WIDE_CHOSEN_NOTE))
        assertTrue(picked(WIDE_TOGETHER), "두 쪽을 눌렀는데 골라지지 않았다")
        assertTrue(has(hasText(WIDE_SKETCH_TOGETHER)) && !has(hasText(WIDE_SKETCH_HALVES)), "두 쪽을 골랐는데 그림 줄이 함께라고 하지 않는다")
        save(screen(), "split-work-panel.png")
        back(); back()
        assertSubtitle("2 / 4쪽", "두 쪽을 골랐는데 2쪽이 반으로 남았다")

        // 다른 작품(달)은 고르지 않아 자동 그대로 — 별에 고른 값이 번지지 않는다.
        assertEquals(null, work("달").splitSpreads)
        back(); back()
        compose.waitUntil(30_000) { has(hasContentDescription("달 작품")) }
        open("달", fresh = false)
        next()
        waitForColor(blue, "별에 고른 두 쪽이 달까지 번졌다(2쪽이 왼쪽 반이 아니다)")

        // 별을 자동으로 되돌리면 다시 두 쪽 — 반씩 넘어간다.
        back(); back()
        compose.waitUntil(30_000) { has(hasContentDescription("별 1권 이어 읽기")) }
        open("별", fresh = false)
        openViewPanel()
        node(hasText(WIDE_CHOSEN_NOTE)).performClick()
        compose.waitUntil(10_000) { work("별").splitSpreads == null }
        waitFor(hasText(WIDE_AUTO_NOTE))
        waitFor(hasText("2 / 4쪽 · 왼쪽 반"))
    }

    @Test
    fun `the page list gives a spread a full row with two halves and opens the tapped half in either direction`() {
        open("별")
        openPageList()
        save(screen(), "split-work-pages.png")
        // 세로 표지는 그대로 한 칸, 펼침면은 줄을 다 쓰고 두 반으로 나뉜다.
        assertTrue(has(hasText("1쪽")) && has(hasText("2쪽 왼쪽")) && has(hasText("2쪽 오른쪽")) && has(hasText("3쪽 왼쪽")))
        assertFalse(has(hasText("2쪽")), "펼침면이 통째로 한 칸이다")
        val cover = bounds(hasContentDescription("1쪽으로"))
        val left = bounds(hasContentDescription("2쪽 왼쪽으로"))
        val right = bounds(hasContentDescription("2쪽 오른쪽으로"))
        val gutter = CpMetrics().gutter.value * density
        val width = compose.activity.window.decorView.width
        assertEquals(cover.left, left.left, 2f, "펼침면 줄이 줄 머리에서 시작하지 않는다")
        assertEquals(width - gutter, right.right, 2f, "펼침면 줄이 줄 끝까지 닿지 않는다")
        assertEquals(left.width, right.width, 2f)
        assertTrue(left.top > cover.bottom - 1f, "펼침면이 표지와 같은 줄에 섰다")
        // 반 칸은 그림의 반과 같은 비(600×840) — 세로 칸 0.7 이 아니라 그림 비에서 나온다.
        assertTrue(left.height > left.width, "반 칸이 세로로 서지 않았다")

        // 오른쪽 반을 누르면 그 반이 열린다.
        click(hasContentDescription("2쪽 오른쪽으로"))
        waitForColor(green, "왼→오에서 2쪽 오른쪽 반을 눌렀는데 초록이 아니다")
        assertSubtitle("2 / 4쪽 · 오른쪽 반", "오른쪽 반의 부제가 아니다")
        // 지금 반(오른쪽)만 굵은 강조 테: 테 안쪽 2dp 자리가 오른쪽은 테 색, 왼쪽은 그림(파랑)이 보인다(1dp 테 안쪽).
        openPageList()
        assertCurrentHalf(right = true)

        // 오→왼: 오른쪽 반이 먼저인 책에서도 누른 반이 열린다(차례가 아니라 실제 쪽).
        back(); back()
        runBlocking { comics.setRightToLeft(work("별"), true) }
        compose.waitForIdle()
        assertSubtitle("2 / 4쪽 · 오른쪽 반 · 오→왼", "방향을 바꾼 뒤 보던 오른쪽 반에 머물지 않았다")
        openPageList()
        click(hasContentDescription("2쪽 왼쪽으로"))
        waitForColor(blue, "오→왼에서 2쪽 왼쪽 반을 눌렀는데 파랑이 아니다")
        assertSubtitle("2 / 4쪽 · 왼쪽 반 · 오→왼", "오→왼 왼쪽 반의 부제가 아니다")
        openPageList()
        assertCurrentHalf(right = false)
        click(hasContentDescription("2쪽 오른쪽으로"))
        waitForColor(green, "오→왼에서 2쪽 오른쪽 반을 눌렀는데 초록이 아니다")
        assertSubtitle("2 / 4쪽 · 오른쪽 반 · 오→왼", "오→왼 오른쪽 반의 부제가 아니다")
    }

    private fun assertCurrentHalf(right: Boolean) {
        val shot = screen()
        val inset = (2f * density).toInt()
        fun inside(r: Rect) = shot.getPixel(r.left.toInt() + inset, (r.top + r.width).toInt())
        val l = inside(bounds(hasContentDescription("2쪽 왼쪽으로")))
        val r = inside(bounds(hasContentDescription("2쪽 오른쪽으로")))
        if (right) {
            assertFalse(near(r, green), "지금 반(오른쪽)에 굵은 테가 없다")
            assertTrue(near(l, blue), "지금이 아닌 반(왼쪽)이 굵은 테로 강조됐다 #${Integer.toHexString(l)}")
        } else {
            assertFalse(near(l, blue), "지금 반(왼쪽)에 굵은 테가 없다")
            assertTrue(near(r, green), "지금이 아닌 반(오른쪽)이 굵은 테로 강조됐다 #${Integer.toHexString(r)}")
        }
    }

    @Test
    fun `the page list keeps a spread as one full-width cell when the work is not split`() {
        oneSheet("별")
        open("별")
        openPageList()
        // 나누지 않으면 펼침면은 줄을 다 쓰는 한 칸 "2쪽" — 반을 고를 수 없다.
        waitFor(hasText("2쪽"))
        assertFalse(has(hasText("2쪽 왼쪽")))
        val whole = bounds(hasContentDescription("2쪽으로"))
        val cover = bounds(hasContentDescription("1쪽으로"))
        val width = compose.activity.window.decorView.width
        assertEquals(cover.left, whole.left, 2f)
        assertEquals(width - CpMetrics().gutter.value * density, whole.right, 2f, "펼침면 칸이 줄을 다 쓰지 않는다")
        // 칸은 그림처럼 가로로 길다 — 세로 칸(0.7)에 넣으면 납작한 띠가 된다.
        assertTrue(whole.width > whole.height, "펼침면 칸이 가로로 길지 않다")
        click(hasContentDescription("2쪽으로"))
        waitFor(hasContentDescription("만화 2쪽"))
    }

    @Test
    fun `reopening lands on the same physical half even after a direction change, and on the whole page with the split off`() {
        open("별")
        next(); next()
        waitForColor(green, "2쪽 오른쪽 반(초록)에 오지 않았다")
        compose.waitUntil(10_000) { saved("별")?.half == SpreadSplit.Half.RIGHT }
        reopen()
        waitForColor(green, "다시 열자 보던 오른쪽 반이 아니다")
        assertSubtitle("2 / 4쪽 · 오른쪽 반", "다시 연 부제가 오른쪽 반이 아니다")

        // 닫은 채 방향을 바꾼다. 오른쪽 반은 이제 첫 반이지만 저장한 것은 실제 쪽이라 그대로 오른쪽 반이 뜬다.
        close()
        runBlocking { comics.setRightToLeft(work("별"), true) }
        enter("별")
        waitForColor(green, "방향을 바꾸고 다시 열자 보던 반의 반대쪽(파랑)이 떴다")
        assertSubtitle("2 / 4쪽 · 오른쪽 반 · 오→왼", "방향을 바꾸고 다시 연 부제가 오른쪽 반이 아니다")
        // 오→왼의 다음은 왼쪽 반 — 반 기억이 차례를 망가뜨리지 않았다.
        compose.onRoot().performTouchInput { click(centerLeft.copy(x = width * 0.1f)) }
        compose.mainClock.advanceTimeBy(1_000)
        waitForColor(blue, "오→왼 오른쪽 반 다음이 왼쪽 반이 아니다")
        compose.waitUntil(10_000) { saved("별")?.half == SpreadSplit.Half.LEFT }

        // 이 작품만 통째로: 저장한 반은 버리고 쪽 전체.
        close()
        runBlocking { comics.setSplitSpreads(work("별"), false) }
        enter("별")
        waitFor(hasContentDescription("만화 2쪽"))
        assertSubtitle("2 / 4쪽 · 오→왼", "나누지 않는 작품이 반으로 열렸다")
        compose.waitUntil(10_000) { saved("별")?.half == null }
    }

    @Test
    fun `a saved half for a page that is no longer a spread opens the whole page`() {
        // 망가뜨린 입력: 세로 쪽(1쪽)에 반이 적혀 있다(파일이 바뀌었거나 손상). 반을 믿으면 없는 반 조각을 찾는다.
        val unit = work("별").entries.single().unit.id
        runBlocking { comics.saveProgress(unit, 0, 4, 1L, half = SpreadSplit.Half.RIGHT) }
        open("별")
        waitForColor(red, "1쪽이 통째로 보이지 않는다")
        assertSubtitle("1 / 4쪽", "펼침면이 아닌 쪽에 반 표시가 붙었다")
        // 다음은 2쪽의 첫 반(왼→오의 왼쪽, 파랑) — 둘째 반 표시가 남아 건너뛰지 않는다.
        next()
        waitForColor(blue, "다음 조각이 2쪽 왼쪽 반이 아니다")
        compose.waitUntil(10_000) { saved("별")?.half == SpreadSplit.Half.LEFT }
    }
}
