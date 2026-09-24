package io.github.kgcaudit.reader.app

import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
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
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.document.Annotation
import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.HighlightColor
import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.reflow.ReaderPrefs
import io.github.kgcaudit.reader.ui.design.PaperTheme
import io.github.kgcaudit.reader.ui.design.Pen
import io.github.kgcaudit.reader.ui.design.ScreenPrefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 3단계(0.15.0) 독서노트: 길게 눌러 고르기 · 손잡이 · 칠하기 · 메모 · 복사 · 공유 · 사전 · 칠한 곳 누르기 ·
 * 독서노트 목록 · 내보내기. 사람이 누르는 순서 그대로.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class ReadingNotesAppTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>(StandardTestDispatcher())

    private val shots = File(System.getProperty("reader.screenshots") ?: "build/screenshots").apply { mkdirs() }
    private val app get() = ApplicationProvider.getApplicationContext<OloApp>()
    private val density get() = compose.activity.resources.displayMetrics.density

    @Before
    fun setUp() {
        val folder = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        File(folder, "소설").mkdirs()
        File(folder, "소설/노트 책.epub").writeBytes(book())
        runBlocking { app.container.data.folders.register(FolderProvider.treeUri) }
    }

    /** 첫 장: 한 줄짜리 문단 셋(누를 자리를 셈으로 알 수 있게) + 긴 본문. 둘째 장은 짧다. */
    private fun book(): ByteArray {
        val body = (1..16).joinToString("") { "<p>보아 구렁이 이야기 $it 번째 문단이다. 어른들은 모자라고 했다.</p>" }
        val files = mapOf(
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""",
            "OEBPS/content.opf" to """
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>노트 책</dc:title><dc:creator>생텍쥐페리</dc:creator></metadata>
                  <manifest>
                    <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
                </package>
            """.trimIndent(),
            "OEBPS/ch1.xhtml" to """<html><body>
                <p>보아구렁이는 먹이를 삼킨다.</p>
                <p>어른들은 숫자를 좋아한다.</p>
                <p>코끼리를 삼킨 그림이다.</p>
                $body</body></html>""",
            "OEBPS/ch2.xhtml" to """<html><body><p>둘째 장의 첫 문단이다.</p></body></html>""",
        )
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip -> files.forEach { (n, b) -> zip.putNextEntry(ZipEntry(n)); zip.write(b.toByteArray()); zip.closeEntry() } }
        return out.toByteArray()
    }

    private fun openWith(prefs: ReaderPrefs = ReaderPrefs()) {
        app.container.prefs.save(prefs)
        compose.activityRule.scenario.recreate()
        waitFor(hasText("노트 책", substring = true))
        node(hasText("노트 책", substring = true)).performClick()
        waitFor(hasText("1 / ", substring = true))
    }

    /** 첫 장 [line] 번째 줄(0부터)의 첫 글자 가운데쯤. FindAndNotesTest 와 같은 셈. */
    private fun lineStart(line: Int) = Offset((36 + 18 + 6) * density, (34 + 16 + line * (18 * 1.65f + 4.5f)) * density)

    private fun select(line: Int) {
        compose.onRoot().performTouchInput { longClick(lineStart(line)) }
        waitFor(hasContentDescription("고른 글 메뉴"))
    }

    @Test
    fun `a long press picks a word and a colour dot highlights it for good`() {
        openWith()
        select(0)
        // 손잡이 둘과 메뉴(4색 · 메모 · 복사 · 공유 · 사전).
        assertTrue(hasNode(hasContentDescription("고르기 시작 손잡이")) && hasNode(hasContentDescription("고르기 끝 손잡이")))
        listOf("메모", "복사", "공유", "사전").forEach { assertTrue(hasNode(hasText(it)), "메뉴에 $it 이 없다") }
        shot("70-select")
        node(hasContentDescription("노랑")).performClick()
        compose.waitUntil(5_000) { !hasNode(hasContentDescription("고른 글 메뉴")) }
        compose.waitUntil(5_000) { tinted(page(), Pen.Yellow) }
        shot("71-highlight")

        // 책을 닫았다 열어도 남는다 — 글자 크기를 바꿔도 같은 낱말이 칠해져 있다(자리는 글자 오프셋).
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        openWith(ReaderPrefs(fontSizeSp = 22))
        compose.waitUntil(10_000) { tinted(page(), Pen.Yellow) }
        val saved = runBlocking { app.container.data.annotations.forBook(bookId()) }
        assertEquals(listOf("보아구렁이는"), saved.map { it.snippet })
        assertEquals(HighlightColor.Yellow, saved.single().color)
    }

    @Test
    fun `dragging the end handle widens the selection and copy puts it on the clipboard`() {
        openWith()
        select(1)
        val handle = node(hasContentDescription("고르기 끝 손잡이")).fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput {
            swipe(start = handle, end = Offset(width * 0.95f, handle.y), durationMillis = 800)
        }
        compose.waitForIdle()
        node(hasText("복사")).performClick()
        val clip = app.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()
        // 처음 고른 것은 "어른들은" 한 어절. 손잡이를 줄 끝까지 끌었으니 문단 끝까지다.
        assertEquals("어른들은 숫자를 좋아한다.", clip)
        // 끄는 동안 쪽이 넘어가지 않았다(끌기를 손잡이가 먹었다).
        assertTrue(hasNode(hasText("1 / ", substring = true)))

        // 왼쪽으로 크게 끌면 줄어든다(최소 한 글자). 이 끌기는 "다음 쪽" 밀기와 같은 방향이다 — 손잡이가 끌기를
        // 먹지 않으면 쪽이 넘어가 고른 것을 잃는다.
        select(1)
        val end = node(hasContentDescription("고르기 끝 손잡이")).fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { swipe(start = end, end = Offset(2f, end.y), durationMillis = 800) }
        // 쪽 넘김은 늦게 온다 — 화면 시계를 돌리며 넘어갈 틈을 준 뒤에 본다(바로 보면 넘어가기 전이라 늘 통과한다.
        // Thread.sleep 으로는 시험 디스패처의 시계가 흐르지 않아 넘김 요청 자체가 돌지 않는다).
        runCatching { compose.waitUntil(3_000) { hasNode(hasText("2 / ", substring = true)) } }
        assertTrue(hasNode(hasText("1 / ", substring = true)), "손잡이를 왼쪽으로 끌었는데 쪽이 넘어갔다")
        node(hasText("복사")).performClick()
        assertEquals("어", app.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun `a memo is saved with its highlight, marked on the page and cancelling leaves nothing behind`() {
        openWith()
        // 취소하면 칠도 메모도 남지 않는다 — 메모 판을 열었다는 것만으로 칠해지면 안 된다.
        select(2)
        node(hasText("메모")).performClick()
        waitFor(hasContentDescription("메모 입력"))
        node(hasText("취소")).performClick()
        compose.waitUntil(5_000) { !hasNode(hasContentDescription("메모 입력")) }
        assertTrue(runBlocking { app.container.data.annotations.forBook(bookId()) }.isEmpty())

        select(2)
        node(hasText("메모")).performClick()
        waitFor(hasContentDescription("메모 입력"))
        node(hasContentDescription("초록")).performClick()
        node(hasContentDescription("메모 입력")).performTextInput("여섯 달 동안 잠을 잔다니")
        shot("72-memo-sheet")
        node(hasText("저장")).performClick()
        compose.waitUntil(5_000) { !hasNode(hasContentDescription("메모 입력")) }
        compose.waitUntil(5_000) { tinted(page(), Pen.Green) }
        // 칠한 끝에 쪽지(진한 초록).
        assertTrue(colourNear(page(), Pen.Green.mark(false), lineStart(2).copy(x = lineStart(2).x + 80 * density), 90), "메모 쪽지가 없다")
        shot("73-memo-mark")
        val saved = runBlocking { app.container.data.annotations.forBook(bookId()) }.single()
        assertEquals("여섯 달 동안 잠을 잔다니", saved.note)
        assertEquals(HighlightColor.Green, saved.color)
    }

    @Test
    fun `tapping a highlight offers its colours and erasing removes it from the page`() {
        openWith()
        select(0)
        node(hasContentDescription("분홍")).performClick()
        compose.waitUntil(5_000) { tinted(page(), Pen.Pink) }
        compose.onRoot().performTouchInput { click(lineStart(0)) }
        // 지금 색에 고리, 사전 대신 지우기.
        waitFor(hasContentDescription("분홍 (지금 색)"))
        assertTrue(hasNode(hasText("지우기")) && !hasNode(hasText("사전")))
        shot("74-tap-highlight")
        // 색 바꾸기.
        node(hasContentDescription("파랑")).performClick()
        compose.waitUntil(5_000) { tinted(page(), Pen.Blue) && !tinted(page(), Pen.Pink) }
        // 지우기.
        compose.onRoot().performTouchInput { click(lineStart(0)) }
        waitFor(hasText("지우기"))
        node(hasText("지우기")).performClick()
        waitFor(hasText("형광펜을 지웠습니다"))
        compose.waitUntil(5_000) { !tinted(page(), Pen.Blue) }
        assertTrue(runBlocking { app.container.data.annotations.forBook(bookId()) }.isEmpty())
        // 칠한 곳이 아닌 자리를 누르면 여느 때처럼 — 가운데는 메뉴.
        compose.onRoot().performTouchInput { click(center) }
        waitFor(hasText("독서노트"))
    }

    @Test
    fun `the dictionary goes to an installed app and says so when there is none`() {
        openWith()
        select(0)
        node(hasText("사전")).performClick()
        waitFor(hasText("낱말을 찾아 줄 사전 앱이 없습니다"))

        // 사전 앱이 하나 있으면 고른 말을 그 앱에 넘긴다(ACTION_PROCESS_TEXT).
        val info = ResolveInfo().apply { activityInfo = ActivityInfo().apply { packageName = "org.example.dict"; name = "org.example.dict.Look" } }
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain"), info)
        select(0)
        node(hasText("사전")).performClick()
        val sent = shadowOf(compose.activity).nextStartedActivity
        assertNotNull(sent)
        assertEquals(Intent.ACTION_PROCESS_TEXT, sent.action)
        assertEquals("보아구렁이는", sent.getStringExtra(Intent.EXTRA_PROCESS_TEXT))
    }

    @Test
    fun `sharing sends the quote with the book title`() {
        openWith()
        select(1)
        node(hasText("공유")).performClick()
        val chooser = shadowOf(compose.activity).nextStartedActivity
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertEquals("“어른들은”\n— 노트 책", send?.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `the reading notes gather bookmarks and highlights in book order with memos indented under them`() {
        openWith()
        // 2쪽의 낱말을 칠하고(초록 + 메모), 1쪽에 책갈피 · 노랑 칠. 목록은 꽂은 순서가 아니라 책 순서다.
        compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.9f)) }
        waitFor(hasText("2 / ", substring = true))
        select(0)
        node(hasText("메모")).performClick()
        waitFor(hasContentDescription("메모 입력"))
        node(hasContentDescription("초록")).performClick()
        node(hasContentDescription("메모 입력")).performTextInput("둘째 쪽 메모")
        node(hasText("저장")).performClick()
        compose.waitUntil(5_000) { !hasNode(hasContentDescription("메모 입력")) }
        compose.onRoot().performTouchInput { click(centerLeft.copy(x = width * 0.1f)) }
        waitFor(hasText("1 / ", substring = true))
        select(0)
        node(hasContentDescription("노랑")).performClick()
        compose.waitUntil(5_000) { !hasNode(hasContentDescription("고른 글 메뉴")) }
        compose.onRoot().performTouchInput { click(topRight.copy(x = width - 20 * density, y = 20 * density)) }
        waitFor(hasText("책갈피를 꽂았습니다"))

        // 도구줄: 목차 · 독서노트 · 보기(N5). 책갈피 단추는 없다.
        compose.onRoot().performTouchInput { click(center) }
        waitFor(hasText("독서노트"))
        assertFalse(hasNode(hasText("책갈피")))
        shot("75-toolbar")
        node(hasText("독서노트")).performClick()
        waitFor(hasText("독서노트 3"))
        listOf("전체 3", "책갈피 1", "형광펜 1", "메모 1").forEach { assertTrue(hasNode(hasText(it)), "칩 $it 이 없다") }
        waitFor(hasText("둘째 쪽 메모"))
        shot("76-notes")

        // 책 순서: 책갈피(1쪽 머리) → 노랑(1쪽 첫 낱말) → 초록(2쪽).
        val mark = node(hasText("보아구렁이는 먹이를", substring = true)).fetchSemanticsNode().boundsInRoot
        val yellow = node(hasText("보아구렁이는")).fetchSemanticsNode().boundsInRoot
        val memo = node(hasText("둘째 쪽 메모")).fetchSemanticsNode().boundsInRoot
        assertTrue(mark.top < yellow.top && yellow.top < memo.top)
        // 위계(UI 규칙 1): 장 이름 아래 항목은 한 단(16dp) 안쪽 — 앞머리(아이콘 · 점)가 gutter + levelIndent 에서
        // 시작하고 글자는 거기서 childIndent(38dp). 메모 상자(안쪽 여백 12dp)는 그 칠한 글의 글자 시작선에서 시작한다.
        val m = io.github.kgcaudit.reader.ui.design.CpMetrics()
        val section = node(hasText("1장")).fetchSemanticsNode().boundsInRoot
        val textStart = (m.gutter + m.levelIndent + m.childIndent).value
        assertNear(m.gutter.value, section.left / density, "장 이름")
        assertNear(textStart, yellow.left / density, "칠한 글")
        assertNear(textStart, mark.left / density, "책갈피 글")
        val green = runBlocking { app.container.data.annotations.forBook(bookId()) }.first { it.note != null }
        val highlight = node(hasText(green.snippet)).fetchSemanticsNode().boundsInRoot
        assertNear(textStart, highlight.left / density, "메모 달린 칠")
        assertNear(textStart, memo.left / density - 12, "메모 상자")

        // ⋮ 메뉴: 메모 고치기 · 색 바꾸기 · 공유 · 지우기. 색 바꾸기는 그 자리에서 4색으로 바뀐다.
        compose.onAllNodes(hasContentDescription("더 보기"), useUnmergedTree = true)[2].performClick()
        waitFor(hasText("메모 고치기"))
        listOf("색 바꾸기", "공유", "지우기").forEach { assertTrue(hasNode(hasText(it)), "⋮ 메뉴에 $it 이 없다") }
        shot("78-notes-menu")
        node(hasText("색 바꾸기")).performClick()
        waitFor(hasContentDescription("초록 (지금 색)"))
        node(hasContentDescription("파랑")).performClick()
        compose.waitUntil(5_000) {
            runBlocking { app.container.data.annotations.forBook(bookId()) }.first { it.note != null }.color == HighlightColor.Blue
        }
        compose.waitUntil(5_000) { !hasNode(hasText("색 바꾸기")) }

        // 거르개: 메모만.
        node(hasText("메모 1")).performClick()
        compose.waitUntil(5_000) { !hasNode(hasText("보아구렁이는")) }
        assertTrue(hasNode(hasText("둘째 쪽 메모")))
        node(hasText("전체 3")).performClick()

        // 내보내기(N8): 공유 시트로 글을 보낸다.
        node(hasText("내보내기")).performClick()
        val chooser = shadowOf(compose.activity).nextStartedActivity
        @Suppress("DEPRECATION")
        val text = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        assertTrue(text.startsWith("노트 책 — 생텍쥐페리\n독서노트 3개"), text)
        assertTrue("└ 메모: 둘째 쪽 메모" in text, text)

        // 목록에서 칠을 누르면 그 자리(2쪽)로.
        node(hasText("둘째 쪽 메모")).performClick()
        waitFor(hasText("2 / ", substring = true))
    }

    @Test
    fun `a highlight pointing past the chapter end does not stop the book from opening`() {
        // 책 파일이 바뀌었거나 DB 가 상했다(규칙 6): 그 칠만 못 그리고 책은 연다. 목록에도 남아 지울 수 있다.
        // 칠은 지금 읽는 장이 아닌 둘째 장에 둔다 — 다른 장의 칠을 지워도 목록이 새로 모아져야 한다.
        openWith()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        runBlocking {
            app.container.data.annotations.add(
                Annotation(0, bookId(), Locator.Reflow(1, 99_000), Locator.Reflow(1, 99_500), HighlightColor.Pink, "사라진 자리", "옛 글", 1L),
            )
        }
        openWith()
        compose.onRoot().performTouchInput { click(center) }
        waitFor(hasText("독서노트"))
        node(hasText("독서노트")).performClick()
        waitFor(hasText("사라진 자리"))
        node(hasContentDescription("더 보기")).performClick()
        node(hasText("지우기")).performClick()
        compose.waitUntil(5_000) { !hasNode(hasText("사라진 자리")) }
        assertTrue(runBlocking { app.container.data.annotations.forBook(bookId()) }.isEmpty())
    }

    @Test
    fun `highlights stay readable on black paper`() {
        openWith(ReaderPrefs(screen = ScreenPrefs(theme = PaperTheme.Black)))
        select(0)
        node(hasContentDescription("노랑")).performClick()
        compose.waitUntil(5_000) { tinted(page(), Pen.Yellow, dark = true) }
        shot("77-highlight-black")
    }

    // ── 도구 ────────────────────────────────────────────────────────

    private fun bookId(): BookId = runBlocking {
        app.container.data.library.books().first().first { it.displayName.startsWith("노트 책") }.id
    }

    private fun assertNear(expected: Float, actual: Float, what: String) =
        assertTrue(abs(expected - actual) < 1.5f, "$what: ${expected}dp 에서 시작해야 하는데 ${actual}dp")

    /** 지면(흰 · 검정)에 [pen] 칠이 이어진 띠(12점 이상)로 있는가. 칠 색 = 칠을 지면에 얹은 색. */
    private fun tinted(bitmap: Bitmap, pen: Pen, dark: Boolean = false): Boolean {
        val paper = if (dark) PaperTheme.Black.swatch!! else Color.White
        val want = pen.fill(dark).compositeOver(paper).toArgb()
        for (y in (bitmap.height * 0.03).toInt() until (bitmap.height * 0.9).toInt() step 2) {
            var run = 0
            for (x in 0 until bitmap.width) {
                run = if (close(bitmap.getPixel(x, y), want, 6)) run + 1 else 0
                if (run >= 12) return true
            }
        }
        return false
    }

    private fun colourNear(bitmap: Bitmap, colour: Color, at: Offset, radiusDp: Int): Boolean {
        val want = colour.toArgb()
        val r0 = (radiusDp * density).toInt()
        var hits = 0
        for (y in (at.y.toInt() - r0).coerceAtLeast(0) until (at.y.toInt() + r0).coerceAtMost(bitmap.height)) {
            for (x in (at.x.toInt() - r0).coerceAtLeast(0) until (at.x.toInt() + r0).coerceAtMost(bitmap.width)) {
                if (close(bitmap.getPixel(x, y), want, 10)) hits++
            }
        }
        return hits >= 10
    }

    private fun close(a: Int, b: Int, tolerance: Int): Boolean =
        abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) <= tolerance &&
            abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) <= tolerance &&
            abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b)) <= tolerance

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
