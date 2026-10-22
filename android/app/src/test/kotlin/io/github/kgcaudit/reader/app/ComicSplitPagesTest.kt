package io.github.kgcaudit.reader.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import io.github.kgcaudit.reader.data.ComicPages
import io.github.kgcaudit.reader.document.comic.ComicUnit
import io.github.kgcaudit.reader.document.comic.ComicUnitKind
import io.github.kgcaudit.reader.document.comic.SpreadSplit
import io.github.kgcaudit.reader.document.image.ImageSize
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 펼침면 나누기의 반 조각 그림(0.50.0)과 쪽 그림 풀기. 크기는 실제 스캔본과 같게 둔다 — 3000×2100 펼침면을 휴대폰(786 픽셀
 * 폭)에서 본다. 작은 그림으로 시험하면 저장소에 모두 들어가 반 조각이 밀려나는 일 · 통째로 푸는 일이 드러나지 않는다.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ComicSplitPagesTest {

    private val blue = 0xFF3050D0.toInt()
    private val green = 0xFF30A050.toInt()
    private val white = 0xFFFFFFFF.toInt()

    /** 휴대폰 세로 화면(393dp × xhdpi)의 그림 자리. */
    private val screenW = 786
    private val screenH = 1700

    /** [w]×[h] 펼침면: 바탕 흰색, [left]..[fold) 파랑, [fold]..[right) 초록(바깥은 흰 여백). */
    private fun spread(w: Int, h: Int, left: Int = 0, fold: Int = w / 2, right: Int = w): ByteArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(b).apply {
            drawColor(white)
            drawRect(left.toFloat(), 0f, fold.toFloat(), h.toFloat(), Paint().apply { color = blue })
            drawRect(fold.toFloat(), 0f, right.toFloat(), h.toFloat(), Paint().apply { color = green })
        }
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray().also { b.recycle() }
    }

    private val unit = ComicUnit("u", "별 01권.cbz", listOf("C"), ComicUnitKind.ARCHIVE)

    private fun book(pages: List<ByteArray>, budget: Int = ComicBook.memoryBudget()) =
        ComicBook(unit, ComicPages(List(pages.size) { "%03d.png".format(it + 1) }, { n -> pages[n.take(3).toInt() - 1].inputStream() }, null), regions = false, budget = budget)

    private fun near(pixel: Int, color: Int): Boolean {
        fun d(c: Int) = listOf(android.graphics.Color::red, android.graphics.Color::green, android.graphics.Color::blue).sumOf { f -> (f(pixel) - f(c)).let { it * it } }
        return d(color) < 40 * 40 * 3
    }

    private fun count(b: Bitmap, color: Int): Int {
        val px = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        return px.count { near(it, color) }
    }

    @Test
    fun `a half pushed out of its cache is cut again from the spread, so a page turn has something to draw`() {
        // 넘김 효과(말림 · 덮기)는 넘기는 순간 앞뒤 조각을 새로 그리며 저장소만 본다. 반 조각 저장소가 이웃 반에 밀려 비면
        // 말리는 종이가 검게 비었다. 반 저장소 한도를 1바이트로 둬 무엇을 넣어도 곧바로 밀려나게 한다.
        val bytes = spread(3000, 2100)
        val base = book(listOf(bytes, bytes))
        val parts = SpreadSplit.parts(listOf(ImageSize(3000, 2100), ImageSize(3000, 2100)), rightToLeft = false)
        val pages = SplitPages(base, parts, budget = 1)
        runBlocking { for (i in parts.indices) assertNotNull(pages.page(i, screenW, screenH)) }
        for (i in parts.indices) {
            val b = assertNotNull(pages.cached(i, screenW, screenH), "${i}번 조각이 저장소에서 사라져 넘김 효과가 그릴 것이 없다")
            // 왼쪽 반은 파랑, 오른쪽 반은 초록 — 다시 자른 것도 제 반이다.
            val color = if (parts[i].half == SpreadSplit.Half.LEFT) blue else green
            assertEquals(b.width * b.height, count(b, color), "${i}번 조각이 제 반이 아니다")
        }
    }

    @Test
    fun `the current half and its neighbours survive even when both caches let them go`() {
        // 책 저장소도 1바이트: 붙잡은 조각(지금 · 앞뒤)만 남아야 한다. 붙잡지 않은 조각은 놓는다 — 다 붙잡으면 긴 권에서 넘친다.
        val bytes = spread(3000, 2100)
        val base = book(listOf(bytes, bytes, bytes), budget = 1)
        val parts = SpreadSplit.parts(List(3) { ImageSize(3000, 2100) }, rightToLeft = false)
        val pages = SplitPages(base, parts, budget = 1)
        pages.pin(setOf(1, 2, 3))
        runBlocking { for (i in parts.indices) pages.page(i, screenW, screenH) }
        for (i in 1..3) assertNotNull(pages.cached(i, screenW, screenH), "붙잡은 ${i}번 조각이 사라졌다")
        assertNull(pages.cached(5, screenW, screenH), "붙잡지 않은 조각까지 붙잡았다")
    }

    @Test
    fun `a big scan is decoded at the size it is shown, not at full resolution`() {
        // 3000×2100 을 화면 두 배 폭(1572)에 맞추면 2의 거듭제곱 솎기로는 줄지 않는다(1500 < 1572) — 통째(25MB)로 남았다.
        val base = book(listOf(spread(3000, 2100)))
        val b = assertNotNull(runBlocking { base.page(0, screenW * 2, screenH) })
        assertTrue(b.width <= screenW * 2 && b.width >= screenW * 2 - 2, "화면에 맞춘 크기로 줄이지 않았다: ${b.width}×${b.height}")
        assertEquals(2100f / 3000f, b.height.toFloat() / b.width, 0.01f)
        // 반 조각도 그 절반이다.
        val pages = SplitPages(base, SpreadSplit.parts(listOf(ImageSize(3000, 2100)), rightToLeft = false))
        val half = assertNotNull(runBlocking { pages.page(0, screenW, screenH) })
        assertTrue(half.width <= screenW, "반 조각이 화면보다 넓다: ${half.width}")
        // 조금 큰 것(1.25 배 안)은 그대로 둔다 — 줄이자고 한 벌 더 만들지 않는다.
        val small = book(listOf(spread(1700, 1190)))
        assertEquals(1700, assertNotNull(runBlocking { small.page(0, 1572, screenH) }).width)
    }

    @Test
    fun `running out of memory once does not mark a page broken for good`() {
        // 그려 내는 쪽(스캔 PDF)이 메모리가 모자란 척한다: 처음 [fail] 번은 OutOfMemoryError.
        var fail = 0
        var calls = 0
        val sizes = mutableListOf<Pair<Int, Int>>()
        val renderer = object : ComicPages.Renderer {
            override fun size(index: Int) = ImageSize(600, 900)
            override fun render(index: Int, maxWidth: Int, maxHeight: Int): Bitmap? {
                calls++
                if (fail-- > 0) throw OutOfMemoryError("test")
                sizes += maxWidth to maxHeight
                return Bitmap.createBitmap(maxWidth, maxHeight, Bitmap.Config.ARGB_8888)
            }
        }
        val book = ComicBook(unit, ComicPages(listOf("a"), { null }, null, renderer), regions = false)
        // 한 번 모자라면 저장소를 비우고 반 크기로 다시 — 그 쪽은 그려진다.
        fail = 1
        assertNotNull(runBlocking { book.page(0, 400, 600) }, "한 번 모자랐다고 쪽을 그리지 않았다")
        assertEquals(400 / 2 to 600 / 2, sizes.last())
        // 두 번 다 모자라면 이번만 빈손이다 — 깨진 쪽으로 적으면 권을 닫을 때까지 "그리지 못했습니다" 로 남는다.
        fail = 2
        assertNull(runBlocking { book.page(0, 500, 700) })
        assertFalse(book.isBroken(0), "메모리가 잠깐 모자랐던 쪽을 깨진 쪽으로 적었다")
        // 나중에 다시 하면 그려진다.
        val before = calls
        assertNotNull(runBlocking { book.page(0, 500, 700) }, "메모리가 돌아왔는데 다시 풀지 않았다")
        assertTrue(calls > before)
        // 정말 그림이 아닌 쪽(그려 내지 못함)은 여전히 깨진 쪽이다 — 다시 애쓰지 않는다.
        val none = ComicBook(unit, ComicPages(listOf("a"), { null }, null), regions = false)
        assertNull(runBlocking { none.page(0, 400, 600) })
        assertTrue(none.isBroken(0))
    }

    @Test
    fun `with uneven outer margins the halves are cut at the fold, the same size, and without the other page`() {
        // 왼쪽 바깥 여백 10%, 오른쪽 2% 인 펼침면. 접힌 자리는 원래 그림의 가운데(300). 0.52 까지는 잘린 그림의 가운데를
        // 갈라, 왼쪽 반에 오른쪽 쪽(초록)의 띠가 섞였다.
        val bytes = spread(600, 420, left = 60, fold = 300, right = 588)
        val base = book(listOf(bytes))
        val parts = SpreadSplit.parts(listOf(ImageSize(600, 420)), rightToLeft = false)
        val pages = SplitPages(base, parts)
        val left = assertNotNull(runBlocking { pages.page(0, 300, 420, ComicTrim.Own) })
        val right = assertNotNull(runBlocking { pages.page(1, 300, 420, ComicTrim.Own) })
        assertEquals(0, count(left, green), "왼쪽 반에 오른쪽 쪽이 섞였다")
        assertEquals(0, count(right, blue), "오른쪽 반에 왼쪽 쪽이 섞였다")
        assertEquals(left.width, right.width, "두 반의 폭이 다르다")
        // 가운데 가장자리를 잃지 않았다: 왼쪽 반의 오른쪽 끝 줄은 파랑, 오른쪽 반의 왼쪽 끝 줄은 초록.
        assertTrue(near(left.getPixel(left.width - 1, left.height / 2), blue), "왼쪽 반이 접힌 자리에 닿지 않는다")
        assertTrue(near(right.getPixel(0, right.height / 2), green), "오른쪽 반이 접힌 자리에서 시작하지 않는다")
        // 그래도 여백은 걷혔다(덜 걷힌 오른쪽만큼 양쪽 모두) — 자르기를 끈 반보다 좁다.
        val whole = assertNotNull(runBlocking { pages.page(0, 300, 420, ComicTrim.Off) })
        assertTrue(left.width < whole.width, "여백을 전혀 걷지 않았다")
    }

    @Test
    fun `a spread whose header hides its size is still measured and split into two pages`() {
        // 머리로 크기를 못 읽는 그림(여기서는 SOF 앞에 4MB 넘는 덧붙인 조각이 있는 JPEG — 머리 읽기는 4MB 에서 멈춘다)도 화면에는
        // 나온다. 0.55.1 까지는 크기를 몰라 넓은 그림으로 판정되지 않고, 두 쪽 그림의 오른쪽 반을 건너뛰고 다음 그림으로 갔다.
        val b = Bitmap.createBitmap(1200, 840, Bitmap.Config.ARGB_8888).apply { eraseColor(blue) }
        val jpeg = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        val pad = ByteArrayOutputStream()
        repeat(70) { pad.write(byteArrayOf(0xFF.toByte(), 0xEF.toByte(), 0xFF.toByte(), 0xFF.toByte())); pad.write(ByteArray(0xFFFD)) }
        val hidden = jpeg.copyOfRange(0, 2) + pad.toByteArray() + jpeg.copyOfRange(2, jpeg.size)
        assertNull(io.github.kgcaudit.reader.document.image.ImageHeader.read(hidden.inputStream()), "시험 그림의 크기가 머리로 읽혔다 — 시험이 아무것도 재지 않는다")
        val sizes = runBlocking { book(listOf(spread(600, 900), hidden)).sizes() }
        assertEquals(ImageSize(1200, 840), sizes[1])
        assertEquals(
            listOf(SpreadSplit.Part(0, SpreadSplit.Half.WHOLE), SpreadSplit.Part(1, SpreadSplit.Half.LEFT), SpreadSplit.Part(1, SpreadSplit.Half.RIGHT)),
            SpreadSplit.parts(sizes, rightToLeft = false),
            "크기를 잰 펼침면이 두 반으로 나뉘지 않았다",
        )
    }
}
