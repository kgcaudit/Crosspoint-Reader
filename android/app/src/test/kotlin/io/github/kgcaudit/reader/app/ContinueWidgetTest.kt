package io.github.kgcaudit.reader.app

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.document.ReadingProgress
import io.github.kgcaudit.reader.ui.design.DarkColors
import io.github.kgcaudit.reader.ui.design.LightColors
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
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 홈 화면 "이어 읽기" 위젯(사용자 결정 3-1). 무엇을 보이는가(가장 최근에 읽던 것), 누르면 그 책 · 권이 읽던 자리로 열리는가, 그사이
 * 파일이 사라졌으면 앱이 무너지지 않고 서재와 알림을 보이는가.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class ContinueWidgetTest {

    @get:Rule
    val compose = createEmptyComposeRule(StandardTestDispatcher())

    private val app = ApplicationProvider.getApplicationContext<OloApp>()
    private lateinit var root: File
    private var scenario: ActivityScenario<MainActivity>? = null

    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(60, 90, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Before
    fun setUp() {
        root = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        File(root, "소설").mkdirs()
        File(root, "소설/어린 왕자.epub").writeBytes(SampleBooks.epub())
        File(root, "소설/메모.txt").writeBytes(SampleBooks.txt())
        File(root, "만화/별").mkdirs()
        ZipOutputStream(File(root, "만화/별/별 01권.cbz").outputStream()).use { zip ->
            listOf(0xFFD03030, 0xFF3050D0, 0xFF2E7D6B).forEachIndexed { i, c ->
                zip.putNextEntry(ZipEntry("00${i + 1}.png"))
                zip.write(png(c.toInt()))
                zip.closeEntry()
            }
        }
        runBlocking {
            val data = app.container.data
            data.folders.register(FolderProvider.treeUri)
            data.rescanAll()
            data.probeComics()
        }
    }

    private val data get() = app.container.data
    private fun bookId(name: String) = runBlocking { data.library.books().first().single { it.displayName == name }.id }
    private fun comicId() = runBlocking { data.comics.units().first().single().id }

    private fun launch(intent: Intent) {
        scenario = ActivityScenario.launch(intent.setClass(app, MainActivity::class.java))
    }

    private fun has(text: String, substring: Boolean = false) =
        compose.onAllNodes(hasText(text, substring = substring), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(text: String, substring: Boolean = false) = compose.waitUntil(30_000) { has(text, substring) }

    private fun waitForComicPage(n: Int) = compose.waitUntil(30_000) {
        compose.onAllNodes(hasContentDescription("만화 ${n}쪽"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    /** 지금 위젯을 뷰로 펴 본다(런처가 하는 일). */
    private fun widget(): View {
        val views = runBlocking { ContinueWidgets.views(app) }
        return views.apply(app, FrameLayout(app))
    }

    private fun View.text(id: Int) = findViewById<TextView>(id).let { if (it.visibility == View.VISIBLE) it.text.toString() else null }

    // ── 무엇을 보이나(순수 규칙) ──────────────────────────────

    private fun candidate(id: String, readAt: Long, percent: Float = 40f, finishedAt: Long? = null) =
        ContinueCandidate(ContinueTarget.Book(id), id, null, percent, readAt, finishedAt)

    @Test
    fun `the most recently read unfinished book or comic is the one shown`() {
        val model = continueModel(
            listOf(
                candidate("옛 책", readAt = 1_000),
                ContinueCandidate(ContinueTarget.Comic("별 1권"), "별 1권", "만화", 30f, 5_000, null),
                candidate("어제 책", readAt = 4_000),
            ),
        )
        assertIs<WidgetModel.Continue>(model)
        assertEquals(ContinueTarget.Comic("별 1권"), model.target)
        assertEquals("만화", model.byline)
    }

    @Test
    fun `a book just finished gives way to the one before it but a finished book read again counts`() {
        // 방금 끝낸 책을 보이면 "이어 읽기" 가 할 일이 없다.
        val justFinished = candidate("다 읽은 책", readAt = 9_000, percent = 100f, finishedAt = 9_000)
        val closedAtEnd = candidate("끝에서 덮은 책", readAt = 8_000, percent = 97f, finishedAt = 7_990)
        val reading = candidate("읽는 책", readAt = 2_000)
        assertEquals(ContinueTarget.Book("읽는 책"), (continueModel(listOf(justFinished, closedAtEnd, reading)) as WidgetModel.Continue).target)
        // 다 읽고 며칠 뒤 다시 펼쳐 읽는 책은 다시 읽는 중이다.
        val reread = candidate("다시 읽는 책", readAt = 10 * 86_400_000L, percent = 12f, finishedAt = 1_000)
        assertEquals(ContinueTarget.Book("다시 읽는 책"), (continueModel(listOf(reread, reading)) as WidgetModel.Continue).target)
        // 다 읽은 것뿐이면 빈 판.
        assertEquals(WidgetModel.Empty, continueModel(listOf(justFinished)))
        assertEquals(WidgetModel.Empty, continueModel(emptyList()))
    }

    @Test
    fun `percent rounds down and the remaining time appears only when it is known`() {
        // 99.6% 를 "100%" 로 올리면 다 읽지 않은 책이 다 읽은 것처럼 보인다.
        val nearly = continueModel(listOf(candidate("책", readAt = 1, percent = 99.6f))) as WidgetModel.Continue
        assertEquals("99%", nearly.percentText)
        assertEquals(996, nearly.progress)
        assertNull(nearly.remaining)
        val known = continueModel(listOf(candidate("책", readAt = 1).copy(remainingMinutes = 80))) as WidgetModel.Continue
        assertEquals("남은 1시간 20분", known.remaining)
        assertEquals("45분", minutesWords(45))
        assertEquals("2시간", minutesWords(120))
    }

    // ── 그린 위젯 ────────────────────────────────────────────

    @Test
    fun `the widget shows title, author, percent and bar of the book read last`() {
        val prince = bookId("어린 왕자.epub")
        val memo = bookId("메모.txt")
        runBlocking {
            data.library.updateMetadata(prince, "어린 왕자", "생텍쥐페리")
            data.progress.save(ReadingProgress(prince, Locator.Reflow(1, 0), 61f, 5_000))
            data.progress.save(ReadingProgress(memo, Locator.Reflow(0, 10), 10f, 1_000))
            data.comics.saveProgress(comicId(), 1, 3, 2_000)
        }
        val view = widget()
        assertEquals("이어 읽기", view.text(R.id.widget_label))
        assertEquals("어린 왕자", view.text(R.id.widget_title))
        assertEquals("생텍쥐페리", view.text(R.id.widget_byline))
        assertEquals("61%", view.text(R.id.widget_percent))
        assertEquals(610, view.findViewById<ProgressBar>(R.id.widget_progress).progress)
        // 남은 시간은 어림할 재료가 없으면 비운다 — 지어낸 숫자를 보이지 않는다.
        assertNull(view.text(R.id.widget_remaining))

        // 누르면 그 책으로 가는 인텐트가 MainActivity 에 간다.
        view.findViewById<View>(R.id.widget_root).performClick()
        val started = shadowOf(app).nextStartedActivity
        assertEquals(MainActivity::class.java.name, started.component?.className)
        assertEquals(ContinueTarget.Book(prince.value), ContinueWidget.targetOf(started))
    }

    @Test
    fun `a comic read last shows its work title and a book without a cover gets a title card`() {
        runBlocking {
            data.progress.save(ReadingProgress(bookId("메모.txt"), Locator.Reflow(0, 10), 10f, 1_000))
            data.comics.saveProgress(comicId(), 1, 3, 9_000)
        }
        val comic = widget()
        assertEquals("만화", comic.text(R.id.widget_byline))
        assertTrue(comic.text(R.id.widget_title)!!.startsWith("별"), comic.text(R.id.widget_title))
        runBlocking { data.progress.save(ReadingProgress(bookId("메모.txt"), Locator.Reflow(0, 20), 12f, 20_000)) }
        val txt = widget()
        // TXT 는 표지가 없다 — 빈 칸 대신 제목을 얹은 대신 표지.
        assertEquals("메모.txt", txt.text(R.id.widget_cover_title))
    }

    @Test
    fun `with nothing read the widget offers to open the app`() {
        val view = widget()
        assertEquals("OLO eBook 열기", view.text(R.id.widget_title))
        view.findViewById<View>(R.id.widget_root).performClick()
        val started = shadowOf(app).nextStartedActivity
        assertNull(ContinueWidget.targetOf(started))
    }

    @Test
    fun `a placed widget is redrawn after reading moves on`() {
        val manager = shadowOf(AppWidgetManager.getInstance(app))
        val id = manager.createWidget(ContinueWidget::class.java, R.layout.widget_continue_empty)
        val prince = bookId("어린 왕자.epub")
        runBlocking { data.progress.save(ReadingProgress(prince, Locator.Reflow(1, 0), 33f, 5_000)) }
        // 리더마다 고리를 걸지 않는다 — 진도 표가 바뀐 것만으로 잠깐 뒤 다시 그려진다.
        compose.waitUntil(15_000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            (manager.getViewFor(id)?.findViewById<TextView>(R.id.widget_percent))?.text?.toString() == "33%"
        }
    }

    @Test
    fun `widget colours are the theme's colours in light and dark`() {
        // 위젯은 CpTheme 을 읽지 못해 값을 옮겨 적었다. 테마를 바꾸고 여기를 잊으면 홈 화면의 위젯만 옛 색이 된다.
        fun color(dark: Boolean, id: Int): Int {
            val config = Configuration(app.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or (if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
            }
            return app.createConfigurationContext(config).getColor(id)
        }
        for ((dark, c) in listOf(false to LightColors, true to DarkColors)) {
            assertEquals(c.surface.toArgb(), color(dark, R.color.widget_surface))
            assertEquals(c.text.toArgb(), color(dark, R.color.widget_text))
            assertEquals(c.textMuted.toArgb(), color(dark, R.color.widget_text_muted))
            assertEquals(c.accent.toArgb(), color(dark, R.color.widget_accent))
            assertEquals(c.accentText.toArgb(), color(dark, R.color.widget_accent_text))
            assertEquals(c.progressTrack.toArgb(), color(dark, R.color.widget_track))
            assertEquals(c.tiles.archive.toArgb(), color(dark, R.color.widget_cover_blank))
        }
    }

    // ── 누르면 ──────────────────────────────────────────────

    @Test
    fun `tapping the widget while the app is open opens that book at the page it was left on`() {
        launch(Intent(app, MainActivity::class.java))
        waitFor("어린 왕자", substring = true)
        compose.onAllNodes(hasText("어린 왕자", substring = true), useUnmergedTree = true)[0].performClick()
        waitFor("1 / ", substring = true)
        repeat(2) {
            compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.9f)) }
            compose.waitForIdle()
        }
        waitFor("3 / ", substring = true)
        // 만화를 펴 둔 채 위젯을 누른다 — 보던 것을 닫고 그 책의 읽던 쪽으로.
        scenario!!.onActivity { it.onNewIntent(ContinueWidget.intentFor(app, ContinueTarget.Comic(comicId()))) }
        waitForComicPage(1)
        scenario!!.onActivity { it.onNewIntent(ContinueWidget.intentFor(app, ContinueTarget.Book(bookId("어린 왕자.epub").value))) }
        waitFor("3 / ", substring = true)
    }

    @Test
    fun `a cold start from the widget opens the comic at its saved page`() {
        runBlocking { data.comics.saveProgress(comicId(), 1, 3, 5_000) }
        launch(ContinueWidget.intentFor(app, ContinueTarget.Comic(comicId())))
        waitForComicPage(2)
    }

    @Test
    fun `a book that has gone away opens the library with a notice instead of failing`() {
        val prince = bookId("어린 왕자.epub")
        File(root, "소설/어린 왕자.epub").delete()
        runBlocking { data.rescanAll() }
        launch(ContinueWidget.intentFor(app, ContinueTarget.Book(prince.value)))
        waitFor("읽던 책을 찾을 수 없습니다", substring = true)
        waitFor("메모", substring = true)
        assertTrue(!has("이 책을 열지 못했습니다"))
        // 엉뚱한 값(망가진 런처 · 지운 뒤 다시 깐 앱의 옛 위젯)도 같다.
        scenario!!.onActivity { it.onNewIntent(ContinueWidget.intentFor(app, ContinueTarget.Comic("content://nowhere/1"))) }
        waitFor("읽던 책을 찾을 수 없습니다", substring = true)
        // 값이 빠진 위젯 인텐트는 위젯 것이 아니다 — 앱 아이콘처럼 다룬다.
        assertNull(ContinueWidget.targetOf(Intent(ContinueWidget.ACTION_CONTINUE)))
        assertNull(ContinueWidget.targetOf(Intent(ContinueWidget.ACTION_CONTINUE).putExtra("book", "")))
        assertNull(ContinueWidget.targetOf(Intent(Intent.ACTION_MAIN).putExtra("book", BookId("x").value)))
    }
}
