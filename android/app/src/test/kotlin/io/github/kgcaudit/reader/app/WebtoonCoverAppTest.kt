package io.github.kgcaudit.reader.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.SemanticsMatcher
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.ui.design.COVER_ASPECT
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertTrue

/**
 * 웹툰 작품의 표지(0.47.0, 사용자 결정 ⑩): 1화 첫 그림이 긴 띠여도 표지 칸을 채우고, 위쪽 빈 바탕이 아니라 첫 칸이 보인다.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class WebtoonCoverAppTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>(StandardTestDispatcher())

    private val app = ApplicationProvider.getApplicationContext<OloApp>()
    private val shots = File(System.getProperty("reader.screenshots") ?: "build/screenshots").apply { mkdirs() }
    private val pink = 0xFFE8B4C0.toInt()

    /** 60×900: 위 200줄은 흰 바탕, 그 아래가 분홍 첫 칸. 통째로 표지에 넣으면 칸 높이에 맞춰 줄어 폭 1/10 막대가 된다. */
    private fun strip(): ByteArray {
        val bitmap = Bitmap.createBitmap(60, 900, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
        Canvas(bitmap).drawRect(0f, 200f, 60f, 900f, android.graphics.Paint().apply { color = pink })
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private val teal = 0xFF2E7D6B.toInt()

    private fun solid(w: Int, h: Int, color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Before
    fun setUp() {
        val root = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        for (ep in listOf("001화", "002화")) {
            val dir = File(root, "Webtoon/전학생/$ep").apply { mkdirs() }
            for (i in 1..3) File(dir, "$i.png").writeBytes(strip())
            // 1화 둘째 그림만 청록 — 장면에서 고른 표지가 첫 칸(분홍)과 다른 것을 색으로 안다.
            if (ep == "001화") File(dir, "2.png").writeBytes(solid(60, 900, teal))
            // 깨진 그림 하나: 이 자리를 표지로 고르면 저장하지 못했다고 말하고 그대로 있어야 한다.
            if (ep == "002화") File(dir, "4.png").writeBytes("not a picture".toByteArray())
        }
        app.container.comicRegions = false
        runBlocking {
            val data = app.container.data
            data.folders.register(FolderProvider.treeUri)
            data.rescanAll()
            data.probeComics()
        }
        compose.activityRule.scenario.recreate()
    }

    @Test
    fun `a webtoon cover fills the cover slot and shows the first panel, not the blank top`() {
        compose.waitUntil(30_000) { compose.onAllNodes(hasText("만화 1"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("만화 1"), useUnmergedTree = true)[0].performClick()
        val cover = hasContentDescription("전학생 표지")
        compose.waitUntil(30_000) { compose.onAllNodes(cover, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val box = compose.onAllNodes(cover, useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot
        val aspect = box.width / box.height
        assertTrue(kotlin.math.abs(aspect - COVER_ASPECT) < 0.08f, "표지가 칸 모양이 아니다(긴 띠를 통째로 넣었다): ${box.width}×${box.height}")
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        File(shots, "webtoon-cover.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // 표지 위쪽 1/4 지점이 분홍 — 흰 바탕부터 자르면 위 1/4 이 하얗다(200/870 줄).
        val px = bitmap.getPixel(box.center.x.toInt(), (box.top + box.height * 0.2f).toInt())
        fun d(f: (Int) -> Int) = (f(px) - f(pink)).let { it * it }
        val far = d(android.graphics.Color::red) + d(android.graphics.Color::green) + d(android.graphics.Color::blue)
        assertTrue(far < 30 * 30 * 3, "표지 위쪽이 첫 칸이 아니다(빈 바탕부터 잘랐다): #${Integer.toHexString(px)}")
    }

    private fun has(m: SemanticsMatcher) = compose.onAllNodes(m, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(m: SemanticsMatcher) = compose.waitUntil(30_000) { has(m) }
    private fun click(m: SemanticsMatcher) {
        waitFor(m)
        compose.onAllNodes(m, useUnmergedTree = true)[0].performClick()
    }

    private fun screen(): Bitmap {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        return bitmap
    }

    private fun near(px: Int, color: Int): Boolean {
        fun d(f: (Int) -> Int) = (f(px) - f(color)).let { it * it }
        return d(android.graphics.Color::red) + d(android.graphics.Color::green) + d(android.graphics.Color::blue) < 30 * 30 * 3
    }

    /** 서재의 작품 표지 한가운데 색. */
    private fun coverColor(): Int {
        val cover = hasContentDescription("전학생 표지")
        waitFor(cover)
        val box = compose.onAllNodes(cover, useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot
        return screen().getPixel(box.center.x.toInt(), box.center.y.toInt())
    }

    private fun openMenu() {
        waitFor(hasText("만화 1"))
        compose.onAllNodes(hasText("만화 1"), useUnmergedTree = true)[0].performClick()
        waitFor(hasContentDescription("전학생 표지"))
        compose.onAllNodes(hasContentDescription("전학생 표지"), useUnmergedTree = true)[0].performTouchInput { longClick() }
        waitFor(hasText("보던 장면에서 표지 고르기"))
    }

    @Test
    fun `a scene picked in the webtoon becomes the work cover, and reverting brings the first panel back`() {
        openMenu()
        assertTrue(compose.onAllNodes(hasText("원래 표지로 되돌리기"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        click(hasText("보던 장면에서 표지 고르기"))
        waitFor(hasText("표지로 쓸 부분"))
        // 틀 밖(왼쪽 끝)을 밀어 둘째 그림(청록)까지 내린다. 틀 안을 밀면 틀이 움직인다.
        repeat(6) {
            compose.onRoot().performTouchInput { swipe(Offset(width * 0.03f, height * 0.8f), Offset(width * 0.03f, height * 0.2f), 300) }
        }
        compose.mainClock.advanceTimeBy(1_000)
        val s = screen()
        assertTrue(near(s.getPixel(s.width / 2, s.height / 2), teal), "청록 그림까지 내려가지 않았다(시험 준비)")
        click(hasText("이 부분을 표지로"))
        // 고르면 서재로 돌아오고, 작품 표지가 고른 장면이다.
        compose.waitUntil(30_000) { !has(hasText("표지로 쓸 부분")) }
        compose.waitUntil(30_000) { near(coverColor(), teal) }
        File(shots, "webtoon-cover-picked.png").outputStream().use { screen().compress(Bitmap.CompressFormat.PNG, 100, it) }
        // 되돌리면 첫 칸(분홍).
        compose.onAllNodes(hasContentDescription("전학생 표지"), useUnmergedTree = true)[0].performTouchInput { longClick() }
        click(hasText("원래 표지로 되돌리기"))
        compose.waitUntil(30_000) { near(coverColor(), pink) }
    }

    @Test
    fun `a broken picture under the frame is refused and the picker stays open`() {
        openMenu()
        click(hasText("보던 장면에서 표지 고르기"))
        waitFor(hasText("표지로 쓸 부분"))
        // 2화 끝의 깨진 그림까지 — 1화 · 2화가 이어 붙어 내려간다.
        repeat(40) {
            compose.onRoot().performTouchInput { swipe(Offset(width * 0.03f, height * 0.8f), Offset(width * 0.03f, height * 0.1f), 200) }
        }
        compose.mainClock.advanceTimeBy(1_000)
        waitFor(hasText("이 그림을 그리지 못했습니다"))
        // 틀 안을 끌어 틀을 내린다 — 틀 위쪽이 깨진 그림 안에 들어가야 그 그림을 고른 것이다.
        val before = screen()
        compose.onRoot().performTouchInput { swipe(Offset(width * 0.5f, height * 0.45f), Offset(width * 0.5f, height * 0.55f), 400) }
        compose.mainClock.advanceTimeBy(500)
        val after = screen()
        fun frameTop(b: Bitmap) = (0 until b.height).first { y -> b.getPixel(b.width / 2, y).let { android.graphics.Color.red(it) > 240 && android.graphics.Color.green(it) > 240 && android.graphics.Color.blue(it) > 240 } && y > b.height / 10 }
        assertTrue(frameTop(after) > frameTop(before) + 20, "틀 안을 끌었는데 틀이 움직이지 않았다")
        click(hasText("이 부분을 표지로"))
        compose.mainClock.advanceTimeBy(500)
        waitFor(hasText("이 부분을 표지로 쓸 수 없습니다"))
        assertTrue(has(hasText("표지로 쓸 부분")), "저장하지 못했는데 고르기 화면이 닫혔다")
    }
}
