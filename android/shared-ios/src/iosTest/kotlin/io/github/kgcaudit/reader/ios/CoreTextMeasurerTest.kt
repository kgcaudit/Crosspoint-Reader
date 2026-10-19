@file:OptIn(ExperimentalForeignApi::class)

package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.layout.Insets
import io.github.kgcaudit.reader.layout.LayoutSpec
import io.github.kgcaudit.reader.layout.Page
import io.github.kgcaudit.reader.layout.Paginator
import io.github.kgcaudit.reader.layout.TextAlign
import io.github.kgcaudit.reader.layout.TextMeasurer
import io.github.kgcaudit.reader.layout.TextStyle
import io.github.kgcaudit.reader.layout.conformance.MeasurerConformance
import io.github.kgcaudit.reader.layout.html.StyleContext
import io.github.kgcaudit.reader.layout.html.TextChapter
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextGetData
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextScaleCTM
import platform.CoreGraphics.CGContextSetRGBFillColor
import platform.CoreGraphics.CGContextTranslateCTM
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGRectMake
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoreTextMeasurerTest {
    private val spec = LayoutSpec(
        viewportWidthPx = 360f,
        viewportHeightPx = 640f,
        margin = Insets.all(24f),
        baseSizePx = 18f,
        lineHeightMultiplier = 1.6f,
        align = TextAlign.Justify,
    )

    private val text = "여섯 살 적에 나는 체험한 이야기라는 제목의 책에서 기막힌 그림 하나를 보았다. " +
        "In 1943 the pilot drew a box instead of a sheep, and the little prince was pleased. " +
        "똠방각하는 붉은 담요를 뒤집어쓰고 漢字 몇 자를 적었다.\n".repeat(1) +
        "나는 그 걸작품을 어른들에게 보여 주고 내 그림이 무섭지 않느냐고 물었다. ".repeat(6)

    @Test
    fun `the coretext measurer passes the shared conformance suite`() {
        // 안드로이드(AndroidTextMeasurerTest)와 같은 두 크기. 실패하면 줄 나눔 · 쪽 나눔이 두 앱에서 갈라진다.
        for (size in listOf(16f, 42f)) {
            val problems = MeasurerConformance.check(CoreTextMeasurer(size))
            assertTrue(problems.isEmpty(), "size $size:\n" + problems.joinToString("\n"))
        }
    }

    @Test
    fun `the conformance suite rejects a measurer that ignores font size`() {
        // 적합성 시험이 iOS 에서도 실제로 잡는지. 크기를 무시하는 측정기는 제목 · 각주의 줄이 본문과 같은 폭으로 나뉜다.
        val real = CoreTextMeasurer(16f)
        val broken = object : TextMeasurer by real {
            override fun advance(text: CharSequence, start: Int, endExclusive: Int, style: TextStyle) =
                real.advance(text, start, endExclusive, style.copy(sizeScale = 1f))
        }
        assertTrue(MeasurerConformance.check(broken).isNotEmpty())
    }

    @Test
    fun `bold and size scale change widths the way readers expect`() {
        val m = CoreTextMeasurer(20f)
        val latin = m.advance("Hello world", 0, 11, TextStyle())
        assertTrue(m.advance("Hello world", 0, 11, TextStyle(bold = true)) > latin * 1.03f, "bold Latin must be visibly wider")
        // 한글은 굵어도 네모 칸이라 폭이 거의 같다(대체 글꼴의 굵은 꼴 공백이 조금 좁다). 크게 달라지면 다른 글꼴이 걸린 것.
        val plain = m.advance("한글 문장", 0, 5, TextStyle())
        assertTrue(abs(m.advance("한글 문장", 0, 5, TextStyle(bold = true)) - plain) <= plain * 0.05f)
        // 기울임은 폭을 바꾸지 않는다(합성 기울임) — 바꾸면 기울인 단어가 든 줄만 넘친다.
        assertEquals(plain, m.advance("한글 문장", 0, 5, TextStyle(italic = true)))
        assertEquals(20f * 1.5f, m.lineHeight(TextStyle(sizeScale = 1.5f)))
    }

    @Test
    fun `every laid out line stays inside the text column`() {
        val page = firstPage()
        val m = CoreTextMeasurer.forSpec(spec)
        val right = spec.viewportWidthPx - spec.margin.right
        for (run in page.runs) {
            val end = run.xPx + m.advance(text, run.start, run.endExclusive, run.style)
            assertTrue(end <= right + 0.5f, "run ${text.substring(run.start, run.endExclusive)} ends at $end > $right")
            assertTrue(run.xPx >= spec.margin.left - 0.01f)
        }
    }

    @Test
    fun `drawn glyphs land where the layout measured them`() {
        // 그린 글자의 윗선은 위 여백에, 양쪽정렬한 줄의 끝은 오른 여백에 닿아야 한다. 좌표를 뒤집지 않으면 글자가 기준선
        // 아래로 그려지고, 잴 때와 다른 글꼴로 그리면 줄 끝이 여백을 넘거나 모자란다.
        val page = firstPage()
        val ink = inkBounds(page)
        assertTrue(abs(ink.top - spec.margin.top) <= 2, "top ink row ${ink.top}, margin ${spec.margin.top}")
        assertTrue(ink.left >= spec.margin.left - 2 && ink.left <= spec.margin.left + 3, "left ink ${ink.left}")
        val right = spec.viewportWidthPx - spec.margin.right
        assertTrue(ink.right <= right + 1 && ink.right >= right - 4, "right ink ${ink.right}, column ends at $right")
        assertTrue(ink.bottom <= spec.viewportHeightPx - spec.margin.bottom + 6, "bottom ink ${ink.bottom}")
    }

    private fun firstPage(): Page {
        val chapter = TextChapter.parse(text, StyleContext.of(spec))
        return Paginator(spec, CoreTextMeasurer.forSpec(spec)).paginate(chapter.text, chapter.blocks).first()
    }

    private class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /** 흰 비트맵(1pt = 1px)에 검게 그리고 잉크가 묻은 상자를 잰다. */
    private fun inkBounds(page: Page): Bounds {
        val w = spec.viewportWidthPx.toInt()
        val h = spec.viewportHeightPx.toInt()
        val space = CGColorSpaceCreateDeviceRGB()
        val context = CGBitmapContextCreate(null, w.toULong(), h.toULong(), 8u, (w * 4).toULong(), space,
            CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value)!!
        CGColorSpaceRelease(space)
        CGContextSetRGBFillColor(context, 1.0, 1.0, 1.0, 1.0)
        CGContextFillRect(context, CGRectMake(0.0, 0.0, w.toDouble(), h.toDouble()))
        // 비트맵은 아래가 0 이다. UIKit 처럼 위를 0 으로 뒤집고 그린다(UIGraphicsImageRenderer 가 하는 것).
        CGContextTranslateCTM(context, 0.0, h.toDouble())
        CGContextScaleCTM(context, 1.0, -1.0)
        PagePainter.draw(context, page, text, CoreTextMeasurer.forSpec(spec), ink = 0x000000)
        val data: CPointer<ByteVar> = CGBitmapContextGetData(context)!!.reinterpret()
        var left = w; var top = h; var right = -1; var bottom = -1
        for (y in 0 until h) for (x in 0 until w) {
            // 메모리의 첫 줄이 위쪽이다.
            val red = data[(y * w + x) * 4].toInt() and 0xFF
            if (red < 128) {
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        CGContextRelease(context)
        return Bounds(left, top, right + 1, bottom + 1)
    }
}
