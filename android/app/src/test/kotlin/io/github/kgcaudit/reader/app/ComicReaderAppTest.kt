package io.github.kgcaudit.reader.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ApplicationProvider
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 만화 뷰어(0.34.0, docs/COMIC_PLAN.md C1). 가짜 저장소에 진짜 cbz 를 넣고 서재에서 열어 넘긴다.
 *
 * 1권 쪽 그림은 왼쪽 반이 빨강, 오른쪽 반이 파랑이다 — 오→왼 책에서 넘김 효과만 뒤집고 그림은 바로 보이는지 화소로 본다.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class ComicReaderAppTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>(StandardTestDispatcher())

    @After
    fun settleTurn() = compose.waitForIdle()

    private val app = ApplicationProvider.getApplicationContext<OloApp>()
    private val shots = File(System.getProperty("reader.screenshots") ?: "build/screenshots").apply { mkdirs() }
    private val red = 0xFFD03030.toInt()
    private val blue = 0xFF3050D0.toInt()

    private fun png(draw: (Bitmap) -> Unit): ByteArray {
        val bitmap = Bitmap.createBitmap(60, 90, Bitmap.Config.ARGB_8888).apply(draw)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** 왼쪽 빨강 · 오른쪽 파랑. */
    private val halves = png { b -> for (x in 0 until b.width) for (y in 0 until b.height) b.setPixel(x, y, if (x < b.width / 2) red else blue) }
    private val plain = png { it.eraseColor(0xFF2E7D6B.toInt()) }

    private fun cbz(file: File, pages: List<Pair<String, ByteArray>>) {
        file.parentFile.mkdirs()
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in pages) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private lateinit var root: File

    @Before
    fun setUp() {
        root = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        val star = File(root, "Comics/별")
        cbz(File(star, "별 01권.cbz"), listOf("001.png" to halves, "002.png" to halves, "003.png" to halves))
        // 2권 둘째 쪽은 그림이 아니다(깨진 쪽).
        cbz(File(star, "별 02권.cbz"), listOf("001.png" to plain, "002.png" to "not a picture".toByteArray(), "003.png" to plain))
        cbz(File(star, "별 3-4권 합본.cbz"), listOf("3권/1.png" to plain, "3권/2.png" to plain, "4권/1.png" to plain, "4권/2.png" to plain))
        runBlocking {
            val data = app.container.data
            data.folders.register(FolderProvider.treeUri)
            data.rescanAll()
            data.probeComics()
        }
        compose.activityRule.scenario.recreate()
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
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        return bitmap
    }

    private fun shot(name: String) = File(shots, "$name.png").outputStream().use { screen().compress(Bitmap.CompressFormat.PNG, 100, it) }

    private fun openWork() {
        click(hasText("만화 1"))
        click(hasContentDescription("별 작품"))
        // 작품 화면에만 있는 것(작품 정리 단추)을 기다린다. 줄 수 머리 "3권" 은 서재 칸 꼬리표와 같은 글자다.
        waitFor(hasContentDescription("작품 정리"))
    }

    private fun openVolume(label: String) {
        openWork()
        click(hasText(label))
    }

    private fun page(n: Int) = hasContentDescription("만화 ${n}쪽")

    /** 메뉴를 열어 부제(쪽 · 방향)를 기다렸다가 닫는다. 쪽 만화에는 아래 정보 줄이 없어(0.45.0) 메뉴가 유일한 표시다. */
    private fun waitMenuSubtitle(text: String) {
        compose.mainClock.advanceTimeBy(1_000)
        compose.onRoot().performTouchInput { click(center) }
        waitFor(hasText(text))
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
    }
    private fun tapRight() = compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.9f)) }
    private fun tapLeft() = compose.onRoot().performTouchInput { click(centerLeft.copy(x = width * 0.1f)) }
    private suspend fun progressOf(name: String) = app.container.data.comics.progress().first()
        .entries.single { it.key.endsWith(android.net.Uri.encode(name)) || it.key.endsWith(name) }.value

    /** 화면 가로 [fx] · 세로 가운데 화소가 빨강에 더 가까운가. */
    private fun redAt(bitmap: Bitmap, fx: Float): Boolean {
        val p = bitmap.getPixel((bitmap.width * fx).toInt(), bitmap.height / 2)
        fun d(c: Int) = listOf(android.graphics.Color::red, android.graphics.Color::green, android.graphics.Color::blue).sumOf { f -> (f(p) - f(c)).let { it * it } }
        return d(red) < d(blue)
    }

    @Test
    fun `a volume opens from the work page and turns forward and back`() {
        openVolume("1권")
        waitFor(page(1))
        // 그림이 화면 끝까지 찬다 — 아래 정보 줄(쪽 · %)이 없다(0.45.0).
        assertTrue(!has(hasText("1 / 3")) && !has(hasText("33%")), "만화 아래에 정보 줄이 남았다")
        tapRight()
        waitFor(page(2))
        compose.onRoot().performTouchInput { swipeLeft() }
        waitFor(page(3))
        tapLeft()
        waitFor(page(2))
        compose.waitUntil(30_000) { runBlocking { progressOf("별 01권.cbz").page == 1 } }
        // 쪽 그림이 실제로 그려졌다(왼쪽 빨강 · 오른쪽 파랑).
        val s = screen()
        assertTrue(redAt(s, 0.25f) && !redAt(s, 0.75f), "쪽 그림이 그려지지 않았거나 뒤집혔다")
        shot("comic-reader")
    }

    @Test
    fun `right to left flips the tap zones and swipes but not the picture`() {
        openWork()
        // 작품의 방향을 오→왼으로(보기 판과 같은 저장 — 작품마다 기억).
        runBlocking { app.container.data.comics.setRightToLeft(app.container.data.comics.works().first().single(), true) }
        click(hasText("1권"))
        waitFor(page(1))
        waitMenuSubtitle("1 / 3쪽 · 오→왼")
        tapRight()
        // 한 번 누르기는 두 번 누르기를 기다린 뒤에 온다 — 시계를 넉넉히 흘린 뒤 본다(흘리지 않으면 누름이 아직 오지 않아
        // 어느 쪽으로 가든 통과했다).
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertTrue(has(page(1)), "오→왼인데 오른쪽을 누르니 다음 쪽으로 갔다")
        // 두 번 누르기(확대)로 읽히지 않게 사이를 둔다 — 시험 시계는 저절로 흐르지 않는다.
        compose.mainClock.advanceTimeBy(1_000)
        tapLeft()
        waitFor(page(2))
        compose.onRoot().performTouchInput { swipeRight() }
        waitFor(page(3))
        val s = screen()
        assertTrue(redAt(s, 0.25f) && !redAt(s, 0.75f), "오→왼에서 쪽 그림까지 거울처럼 뒤집혔다")
    }

    @Test
    fun `the end of a volume offers the next one and a broken page does not stop reading`() {
        openVolume("1권")
        waitFor(page(1))
        tapRight(); waitFor(page(2))
        tapRight(); waitFor(page(3))
        tapRight()
        waitFor(hasText("1권을 다 읽었습니다"))
        assertTrue(has(hasText("다음: 별 2권")))
        shot("comic-volume-end")
        runBlocking { assertTrue(progressOf("별 01권.cbz").finished, "마지막 쪽까지 읽었는데 다 읽음이 아니다") }
        click(hasText("이어서 읽기"))
        waitFor(page(1))
        tapRight()
        waitFor(page(2))
        waitFor(hasText("이 쪽을 그리지 못했습니다"))
        tapRight()
        waitFor(page(3))
    }

    @Test
    fun `closing comes back to the work page with a resume button and a reading shelf`() {
        openVolume("2권")
        waitFor(page(1))
        tapRight(); waitFor(page(2))
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        // 보던 작품 화면으로 돌아온다(서재 맨 앞이 아니라).
        waitFor(hasText("2권 이어 읽기 · 2쪽"))
        assertTrue(has(hasText("2 / 3쪽")))
        click(hasText("2권 이어 읽기 · 2쪽"))
        waitFor(page(2))
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitFor(hasText("2권 이어 읽기 · 2쪽"))
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitFor(hasText("읽는 책 · 1작품"))
        assertTrue(has(hasText("2권 · 2쪽")))
        shot("comic-reading-shelf")
    }

    @Test
    fun `a bookmark is set from the corner and listed`() {
        openVolume("1권")
        waitFor(page(1))
        tapRight(); waitFor(page(2))
        compose.seeBriefly(hasText("책갈피를 꽂았습니다")) {
            compose.onRoot().performTouchInput { click(topRight.copy(x = width - 20f, y = 40f)) }
        }
        compose.onRoot().performTouchInput { click(center) }
        click(hasText("책갈피"))
        waitFor(hasText("2쪽"))
        click(hasText("2쪽"))
        waitFor(page(2))
    }

    @Test
    fun `a volume inside an omnibus opens at its own first page`() {
        openWork()
        // 합본 줄 아래 들여쓴 "4권" — 합본 파일의 셋째 쪽부터.
        click(hasText("4권"))
        waitFor(page(3))
        waitMenuSubtitle("3 / 4쪽")
    }

    @Test
    fun `the direction choice in the view panel holds for the next volume`() {
        openVolume("1권")
        waitFor(page(1))
        compose.onRoot().performTouchInput { click(center) }
        click(hasText("보기"))
        click(hasText("오→왼"))
        compose.waitUntil(30_000) { runBlocking { app.container.data.comics.works().first().single().rightToLeft == true } }
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        click(hasText("2권"))
        waitFor(page(1))
        waitMenuSubtitle("1 / 3쪽 · 오→왼")
    }

    private fun bounds(text: String) = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot

    @Test
    @Config(qualifiers = "w900dp-h680dp-xhdpi")
    fun `on an unfolded foldable the work page has an info pane and a cover grid side by side`() {
        // 0.46.0 사용자 결정(반응형 체계 · 구상안 가안): 넓고 높은 창은 두 판 — 왼쪽 작품 정보, 오른쪽 권 표지 격자.
        // 0.45 까지는 가운데 600dp 줄 목록이라 양옆이 비고 권이 세로로 한 줄씩 섰다.
        openWork()
        waitFor(hasText("1권"))
        val density = compose.activity.resources.displayMetrics.density
        val one = bounds("1권"); val two = bounds("2권")
        assertEquals(one.top / density, two.top / density, 1f, "권 표지가 격자로 나란히 서지 않았다")
        assertTrue(two.left > one.left, "격자 차례가 어긋났다")
        // 권 격자는 왼쪽 정보 판(320dp) 오른쪽에 선다.
        assertTrue(one.left / density > 320f, "권 격자가 왼쪽 정보 판 자리에 있다: ${one.left / density}dp")
        shot("comic-work-two-pane")
    }

    @Test
    @Config(qualifiers = "w680dp-h900dp-xhdpi")
    fun `on an upright unfolded foldable the work page is one pane with a cover grid`() {
        // 중간(600–839dp)은 한 판: 위에 작품 정보, 아래 권 표지 격자(폭 전체).
        openWork()
        waitFor(hasText("1권"))
        val density = compose.activity.resources.displayMetrics.density
        val one = bounds("1권"); val two = bounds("2권")
        assertEquals(one.top / density, two.top / density, 1f, "권 표지가 격자로 나란히 서지 않았다")
        assertTrue(one.left / density < 60f, "한 판인데 격자가 왼쪽 판 자리만큼 밀렸다: ${one.left / density}dp")
    }

    @Test
    fun `particles follow the last syllable`() {
        assertEquals("을", objectParticle("1권"))
        assertEquals("를", objectParticle("48화"))
        assertEquals("을", objectParticle("외전"))
        // 숫자는 읽는 소리로: 삼(받침 있음) → 을, 이(받침 없음) → 를. 여는 중 안내와 다 읽음 판이 같은 규칙을 쓴다.
        assertEquals("을", objectParticle("Vol 3"))
        assertEquals("를", objectParticle("Extra 2"))
        assertEquals("을", objectParticle("Part 10"))
        assertEquals("을", objectParticle("Bonus"))
        assertEquals("Extra 2를 여는 중…", openingNotice("Extra 2"))
    }

    @Test
    fun `the page being read can become the work cover`() {
        // 2권(청록)을 펼쳐 둔다 — 장면 고르기는 보던 권 · 쪽에서 연다(1권 표지는 빨강 · 파랑 반반).
        openVolume("2권")
        waitFor(page(1))
        compose.mainClock.advanceTimeBy(1_000)
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        val cover = hasContentDescription("별 표지")
        waitFor(cover)
        node(cover).performTouchInput { longClick() }
        click(hasText("보던 장면에서 표지 고르기"))
        waitFor(hasText("표지로 쓸 쪽"))
        click(hasText("이 쪽을 표지로"))
        compose.waitUntil(30_000) { !has(hasText("표지로 쓸 쪽")) }
        // 서재의 작품 표지 한가운데가 청록(2권 1쪽).
        compose.waitUntil(30_000) {
            has(cover) && run {
                val box = node(cover).fetchSemanticsNode().boundsInRoot
                val p = screen().getPixel(box.center.x.toInt(), box.center.y.toInt())
                kotlin.math.abs(android.graphics.Color.red(p) - 0x2E) < 30 && kotlin.math.abs(android.graphics.Color.green(p) - 0x7D) < 30
            }
        }
    }
}
