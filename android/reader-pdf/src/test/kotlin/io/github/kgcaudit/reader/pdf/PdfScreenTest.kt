package io.github.kgcaudit.reader.pdf

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import io.github.kgcaudit.reader.document.BookFormat
import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.BookMeta
import io.github.kgcaudit.reader.document.Bookmark
import io.github.kgcaudit.reader.document.BookmarkRepository
import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.document.ProgressRepository
import io.github.kgcaudit.reader.document.ReadingProgress
import io.github.kgcaudit.reader.document.TocEntry
import io.github.kgcaudit.reader.listen.ListenHub
import io.github.kgcaudit.reader.listen.ListenKit
import io.github.kgcaudit.reader.listen.Speaker
import io.github.kgcaudit.reader.listen.SpeakerEvents
import io.github.kgcaudit.reader.ui.design.CpTheme
import io.github.kgcaudit.reader.ui.design.PageTurn
import io.github.kgcaudit.reader.ui.design.PdfFit
import io.github.kgcaudit.reader.ui.design.ScreenPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

/**
 * PDF 화면을 앱 없이 띄워, 화면에서만 드러나는 동작(밀기 · 폭 맞춤의 쪽 안 자리 · 듣기 판)을 본다. 엔진은 가짜다.
 * 가로 휴대폰 — 세로 A4 를 폭에 맞추면 한 쪽이 여러 화면이 된다.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w851dp-h393dp-xhdpi")
class PdfScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val id = BookId("content://books/screen.pdf")
    private val progress = SavedPlace()

    // 넘김 효과 없이 — 효과가 도는 동안은 옛 쪽과 새 쪽이 함께 있어 쪽 안 위치 표시가 둘이 된다.
    private val fitWidth = ScreenPrefs(pdfFit = PdfFit.Width, pageTurn = PageTurn.None)

    @After
    fun stopListening() = compose.runOnIdle { ListenHub.detach() }

    private fun reader(source: PdfSource = BlankPages(4), contents: List<TocEntry> = emptyList()) =
        PdfReader(PdfBook(BookMeta(id, BookFormat.PDF, "화면"), source, contents), NoBookmarks(), progress, Dispatchers.Unconfined) { 1_000L }
            .also { runBlocking { it.open() } }

    private fun waitFor(matcher: SemanticsMatcher) =
        compose.waitUntil(5_000) { compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun shows(matcher: SemanticsMatcher): Boolean =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `swiping back in fit width shows the end of the previous page like tapping back does`() {
        progress.place = Locator.FixedPage(1)
        val r = reader()
        compose.setContent { CpTheme(dark = false) { PdfScreen(r, onClose = {}, onChrome = {}, prefs = fitWidth) } }
        waitFor(hasText("2쪽 · 첫 화면", substring = true))
        // 앞 쪽으로 미는 것은 "방금 읽던 곳의 앞" 으로 가는 것이다 — 누름 · 볼륨 키처럼 그 쪽의 끝이 보여야 이어 읽힌다.
        compose.onRoot().performTouchInput { swipeRight() }
        waitFor(hasText("1쪽 · 끝 화면", substring = true))
        assertEquals(0, r.state.value.page)
    }

    @Test
    fun `a page read halfway down in fit width reopens at the same screen`() {
        val first = reader()
        var shown by mutableStateOf(first)
        compose.setContent { CpTheme(dark = false) { key(shown) { PdfScreen(shown, onClose = {}, onChrome = {}, prefs = fitWidth) } } }
        waitFor(hasText("1쪽 · 첫 화면", substring = true))
        val total = compose.onAllNodes(hasText("1쪽 · 첫 화면", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
            .first().config[androidx.compose.ui.semantics.SemanticsProperties.Text].first().text
            .substringAfter("(1/").substringBefore(")").toInt()
        check(total >= 3) { "가로에서 폭에 맞춘 A4 가 세 화면도 안 된다: $total" }
        // 다음 쪽 자리를 두 번 눌러 셋째 화면까지 내린다(한 번씩 기다린다 — 잇달아 누르면 두 번 누르기, 곧 확대다).
        // 화면마다 손을 멈춘 뒤 저장한다 — 저장된 자리가 앞 화면보다 내려갈 때까지 기다린다.
        fun saved() = (progress.place as? Locator.FixedPage)?.yPermille ?: 0
        for (i in 2..3) {
            val before = saved()
            compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.9f)) }
            waitFor(hasText("1쪽 · ", substring = true) and hasText("($i/$total)", substring = true))
            compose.waitUntil(5_000) { saved() > before }
        }
        first.close()

        // 책을 닫았다 다시 연다. 쪽 머리("첫 화면")가 아니라 읽던 셋째 화면.
        shown = reader()
        waitFor(hasText("1쪽 · ", substring = true) and hasText("(3/$total)", substring = true))
    }

    @Test
    fun `the chapter end timer is offered only for a pdf with contents`() {
        val texts = (0 until 4).associateWith { "${it + 1}쪽의 문장입니다." }
        var book by mutableStateOf(reader(BlankPages(4, texts)))
        val kit = ListenKit({ Silent() }, { emptyList() })
        compose.setContent { CpTheme(dark = false) { key(book) { PdfScreen(book, onClose = {}, onChrome = {}, listenKit = kit) } } }

        fun openListenSheet() {
            waitFor(hasText("1 / 4", substring = true))
            compose.onRoot().performTouchInput { click(center) }
            waitFor(hasContentDescription("듣기"))
            compose.onAllNodes(hasContentDescription("듣기"), useUnmergedTree = true)[0].performClick()
            waitFor(hasContentDescription("듣기 설정"))
            compose.onAllNodes(hasContentDescription("듣기 설정"), useUnmergedTree = true)[0].performClick()
            waitFor(hasText("타이머"))
        }

        // 목차가 없으면 장이 없다. "장 끝" 을 두면 골라도 책 끝까지 멈추지 않는다(전에는 쪽마다 멈췄다).
        openListenSheet()
        compose.waitForIdle()
        check(!shows(hasText("장 끝"))) { "목차 없는 PDF 에 장 끝이 있다" }
        check(shows(hasText("30분")))

        compose.runOnIdle { ListenHub.detach() }
        book = reader(BlankPages(4, texts), contents = listOf(TocEntry("1장", Locator.FixedPage(0)), TocEntry("2장", Locator.FixedPage(2))))
        openListenSheet()
        waitFor(hasText("장 끝"))
    }
}

/** 흰 쪽(A4 세로). [texts] 를 주면 글자 층이 있는 엔진(안드로이드 15+)이다. */
private class BlankPages(override val pageCount: Int, private val texts: Map<Int, String>? = null) : PdfSource {
    override fun pageSize(index: Int): Pair<Int, Int> = 595 to 842
    override fun render(index: Int, target: Bitmap, region: PageRegion) = target.eraseColor(Color.WHITE)
    override val readsText: Boolean get() = texts != null
    override fun pageText(index: Int): String? = texts?.let { it[index].orEmpty() }
    override fun textLayer(index: Int): PageText? = texts?.let { PageText.textOnly(it[index].orEmpty()) }
    override fun close() = Unit
}

private class Silent : Speaker {
    override var events: SpeakerEvents? = null
    override suspend fun prepare() = true
    override fun speak(id: String, text: String, flush: Boolean) = Unit
    override fun stop() = Unit
    override fun setRate(rate: Float) = Unit
    override fun setVoice(voice: String?) = Unit
    override fun shutdown() = Unit
}

private class SavedPlace : ProgressRepository {
    var place: Locator? = null
    override suspend fun get(bookId: BookId): ReadingProgress? = place?.let { ReadingProgress(bookId, it, 0f, 0L) }
    override suspend fun save(progress: ReadingProgress) {
        place = progress.locator
    }
    override suspend fun remove(bookId: BookId) {
        place = null
    }
}

private class NoBookmarks : BookmarkRepository {
    override suspend fun forBook(bookId: BookId): List<Bookmark> = emptyList()
    override suspend fun add(bookmark: Bookmark): Bookmark = bookmark
    override suspend fun remove(id: Long) = Unit
}
