package io.github.kgcaudit.reader.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
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
import io.github.kgcaudit.reader.ui.design.PageTurn
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 펼침면 나누기를 실제 스캔본 크기(3000×2100 펼침면)로 본다. 작은 그림으로는 반 조각이 저장소에 모두 들어가 드러나지 않던
 * 것들: 넘김 효과가 반 조각을 그리는지, 방향을 바꿔도 보던 반에 머무는지, 마지막 펼침면의 첫 반에서 다 읽음이 되지 않는지.
 *
 * 쪽: 1쪽 빨강(세로), 2쪽 펼침면(왼쪽 파랑 · 오른쪽 초록), 3쪽 펼침면(왼쪽 노랑 · 오른쪽 보라) — 마지막 쪽이 펼침면이다.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class SpreadSplitScanAppTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>(StandardTestDispatcher())

    @After
    fun settleTurn() {
        // 앱 그릇은 시험 사이에 남는다 — 늦춘 풀기를 돌려놓지 않으면 다음 시험의 쪽이 풀리지 않는다.
        app.container.comicPageDelayMs = 0
        compose.waitForIdle()
    }

    private val app = ApplicationProvider.getApplicationContext<OloApp>()
    private val shots = File(System.getProperty("reader.screenshots") ?: "build/screenshots").apply { mkdirs() }
    private val red = 0xFFD03030.toInt()
    private val blue = 0xFF3050D0.toInt()
    private val green = 0xFF30A050.toInt()
    private val yellow = 0xFFE0C020.toInt()
    private val purple = 0xFF9040C0.toInt()

    private fun png(w: Int, h: Int, left: Int, right: Int): ByteArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(b).apply {
            drawRect(0f, 0f, w / 2f, h.toFloat(), Paint().apply { color = left })
            drawRect(w / 2f, 0f, w.toFloat(), h.toFloat(), Paint().apply { color = right })
        }
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray().also { b.recycle() }
    }

    @Before
    fun setUp() {
        val root = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        File(root, "C").mkdirs()
        ZipOutputStream(File(root, "C/별 01권.cbz").outputStream()).use { zip ->
            fun put(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            put("001.png", png(1500, 2100, red, red))
            put("002.png", png(3000, 2100, blue, green))
            put("003.png", png(3000, 2100, yellow, purple))
        }
        runBlocking {
            val data = app.container.data
            data.folders.register(FolderProvider.treeUri)
            data.rescanAll()
            data.probeComics()
        }
    }

    private fun useTurn(effect: PageTurn) {
        runBlocking {
            val p = app.container.prefs.load()
            app.container.prefs.save(p.copy(screen = p.screen.copy(pageTurn = effect)))
        }
    }

    private fun has(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(30_000) { has(matcher) }
    private fun node(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true).let { it[it.fetchSemanticsNodes().size - 1] }
    private fun click(matcher: SemanticsMatcher) {
        waitFor(matcher)
        node(matcher).performClick()
    }

    private fun screen(): Bitmap {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(b)) }
        return b
    }

    private fun near(pixel: Int, color: Int): Boolean {
        fun d(c: Int) = listOf(android.graphics.Color::red, android.graphics.Color::green, android.graphics.Color::blue).sumOf { f -> (f(pixel) - f(c)).let { it * it } }
        return d(color) < 40 * 40 * 3
    }

    private fun at(shot: Bitmap, fx: Float, fy: Float) = shot.getPixel((shot.width * fx).toInt(), (shot.height * fy).toInt())

    private fun waitForColor(color: Int, message: String) {
        val ok = runCatching { compose.waitUntil(30_000) { near(at(screen(), 0.5f, 0.3f), color) } }.isSuccess
        if (!ok) File(shots, "split-scan-fail.png").outputStream().use { screen().compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(ok, message)
    }

    private fun next(rtl: Boolean = false) {
        compose.onRoot().performTouchInput { click(if (rtl) centerLeft.copy(x = width * 0.1f) else centerRight.copy(x = width * 0.9f)) }
        compose.mainClock.advanceTimeBy(1_000)
    }

    private fun open() {
        compose.activityRule.scenario.recreate()
        click(hasText("만화 1"))
        click(hasContentDescription("별 작품"))
        click(hasText("1권"))
        waitFor(hasContentDescription("만화 1쪽"))
    }

    /**
     * 이 작품의 넘기는 방향을 바꾸고 화면이 받아들일 때까지 기다린다(위 막대의 "오→왼" 표시로 본다). 저장소를 거쳐 돌아오므로
     * 기다리지 않으면 아직 옛 방향인 화면을 넘긴다 — 옛 방향의 "앞 쪽" 누름이 마침 같은 반으로 가면 시험이 거짓으로 통과한다.
     */
    private fun direction(rightToLeft: Boolean) {
        runBlocking { app.container.data.comics.let { c -> c.setRightToLeft(c.works().first().single(), rightToLeft) } }
        // 방향이 바뀌면 누름 처리기가 새로 시작해, 그 순간의 누름은 사라진다 — 막대가 뜰 때까지 다시 누른다.
        val shown = (1..10).any {
            compose.onRoot().performTouchInput { click(center) }
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            val bar = has(hasContentDescription("지금 위치"))
            val ok = bar && has(hasText("오→왼", substring = true)) == rightToLeft
            if (bar) compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            ok
        }
        assertTrue(shown, "넘기는 방향이 화면에 닿지 않았다")
    }

    private suspend fun saved() = app.container.data.comics.progress().first().values.single()

    /**
     * 끄는 중의 한 장면에 지금 반(파랑)과 다음 반(초록)이 함께 그려지는가. 반 조각 저장소에 든 반이 이웃 반에 밀려나면 넘기는
     * 종이에 아무것도 그려지지 않았다(밀기만 됐다).
     */
    private fun dragHalfToHalf(effect: PageTurn) {
        useTurn(effect)
        open()
        waitForColor(red, "1쪽이 아니다")
        next()
        waitForColor(blue, "2쪽 왼쪽 반(파랑)이 아니다")
        // 앞뒤 조각을 미리 풀 틈을 준 뒤, 이제부터 쪽 받아 오기는 오래 걸린다 — 넘김 효과는 저장소에 남은 그림만 곧바로 그린다.
        // Robolectric 에서는 받아 오기가 화면을 뜨기 전에 끝나, 저장소에서 밀려난 반도 그려져 버렸다.
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        app.container.comicPageDelayMs = 60_000
        compose.onRoot().performTouchInput { down(center); moveBy(Offset(-width * 0.2f, 0f)); moveBy(Offset(-width * 0.2f, 0f)) }
        compose.mainClock.advanceTimeBy(100)
        val mid = screen()
        File(shots, "split-scan-${effect.name.lowercase()}.png").outputStream().use { mid.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // 왼쪽 위는 아직 지금 반(파랑), 오른쪽 아래 걷힌 자리(말림) · 들어오는 종이(덮기)는 다음 반(초록).
        assertTrue(near(at(mid, 0.1f, 0.3f), blue), "$effect: 넘기는 동안 지금 반이 그려지지 않았다 #${Integer.toHexString(at(mid, 0.1f, 0.3f))}")
        assertTrue(near(at(mid, 0.97f, 0.6f), green), "$effect: 넘기는 동안 다음 반이 그려지지 않았다 #${Integer.toHexString(at(mid, 0.97f, 0.6f))}")
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1_000)
        waitForColor(green, "$effect: 놓은 뒤 오른쪽 반(초록)에 자리 잡지 않았다")
    }

    @Test
    fun `curling from one half of a scan to the other draws both halves`() = dragHalfToHalf(PageTurn.Curl)

    @Test
    fun `covering from one half of a scan to the other draws both halves`() = dragHalfToHalf(PageTurn.Cover)

    @Test
    fun `changing the reading direction keeps the same half of the spread on screen`() {
        open()
        next(); next()
        waitForColor(green, "왼→오 둘째 반(오른쪽 · 초록)에 오지 않았다")
        // 오→왼으로 바꾼다. 둘째 반이던 오른쪽 반은 이제 첫 반이다 — 화면은 그대로 초록이어야 한다.
        direction(rightToLeft = true)
        waitForColor(green, "방향을 바꾸자 보던 반의 반대쪽(파랑)으로 건너뛰었다")
        // 오→왼에서 다음은 왼쪽 반(파랑).
        next(rtl = true)
        waitForColor(blue, "오→왼에서 오른쪽 반 다음이 왼쪽 반이 아니다")
        // 되돌려도 같은 반에 머문다.
        direction(rightToLeft = false)
        waitForColor(blue, "왼→오로 되돌리자 보던 반을 떠났다")
    }

    @Test
    fun `after changing direction on a plain page, the next spread starts from the new reading side`() {
        // 밀기는 앞뒤 판을 미리 그려 둔다. 방향을 바꾸면 다음 조각(같은 번호)이 펼침면의 다른 반이 되는데, 번호만 열쇠로 둔
        // 그림이 남아 옛 차례의 반(왼쪽 · 파랑)이 밀려 들어왔다. 위 막대를 열면 화면 크기가 바뀌어 그림을 새로 그리므로 막대를
        // 열지 않고 바꾼다.
        useTurn(PageTurn.Slide)
        open()
        waitForColor(red, "1쪽이 아니다")
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        runBlocking { app.container.data.comics.let { c -> c.setRightToLeft(c.works().first().single(), true) } }
        // 왼쪽 누름은 왼→오의 첫 쪽에서는 아무 일이 없고, 오→왼이 화면에 닿으면 다음 판이다 — 넘어갈 때까지 누른다.
        val turned = (1..10).any {
            next(rtl = true)
            compose.waitForIdle()
            has(hasContentDescription("만화 2쪽"))
        }
        assertTrue(turned, "오→왼으로 바꾼 뒤 왼쪽 누름이 넘기지 않았다")
        waitForColor(green, "오→왼으로 바꾼 뒤 펼침면의 첫 반이 오른쪽 반(초록)이 아니다")
    }

    @Test
    fun `the first half of a last spread is not the end of the volume`() {
        open()
        repeat(3) { next() }
        waitForColor(yellow, "마지막 펼침면의 첫 반(노랑)에 오지 않았다")
        compose.waitUntil(10_000) { runBlocking { saved().page } == 2 }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        // 책의 마지막 쪽이지만 반만 봤다 — 다 읽음이 아니다.
        assertFalse(runBlocking { saved().finished }, "마지막 펼침면의 첫 반에서 다 읽음이 됐다")
        assertFalse(has(hasText("1권을 다 읽었습니다")))
        next()
        waitForColor(purple, "마지막 반(보라)에 오지 않았다")
        compose.waitUntil(10_000) { runBlocking { saved().finished } }
        assertFalse(has(hasText("1권을 다 읽었습니다")), "마지막 반을 보자마자 끝 판이 덮었다")
        next()
        waitFor(hasText("1권을 다 읽었습니다"))
    }
}
