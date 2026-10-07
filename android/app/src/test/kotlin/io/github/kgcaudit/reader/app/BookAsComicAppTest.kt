package io.github.kgcaudit.reader.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.data.library.BookView
import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.pdf.PageRegion
import io.github.kgcaudit.reader.pdf.PdfSource
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
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 그림 위주의 책을 만화 뷰어로(0.50.0): 그림만 든 EPUB 은 처음부터 만화로, 스캔 PDF 는 고르면 만화로. 표지 판의 한 줄로 오가고,
 * 읽던 자리 · 책갈피는 책의 것을 함께 쓴다.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class BookAsComicAppTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>(StandardTestDispatcher())

    @After
    fun settleTurn() = compose.waitForIdle()

    private val app = ApplicationProvider.getApplicationContext<OloApp>()
    private val red = 0xFFD03030.toInt()
    private val blue = 0xFF3050D0.toInt()
    private val green = 0xFF30A050.toInt()

    private fun png(color: Int): ByteArray {
        val b = Bitmap.createBitmap(60, 90, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** 장마다 그림 한 장인 EPUB(일본 만화처럼 오→왼). */
    private fun pictureEpub(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val mime = "application/epub+zip".toByteArray()
            zip.putNextEntry(ZipEntry("mimetype").apply { method = ZipEntry.STORED; size = mime.size.toLong(); compressedSize = size; crc = CRC32().apply { update(mime) }.value })
            zip.write(mime)
            fun put(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes) }
            put("META-INF/container.xml", """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""".toByteArray())
            put(
                "OEBPS/content.opf",
                """<package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>고양이 탐정</dc:title></metadata>
                <manifest>${(1..3).joinToString("") { """<item id="p$it" href="Text/p$it.xhtml" media-type="application/xhtml+xml"/>""" }}</manifest>
                <spine page-progression-direction="rtl">${(1..3).joinToString("") { """<itemref idref="p$it"/>""" }}</spine></package>""".toByteArray(),
            )
            listOf(red, blue, green).forEachIndexed { i, color ->
                put("OEBPS/Text/p${i + 1}.xhtml", """<html><head><title>p</title></head><body><div><img src="../Images/${i + 1}.png"/></div></body></html>""".toByteArray())
                put("OEBPS/Images/${i + 1}.png", png(color))
            }
        }
        return out.toByteArray()
    }

    /** 스캔 PDF 흉내: 쪽마다 한 색. */
    private class ColorPdf(private val colors: List<Int>) : PdfSource {
        override val pageCount: Int get() = colors.size
        override fun pageSize(index: Int) = 600 to 900
        override fun render(index: Int, target: Bitmap, region: PageRegion) = Canvas(target).drawColor(colors[index])
        override fun close() {}
    }

    @Before
    fun setUp() {
        val root = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        File(root, "책").mkdirs()
        File(root, "책/고양이 탐정.epub").writeBytes(pictureEpub())
        File(root, "책/스캔 만화.pdf").writeBytes("%PDF-1.4\n%%EOF\n".toByteArray())
        File(root, "책/어린 왕자.epub").writeBytes(SampleBooks.epub())
        app.container.pdfEngine = { ColorPdf(listOf(red, blue, green)) }
        runBlocking {
            val data = app.container.data
            data.folders.register(FolderProvider.treeUri)
            data.rescanAll()
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

    private fun centerColor(): Int {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(b)) }
        return b.getPixel(b.width / 2, b.height / 2)
    }

    private fun near(pixel: Int, color: Int): Boolean {
        fun d(c: Int) = listOf(android.graphics.Color::red, android.graphics.Color::green, android.graphics.Color::blue).sumOf { f -> (f(pixel) - f(c)).let { it * it } }
        return d(color) < 40 * 40 * 3
    }

    private fun waitForColor(color: Int, message: String) =
        assertTrue(runCatching { compose.waitUntil(30_000) { near(centerColor(), color) } }.isSuccess, message)

    private fun back() = compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

    private fun idOf(name: String) = runBlocking { app.container.data.library.books().first().first { it.displayName == name }.id }

    private fun menuOf(name: String) {
        waitFor(hasText(name))
        node(hasText(name)).performTouchInput { longClick() }
        waitFor(hasText("사진 · 파일에서 표지 고르기"))
    }

    @Test
    fun `a picture-only epub opens as a comic in the direction the book gives and keeps the book's place`() {
        click(hasText("고양이 탐정.epub"))
        waitFor(hasContentDescription("만화 1쪽"))
        waitForColor(red, "그림책 첫 쪽(빨강)이 만화 뷰어에 그려지지 않았다")
        // 오→왼 책: 왼쪽을 누르면 다음 쪽.
        compose.onRoot().performTouchInput { click(centerLeft.copy(x = width * 0.1f)) }
        compose.mainClock.advanceTimeBy(1_000)
        waitFor(hasContentDescription("만화 2쪽"))
        waitForColor(blue, "둘째 쪽(파랑)이 아니다")
        // 자리는 책의 것 — 둘째 그림이 든 장.
        compose.waitUntil(10_000) { runBlocking { app.container.data.progress.get(idOf("고양이 탐정.epub"))?.locator } == Locator.Reflow(1, 0) }
        // 보기 판에 보는 방식 줄이 없다(쪽 넘김뿐) · 넘기는 방향은 오→왼.
        compose.onRoot().performTouchInput { click(center) }
        click(hasText("보기"))
        waitFor(hasText("넘기는 방향"))
        assertFalse(has(hasText("보는 방식")), "책을 만화로 볼 때 보는 방식 줄이 보인다")
        // 만화로만 열어도 책 제목을 적는다 — 위 막대와 서재에 파일 이름이 남지 않는다.
        assertTrue(has(hasText("고양이 탐정")), "위 막대가 책 제목 대신 파일 이름을 보인다")
        assertEquals("고양이 탐정", runBlocking { app.container.data.library.get(idOf("고양이 탐정.epub"))?.label })
    }

    @Test
    fun `a picture book switched to the book view opens in the book viewer, and back`() {
        menuOf("고양이 탐정.epub")
        click(hasText("책으로 보기"))
        compose.waitUntil(10_000) { runBlocking { app.container.data.library.bookView(idOf("고양이 탐정.epub")) } == BookView.BOOK }
        click(hasText("고양이 탐정.epub"))
        waitFor(hasText("1 / ", substring = true))
        assertFalse(has(hasContentDescription("만화 1쪽")), "책으로 보기를 골랐는데 만화로 열렸다")
        back()
        compose.waitForIdle()
        // 책으로 한 번 열면 서재는 파일 이름 대신 책 제목을 보인다.
        menuOf("고양이 탐정")
        click(hasText("만화로 보기"))
        compose.waitUntil(10_000) { runBlocking { app.container.data.library.bookView(idOf("고양이 탐정.epub")) } == BookView.COMIC }
    }

    @Test
    fun `a scanned pdf is read as a comic only when chosen, and a text epub is never offered`() {
        // 고르기 전: PDF 는 PDF 뷰어.
        menuOf("스캔 만화.pdf")
        click(hasText("만화로 보기"))
        compose.waitUntil(10_000) { runBlocking { app.container.data.library.bookView(idOf("스캔 만화.pdf")) } == BookView.COMIC }
        click(hasText("스캔 만화.pdf"))
        waitFor(hasContentDescription("만화 1쪽"))
        waitForColor(red, "PDF 첫 쪽이 만화 뷰어에 그려지지 않았다")
        compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.9f)) }
        compose.mainClock.advanceTimeBy(1_000)
        waitFor(hasContentDescription("만화 2쪽"))
        waitForColor(blue, "PDF 둘째 쪽이 아니다")
        compose.waitUntil(10_000) { runBlocking { app.container.data.progress.get(idOf("스캔 만화.pdf"))?.locator } == Locator.FixedPage(1) }
        back()
        compose.waitForIdle()
        // 글 EPUB 에는 만화로 보기 줄이 없다 — 그림만 보이면 글이 사라진다.
        menuOf("어린 왕자.epub")
        // 판정은 입출력 스레드에서 끝난다 — 끝나기 전에 "줄이 없다" 를 보면 늘 맞는 시험이 된다. 판정이 적힐 때까지 기다린다.
        val prince = runBlocking { app.container.data.library.books().first().first { it.displayName == "어린 왕자.epub" } }
        compose.waitUntil(10_000) { runBlocking { app.container.data.library.pictureBook(prince.id, prince.sizeBytes) } != null }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertFalse(has(hasText("만화로 보기")), "글 EPUB 에 만화로 보기 줄이 있다")
        assertFalse(has(hasText("책으로 보기")))
    }

    @Test
    fun `a bookmark set in the comic view is the book's bookmark`() {
        click(hasText("고양이 탐정.epub"))
        waitFor(hasContentDescription("만화 1쪽"))
        // 오른쪽 위 모서리를 누르면 책갈피(만화와 같다).
        compose.onRoot().performTouchInput { click(topRight.copy(x = width - 20f, y = 40f)) }
        compose.waitUntil(10_000) { runBlocking { app.container.data.bookmarks.forBook(idOf("고양이 탐정.epub")) }.map { it.locator } == listOf(Locator.Reflow(0, 0)) }
        assertEquals(1, runBlocking { app.container.data.bookmarks.forBook(idOf("고양이 탐정.epub")) }.size)
    }
}
