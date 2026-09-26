package io.github.kgcaudit.reader.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.document.HighlightColor
import io.github.kgcaudit.reader.document.pdf.TestPdf
import io.github.kgcaudit.reader.document.pdf.TestPdf.Companion.pages
import io.github.kgcaudit.reader.document.pdf.TestPdf.Companion.utf16
import io.github.kgcaudit.reader.listen.ListenHub
import io.github.kgcaudit.reader.listen.ListenKit
import io.github.kgcaudit.reader.pdf.PageText
import io.github.kgcaudit.reader.reflow.ReaderPrefs
import io.github.kgcaudit.reader.ui.design.PaperTheme
import io.github.kgcaudit.reader.ui.design.Pen
import io.github.kgcaudit.reader.ui.design.ScreenPrefs
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
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PDF 찾기 · 형광펜 · 듣기(0.18.0). 엔진은 글자 층을 내주는 가짜([DrawnPdf], 안드로이드 15+ 흉내)이고 나머지는
 * 실제 앱이다. 지면을 회색으로 두어 흰 PDF 쪽의 자리를 스크린샷에서 찾는다 — 그래야 "이 낱말을 길게 누른다" 를
 * 화면 좌표로 셀 수 있다.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class PdfTextAppTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>(StandardTestDispatcher())

    private val app get() = ApplicationProvider.getApplicationContext<OloApp>()
    private val density get() = compose.activity.resources.displayMetrics.density
    private val shots = File(System.getProperty("reader.screenshots") ?: "build/screenshots").apply { mkdirs() }
    private val speakers = ArrayList<ListenAppTest.FakeSpeaker>()
    private val speaker get() = speakers.last()

    /** 쪽마다 본문. 3쪽에 "책갈피" 둘, 5쪽에 하나. */
    private val body: (Int) -> List<String> = { page ->
        when (page) {
            0 -> listOf("첫 쪽이다. 작은 글자는 확대해서 읽는다.")
            2 -> listOf("오른쪽 위를 누르면 책갈피가 꽂힌다.", "꽂은 책갈피는 독서노트에 모인다.")
            4 -> listOf("책갈피는 쪽 번호로 저장된다.")
            else -> listOf("${page + 1}번째 쪽의 본문이다.")
        }
    }
    private var scanned: (Int) -> Boolean = { false }
    /** 화면과 같은 글자 층(글자 자리를 셈하려고). 앱의 엔진과 같은 가짜를 따로 하나 만든다. */
    private val layout get() = DrawnPdf(null, 6, runningHead = true, body = body)

    @Before
    fun setUp() {
        val folder = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        File(folder, "문서").mkdirs()
        File(folder, "문서/설명서.pdf").writeBytes(manual())
        app.container.pdfEngine = { DrawnPdf(it, pageCount = 6, runningHead = true, scanned = scanned, body = body) }
        app.container.listenKit = ListenKit(
            speaker = { engine -> ListenAppTest.FakeSpeaker(engine).also { speakers += it } },
            voices = { emptyList() },
        )
        runBlocking { app.container.data.folders.register(FolderProvider.treeUri) }
        // 회색 지면: 흰 PDF 쪽의 가장자리가 보인다.
        app.container.prefs.save(ReaderPrefs(screen = ScreenPrefs(theme = PaperTheme.Gray)))
        compose.activityRule.scenario.recreate()
        waitFor(hasText("설명서.pdf"))
    }

    @After
    fun tearDown() {
        compose.runOnUiThread { ListenHub.detach() }
    }

    private fun open() {
        node(hasText("설명서.pdf")).performClick()
        waitFor(hasText("1 / 6"))
    }

    @Test
    fun `search finds a word on every page, lists it by page and lands on it with the result bar`() {
        open()
        compose.onRoot().performTouchInput { click(center) }
        waitFor(hasContentDescription("본문에서 찾기"))
        node(hasContentDescription("본문에서 찾기")).performClick()
        waitFor(hasContentDescription("찾을 말"))
        node(hasContentDescription("찾을 말")).performTextInput("책갈피")
        node(hasContentDescription("찾을 말")).performImeAction()
        // 3쪽에 둘, 5쪽에 하나. 결과 자리는 % 가 아니라 쪽.
        waitFor(hasText("3곳 · 2쪽에서"))
        assertEquals(2, compose.onAllNodes(hasText("3쪽"), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertTrue(hasNode(hasText("5쪽")))
        shot("90-pdf-search")
        compose.onAllNodes(hasText("3쪽"), useUnmergedTree = true)[0].performClick()
        waitFor(hasText("3 / 6"))
        waitFor(hasText("1 / 3"))
        // 찾은 말 위에 진한 칠(지금 결과).
        val word = wordOn(2, "책갈피")
        compose.waitUntil(5_000) { tintAround(word, accent().copy(alpha = 0.55f)) }
        shot("91-pdf-search-result")
        // 다음 결과는 같은 쪽의 둘째, 그다음은 5쪽.
        node(hasContentDescription("다음 결과")).performClick()
        waitFor(hasText("2 / 3"))
        node(hasContentDescription("다음 결과")).performClick()
        waitFor(hasText("5 / 6"))
        waitFor(hasText("3 / 3"))

        // 없는 말은 없다고 말한다(빈 목록만 남기지 않는다).
        node(hasText("목록")).performClick()
        waitFor(hasContentDescription("지우기"))
        node(hasContentDescription("지우기")).performClick()
        node(hasContentDescription("찾을 말")).performTextInput("코끼리")
        node(hasContentDescription("찾을 말")).performImeAction()
        waitFor(hasText("찾지 못했습니다"))
    }

    @Test
    fun `a long press picks a word, a colour highlights it and it stays after reopening and shows in the notes`() {
        open()
        next()
        waitFor(hasText("2 / 6"))
        // 바로 이어 누르면 "두 번 누르기"(확대)가 된다 — 쪽이 바뀐 뒤 누른다.
        compose.mainClock.advanceTimeBy(500)
        next()
        waitFor(hasText("3 / 6"))
        val word = wordOn(2, "책갈피가")
        compose.onRoot().performTouchInput { longClick(word.center) }
        waitFor(hasContentDescription("고른 글 메뉴"))
        listOf("메모", "복사", "공유", "사전").forEach { assertTrue(hasNode(hasText(it)), "메뉴에 $it 이 없다") }
        shot("92-pdf-select")
        node(hasContentDescription("노랑")).performClick()
        compose.waitUntil(5_000) { !hasNode(hasContentDescription("고른 글 메뉴")) }
        compose.waitUntil(5_000) { tintAround(word, Pen.Yellow.fill(false)) }
        shot("93-pdf-highlight")
        val saved = runBlocking { app.container.data.annotations.forBook(bookId()) }.single()
        assertEquals("책갈피가", saved.snippet)
        assertEquals(2, saved.start.spine)
        assertEquals(HighlightColor.Yellow, saved.color)

        // 칠을 누르면 칠 메뉴(지우기) — 쪽이 넘어가지 않는다.
        compose.onRoot().performTouchInput { click(word.center) }
        waitFor(hasText("지우기"))
        assertTrue(hasNode(hasText("3 / 6")))
        node(hasText("메모")).performClick()
        waitFor(hasContentDescription("메모 입력"))
        node(hasContentDescription("메모 입력")).performTextInput("모서리를 누른다")
        node(hasText("저장")).performClick()
        compose.waitUntil(5_000) { runBlocking { app.container.data.annotations.forBook(bookId()) }.single().note == "모서리를 누른다" }

        // 닫았다 열어도 칠이 있다.
        compose.activity.onBackPressedDispatcher.onBackPressed()
        waitFor(hasText("OLO 사용 설명서"))
        node(hasText("OLO 사용 설명서")).performClick()
        waitFor(hasText("3 / 6"))
        compose.waitUntil(10_000) { tintAround(word, Pen.Yellow.fill(false)) }

        // 독서노트: PDF 도 형광펜 · 메모가 모이고, 자리는 쪽. 거르개(전체 · 책갈피 · 형광펜 · 메모)도 있다.
        compose.onRoot().performTouchInput { click(center) }
        waitFor(hasText("독서노트"))
        node(hasText("독서노트")).performClick()
        waitFor(hasText("책갈피가"))
        waitFor(hasText("3쪽 · ", substring = true))
        waitFor(hasText("모서리를 누른다"))
        assertTrue(hasNode(hasText("메모 1")))
        shot("94-pdf-notes")

        // 형광펜을 숨기면(보기 설정, EPUB 과 한 벌) PDF 칠도 그리지 않고, 눌러도 칠 메뉴가 뜨지 않는다.
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.activity.onBackPressedDispatcher.onBackPressed()
        waitFor(hasText("OLO 사용 설명서"))
        app.container.prefs.save(app.container.prefs.load().let { it.copy(screen = it.screen.copy(showHighlights = false)) })
        compose.activityRule.scenario.recreate()
        waitFor(hasText("OLO 사용 설명서"))
        node(hasText("OLO 사용 설명서")).performClick()
        waitFor(hasText("3 / 6"))
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertFalse(tintAround(word, Pen.Yellow.fill(false)), "숨겼는데 칠이 보인다")
        compose.onRoot().performTouchInput { click(word.center) }
        compose.waitForIdle()
        assertFalse(hasNode(hasText("지우기")), "숨긴 칠을 눌렀는데 칠 메뉴가 떴다")
    }

    @Test
    fun `dragging the end handle widens the selection within the page without turning it`() {
        open()
        next()
        waitFor(hasText("2 / 6"))
        // 바로 이어 누르면 "두 번 누르기"(확대)가 된다 — 쪽이 바뀐 뒤 누른다.
        compose.mainClock.advanceTimeBy(500)
        next()
        waitFor(hasText("3 / 6"))
        val word = wordOn(2, "오른쪽")
        compose.onRoot().performTouchInput { longClick(word.center) }
        waitFor(hasContentDescription("고르기 끝 손잡이"))
        val handle = node(hasContentDescription("고르기 끝 손잡이")).fetchSemanticsNode().boundsInRoot.center
        val end = wordOn(2, "꽂힌다.")
        compose.onRoot().performTouchInput { swipe(start = handle, end = Offset(end.right + 6, handle.y), durationMillis = 900) }
        compose.waitForIdle()
        // 옆으로 끌었지만(넘기기와 같은 방향) 쪽은 그대로다 — 손잡이가 끌기를 먼저 먹었다.
        assertTrue(hasNode(hasText("3 / 6")), "손잡이를 끄는데 쪽이 넘어갔다")
        node(hasContentDescription("분홍")).performClick()
        compose.waitUntil(5_000) { runBlocking { app.container.data.annotations.forBook(bookId()) }.isNotEmpty() }
        assertEquals("오른쪽 위를 누르면 책갈피가 꽂힌다.", runBlocking { app.container.data.annotations.forBook(bookId()) }.single().snippet)
    }

    @Test
    fun `listening reads the body page by page and skips the running head and page number`() {
        open()
        compose.onRoot().performTouchInput { click(center) }
        waitFor(hasContentDescription("듣기"))
        node(hasContentDescription("듣기")).performClick()
        waitFor(hasContentDescription("듣기 조종판"))
        compose.waitUntil(5_000) { speakers.isNotEmpty() && speaker.current != null }
        // 머리말("OLO 사용 설명서")이 아니라 제목부터. 제목은 마침표가 없어도 따로 읽는다.
        assertEquals("제 1 쪽 · 사용 설명서", speaker.current)
        shot("95-pdf-listen")
        finish(3)
        // 1쪽을 다 읽으면 2쪽으로 넘어가 그 쪽의 제목부터. 쪽 번호("1")는 읽지 않았다.
        waitFor(hasText("2 / 6"))
        compose.waitUntil(5_000) { speaker.current == "제 2 쪽 · 사용 설명서" }
        assertFalse(speaker.spoken.any { it == "1" || it == "OLO 사용 설명서" }, "머리말 · 쪽 번호를 읽었다: ${speaker.spoken}")
        assertEquals(listOf("제 1 쪽 · 사용 설명서", "첫 쪽이다.", "작은 글자는 확대해서 읽는다."), speaker.spoken.take(3))
    }

    @Test
    fun `a scanned pdf keeps the buttons but says why search, listening and highlighting cannot work`() {
        scanned = { true }
        open()
        compose.onRoot().performTouchInput { click(center) }
        waitFor(hasContentDescription("본문에서 찾기"))
        node(hasContentDescription("본문에서 찾기")).performClick()
        waitFor(hasText("글자가 그림으로 되어 있어", substring = true))
        // 도구줄이 닫혀야 알림이 보인다(열린 채면 도구줄 밑에 가린다).
        assertFalse(hasNode(hasText("목차")), "도구줄이 알림을 가리고 있다")
        shot("96-pdf-scanned")
        assertFalse(hasNode(hasContentDescription("찾을 말")), "스캔본인데 찾기 화면이 열렸다")
        compose.onRoot().performTouchInput { click(center) }
        waitFor(hasContentDescription("듣기"))
        node(hasContentDescription("듣기")).performClick()
        compose.waitForIdle()
        assertTrue(speakers.isEmpty(), "스캔본인데 듣기가 시작됐다")
        // 길게 눌러도 고르기가 시작되지 않고 까닭을 알린다.
        compose.onRoot().performTouchInput { longClick(center) }
        compose.waitForIdle()
        assertFalse(hasNode(hasContentDescription("고른 글 메뉴")))
    }

    // ── 도구 ────────────────────────────────────────────────────────

    /** 밝은 지면의 강조색(찾은 곳 칠의 바탕). 회색 지면은 밝은 쪽이다. */
    private fun accent(): Color = Color(0xFFB95B3B)

    private fun next() = compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.93f)) }

    private fun finish(times: Int) = repeat(times) {
        compose.runOnUiThread { speaker.finish() }
        compose.waitForIdle()
    }

    /** 화면에 그려진 흰 PDF 쪽의 네모(px): 회색 지면 위의 흰 칸. 왼쪽 가장자리 세로줄에서 찾는다. */
    private fun pageRect(): android.graphics.RectF {
        val bitmap = page()
        val x = 3
        var top = -1
        var bottom = -1
        for (y in 0 until bitmap.height) {
            val white = bitmap.getPixel(x, y).let { android.graphics.Color.red(it) > 250 && android.graphics.Color.green(it) > 250 }
            if (white && top < 0) top = y
            if (white) bottom = y
        }
        check(top >= 0) { "흰 쪽을 찾지 못했다" }
        return android.graphics.RectF(0f, top.toFloat(), bitmap.width.toFloat(), bottom + 1f)
    }

    /** [page] 쪽의 [word] 가 화면에서 차지하는 네모. */
    private fun wordOn(page: Int, word: String): androidx.compose.ui.geometry.Rect {
        val layer: PageText = layout.textLayer(page)!!
        val at = layer.text.indexOf(word).also { check(it >= 0) { "$word 가 $page 쪽에 없다" } }
        val r = layer.rects(at, at + word.length).single()
        val p = pageRect()
        return androidx.compose.ui.geometry.Rect(p.left + r.left * p.width(), p.top + r.top * p.height(), p.left + r.right * p.width(), p.top + r.bottom * p.height())
    }

    /** 네모 안에 [tint] 를 흰 종이에 얹은 색이 이어진 띠(8점 이상)로 있는가. 글자 사이 빈 곳이 칠에 덮여 있으면 그 색이다. */
    private fun tintAround(rect: androidx.compose.ui.geometry.Rect, tint: Color): Boolean {
        val want = tint.compositeOver(Color.White).toArgb()
        val bitmap = page()
        for (y in rect.top.toInt().coerceAtLeast(0) until rect.bottom.toInt().coerceAtMost(bitmap.height)) {
            var run = 0
            for (x in rect.left.toInt().coerceAtLeast(0) until rect.right.toInt().coerceAtMost(bitmap.width)) {
                run = if (close(bitmap.getPixel(x, y), want, 8)) run + 1 else 0
                if (run >= 8) return true
            }
        }
        return false
    }

    private fun close(a: Int, b: Int, tolerance: Int): Boolean =
        abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) <= tolerance &&
            abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) <= tolerance &&
            abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b)) <= tolerance

    private fun bookId() = runBlocking { app.container.data.library.books().first().first { it.displayName.startsWith("설명서") }.id }

    private fun manual(): ByteArray = TestPdf().run {
        val p = pages(10, 6)
        obj(1, "<< /Type /Catalog /Pages 10 0 R /Outlines 2 0 R >>")
        obj(2, "<< /Type /Outlines /First 50 0 R >>")
        obj(50, "<< /Title ${utf16("1장 시작하기")} /Next 51 0 R /Dest [${p[0]} 0 R /Fit] >>")
        obj(51, "<< /Title ${utf16("2장 책갈피")} /Dest [${p[2]} 0 R /Fit] >>")
        obj(30, "<< /Title ${utf16("OLO 사용 설명서")} /Author ${utf16("OLO 팀")} >>")
        classic(trailerExtra = "/Info 30 0 R")
    }

    private fun hasNode(matcher: SemanticsMatcher) =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun node(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true)[0]

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 30_000) {
        compose.waitUntil(timeoutMs) { hasNode(matcher) }
    }

    private fun page(): Bitmap {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        return bitmap
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        File(shots, "$name.png").outputStream().use { page().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
