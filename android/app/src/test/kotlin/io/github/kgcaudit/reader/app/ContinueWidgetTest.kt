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
import androidx.compose.ui.test.onLast
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

    /** 지금 위젯을 뷰로 펴 본다(런처가 하는 일). 크기를 주면 그 크기로 재고 놓는다. */
    private fun widget(size: WidgetSize = WidgetSize.Wide, settings: WidgetSettings = WidgetSettings(), id: Int = 0, dp: Pair<Int, Int>? = null): View {
        val views = runBlocking { ContinueWidgets.views(app, size, settings, id) }
        return views.apply(app, FrameLayout(app)).apply {
            if (dp != null) {
                val d = app.resources.displayMetrics.density
                val w = (dp.first * d).toInt()
                val h = (dp.second * d).toInt()
                measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                layout(0, 0, w, h)
            }
        }
    }

    private fun View.shown(id: Int) = findViewById<View>(id)?.visibility == View.VISIBLE

    /** 책 셋 · 만화 하나를 차례로 읽은 기록. 가장 최근: 어린 왕자 → 만화 → 메모. */
    private fun readThree() = runBlocking {
        data.library.updateMetadata(bookId("어린 왕자.epub"), "어린 왕자", "생텍쥐페리")
        data.progress.save(ReadingProgress(bookId("어린 왕자.epub"), Locator.Reflow(1, 0), 61f, 9_000))
        data.comics.saveProgress(comicId(), 1, 3, 8_000)
        data.progress.save(ReadingProgress(bookId("메모.txt"), Locator.Reflow(0, 10), 10f, 7_000))
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

    @Test
    fun `the recent list holds the next unfinished reads newest first and never repeats the one on top`() {
        val list = listOf(
            candidate("지금 책", readAt = 9_000),
            candidate("다 읽은 책", readAt = 8_000, percent = 100f, finishedAt = 8_000),
            ContinueCandidate(ContinueTarget.Comic("별 2권"), "별 2권", "만화", 30f, 7_000, null),
            candidate("그제 책", readAt = 5_000),
            candidate("어제 책", readAt = 6_000),
            candidate("지난달 책", readAt = 1_000),
        )
        val all = continueModel(list) as WidgetModel.Continue
        assertEquals(ContinueTarget.Book("지금 책"), all.target)
        // 다 읽은 책은 목록에도 없다 — 눌러 봐야 끝 쪽이 열린다. 세 줄까지만.
        assertEquals(listOf("별 2권", "어제 책", "그제 책"), all.recent.map { it.title })
        // 목록 거르기는 목록에만 닿는다 — 맨 위 한 권은 그대로 가장 최근 것.
        val books = continueModel(list, RecentKind.Books) as WidgetModel.Continue
        assertEquals(ContinueTarget.Book("지금 책"), books.target)
        assertEquals(listOf("어제 책", "그제 책", "지난달 책"), books.recent.map { it.title })
        assertEquals(listOf("별 2권"), (continueModel(list, RecentKind.Comics) as WidgetModel.Continue).recent.map { it.title })
        // 읽던 것이 하나뿐이면 목록이 비고, 위젯은 목록 칸을 숨긴다.
        assertEquals(emptyList(), (continueModel(list.take(2)) as WidgetModel.Continue).recent)
    }

    @Test
    fun `the layout follows the widget size`() {
        // 2×2 는 한 권, 4×2 는 목록(낮으면 두 줄), 4×4 는 큰 판. 런처가 아직 크기를 알리지 않은 것은 render 가 넓은 판으로 받는다.
        assertEquals(WidgetSize.Small, WidgetSize.of(130f, 130f))
        assertEquals(WidgetSize.Small, WidgetSize.of(150f, 400f))
        // 칸이 큰 휴대폰의 2×2(230dp 남짓 정사각)도 한 권 — 0.56.1 은 넓은 판을 눌러 담아 글이 세로로 접혔다.
        assertEquals(WidgetSize.Small, WidgetSize.of(235f, 235f))
        assertEquals(WidgetSize.Small, WidgetSize.of(260f, 180f))
        // 좁은 휴대폰(폭 360dp)의 4×2 는 넓은 판.
        assertEquals(WidgetSize.Wide, WidgetSize.of(310f, 170f))
        assertEquals(WidgetSize.WideShort, WidgetSize.of(300f, 110f))
        assertEquals(WidgetSize.Wide, WidgetSize.of(300f, 180f))
        assertEquals(WidgetSize.Wide, WidgetSize.of(330f, 280f))
        assertEquals(WidgetSize.Large, WidgetSize.of(330f, 380f))
    }

    // ── 그린 위젯 ────────────────────────────────────────────

    @Test
    fun `each recent row opens its own book and the rest of the widget opens the one on top`() {
        readThree()
        val view = widget()
        assertEquals("최근에 읽은", view.text(R.id.widget_recent_label))
        assertTrue(view.text(R.id.widget_recent_title_1)!!.startsWith("별"), view.text(R.id.widget_recent_title_1))
        assertEquals("메모.txt", view.text(R.id.widget_recent_title_2))
        assertEquals("10%", view.text(R.id.widget_recent_percent_2))
        assertEquals(100, view.findViewById<ProgressBar>(R.id.widget_recent_progress_2).progress)
        // 둘뿐이면 셋째 줄은 비워 두지 않고 숨긴다.
        assertTrue(!view.shown(R.id.widget_recent_3))

        // 줄마다 다른 책. 요청 번호가 같으면 안드로이드가 PendingIntent 를 하나로 보고 마지막 줄의 책으로 덮어썼다.
        view.findViewById<View>(R.id.widget_recent_1).performClick()
        assertEquals(ContinueTarget.Comic(comicId()), ContinueWidget.targetOf(shadowOf(app).nextStartedActivity))
        view.findViewById<View>(R.id.widget_recent_2).performClick()
        assertEquals(ContinueTarget.Book(bookId("메모.txt").value), ContinueWidget.targetOf(shadowOf(app).nextStartedActivity))
        view.findViewById<View>(R.id.widget_root).performClick()
        assertEquals(ContinueTarget.Book(bookId("어린 왕자.epub").value), ContinueWidget.targetOf(shadowOf(app).nextStartedActivity))
    }

    @Test
    fun `small widgets show one book, short ones two rows and the large one adds authors and thumbnails`() {
        readThree()
        // 목록이 세 줄 차야 낮은 판이 정말 두 줄로 줄이는지 보인다 — 두 권뿐이면 줄이지 않아도 두 줄이다.
        File(root, "소설/둘째.txt").writeBytes(SampleBooks.txt())
        runBlocking {
            data.rescanAll()
            data.progress.save(ReadingProgress(bookId("메모.txt"), Locator.Reflow(0, 10), 10f, 8_500))
            data.progress.save(ReadingProgress(bookId("둘째.txt"), Locator.Reflow(0, 10), 20f, 6_000))
        }
        val small = widget(WidgetSize.Small)
        assertEquals("어린 왕자", small.text(R.id.widget_title))
        assertEquals("61%", small.text(R.id.widget_percent))
        assertNull(small.findViewById<View>(R.id.widget_recent))

        val wide = widget(WidgetSize.Wide)
        assertTrue(wide.shown(R.id.widget_recent_3))
        val short = widget(WidgetSize.WideShort)
        assertTrue(short.shown(R.id.widget_recent_2))
        assertTrue(!short.shown(R.id.widget_recent_3))

        val large = widget(WidgetSize.Large)
        assertEquals("만화", large.text(R.id.widget_recent_byline_2))
        // 만화는 작은 표지가, TXT 는 빈 표지 칸이 선다.
        assertTrue(large.shown(R.id.widget_recent_blank_1))
        assertTrue(!large.shown(R.id.widget_recent_blank_2))
        assertTrue(large.findViewById<android.widget.ImageView>(R.id.widget_recent_cover_2).drawable != null)
    }

    @Test
    fun `the cover keeps its own proportions instead of being cropped to a box`() {
        // 0.55 까지는 84dp 칸에 꽉 채워 표지가 잘렸다(사용자 지적). 만화 표지 60×90 → 놓인 표지 칸도 2:3 이어야 한다.
        runBlocking { data.comics.saveProgress(comicId(), 1, 3, 9_000) }
        val view = widget(WidgetSize.Wide, dp = 340 to 165)
        val cover = view.findViewById<android.widget.ImageView>(R.id.widget_cover)
        assertTrue(cover.drawable != null && !view.shown(R.id.widget_cover_title))
        assertEquals(60f / 90f, cover.width.toFloat() / cover.height, 0.03f)
        assertEquals(android.widget.ImageView.ScaleType.FIT_CENTER, cover.scaleType)
    }

    @Test
    fun `settings switch the background off, dim it and force light or dark colours`() {
        readThree()
        assertTrue(widget().shown(R.id.widget_bg))
        assertTrue(!widget(settings = WidgetSettings(background = false)).shown(R.id.widget_bg))
        assertEquals(255, widget().findViewById<android.widget.ImageView>(R.id.widget_bg).imageAlpha)
        assertEquals(127, widget(settings = WidgetSettings(opacity = 50)).findViewById<android.widget.ImageView>(R.id.widget_bg).imageAlpha)
        // 휴대폰은 밝은데 "어둡게" 를 고르면 글자가 어두운 테마의 색이다. "휴대폰" 이면 자원 색(밤낮을 따르는) 그대로.
        fun titleColor(v: View) = v.findViewById<TextView>(R.id.widget_title).currentTextColor
        assertEquals(DarkColors.text.toArgb(), titleColor(widget(settings = WidgetSettings(color = WidgetColor.Dark))))
        assertEquals(LightColors.text.toArgb(), titleColor(widget(settings = WidgetSettings(color = WidgetColor.Light))))
        assertEquals(LightColors.text.toArgb(), titleColor(widget()))
        assertEquals(DarkColors.textMuted.toArgb(), widget(settings = WidgetSettings(color = WidgetColor.Dark)).findViewById<TextView>(R.id.widget_recent_percent_1).currentTextColor)
        // 목록 거르기: 만화만이면 만화 줄 하나.
        val comics = widget(settings = WidgetSettings(recent = RecentKind.Comics))
        assertTrue(comics.text(R.id.widget_recent_title_1)!!.startsWith("별"))
        assertTrue(!comics.shown(R.id.widget_recent_2))
    }

    @Test
    fun `settings are kept for each widget and forgotten when the widget is removed`() {
        val first = WidgetSettings(background = false, opacity = 25, color = WidgetColor.Dark, recent = RecentKind.Books)
        WidgetSettingsStore.save(app, 7, first)
        WidgetSettingsStore.save(app, 8, WidgetSettings(opacity = 75))
        assertEquals(first, WidgetSettingsStore.load(app, 7))
        assertEquals(75, WidgetSettingsStore.load(app, 8).opacity)
        // 놓을 때 설정을 건너뛴 위젯은 기본값.
        assertEquals(WidgetSettings(), WidgetSettingsStore.load(app, 9))
        ContinueWidget().onDeleted(app, intArrayOf(7))
        assertEquals(WidgetSettings(), WidgetSettingsStore.load(app, 7))
        assertEquals(75, WidgetSettingsStore.load(app, 8).opacity)
        // 망가진 값(옛 판 · 손으로 고친 파일)도 위젯을 그린다.
        app.getSharedPreferences("widget_settings", 0).edit().putInt("opacity_9", 61).putString("color_9", "Purple").commit()
        assertEquals(WidgetSettings(opacity = 50), WidgetSettingsStore.load(app, 9))
    }

    @Test
    fun `the picker preview is the wide widget with sample words`() {
        // 0.55 까지는 위젯 고르기 화면에 앱 아이콘만 보였다. 미리보기는 넓은 판과 같은 뼈대여야 홈 화면에 놓인 모양과 같다.
        fun ids(layout: Int): Set<Int> {
            val root = android.view.LayoutInflater.from(app).inflate(layout, FrameLayout(app), false)
            val out = mutableSetOf<Int>()
            fun walk(v: View) { if (v.id != View.NO_ID) out += v.id; if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
            walk(root)
            return out
        }
        assertEquals(ids(R.layout.widget_continue), ids(R.layout.widget_continue_preview))
        val preview = android.view.LayoutInflater.from(app).inflate(R.layout.widget_continue_preview, FrameLayout(app), false)
        assertEquals("어린 왕자", preview.text(R.id.widget_title))
        assertEquals("최근에 읽은", preview.text(R.id.widget_recent_label))
        // 2×2 항목도 작은 판과 같은 뼈대 · 본보기 글이다.
        assertEquals(ids(R.layout.widget_continue_small), ids(R.layout.widget_continue_single_preview))
        assertEquals("어린 왕자", android.view.LayoutInflater.from(app).inflate(R.layout.widget_continue_single_preview, FrameLayout(app), false).text(R.id.widget_title))
        // 고르기 화면에 두 항목(4×2 · 2×2, 확정 구상안). 0.56.0 은 받는 쪽이 하나라 4×2 만 보였다.
        fun provider(cls: Class<*>): Map<String, String> {
            val meta = app.packageManager.getReceiverInfo(android.content.ComponentName(app, cls), android.content.pm.PackageManager.GET_META_DATA)
            val parser = app.resources.getXml(meta.metaData.getInt("android.appwidget.provider"))
            while (parser.next() != org.xmlpull.v1.XmlPullParser.START_TAG) Unit
            return (0 until parser.attributeCount).associate { parser.getAttributeName(it) to parser.getAttributeValue(it) }
        }
        val wide = provider(ContinueWidget::class.java)
        val single = provider(ContinueWidgetSingle::class.java)
        assertEquals("4" to "2", wide["targetCellWidth"] to wide["targetCellHeight"])
        assertEquals("2" to "2", single["targetCellWidth"] to single["targetCellHeight"])
        assertEquals("@${R.layout.widget_continue_single_preview}", single["previewLayout"])
        // 설정 화면 · 크기 바꾸기는 두 항목이 같다.
        assertEquals(wide["configure"], single["configure"])
        assertEquals(wide["widgetFeatures"], single["widgetFeatures"])
        val info = app.packageManager.getReceiverInfo(android.content.ComponentName(app, ContinueWidget::class.java), android.content.pm.PackageManager.GET_META_DATA)
        assertTrue(info.metaData.getInt("android.appwidget.provider") != 0)
    }

    @Test
    fun `the settings screen saves for its widget and leaves the widget unplaced when cancelled`() {
        readThree()
        val manager = shadowOf(AppWidgetManager.getInstance(app))
        val id = manager.createWidget(ContinueWidget::class.java, R.layout.widget_continue_empty)
        // 저장은 위젯을 다시 그린 뒤(DB 를 읽는다) 닫는다 — 닫힐 때까지 메인 루퍼를 돌리며 기다린다.
        fun ActivityScenario<WidgetSettingsActivity>.waitClosed() = compose.waitUntil(15_000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            var finishing = true
            runCatching { onActivity { finishing = it.isFinishing } }
            finishing
        }
        fun open() = ActivityScenario.launchActivityForResult<WidgetSettingsActivity>(
            Intent(app, WidgetSettingsActivity::class.java).setAction(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
        )
        val saved = open()
        compose.waitUntil(15_000) { has("위젯 설정") }
        compose.onAllNodes(hasText("끔"), useUnmergedTree = true)[0].performClick()
        compose.onAllNodes(hasText("만화만"), useUnmergedTree = true)[0].performClick()
        // 저장하기 전에 화면을 돌려도(활동이 새로 만들어진다) 고른 것이 남는다 — 전에는 처음 값으로 돌아가 옛 설정이 저장됐다.
        saved.recreate()
        compose.waitUntil(15_000) { has("위젯 설정") }
        compose.onAllNodes(hasText("저장"), useUnmergedTree = true)[0].performClick()
        saved.waitClosed()
        assertEquals(android.app.Activity.RESULT_OK, saved.result.resultCode)
        assertEquals(id, saved.result.resultData.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
        val settings = WidgetSettingsStore.load(app, id)
        assertEquals(false, settings.background)
        assertEquals(RecentKind.Comics, settings.recent)
        // 저장하면 그 위젯을 바로 다시 그린다(안드로이드 11 아래는 설정 화면이 첫 그림을 그려야 한다).
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(manager.getViewFor(id)?.findViewById<View>(R.id.widget_bg)?.visibility != View.VISIBLE)

        val cancelled = open()
        compose.waitUntil(15_000) { has("위젯 설정") }
        compose.onAllNodes(hasText("켬"), useUnmergedTree = true).onLast().performClick()
        compose.onAllNodes(hasText("취소"), useUnmergedTree = true).onLast().performClick()
        cancelled.waitClosed()
        assertEquals(android.app.Activity.RESULT_CANCELED, cancelled.result.resultCode)
        assertEquals(false, WidgetSettingsStore.load(app, id).background)
    }

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
        // 고르기 화면의 2×2 항목("한 권")으로 놓은 위젯도 같이 다시 그려진다 — 받는 쪽이 달라 빠뜨리면 옛 책에 머문다.
        val single = manager.createWidget(ContinueWidgetSingle::class.java, R.layout.widget_continue_empty)
        val prince = bookId("어린 왕자.epub")
        runBlocking { data.progress.save(ReadingProgress(prince, Locator.Reflow(1, 0), 33f, 5_000)) }
        // 리더마다 고리를 걸지 않는다 — 진도 표가 바뀐 것만으로 잠깐 뒤 다시 그려진다.
        fun percent(widget: Int) = manager.getViewFor(widget)?.findViewById<TextView>(R.id.widget_percent)?.text?.toString()
        compose.waitUntil(15_000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            percent(id) == "33%" && percent(single) == "33%"
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
