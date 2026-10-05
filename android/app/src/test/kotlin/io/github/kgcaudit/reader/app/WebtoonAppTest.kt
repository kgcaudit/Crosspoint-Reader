package io.github.kgcaudit.reader.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.geometry.Offset
import io.github.kgcaudit.reader.ui.design.ScreenPrefs
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
 * 웹툰 보기(0.35.0, docs/COMIC_PLAN.md C2). 긴 그림이 든 화 폴더 · 압축을 서재에서 열어 밀어 내린다.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class WebtoonAppTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>(StandardTestDispatcher())

    @After
    fun settleTurn() = compose.waitForIdle()

    private val app = ApplicationProvider.getApplicationContext<OloApp>()
    private val shots = File(System.getProperty("reader.screenshots") ?: "build/screenshots").apply { mkdirs() }
    private val pink = 0xFFE8B4C0.toInt()
    private val teal = 0xFF2E7D6B.toInt()

    private fun png(w: Int, h: Int, color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Before
    fun setUp() {
        val root = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        for (ep in listOf("001화", "002화")) {
            val dir = File(root, "Webtoon/전학생/$ep").apply { mkdirs() }
            for (i in 1..3) File(dir, "$i.png").writeBytes(png(60, 400, pink))
        }
        // 한 장이 아주 긴 화: 60×6000(띠 셋). 화면 폭 786px 로 늘리면 78600px — 통째로 풀면 안 되는 크기.
        File(root, "Long").mkdirs()
        ZipOutputStream(File(root, "Long/긴 그림 01화.cbz").outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("001.png")); zip.write(png(60, 6000, teal)); zip.closeEntry()
            zip.putNextEntry(ZipEntry("002.png")); zip.write(png(60, 400, teal)); zip.closeEntry()
        }
        // Robolectric 의 띠 풀기는 빈 그림을 준다 — 대신 길(전체를 줄여 풀고 자르기)로 그린다. 띠 나누기 · 배치는 그대로 시험된다.
        app.container.comicRegions = false
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

    private val webtoon = SemanticsMatcher("webtoon view") { n ->
        n.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith("웹툰 ") && it.endsWith("%") } == true
    }

    /** 지금 보이는 자리(%). */
    private fun percent(): Int {
        waitFor(webtoon)
        val d = node(webtoon).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].first { it.startsWith("웹툰 ") }
        return d.removePrefix("웹툰 ").removeSuffix("%").toInt()
    }

    private fun screen(): Bitmap {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        return bitmap
    }

    /** 화면 [fx],[fy] 자리에 [color] 가 그려질 때까지 기다린다 — 띠는 입출력 스레드에서 풀려 조금 늦게 온다. */
    private fun waitForColor(fx: Float, fy: Float, color: Int, message: String) {
        val ok = runCatching {
            compose.waitUntil(30_000) { screen().let { near(it.getPixel((it.width * fx).toInt(), (it.height * fy).toInt()), color) } }
        }.isSuccess
        if (!ok) File(shots, "webtoon-fail.png").outputStream().use { screen().compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(ok, message)
    }

    private fun near(pixel: Int, color: Int): Boolean {
        fun d(c: Int) = listOf(android.graphics.Color::red, android.graphics.Color::green, android.graphics.Color::blue).sumOf { f -> (f(pixel) - f(c)).let { it * it } }
        return d(color) < 30 * 30 * 3
    }

    private fun openChapter(work: String, label: String) {
        click(hasText("만화 2"))
        click(hasContentDescription("$work 작품"))
        click(hasText(label))
        waitFor(webtoon)
    }

    /** [name] 화의 진도. 아직 적지 않았으면 null — 기다리는 쪽이 다시 묻는다. */
    private suspend fun progressOrNull(name: String) = app.container.data.comics.progress().first()
        .entries.singleOrNull { it.key.endsWith(android.net.Uri.encode(name)) || it.key.endsWith(name) }?.value

    private suspend fun progress(name: String) = progressOrNull(name)!!

    private fun back() = compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

    @Test
    fun `tall pictures open as a webtoon that scrolls down, and a tap goes one screen`() {
        openChapter("전학생", "1화")
        assertEquals(0, percent())
        compose.onRoot().performTouchInput { swipeUp() }
        compose.waitForIdle()
        val swiped = percent()
        assertTrue(swiped > 0, "밀어도 내려가지 않았다")
        compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.9f)) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertTrue(percent() > swiped, "오른쪽을 눌러도 한 화면 내려가지 않았다")
        // 그림이 실제로 그려졌다.
        waitForColor(0.5f, 0.5f, pink, "웹툰 그림이 그려지지 않았다")
        File(shots, "webtoon.png").outputStream().use { screen().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun `the place is kept as picture and fraction and comes back`() {
        openChapter("전학생", "1화")
        compose.onRoot().performTouchInput { swipeUp() }
        compose.mainClock.advanceTimeBy(1_000)
        // 연 자리(0)부터 먼저 적힌다 — 민 뒤의 자리가 적힐 때까지 기다린다.
        compose.waitUntil(30_000) { runBlocking { progressOrNull("001화")?.let { it.page > 0 || (it.offset ?: 0f) > 0f } == true } }
        val saved = runBlocking { progress("001화") }
        val before = percent()
        assertTrue(before > 0)
        back()
        click(hasText("1화 이어 읽기 · ${saved.page + 1}쪽"))
        waitFor(webtoon)
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(before, percent(), 2, "다시 연 웹툰이 읽던 자리가 아니다")
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Int, message: String) =
        assertTrue(kotlin.math.abs(expected - actual) <= tolerance, "$message: $expected vs $actual")

    /** 오른쪽을 눌러 한 화면씩(사람이 읽는 길). 시험 도구의 "그 칸까지 굴리기" 는 웹툰 목록에서 끝없이 굴러 메모리가 넘쳤다. */
    private fun tapUntil(limit: Int = 60, done: () -> Boolean) {
        var taps = 0
        while (!done() && taps < limit) {
            compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.9f)) }
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            taps++
        }
        assertTrue(done(), "$limit 번 눌러도 닿지 않았다")
    }

    /** 메뉴의 부제("2화 · 0%"). 메뉴를 열어 읽고 닫는다. */
    private fun subtitle(): String {
        compose.onRoot().performTouchInput { click(center) }
        val label = SemanticsMatcher("subtitle") { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text.matches(Regex("\\d+화 · \\d+%")) } == true }
        waitFor(label)
        val text = node(label).fetchSemanticsNode().config[SemanticsProperties.Text].first().text
        compose.onRoot().performTouchInput { click(Offset(width * 0.5f, height * 0.3f)) }
        compose.waitForIdle()
        return text
    }

    @Test
    fun `the next episode is attached below a seam and reading carries on into it`() {
        // 0.46 까지는 화 끝에서 "이어서 읽기" 카드를 눌러야 다음 화가 열렸다 — 몰아 보기가 끊겼다(사용자 결정 ③ B).
        openChapter("전학생", "1화")
        tapUntil { has(hasText("1화 끝")) }
        assertTrue(has(hasText("전학생 · 2화 가운데 2번째")), "경계 띠에 다음 화가 없다")
        assertTrue(!has(hasText("이어서 읽기")), "다음 화가 있는데 끝 판이 나왔다")
        // 경계 띠를 지나 2화로: 1화는 다 읽음, 2화의 자리가 적히고 메뉴 · 책갈피가 2화를 따른다.
        tapUntil { runBlocking { progressOrNull("002화") } != null }
        compose.waitUntil(30_000) { runBlocking { progressOrNull("001화")?.finished == true } }
        assertTrue(subtitle().startsWith("2화 · "), "다음 화로 넘어왔는데 메뉴가 1화를 말한다")
        // 마지막 화의 끝은 끝 판.
        tapUntil { has(hasText("마지막 화입니다")) }
        compose.waitUntil(30_000) { runBlocking { progressOrNull("002화")?.finished == true } }
        File(shots, "webtoon-seam.png").outputStream().use { screen().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun `a bookmark set after the seam belongs to the next episode`() {
        // 화면이 다음 화로 넘어간 뒤 꽂은 책갈피가 처음 연 화에 꽂히면, 2화 책갈피 목록에 없고 1화 엉뚱한 그림에 있다.
        openChapter("전학생", "1화")
        tapUntil { runBlocking { progressOrNull("002화") } != null }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onRoot().performTouchInput { click(center) }
        click(hasContentDescription("책갈피 꽂기"))
        compose.waitUntil(30_000) {
            runBlocking { app.container.data.comics.bookmarks(unitIdOf("002화")).first().isNotEmpty() }
        }
        assertTrue(runBlocking { app.container.data.comics.bookmarks(unitIdOf("001화")).first().isEmpty() }, "다음 화에서 꽂은 책갈피가 1화에 들어갔다")
        // 꽂은 것이 보인다: 지금 화가 2화로 바뀌어 있어야 2화의 책갈피를 읽는다.
        compose.mainClock.advanceTimeBy(2_000)
        waitFor(hasContentDescription("책갈피 빼기"))
    }

    private fun unitIdOf(name: String) = runBlocking { app.container.data.comics.progress().first().keys.single { it.endsWith(android.net.Uri.encode(name)) || it.endsWith(name) } }

    @Test
    fun `the episode list shows each episode and jumps to the one picked`() {
        openChapter("전학생", "2화")
        compose.onRoot().performTouchInput { click(center) }
        click(hasText("회차"))
        waitFor(hasText("전학생 · 2화"))
        assertTrue(has(hasText("1화")) && has(hasText("2화")))
        click(hasText("1화"))
        compose.mainClock.advanceTimeBy(1_000)
        waitFor(webtoon)
        compose.waitUntil(30_000) { runBlocking { progressOrNull("001화") } != null }
        assertTrue(subtitle().startsWith("1화 · "), "회차 목록에서 고른 화가 열리지 않았다")
    }

    /** 시계를 손으로 한 프레임씩 돌리며 [condition] 을 기다린다. 자동 스크롤 중에는 "한가해질 때까지" 기다리는 길이 없다. */
    private fun ticking(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < until) { "끝내 맞지 않았다" }
            compose.mainClock.advanceTimeByFrame()
            Thread.sleep(5)
        }
    }

    /** 시계를 [ms] 만큼 프레임 단위로 돌린다. */
    private fun frames(ms: Long) = repeat((ms / 16).toInt()) { compose.mainClock.advanceTimeByFrame() }

    @Test
    fun `auto scroll moves down by itself, stops on a touch and closes with the cross`() {
        openChapter("전학생", "1화")
        compose.onRoot().performTouchInput { click(center) }
        waitFor(hasText("자동"))
        // 자동 스크롤은 그림마다 다음 그림을 청한다 — 시계를 손으로 돌려야 "한가해질 때까지" 기다리다 화 끝까지 흘러가지 않는다.
        compose.mainClock.autoAdvance = false
        try {
            node(hasText("자동")).performClick()
            ticking { has(hasContentDescription("자동 스크롤 멈춤")) }
            val start = percent()
            frames(5_000)
            val moved = percent()
            assertTrue(moved > start, "자동 스크롤이 내려가지 않았다: $start → $moved")
            // 손을 대면 멈춘다. 그 누르기는 멈춤으로만 — 넘김 구역을 눌렀어도 한 화면 내려가지 않는다.
            compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.9f)) }
            ticking { has(hasContentDescription("자동 스크롤 이어 하기")) }
            frames(1_000)
            val paused = percent()
            // 이 화는 한 화면이 10% 남짓이다 — 3% 넘게 움직였으면 넘김까지 했다.
            assertTrue(paused - moved < 3, "멈추려고 누른 손에 한 화면이 더 내려갔다: $moved → $paused")
            frames(5_000)
            assertEquals(paused, percent(), "멈췄는데 내려갔다")
            node(hasContentDescription("자동 스크롤 끄기")).performClick()
            ticking { !has(hasText("자동 스크롤", substring = true)) }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun `two fingers zoom in and the zoom stays after the fingers lift`() {
        openChapter("전학생", "1화")
        compose.mainClock.advanceTimeBy(1_000)
        compose.onRoot().performTouchInput {
            pinch(
                start0 = Offset(width * 0.45f, height * 0.5f), end0 = Offset(width * 0.25f, height * 0.5f),
                start1 = Offset(width * 0.55f, height * 0.5f), end1 = Offset(width * 0.75f, height * 0.5f),
            )
        }
        compose.waitForIdle()
        waitFor(hasText("배", substring = true))
        val label = node(hasText("배", substring = true)).fetchSemanticsNode().config[SemanticsProperties.Text].first().text
        assertTrue(label.removeSuffix("배").toFloat() > 1.5f, "두 손가락으로 벌렸는데 커지지 않았다: $label")
        // 한 손가락으로 밀면 여전히 내려간다(확대가 손짓을 먹지 않는다).
        val before = percent()
        compose.onRoot().performTouchInput { swipeUp() }
        compose.mainClock.advanceTimeBy(1_000)
        assertTrue(percent() > before, "확대한 채로 밀어 내려가지 않았다")
    }

    @Test
    fun `a picture far taller than any bitmap is drawn strip by strip`() {
        click(hasText("만화 2"))
        click(hasContentDescription("긴 그림 작품"))
        click(hasText("1화"))
        waitFor(webtoon)
        // 긴 그림 한가운데쯤(둘째 띠)으로.
        repeat(3) { compose.onRoot().performTouchInput { swipeUp() } }
        compose.mainClock.advanceTimeBy(1_000)
        waitForColor(0.5f, 0.5f, teal, "긴 그림의 띠가 그려지지 않았다")
        assertTrue(percent() in 1..99)
    }

    @Test
    fun `choosing page view switches the reader and is kept for the work`() {
        openChapter("전학생", "1화")
        compose.onRoot().performTouchInput { click(center) }
        click(hasText("보기"))
        click(hasText("쪽 넘김"))
        waitFor(hasContentDescription("만화 1쪽"))
        compose.waitUntil(30_000) {
            runBlocking { app.container.data.comics.works().first().single { it.title == "전학생" }.view == io.github.kgcaudit.reader.document.comic.ComicView.PAGE }
        }
        compose.onRoot().performTouchInput { click(center) }
        click(hasText("보기"))
        click(hasText("자동"))
        waitFor(webtoon)
    }

    @Test
    fun `webtoon settings show only what a webtoon follows`() {
        // 0.41 까지 웹툰 설정에는 두 쪽 보기 · 넘김 효과 · 자동 넘김 · 왼쪽 끝 밝기가 보였는데 바꿔도 아무 일이 없었다.
        openChapter("전학생", "1화")
        compose.onRoot().performTouchInput { click(center) }
        click(hasText("보기"))
        click(hasText("모든 보기 설정"))
        waitFor(hasText("하단 정보"))
        for (row in listOf("가로에서 두 쪽 보기", "세로에서 두 쪽 보기", "두 쪽 보기에서 표지", "넘김 효과", "넘김 소리", "자동 넘김", "왼쪽 끝을 밀어 밝기 조절")) {
            assertTrue(!has(hasText(row)), "웹툰 설정에 따르지 않는 줄이 있다: $row")
        }
        assertTrue(has(hasText("화면 켜짐 유지")))
    }

    @Test
    fun `the picture width can be narrowed on a phone and is kept apart from the wide screen value`() {
        // 0.46 까지 휴대폰 세로는 늘 꽉 채웠다 — 기기에 따라 컷이 커져 웹툰 느낌이 옅어졌다(사용자 결정 ⑧).
        openChapter("전학생", "1화")
        compose.mainClock.advanceTimeBy(1_000)
        waitForColor(0.04f, 0.5f, pink, "처음에는 꽉 채운다")
        compose.onRoot().performTouchInput { click(center) }
        click(hasText("보기"))
        waitFor(hasContentDescription("그림 폭"))
        // 막대 1/4 자리를 누르면 55%. 가운데(70%)는 넓은 화면 기본값과 같아, 넓은 값을 덮어써도 시험이 알아채지 못했다.
        compose.onAllNodes(hasContentDescription("그림 폭"), useUnmergedTree = true)[0].performTouchInput { click(Offset(width * 0.25f, centerY)) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitUntil(5_000) { app.container.prefs.load().screen.webtoonColumnNarrow < 100 }
        val saved = app.container.prefs.load().screen
        assertTrue(saved.webtoonColumnNarrow in 50..60, "누른 자리만큼 줄지 않았다: ${saved.webtoonColumnNarrow}")
        assertEquals(ScreenPrefs.DEFAULT_WEBTOON_COLUMN, saved.webtoonColumn, "휴대폰에서 바꿨는데 넓은 화면 값이 바뀌었다")
        // 판을 닫으면 양옆이 비어 있다 — 그림이 정말 좁아졌다.
        compose.onRoot().performTouchInput { click(Offset(width * 0.5f, height * 0.2f)) }
        compose.mainClock.advanceTimeBy(1_000)
        waitForColor(0.5f, 0.3f, pink, "좁힌 그림이 가운데에 없다")
        val s = screen()
        assertTrue(!near(s.getPixel((s.width * 0.04f).toInt(), (s.height * 0.3f).toInt()), pink), "그림 폭을 줄였는데 꽉 채웠다")
        File(shots, "webtoon-narrow.png").outputStream().use { s.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun `changing the picture width never leaves the picture blank for a moment`() {
        // 폭을 바꾸면 띠가 새 폭으로 다시 풀리는 동안(수십 ms) 칸이 비어 화면이 깜박였다(사용자 보고, 녹화의 4.9초).
        openChapter("전학생", "1화")
        compose.mainClock.advanceTimeBy(1_000)
        waitForColor(0.5f, 0.3f, pink, "처음 그림이 없다")
        compose.onRoot().performTouchInput { click(center) }
        click(hasText("보기"))
        waitFor(hasContentDescription("그림 폭"))
        // 휴대폰처럼 새 폭의 띠가 풀리는 데 시간이 걸리게 한다(2초). 그 사이의 화면을 본다.
        app.container.comicStripDelayMs = 2_000
        try {
            compose.onAllNodes(hasContentDescription("그림 폭"), useUnmergedTree = true)[0].performTouchInput { click(Offset(width * 0.25f, centerY)) }
            compose.mainClock.advanceTimeBy(100)
            compose.waitForIdle()
            val view = compose.activity.window.decorView
            val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            compose.runOnUiThread { view.draw(Canvas(b)) }
            // 폭은 이미 줄었다(양옆이 비었다) — 그런데 새 폭의 그림은 아직 풀리는 중이다.
            assertTrue(!near(b.getPixel((b.width * 0.04f).toInt(), (b.height * 0.3f).toInt()), pink), "폭이 바뀌지 않았다 — 시험이 깜박일 자리를 보지 못한다")
            assertTrue(near(b.getPixel(b.width / 2, (b.height * 0.3f).toInt()), pink), "새 폭의 그림이 풀리는 동안 그림 칸이 비었다")
        } finally {
            app.container.comicStripDelayMs = 0
        }
    }

    @Test
    @Config(qualifiers = "w851dp-h393dp-xhdpi")
    fun `a wide screen shows the webtoon as a centred column`() {
        openChapter("전학생", "1화")
        compose.mainClock.advanceTimeBy(1_000)
        waitForColor(0.5f, 0.5f, pink, "가운데에 그림이 없다")
        val s = screen()
        assertTrue(!near(s.getPixel((s.width * 0.05f).toInt(), s.height / 2), pink), "가로 화면에서 꽉 채웠다 — 기둥이 아니다")
        File(shots, "webtoon-wide.png").outputStream().use { s.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
