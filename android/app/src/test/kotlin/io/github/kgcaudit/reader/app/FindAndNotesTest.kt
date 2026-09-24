package io.github.kgcaudit.reader.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.reflow.ReaderPrefs
import io.github.kgcaudit.reader.ui.design.Footer
import io.github.kgcaudit.reader.ui.design.FooterItem
import io.github.kgcaudit.reader.ui.design.PageTurn
import io.github.kgcaudit.reader.ui.design.ReadingSpeed
import io.github.kgcaudit.reader.ui.design.ScreenPrefs
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 2단계(0.14.0) 찾기 · 이동: 본문 검색, 각주 판, 책 안 링크와 "읽던 곳으로", 책 밖 링크, 남은 시간, 밝기 밀기,
 * 넘김 효과. 사람이 누르는 순서 그대로.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class FindAndNotesTest {

    /** 효과를 UI 스레드 하나에서 돌린다 — 실제 앱과 같게(AppWalkthroughTest 의 설명 참고). */
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>(StandardTestDispatcher())

    private val shots = File(System.getProperty("reader.screenshots") ?: "build/screenshots").apply { mkdirs() }
    private val app get() = ApplicationProvider.getApplicationContext<OloApp>()
    private val density get() = compose.activity.resources.displayMetrics.density

    @Before
    fun setUp() {
        val folder = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        File(folder, "소설").mkdirs()
        File(folder, "소설/각주 책.epub").writeBytes(notesBook())
        kotlinx.coroutines.runBlocking { app.container.data.folders.register(FolderProvider.treeUri) }
    }

    /**
     * 첫 장: 한 줄짜리 문단 넷이 각각 각주 · 책 안 링크 · 책 밖 링크 · 깨진 각주로 **시작한다**(누를 자리를 셈으로
     * 알 수 있게). 그 뒤로 "보아 구렁이" 가 여러 번 나오는 본문, 둘째 장, 주석 장.
     */
    private fun notesBook(): ByteArray {
        val body = (1..14).joinToString("") { "<p>보아 구렁이 이야기 $it 번째 문단이다. 어른들은 모자라고 했다.</p>" }
        val files = mapOf(
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""",
            "OEBPS/content.opf" to """
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>각주 책</dc:title></metadata>
                  <manifest>
                    <item id="c1" href="Text/ch1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="Text/ch2.xhtml" media-type="application/xhtml+xml"/>
                    <item id="n" href="Text/notes.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine><itemref idref="c1"/><itemref idref="c2"/><itemref idref="n"/></spine>
                </package>
            """.trimIndent(),
            "OEBPS/Text/ch1.xhtml" to """<html xmlns:epub="http://www.idpf.org/2007/ops"><body>
                <p><a epub:type="noteref" href="notes.xhtml#n1">1</a> 각주 표시.</p>
                <p><a href="ch2.xhtml#rose">장미</a> 로 가는 링크.</p>
                <p><a href="https://example.com/rose">누리집</a> 링크.</p>
                <p><a href="notes.xhtml#none">9</a> 깨진 각주.</p>
                $body</body></html>""",
            "OEBPS/Text/ch2.xhtml" to """<html><body><p>앞 문단.</p><p id="rose">장미 한 송이. 보아 구렁이는 없다.</p></body></html>""",
            "OEBPS/Text/notes.xhtml" to """<html><body><p>주석</p>
                <p id="n1">1) 보아 구렁이: 큰 뱀. <a href="ch1.xhtml">↩</a></p>
                <p id="n2">2) 체험한 이야기: 어린이 책.</p></body></html>""",
        )
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip -> files.forEach { (n, b) -> zip.putNextEntry(ZipEntry(n)); zip.write(b.toByteArray()); zip.closeEntry() } }
        return out.toByteArray()
    }

    private fun openWith(prefs: ReaderPrefs = ReaderPrefs(), page: Int = 1) {
        app.container.prefs.save(prefs)
        compose.activityRule.scenario.recreate()
        // 한 번 연 책은 목록에 파일 이름 대신 책 제목("각주 책")으로 나온다 — 둘 다 이 글자로 시작한다.
        waitFor(hasText("각주 책", substring = true))
        node(hasText("각주 책", substring = true)).performClick()
        waitFor(hasText("$page / ", substring = true))
    }

    /** 첫 장 [line] 번째 줄(0부터)의 첫 글자 자리. 여백 보통(36dp) · 첫 줄 들여쓰기 1em · 줄 높이 18sp×1.65 + 문단 간격. */
    private fun lineStart(line: Int) = Offset((36 + 18 + 6) * density, (34 + 16 + line * (18 * 1.65f + 4.5f)) * density)

    @Test
    fun `searching the book lists hits by chapter and walks through them on the page`() {
        openWith()
        compose.onRoot().performTouchInput { click(center) }
        node(hasContentDescription("본문에서 찾기")).performClick()
        node(hasContentDescription("찾을 말")).performTextInput("보아 구렁이")
        node(hasContentDescription("찾을 말")).performImeAction()
        waitFor(hasText("곳 · ", substring = true))
        shot("60-search-results")
        // 위계 규칙: 결과 줄은 장 이름(글자만)보다 한 단(16dp) 안쪽.
        fun left(m: SemanticsMatcher) = node(m).fetchSemanticsNode().boundsInRoot.left / density
        assertEquals(left(hasText("1장")) + 16f, left(hasText("번째 문단", substring = true)), 1f)
        // 셋째 장(주석)까지 찾았다(목록이 길어 뒤 장의 머리는 화면 아래 — 요약 줄로 본다).
        waitFor(hasText("3장에서", substring = true))

        // 결과를 누르면 그 쪽: 찾은 말이 칠해져 있고 아래에 결과 막대.
        compose.onAllNodes(hasText("번째 문단", substring = true), useUnmergedTree = true)[0].performClick()
        waitFor(hasText("1 / ", substring = true))
        waitFor(hasContentDescription("다음 결과"))
        compose.waitUntil(5_000) { tinted(page()) }
        shot("61-search-page")
        node(hasContentDescription("다음 결과")).performClick()
        waitFor(hasText("2 / ", substring = true))
        // 끝내면 막대와 칠한 것이 사라진다.
        node(hasContentDescription("찾기 끝내기")).performClick()
        compose.waitUntil(5_000) { !hasNode(hasContentDescription("다음 결과")) }
        compose.waitUntil(5_000) { !tinted(page()) }
    }

    @Test
    fun `a footnote opens in a sheet without turning the page and can take the reader there and back`() {
        openWith()
        // 각주 표시는 강조색(F1).
        assertTrue(accentNear(page(), lineStart(0)), "각주 표시가 강조색이 아니다")
        compose.onRoot().performTouchInput { click(lineStart(0)) }
        waitFor(hasText("각주 1"))
        waitFor(hasText("1) 보아 구렁이: 큰 뱀."))
        assertFalse(hasNode(hasText("2) 체험한", substring = true)), "다음 각주까지 따라왔다")
        assertFalse(hasNode(hasText("↩", substring = true)), "되돌아가기 표시가 남았다")
        shot("62-footnote-sheet")
        // 넘어가지 않았다.
        assertTrue(hasNode(hasText("1 / ", substring = true)))
        node(hasText("닫기")).performClick()
        compose.waitUntil(5_000) { !hasNode(hasText("각주 1")) }

        // 각주 자리로 갔다가 "읽던 곳으로".
        compose.onRoot().performTouchInput { click(lineStart(0)) }
        waitFor(hasText("각주 자리로 가기"))
        node(hasText("각주 자리로 가기")).performClick()
        waitFor(hasText("읽던 곳으로"))
        // 주석 장에서 그 각주가 칠해져 있다 — 비슷한 문단이 줄지어 있어 어느 것인지 찾지 않아도 되게.
        compose.waitUntil(5_000) { tinted(page()) }
        shot("63-footnote-place")
        node(hasText("읽던 곳으로")).performClick()
        compose.waitUntil(10_000) { !hasNode(hasText("읽던 곳으로")) }
        compose.waitUntil(10_000) { accentNear(page(), lineStart(0)) }
    }

    @Test
    fun `a link in the text jumps there, an outside link asks first and a broken note says so`() {
        openWith()
        // 책 안 링크(F5): 판 없이 바로 간다 + "읽던 곳으로".
        compose.onRoot().performTouchInput { click(lineStart(1)) }
        waitFor(hasText("읽던 곳으로"))
        assertFalse(hasNode(hasText("각주 자리로 가기")), "책 안 링크에 각주 판이 떴다")
        node(hasText("읽던 곳으로")).performClick()
        compose.waitUntil(10_000) { !hasNode(hasText("읽던 곳으로")) }

        // 책 밖(F6): 묻는다. 취소하면 그대로.
        compose.onRoot().performTouchInput { click(lineStart(2)) }
        waitFor(hasText("브라우저로 열까요?"))
        waitFor(hasText("https://example.com/rose"))
        shot("64-external-link")
        node(hasText("취소")).performClick()
        compose.waitUntil(5_000) { !hasNode(hasText("브라우저로 열까요?")) }

        // 깨진 각주(규칙 6): 넘기지 않고 알린다.
        compose.onRoot().performTouchInput { click(lineStart(3)) }
        waitFor(hasText("이 각주의 내용을 책에서 찾지 못했습니다"))
        assertTrue(hasNode(hasText("1 / ", substring = true)))
    }

    @Test
    fun `the footer shows the time left once the reading speed is known`() {
        // 속도를 모르면 빈칸(E5) — 첫 쪽 하나로 센 시간은 틀린다.
        val footer = Footer(right = FooterItem.ChapterTime)
        openWith(ReaderPrefs(screen = ScreenPrefs(footer = footer)))
        assertFalse(hasNode(hasText("남음", substring = true)))
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        // 분당 300자로 세 번 잰 뒤라면 보인다.
        app.container.prefs.saveSpeed("chars", ReadingSpeed(300.0, 3))
        openWith(ReaderPrefs(screen = ScreenPrefs(footer = footer)))
        waitFor(hasText("이 장 ", substring = true))
        waitFor(hasText("남음", substring = true))
    }

    @Test
    fun `sliding along the left edge changes the brightness without turning the page`() {
        openWith()
        compose.onRoot().performTouchInput {
            swipe(start = Offset(10 * density, height * 0.7f), end = Offset(10 * density, height * 0.3f), durationMillis = 600)
        }
        compose.waitForIdle()
        val saved = app.container.prefs.load().screen.brightness
        assertTrue(saved != null && saved > 0.5f, "위로 밀었는데 밝아지지 않았다: $saved")
        assertEquals(saved, compose.activity.window.attributes.screenBrightness, 0.01f)
        assertTrue(hasNode(hasText("1 / ", substring = true)), "밝기를 밀었는데 쪽이 넘어갔다")

        // 끄면 같은 동작이 아무것도 바꾸지 않는다.
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        openWith(ReaderPrefs(screen = ScreenPrefs(brightnessGesture = false)))
        compose.onRoot().performTouchInput {
            swipe(start = Offset(10 * density, height * 0.7f), end = Offset(10 * density, height * 0.3f), durationMillis = 600)
        }
        compose.waitForIdle()
        assertEquals(null, app.container.prefs.load().screen.brightness)
    }

    @Test
    fun `both page turn effects still turn the page`() {
        // 밀기로 앞으로(1 → 2쪽), 서서히로 뒤로(2 → 1쪽 — 이어 읽기로 2쪽에서 열린다).
        for ((i, effect) in listOf(PageTurn.Slide, PageTurn.Fade).withIndex()) {
            openWith(ReaderPrefs(screen = ScreenPrefs(pageTurn = effect)), page = i + 1)
            val x = if (i == 0) 0.9f else 0.1f
            compose.onRoot().performTouchInput { click(centerLeft.copy(x = width * x)) }
            waitFor(hasText("${if (i == 0) 2 else 1} / ", substring = true))
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            shot("65-turn-${effect.name.lowercase()}")
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        }
    }

    // ── 도구 ────────────────────────────────────────────────────────

    /**
     * 찾은 말 칠함(강조색 30% 가 흰 바탕에 얹힌 색 ≈ #EACEC4)이 지면에 있는가. 진행 막대의 옅은 칸(#DCD3C6)은
     * 붉은 기가 약해 걸리지 않는다(처음 쓴 판별은 그것까지 칠함으로 봤다).
     */
    private fun tinted(bitmap: Bitmap): Boolean {
        // 칠함은 글자 폭만큼 **이어진** 띠다. 강조색 각주 표시("1")의 가장자리도 이 색이 나오지만 몇 점뿐이다.
        for (y in (bitmap.height * 0.05).toInt() until (bitmap.height * 0.85).toInt() step 3) {
            var run = 0
            for (x in 0 until bitmap.width) {
                val c = bitmap.getPixel(x, y)
                val r = android.graphics.Color.red(c)
                val g = android.graphics.Color.green(c)
                val b = android.graphics.Color.blue(c)
                run = if (r in 226..240 && g in 196..214 && b in 184..204 && r - b > 30) run + 1 else 0
                if (run >= 12) return true
            }
        }
        return false
    }

    /** [at] 둘레(24dp)에 강조색(붉은 기) 글자 점이 있는가. */
    private fun accentNear(bitmap: Bitmap, at: Offset): Boolean {
        val r0 = (24 * density).toInt()
        for (y in (at.y.toInt() - r0).coerceAtLeast(0) until (at.y.toInt() + r0).coerceAtMost(bitmap.height)) {
            for (x in (at.x.toInt() - r0).coerceAtLeast(0) until (at.x.toInt() + r0).coerceAtMost(bitmap.width)) {
                val c = bitmap.getPixel(x, y)
                val r = android.graphics.Color.red(c)
                val g = android.graphics.Color.green(c)
                if (r > 120 && r - g > 50) return true
            }
        }
        return false
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
