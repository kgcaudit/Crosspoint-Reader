package io.github.kgcaudit.reader.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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
import kotlin.test.assertTrue

/**
 * 펼침면 표지(0.52.x): 앞 · 뒤표지를 한 장에 스캔한 만화는 표지 칸에서 두 쪽이 납작한 띠로 섰다. 자동 표지 · 위젯은 앞표지 반만,
 * 장면에서 고르기는 보이는 반 · 고른 곳만 쓴다.
 *
 * 1쪽은 펼침면(왼쪽 반 파랑 · 오른쪽 반 초록, 120×90), 2쪽은 빨강 세로 쪽. 왼→오 책의 앞표지는 오른쪽(초록), 오→왼 책은 왼쪽(파랑)
 * 이다 — 색으로 어느 반이 쓰였는지 본다.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
class ComicSpreadCoverTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>(StandardTestDispatcher())

    @After
    fun settle() = compose.waitForIdle()

    private val app = ApplicationProvider.getApplicationContext<OloApp>()
    private val data get() = app.container.data
    private val shots = File(System.getProperty("reader.screenshots") ?: "build/screenshots").apply { mkdirs() }
    private val red = 0xFFD03030.toInt()
    private val blue = 0xFF3050D0.toInt()
    private val green = 0xFF30A050.toInt()

    private fun png(w: Int, h: Int, paint: (Bitmap) -> Unit): ByteArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply(paint)
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private lateinit var root: File

    @Before
    fun setUp() {
        // 띠 풀기는 Robolectric 에서 빈 그림을 준다 — 앱의 대신 길(줄여 풀고 자르기)로 시험한다.
        app.container.comicRegions = false
        root = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        File(root, "C").mkdirs()
        ZipOutputStream(File(root, "C/별 01권.cbz").outputStream()).use { zip ->
            fun put(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            put("001.png", png(120, 90) { b -> for (x in 0 until 120) for (y in 0 until 90) b.setPixel(x, y, if (x < 60) blue else green) })
            put("002.png", png(60, 90) { it.eraseColor(red) })
        }
        runBlocking {
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

    private fun screen(): Bitmap {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        return bitmap
    }

    private fun shot(name: String) = File(shots, "$name.png").outputStream().use { screen().compress(Bitmap.CompressFormat.PNG, 100, it) }

    private fun near(pixel: Int, color: Int, tolerance: Int = 40): Boolean {
        fun d(c: Int) = listOf(android.graphics.Color::red, android.graphics.Color::green, android.graphics.Color::blue).sumOf { f -> (f(pixel) - f(c)).let { it * it } }
        return d(color) < tolerance * tolerance * 3
    }

    private fun work() = runBlocking { data.comics.works().first().single() }

    private fun setRightToLeft(rtl: Boolean) = runBlocking { data.comics.setRightToLeft(work(), rtl) }

    private fun setSplit(on: Boolean) {
        val prefs = app.container.prefs.load()
        app.container.prefs.save(prefs.copy(screen = prefs.screen.copy(comicSplitSpreads = on)))
        compose.activityRule.scenario.recreate()
    }

    /** 서재의 작품 칸을 길게 눌러 "보던 장면에서 표지 고르기" 로 연다. */
    private fun openPick() {
        click(hasText("만화 1"))
        val cover = hasContentDescription("별 표지")
        waitFor(cover)
        node(cover).performTouchInput { longClick() }
        click(hasText("보던 장면에서 표지 고르기"))
        waitFor(hasText("표지로 쓸 쪽"))
        compose.mainClock.advanceTimeBy(1_000)
    }

    /** 사람이 고른 작품 표지(없으면 null). */
    private fun customCover(): Bitmap? = runBlocking {
        app.container.covers.work(work().key).takeIf { it.custom }?.image?.asAndroidBitmap()
    }

    private fun pickAndWait() {
        click(hasText("이 쪽을 표지로"))
        compose.waitUntil(30_000) { !has(hasText("표지로 쓸 쪽")) }
        compose.waitUntil(10_000) { customCover() != null }
    }

    private fun assertCover(bitmap: Bitmap, color: Int, what: String) {
        assertTrue(bitmap.height > bitmap.width, "$what: 표지가 세로로 길지 않다(${bitmap.width}×${bitmap.height}) — 두 쪽이 다 들어갔다")
        // 왼쪽 끝 · 오른쪽 끝까지 한 색이어야 한 반만 든 것이다.
        for (x in listOf(2, bitmap.width / 2, bitmap.width - 3)) {
            val p = bitmap.getPixel(x, bitmap.height / 2)
            assertTrue(near(p, color), "$what: ${x}px 의 색이 ${Integer.toHexString(p)}")
        }
    }

    @Test
    fun `picking a cover while a split half is shown keeps only that half`() {
        // 휴대폰 세로 한 쪽 보기는 펼침면을 반씩 넘긴다(기본 켬). 처음 보이는 것은 1쪽 왼쪽 반(파랑).
        openPick()
        waitFor(hasText("1쪽 · 왼쪽 반"))
        shot("pick-half")
        pickAndWait()
        // 자동 표지(왼→오의 앞표지 = 초록)와 다른 색이라, 고른 반이 쓰였는지 · 두 쪽이 다 들어갔는지 함께 갈린다.
        assertCover(customCover()!!, blue, "왼쪽 반을 보며 골랐다")
    }

    @Test
    fun `the right half shown second is named and saved as the right half`() {
        openPick()
        waitFor(hasText("1쪽 · 왼쪽 반"))
        compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.9f)) }
        compose.mainClock.advanceTimeBy(1_000)
        waitFor(hasText("1쪽 · 오른쪽 반"))
        pickAndWait()
        assertCover(customCover()!!, green, "오른쪽 반을 보며 골랐다")
    }

    @Test
    fun `a whole spread offers left, right or whole with the front side chosen for each direction`() {
        // 나누기를 끄면 펼침면이 통째로 보인다 — 어느 곳을 쓸지 고르게 하고, 처음에는 앞표지 쪽(왼→오: 오른쪽)을 골라 둔다.
        setSplit(false)
        openPick()
        waitFor(hasText("1쪽 — 표지로 쓸 곳을 고릅니다"))
        waitFor(hasText("표지로 쓸 곳"))
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("오른쪽") and isSelected()).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodes(hasText("왼쪽") and isSelected()).fetchSemanticsNodes().isEmpty(), "두 곳이 함께 골라져 있다")
        // 고르지 않은 반(왼쪽, 파랑)은 어둡게 덮이고, 고른 반(오른쪽, 초록)은 그대로 보인다. 그림은 위에 붙어 폭 400dp × 높이 300dp.
        val density = compose.activity.resources.displayMetrics.density
        val s = screen()
        val y = (220 * density).toInt()
        val dimmed = s.getPixel((100 * density).toInt(), y)
        val kept = s.getPixel((300 * density).toInt(), y)
        assertTrue(near(kept, green), "고른 반이 그대로가 아니다: ${Integer.toHexString(kept)}")
        assertTrue(!near(dimmed, blue) && android.graphics.Color.blue(dimmed) < 0xD0 / 2, "고르지 않은 반이 어둡게 덮이지 않았다: ${Integer.toHexString(dimmed)}")
        shot("pick-whole")
        pickAndWait()
        assertCover(customCover()!!, green, "앞표지 쪽(오른쪽)을 그대로 골랐다")
    }

    @Test
    fun `a right-to-left whole spread starts with the left half chosen`() {
        setRightToLeft(true)
        setSplit(false)
        openPick()
        waitFor(hasText("표지로 쓸 곳"))
        // 오→왼 책의 앞표지는 왼쪽이다. 왼→오처럼 오른쪽을 골라 두면 뒤표지(줄거리 · 바코드)가 표지가 된다.
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("왼쪽") and isSelected()).fetchSemanticsNodes().isNotEmpty() }
        pickAndWait()
        assertCover(customCover()!!, blue, "오→왼의 앞표지(왼쪽)")
    }

    @Test
    fun `choosing whole keeps both pages and nothing is dimmed`() {
        setSplit(false)
        openPick()
        click(hasText("통째로"))
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("통째로") and isSelected()).fetchSemanticsNodes().isNotEmpty() }
        val density = compose.activity.resources.displayMetrics.density
        val left = screen().getPixel((100 * density).toInt(), (220 * density).toInt())
        assertTrue(near(left, blue), "통째로인데 왼쪽 반이 덮였다: ${Integer.toHexString(left)}")
        pickAndWait()
        val cover = customCover()!!
        assertTrue(cover.width > cover.height, "통째로 골랐는데 반만 남았다")
    }

    @Test
    fun `the automatic cover of a wide first picture is the front half and flips with the direction`() {
        click(hasText("만화 1"))
        val cover = hasContentDescription("별 표지")
        fun shows(color: Int): Boolean {
            if (!has(cover)) return false
            val box = node(cover).fetchSemanticsNode().boundsInRoot
            if (box.height <= box.width) return false
            val s = screen()
            val y = box.center.y.toInt()
            // 칸의 왼쪽 4분의 1 · 오른쪽 4분의 1 이 모두 같은 색이어야 한 반이다 — 두 쪽짜리 띠면 두 색이 갈린다.
            return near(s.getPixel((box.left + box.width / 4).toInt(), y), color) && near(s.getPixel((box.right - box.width / 4).toInt(), y), color)
        }
        assertTrue(runCatching { compose.waitUntil(30_000) { shows(green) } }.isSuccess, "왼→오 책의 서재 표지가 앞표지(오른쪽 반, 초록)가 아니다")
        val unit = runBlocking { data.comics.units().first().single() }
        assertCover(runBlocking { app.container.covers.comic(unit, false) }!!.asAndroidBitmap(), green, "왼→오 자동 표지")
        // 방향을 바꾸면 앞표지가 왼쪽 반이다 — 저장해 둔 옛 반이 남으면 안 된다.
        setRightToLeft(true)
        assertTrue(runCatching { compose.waitUntil(30_000) { shows(blue) } }.isSuccess, "오→왼으로 바꿨는데 서재 표지가 왼쪽 반(파랑)으로 바뀌지 않았다")
        assertCover(runBlocking { app.container.covers.comic(unit, true) }!!.asAndroidBitmap(), blue, "오→왼 자동 표지")
    }

    @Test
    fun `the home screen widget shows the same front half`() {
        val unit = runBlocking { data.comics.units().first().single() }
        runBlocking { data.comics.saveProgress(unit.id, 0, 2, 5_000) }
        fun widgetCover(): Bitmap {
            val views = runBlocking { ContinueWidgets.views(app) }.apply(app, FrameLayout(app))
            val drawable = views.findViewById<ImageView>(R.id.widget_cover).drawable as android.graphics.drawable.BitmapDrawable
            return drawable.bitmap
        }
        // 위젯 칸은 가운데를 잘라 채운다(centerCrop) — 두 쪽짜리 띠면 책등이 칸 한가운데에 섰다.
        assertCover(widgetCover(), green, "왼→오 위젯 표지")
        setRightToLeft(true)
        assertCover(widgetCover(), blue, "오→왼 위젯 표지")
    }

    /** 펼침면 첫 쪽(왼쪽 반 파랑 · 오른쪽 반 초록)을 그리는 스캔 PDF 흉내. */
    private inner class SpreadPdf : io.github.kgcaudit.reader.pdf.PdfSource {
        override val pageCount: Int get() = 2
        override fun pageSize(index: Int) = if (index == 0) 1200 to 900 else 600 to 900
        override fun render(index: Int, target: Bitmap, region: io.github.kgcaudit.reader.layout.pdf.PageRegion) {
            val canvas = Canvas(target)
            canvas.drawColor(if (index == 0) green else red)
            if (index == 0) canvas.drawRect(0f, 0f, target.width / 2f, target.height.toFloat(), android.graphics.Paint().apply { color = blue })
        }
        override fun close() {}
    }

    @Test
    fun `a scanned book read as a comic gets the front half as its cover, a book read as a book stays whole`() {
        app.container.pdfEngine = { SpreadPdf() }
        File(root, "책").mkdirs()
        File(root, "책/스캔 만화.pdf").writeBytes("%PDF-1.4\n%%EOF\n".toByteArray())
        runBlocking { data.rescanAll() }
        val covers = app.container.covers
        fun book() = runBlocking { data.library.books().first().single { it.displayName == "스캔 만화.pdf" } }
        fun cover() = runBlocking { covers.cover(book()) }.image!!.asAndroidBitmap()
        // 책으로 보는 PDF 는 통째로 — 가로로 긴 발표 자료의 첫 쪽을 반으로 자르면 제목이 잘린다.
        val whole = cover()
        assertTrue(whole.width > whole.height, "책으로 보는 PDF 의 표지를 잘랐다")
        // 만화로 보면 앞표지(왼→오: 오른쪽 반)만. 보기를 바꾸면 서재가 표지를 다시 읽는다.
        runBlocking { data.library.setBookView(book().id, io.github.kgcaudit.reader.data.library.BookView.COMIC) }
        covers.facesChanged()
        assertCover(cover(), green, "만화로 보는 PDF")
        // 오→왼으로 바꾸면 왼쪽 반. 저장해 둔 오른쪽 반이 남으면 안 된다.
        runBlocking { data.library.setBookRightToLeft(book().id, true) }
        covers.facesChanged()
        assertCover(cover(), blue, "오→왼으로 보는 PDF")
    }
}
