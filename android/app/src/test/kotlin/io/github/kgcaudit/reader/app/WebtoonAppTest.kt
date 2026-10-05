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
        compose.waitUntil(30_000) { runBlocking { progressOrNull("001화")?.offset != null } }
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

    @Test
    fun `the end of a chapter is reached by scrolling and leads to the next one`() {
        openChapter("전학생", "1화")
        // 오른쪽을 눌러 한 화면씩 끝까지(사람이 읽는 길). 시험 도구의 "그 칸까지 굴리기" 는 웹툰 목록에서 끝없이 굴러 메모리가 넘쳤다.
        var taps = 0
        while (!has(hasText("이어서 읽기")) && taps < 40) {
            compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.9f)) }
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            taps++
        }
        compose.mainClock.advanceTimeBy(1_000)
        waitFor(hasText("다음: 전학생 2화"))
        compose.waitUntil(30_000) { runBlocking { progressOrNull("001화")?.finished == true } }
        click(hasText("이어서 읽기"))
        compose.mainClock.advanceTimeBy(1_000)
        waitFor(webtoon)
        compose.waitUntil(30_000) { runBlocking { app.container.data.comics.progress().first().keys.any { it.contains("002") } } }
        assertEquals(0, percent())
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
