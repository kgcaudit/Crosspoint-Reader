package io.github.kgcaudit.reader.app

import android.graphics.Bitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.data.library.BookView
import io.github.kgcaudit.reader.data.library.ComicLibrary
import io.github.kgcaudit.reader.document.pdf.TestPdf
import io.github.kgcaudit.reader.document.pdf.TestPdf.Companion.pages
import io.github.kgcaudit.reader.ui.design.CpMetrics
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
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 두 쪽 보기의 표지 따로 · 함께를 작품 · 책마다(구상안 5). 겉표지와 속표지가 둘 다 든 권은 표지를 "따로" 두면 속표지가 1쪽과
 * 짝이 되어 본문 펼침이 모두 한 쪽씩 어긋난다. 그 작품만 "함께" 로 바꾸고, 다른 작품은 모든 보기 설정의 기본값을 따른다.
 *
 * 가로 태블릿에서 연다 — 가로 휴대폰은 보기 판이 굴러야 아래 줄에 닿고, 그 굴리기가 시험 힙을 넘겼다(ComicSpreadAppTest 와 같은 사정).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-mdpi")
class CoverChoiceAppTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>(StandardTestDispatcher())

    @After
    fun settleTurn() = compose.waitForIdle()

    private val app = ApplicationProvider.getApplicationContext<OloApp>()
    private val comics get() = app.container.data.comics
    private val library get() = app.container.data.library

    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(60, 90, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** 세로로 선 쪽 여섯: 겉표지 · 속표지 · 본문 1–4. "따로" 면 [0] [1,2] [3,4] [5], "함께" 면 [0,1] [2,3] [4,5]. */
    private fun volume(file: File) = ZipOutputStream(file.outputStream()).use { zip ->
        listOf(0xFF303030, 0xFFD03030, 0xFF3050D0, 0xFF30A050, 0xFFE0A020, 0xFF9040C0).forEachIndexed { i, c ->
            zip.putNextEntry(ZipEntry("%03d.png".format(i + 1)))
            zip.write(png(c.toInt()))
            zip.closeEntry()
        }
    }

    @Before
    fun setUp() {
        val root = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        File(root, "C").mkdirs()
        volume(File(root, "C/별 01권.cbz"))
        volume(File(root, "C/달 01권.cbz"))
        File(root, "문서").mkdirs()
        val pdf = TestPdf().run { pages(10, 6); obj(1, "<< /Type /Catalog /Pages 10 0 R >>"); classic() }
        File(root, "문서/잡지.pdf").writeBytes(pdf)
        File(root, "문서/도록.pdf").writeBytes(pdf)
        File(root, "문서/화집.pdf").writeBytes(pdf)
        app.container.pdfEngine = { DrawnPdf(it, pageCount = 6) }
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
    private fun back() = compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

    private fun spread(label: String) = hasContentDescription("만화 ${label}쪽")
    private fun next() {
        compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.95f)) }
        compose.mainClock.advanceTimeBy(1_000)
    }

    private fun openVolume(work: String) {
        click(hasText("만화 2"))
        click(hasContentDescription("$work 작품"))
        click(hasText("1권"))
        waitFor(spread("1"))
    }

    private fun work(title: String) = runBlocking { comics.works().first().single { it.title == title } }

    /** [label] 줄의 [option] 을 고른다. 같은 글자의 단추가 여러 줄에 있다 — 그 줄과 같은 높이의 것. */
    private fun choose(label: String, option: String) {
        waitFor(hasText(label))
        // 굴러가는 판 아래쪽 줄은 화면 밖이라 잘린 테두리가 0 이다 — 잘리지 않은 자리로 잰다.
        val y = middleY(node(hasText(label)).fetchSemanticsNode())
        val button = hasText(option) and hasClickAction()
        val k = compose.onAllNodes(button).fetchSemanticsNodes().indexOfFirst { abs(middleY(it) - y) < 30f }
        check(k >= 0) { "$label 줄에 $option 이 없다" }
        compose.onAllNodes(button)[k].performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun middleY(n: androidx.compose.ui.semantics.SemanticsNode) = n.positionInRoot.y + n.size.height / 2f

    /** 글자가 시작하는 자리(잘리지 않은 것). */
    private fun startX(text: String) = node(hasText(text)).fetchSemanticsNode().positionInRoot.x

    /** 누르기 동작으로 누른다 — 판이 굴러야 보이는 줄(가로 태블릿의 "모든 보기 설정" 등)은 좌표로 누르면 판 밖을 누른다. */
    private fun press(text: String) {
        val button = hasText(text) and hasClickAction()
        // 글자와 누르기는 합친 나무에서 한 마디다(나눈 나무에서는 글자 마디에 누르기가 없다).
        compose.waitUntil(30_000) { compose.onAllNodes(button).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(button).let { it[it.fetchSemanticsNodes().size - 1] }
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun openViewPanel() {
        compose.onRoot().performTouchInput { click(center) }
        click(hasText("보기"))
        waitFor(hasText("가로에서 두 쪽 보기"))
    }

    @Test
    fun `choosing together for one work re-pairs at once around the page in view and leaves other works on the default`() {
        openVolume("별")
        next(); waitFor(spread("2–3"))
        openViewPanel()
        // 두 쪽 보기 줄 아래 한 단 안쪽(글자만 있는 줄의 자식, UI 규칙 1) — 같은 자리에서 시작하면 같은 급의 선택지로 읽힌다.
        waitFor(hasText("표지"))
        val step = CpMetrics().levelIndent.value * compose.activity.resources.displayMetrics.density
        assertEquals(step, startX("표지") - startX("가로에서 두 쪽 보기"), 1f, "표지 줄이 두 쪽 보기 줄의 한 단 안쪽에서 시작하지 않는다")
        assertTrue(middleY(node(hasText("표지")).fetchSemanticsNode()) > middleY(node(hasText("가로에서 두 쪽 보기")).fetchSemanticsNode()), "표지 줄이 두 쪽 보기 줄 아래에 있지 않다")
        assertTrue(has(hasText("이 작품만 · 겉표지와 속표지가 있으면 \"함께\" 로 두면 본문 펼침이 맞습니다")), "이 작품만 바뀐다는 풀이가 없다")

        choose("표지", "함께")
        // 보던 2쪽이 든 판(1–2)이 곧바로 보인다 — 처음으로 돌아가거나 다른 판으로 튀지 않는다.
        waitFor(spread("1–2"))
        compose.waitUntil(10_000) { work("별").coverAlone == false }
        assertEquals(true, app.container.prefs.load().screen.pdfCoverAlone, "작품의 값을 고르는데 모든 책의 기본값이 바뀌었다")
        back(); back()
        next(); waitFor(spread("3–4"))
        next(); waitFor(spread("5–6"))

        // 다른 작품은 그대로 기본값(따로).
        assertEquals(null, work("달").coverAlone)
        // 서재로 돌아가는 화면 바뀜이 끝난 뒤 누른다 — 사라지는 중인 별 작품 화면의 "1권"(다 읽은 권)을 누르면 별이 다시 열린다.
        back(); settle()
        back(); settle()
        click(hasContentDescription("달 작품"))
        settle()
        click(hasText("1권"))
        waitFor(spread("1"))
        next(); waitFor(spread("2–3"))
    }

    @Test
    fun `the cover row is there only while two pages are shown`() {
        openVolume("별")
        next(); waitFor(spread("2–3"))
        openViewPanel()
        waitFor(hasText("표지"))
        // 가로 두 쪽을 끄면 한 쪽 보기 — 표지 줄은 바꿔도 화면에 아무 일이 없어 사라진다.
        choose("가로에서 두 쪽 보기", "끔")
        waitFor(spread("2"))
        compose.waitForIdle()
        assertFalse(has(hasText("표지")), "한 쪽 보기인데 표지 줄이 보인다")
        choose("가로에서 두 쪽 보기", "켬")
        waitFor(spread("2–3"))
        waitFor(hasText("표지"))
    }

    @Test
    fun `the default in all view settings does not override a work's own choice, and clearing them all hands it back`() {
        // 별은 "따로" 를 손으로 골라 두었다(기본값과 같은 값이어도 고른 것이다 — 규칙 5).
        runBlocking { comics.setCoverAlone(work("별"), true) }
        openVolume("별")
        openViewPanel()
        press("모든 보기 설정")
        waitFor(hasText("표지 (처음 여는 책)"))
        assertTrue(has(hasText("작품마다 보기 판에서 바꾼 값이 이보다 먼저입니다. 바꾼 작품 1개")), "바꾼 작품 수가 보이지 않는다")
        // 기본값의 자식: "가로에서 두 쪽 보기" 의 한 단 안쪽.
        val step = CpMetrics().levelIndent.value * compose.activity.resources.displayMetrics.density
        assertEquals(step, startX("표지 (처음 여는 책)") - startX("가로에서 두 쪽 보기"), 1f, "기본값 줄이 두 쪽 보기 줄의 한 단 안쪽에서 시작하지 않는다")

        choose("표지 (처음 여는 책)", "함께")
        compose.waitUntil(10_000) { !app.container.prefs.load().screen.pdfCoverAlone }
        back(); back(); back()
        // 기본값이 "함께" 가 되어도 별은 제가 고른 "따로" 다.
        waitFor(spread("1"))
        next(); waitFor(spread("2–3"))

        openViewPanel()
        press("모든 보기 설정")
        press("작품마다 바꾼 값 모두 지우기")
        waitFor(hasText("바꾼 값 1개를 지우고 모두 기본값을 따르게 할까요?"))
        press("지우기")
        compose.waitUntil(10_000) { runBlocking { comics.coverChoices().first() }.works.isEmpty() }
        assertEquals(null, work("별").coverAlone)
        // 지울 것이 없으면 단추도 수도 없다.
        compose.waitUntil(10_000) { !has(hasText("작품마다 바꾼 값 모두 지우기")) }
        assertTrue(has(hasText("작품마다 보기 판에서 바꾼 값이 이보다 먼저입니다.")))
        back(); back(); back()
        // 이제 기본값(함께)을 따른다 — 보던 2쪽이 든 판.
        waitFor(spread("1–2"))
    }

    @Test
    fun `a broken stored cover value is treated as not chosen`() {
        // 망가뜨린 입력: 다음 판이 쓴 값. 어느 한쪽으로 읽지 않고 기본값을 따른다. 기본값을 "함께" 로 두어, "따로" 로 잘못
        // 읽으면 걸리게 한다.
        runBlocking {
            val p = app.container.prefs.load()
            app.container.prefs.save(p.copy(screen = p.screen.copy(pdfCoverAlone = false)))
        }
        android.database.sqlite.SQLiteDatabase.openDatabase(app.getDatabasePath("reader.db").path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("INSERT OR REPLACE INTO comic_overrides(kind, subject, value) VALUES (?, ?, ?)", arrayOf<Any>(ComicLibrary.COVER, work("별").key, "yes"))
        }
        compose.activityRule.scenario.recreate()
        click(hasText("만화 2"))
        click(hasContentDescription("별 작품"))
        click(hasText("1권"))
        waitFor(spread("1–2"))
        next(); waitFor(spread("3–4"))
    }

    // ── PDF · 만화로 본 책 ─────────────────────────────────────────

    private fun bookId(name: String) = runBlocking { library.books().first().first { it.displayName == name }.id }

    @Test
    fun `a pdf keeps its own cover choice, opens with it again, and another pdf follows the default`() {
        click(hasText("잡지.pdf"))
        waitFor(hasText("1 / 6"))
        compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.95f)) }
        compose.mainClock.advanceTimeBy(1_000)
        waitFor(hasText("2–3 / 6"))
        compose.onRoot().performTouchInput { click(center) }
        click(hasText("보기"))
        waitFor(hasText("쪽 맞춤"))
        assertTrue(has(hasText("이 책만 · 겉표지와 속표지가 있으면 \"함께\" 로 두면 본문 펼침이 맞습니다")), "PDF 보기 판에 이 책의 표지 줄이 없다")
        val step = CpMetrics().levelIndent.value * compose.activity.resources.displayMetrics.density
        assertEquals(step, startX("표지") - startX("쪽 맞춤"), 1f, "PDF 의 표지 줄이 쪽 맞춤 줄의 한 단 안쪽에서 시작하지 않는다")
        choose("표지", "함께")
        compose.waitUntil(10_000) { runBlocking { library.bookCoverAlone(bookId("잡지.pdf")) } == false }
        assertEquals(true, app.container.prefs.load().screen.pdfCoverAlone)
        back(); back()
        // 보던 2쪽이 든 펼침.
        waitFor(hasText("1–2 / 6"))

        // 닫았다 다시 열어도 이 책의 짝이다.
        back()
        click(hasText("잡지.pdf"))
        waitFor(hasText("1–2 / 6"))
        compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.95f)) }
        compose.mainClock.advanceTimeBy(1_000)
        waitFor(hasText("3–4 / 6"))
        back()

        // 다른 PDF 는 기본값(따로).
        click(hasText("도록.pdf"))
        waitFor(hasText("1 / 6"))
    }

    @Test
    fun `the pdf default changed in all view settings does not override a book's own choice`() {
        runBlocking { library.setBookCoverAlone(bookId("잡지.pdf"), true) }
        click(hasText("잡지.pdf"))
        waitFor(hasText("1 / 6"))
        compose.onRoot().performTouchInput { click(center) }
        click(hasText("보기"))
        press("모든 보기 설정")
        waitFor(hasText("표지 (처음 여는 책)"))
        // PDF 만 고쳤으면 "작품" 이 아니라 "책" 이라 부른다 — 무엇을 지우는지 알 수 있게.
        assertTrue(has(hasText("책마다 보기 판에서 바꾼 값이 이보다 먼저입니다. 바꾼 책 1개")), "바꾼 책 수가 보이지 않는다")
        choose("표지 (처음 여는 책)", "함께")
        compose.waitUntil(10_000) { !app.container.prefs.load().screen.pdfCoverAlone }
        back(); back(); back()
        waitFor(hasText("1 / 6"))
        back()
        click(hasText("도록.pdf"))
        waitFor(hasText("1–2 / 6"))
    }

    @Test
    fun `a book opened as a comic pairs its covers by the book's own choice`() {
        val id = bookId("화집.pdf")
        runBlocking {
            library.setBookView(id, BookView.COMIC)
            library.setBookCoverAlone(id, false)
        }
        click(hasText("화집.pdf"))
        waitFor(spread("1–2"))
        next(); waitFor(spread("3–4"))
        // 보기 판의 줄은 "이 책만" 이고, 고르면 책의 값에 적힌다.
        openViewPanel()
        assertTrue(has(hasText("이 책만 · 겉표지와 속표지가 있으면 \"함께\" 로 두면 본문 펼침이 맞습니다")), "만화로 본 책에 이 책의 표지 줄이 없다")
        choose("표지", "따로")
        compose.waitUntil(10_000) { runBlocking { library.bookCoverAlone(id) } == true }
        // 보던 3쪽이 든 판.
        waitFor(spread("2–3"))
        assertTrue(runBlocking { comics.coverChoices().first() }.works.isEmpty(), "책의 값이 작품의 값으로 적혔다")
    }
}
