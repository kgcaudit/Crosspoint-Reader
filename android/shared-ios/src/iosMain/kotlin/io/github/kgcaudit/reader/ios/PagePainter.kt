@file:OptIn(ExperimentalForeignApi::class)

package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.layout.Page
import io.github.kgcaudit.reader.layout.html.Link
import kotlin.math.pow
import io.github.kgcaudit.reader.layout.VerticalAlign
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGAffineTransformMake
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextRef
import platform.CoreGraphics.CGContextRestoreGState
import platform.CoreGraphics.CGContextSaveGState
import platform.CoreGraphics.CGContextSetBlendMode
import platform.CoreGraphics.CGBlendMode
import platform.CoreGraphics.CGContextSetRGBFillColor
import platform.CoreGraphics.CGContextSetRGBStrokeColor
import platform.CoreGraphics.CGContextSetTextMatrix
import platform.CoreGraphics.CGContextSetTextPosition
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreText.CTLineDraw
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage

/**
 * 조판된 [Page] 를 그린다(안드로이드 `PagePainter.drawPage` 와 같은 규칙). 좌표는 쪽 왼쪽 위에서 잰 pt 이고 여백이 이미
 * 들어 있다 — 그대로 그린다.
 *
 * 비트맵으로 내는 까닭: 넘김 효과(말림 · 덮기)는 앞뒤 쪽을 그림으로 들고 움직인다. 화면에 바로 그리면 효과를 넣을 때 다시
 * 짜야 한다.
 */
object PagePainter {
    /**
     * 한 쪽을 그림으로. [paper] · [ink] 는 0xRRGGBB. [measurer] 는 조판에 쓴 것과 **같은 설정**으로 만든 것이어야 한다 —
     * 다른 글꼴로 그리면 잰 폭과 그린 폭이 달라 양쪽정렬 끝이 들쭉날쭉해진다.
     */
    fun render(
        page: Page,
        text: String,
        measurer: CoreTextMeasurer,
        widthPt: Double,
        heightPt: Double,
        paper: Int,
        ink: Int,
        pictures: Map<String, UIImage>,
        blend: PictureBlend = PictureBlend.None,
        /** 찾은 말(장 안 글자 구간). 없으면 null. 글자 뒤에 [markColor] 30% 로 칠한다(안드로이드 highlightColor). */
        mark: IntRange? = null,
        markColor: Int = 0xB95B3B,
        /** 듣는 문장(장 안 글자 구간). 형광펜 **아래**에 [markColor] [speakingAlpha] 로 칠한다(안드로이드 0.18 · 검은 지면 0.30). */
        speaking: IntRange? = null,
        speakingAlpha: Double = 0.18,
        /** 각주 표시(장 안 글자 구간들). [noteColor] 로 그린다 — 작은 위첨자라 본문과 대비 4.5 이상인 색이어야 한다. */
        noteMarks: List<IntRange> = emptyList(),
        noteColor: Int = 0,
        /** 형광펜: (구간, 0xRRGGBB). [tintAlpha] 로 글자 뒤에 칠한다(밝은 지면 0.45 · 어두운 지면 0.38). */
        tints: List<Pair<IntRange, Int>> = emptyList(),
        tintAlpha: Double = 0.45,
        /** 메모 쪽지: (칠의 끝 글자 다음 자리, 색). 끝 글자 오른쪽 위 행간에 작은 쪽지. */
        memos: List<Pair<Int, Int>> = emptyList(),
    ): UIImage {
        val format = UIGraphicsImageRendererFormat.preferredFormat().apply { opaque = true }
        val renderer = UIGraphicsImageRenderer(size = CGSizeMake(widthPt, heightPt), format = format)
        return renderer.imageWithActions { context ->
            val cg = context?.CGContext ?: return@imageWithActions
            fill(cg, paper, 1.0)
            CGContextFillRect(cg, CGRectMake(0.0, 0.0, widthPt, heightPt))
            // 칠은 글자보다 먼저 — 나중에 칠하면 글자가 덮인다.
            if (speaking != null) {
                fill(cg, markColor, speakingAlpha)
                for (b in rangeBoxes(page, text, measurer, speaking.first, speaking.last + 1)) {
                    CGContextFillRect(cg, CGRectMake(b.left.toDouble(), b.top.toDouble(), (b.right - b.left).toDouble(), (b.bottom - b.top).toDouble()))
                }
            }
            for ((range, color) in tints) {
                fill(cg, color, tintAlpha)
                for (b in rangeBoxes(page, text, measurer, range.first, range.last + 1)) {
                    CGContextFillRect(cg, CGRectMake(b.left.toDouble(), b.top.toDouble(), (b.right - b.left).toDouble(), (b.bottom - b.top).toDouble()))
                }
            }
            if (mark != null) {
                fill(cg, markColor, 0.3)
                for (b in rangeBoxes(page, text, measurer, mark.first, mark.last + 1)) {
                    CGContextFillRect(cg, CGRectMake(b.left.toDouble(), b.top.toDouble(), (b.right - b.left).toDouble(), (b.bottom - b.top).toDouble()))
                }
            }
            draw(cg, page, text, measurer, ink, noteMarks, noteColor)
            for ((end, color) in memos) {
                val box = rangeBoxes(page, text, measurer, end - 1, end).lastOrNull() ?: continue
                memoGlyph(cg, box.right.toDouble(), box.top.toDouble(), maxOf((box.bottom - box.top) * 0.55f, 6f).toDouble(), color)
            }
            drawPictures(cg, page, pictures, blend)
        }
    }

    /**
     * 두 쪽 보기의 펼침(구상안 27): 왼쪽 쪽을 [leftInset] 에, 오른쪽 쪽을 그 옆([pageWidth] 뒤)에 놓고 가운데에 책등 선 하나(지면
     * 글자색 13%, 안드로이드와 같다). [right] 가 없으면(홀수 쪽으로 끝난 장) 오른쪽은 빈 지면이다.
     */
    fun spread(
        left: UIImage,
        right: UIImage?,
        fullWidth: Double,
        heightPt: Double,
        leftInset: Double,
        pageWidth: Double,
        paper: Int,
        ink: Int,
        spineTop: Double,
        spineBottom: Double,
    ): UIImage {
        val format = UIGraphicsImageRendererFormat.preferredFormat().apply { opaque = true }
        return UIGraphicsImageRenderer(size = CGSizeMake(fullWidth, heightPt), format = format).imageWithActions { context ->
            val cg = context?.CGContext ?: return@imageWithActions
            fill(cg, paper, 1.0)
            CGContextFillRect(cg, CGRectMake(0.0, 0.0, fullWidth, heightPt))
            left.drawInRect(CGRectMake(leftInset, 0.0, pageWidth, heightPt))
            right?.drawInRect(CGRectMake(leftInset + pageWidth, 0.0, pageWidth, heightPt))
            fill(cg, ink, 0.13)
            CGContextFillRect(cg, CGRectMake(leftInset + pageWidth - 0.5, spineTop, 1.0, (spineBottom - spineTop).coerceAtLeast(0.0)))
        }
    }

    /** 글자 없는 쪽(읽을 수 없는 장 등). */
    fun blank(widthPt: Double, heightPt: Double, paper: Int): UIImage {
        val format = UIGraphicsImageRendererFormat.preferredFormat().apply { opaque = true }
        return UIGraphicsImageRenderer(size = CGSizeMake(widthPt, heightPt), format = format).imageWithActions { context ->
            val cg = context?.CGContext ?: return@imageWithActions
            fill(cg, paper, 1.0)
            CGContextFillRect(cg, CGRectMake(0.0, 0.0, widthPt, heightPt))
        }
    }

    /**
     * 그림은 칸 안에 원래 비율로 가장 크게, 가운데에(디자인 인계 6-2 "그림은 자르지 않는다"). 조판기가 비율대로 칸을 잡지만
     * 그림 머리의 크기와 실제 그림이 다른 책이 있어, 늘여 채우면 찌그러진다.
     */
    private fun drawPictures(context: CGContextRef, page: Page, pictures: Map<String, UIImage>, blend: PictureBlend) {
        for (placed in page.images) {
            val image = pictures[placed.href] ?: continue
            val (iw, ih) = image.size.useContents { width to height }
            if (iw <= 0.0 || ih <= 0.0) continue
            val boxW = placed.widthPx.toDouble()
            val boxH = placed.heightPx.toDouble()
            val scale = minOf(boxW / iw, boxH / ih)
            val w = iw * scale
            val h = ih * scale
            val rect = CGRectMake(placed.xPx + (boxW - w) / 2, placed.yPx + (boxH - h) / 2, w, h)
            when (blend) {
                // 곱하기: 흰 바탕은 지면색이 되고 검은 글씨는 그대로. 투명한 곳은 지면이 그대로 비친다.
                PictureBlend.Multiply -> image.drawInRect(rect, CGBlendMode.kCGBlendModeMultiply, 1.0)
                PictureBlend.Dim -> {
                    image.drawInRect(rect)
                    CGContextSaveGState(context)
                    CGContextSetBlendMode(context, CGBlendMode.kCGBlendModeMultiply)
                    CGContextSetRGBFillColor(context, DARK_DIM, DARK_DIM, DARK_DIM, 1.0)
                    CGContextFillRect(context, rect)
                    CGContextRestoreGState(context)
                }
                PictureBlend.None -> image.drawInRect(rect)
            }
        }
    }

    /**
     * 쪽 위에서 글자 구간 [start]..[endExclusive] 가 차지하는 네모들(줄마다 하나, 안드로이드 `rangeBoxes`). 조판은 양쪽정렬로
     * 벌린 자리에서 조각을 끊어 두므로, 한 조각 안에서는 글자 폭을 더해 가면 정확한 x 가 나온다. 조판에 쓴 측정기여야 한다.
     */
    internal fun rangeBoxes(page: Page, text: String, measurer: CoreTextMeasurer, start: Int, endExclusive: Int): List<Box> {
        val boxes = ArrayList<Box>()
        for (run in page.runs) {
            val from = maxOf(run.start, start)
            val to = minOf(run.endExclusive, endExclusive, text.length)
            if (from >= to) continue
            val left = run.xPx + measurer.advance(text, run.start, from, run.style)
            val right = left + measurer.advance(text, from, to, run.style)
            val top = run.baselineYPx - measurer.ascent(run.style)
            boxes += Box(left, top, right, top + measurer.lineHeight(run.style))
        }
        return boxes
    }

    /**
     * 메모 쪽지(안드로이드 `drawMemoGlyph`): 접힌 귀가 있는 작은 종이에 흰 줄 둘. ([x], [y]) 는 칠한 끝 글자의 오른쪽 위 —
     * 쪽지는 그 자리에서 4분의 3 쯤 위로 올라가 행간에 걸친다. 줄 안에 두면 다음 글자의 머리를 덮는다.
     */
    private fun memoGlyph(cg: CGContextRef, x0: Double, y0: Double, size: Double, color: Int) {
        val x = x0 + size * 0.1
        val y = y0 - size * 0.75
        val fold = size * 0.32
        fill(cg, color, 1.0)
        platform.CoreGraphics.CGContextBeginPath(cg)
        platform.CoreGraphics.CGContextMoveToPoint(cg, x, y)
        platform.CoreGraphics.CGContextAddLineToPoint(cg, x + size, y)
        platform.CoreGraphics.CGContextAddLineToPoint(cg, x + size, y + size - fold)
        platform.CoreGraphics.CGContextAddLineToPoint(cg, x + size - fold, y + size)
        platform.CoreGraphics.CGContextAddLineToPoint(cg, x, y + size)
        platform.CoreGraphics.CGContextClosePath(cg)
        platform.CoreGraphics.CGContextFillPath(cg)
        fill(cg, 0xFFFFFF, 1.0)
        val stroke = maxOf(size * 0.1, 1.0)
        CGContextFillRect(cg, CGRectMake(x + size * 0.2, y + size * 0.32 - stroke / 2, size * 0.6, stroke))
        CGContextFillRect(cg, CGRectMake(x + size * 0.2, y + size * 0.56 - stroke / 2, size * 0.4, stroke))
    }

    /**
     * 누른 자리([x], [y])에 가장 가까운 글자의 오프셋(안드로이드 `charAt`, 고르기 · 손잡이 끌기). 먼저 [y] 가 든 줄(없으면
     * 세로로 가장 가까운 줄)을 고르고 그 줄에서 [x] 가 든 글자를 찾는다. 줄 끝 바깥이면 그 줄 마지막 글자 — 손잡이를 여백까지
     * 끌어도 고른 것이 사라지지 않게. [after] 면 글자 오른쪽 반에서 다음 오프셋(끝 손잡이는 "이 글자까지" 다).
     */
    internal fun charAt(page: Page, text: String, measurer: CoreTextMeasurer, x: Float, y: Float, after: Boolean = false): Int? {
        val lines = page.runs.filter { it.start < it.endExclusive && it.start < text.length }.groupBy { it.baselineYPx }
        if (lines.isEmpty()) return null
        fun top(r: io.github.kgcaudit.reader.layout.PlacedRun) = r.baselineYPx - measurer.ascent(r.style)
        fun bottom(r: io.github.kgcaudit.reader.layout.PlacedRun) = top(r) + measurer.lineHeight(r.style)
        val line = lines.values.minBy { runs ->
            val t = runs.minOf { top(it) }
            val b = runs.maxOf { bottom(it) }
            when {
                y < t -> t - y
                y > b -> y - b
                else -> 0f
            }
        }.sortedBy { it.xPx }
        val run = line.firstOrNull { x < it.xPx + measurer.advance(text, it.start, it.endExclusive.coerceAtMost(text.length), it.style) }
            ?: return line.last().endExclusive.coerceAtMost(text.length).let { if (after) it else it - 1 }
        if (x <= run.xPx) return run.start
        val end = run.endExclusive.coerceAtMost(text.length)
        var i = run.start
        var left = run.xPx
        while (i < end) {
            val next = if (i + 1 < end && text[i].isHighSurrogate()) i + 2 else i + 1
            val right = run.xPx + measurer.advance(text, run.start, next, run.style)
            if (x < right) return if (after && x > (left + right) / 2) next else i
            left = right
            i = next
        }
        return if (after) end else end - 1
    }

    /**
     * [offset] 이 든 낱말(길게 눌렀을 때 처음 고르는 구간, 안드로이드 `wordAt`). 띄어쓰기 · 문장부호에서 끊는다 — 한국어는
     * 어절 하나. 공백을 눌렀으면 그 한 글자만(빈 구간이면 메뉴가 뜰 자리가 없다).
     */
    internal fun wordAt(text: String, offset: Int): IntRange {
        if (text.isEmpty()) return IntRange.EMPTY
        val at = offset.coerceIn(0, text.length - 1)
        fun inWord(c: Char) = c.isLetterOrDigit() || c == '\'' || c == '’' || c == '-'
        if (!inWord(text[at])) return at..at
        var s = at
        while (s > 0 && inWord(text[s - 1])) s--
        var e = at
        while (e < text.length - 1 && inWord(text[e + 1])) e++
        return s..e
    }

    /** 글자 구간 네모(쪽 좌표, 위가 0). */
    class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val centerX: Float get() = (left + right) / 2
        val centerY: Float get() = (top + bottom) / 2
    }

    /**
     * 누른 자리([x], [y])의 링크(안드로이드 `linkAt`). 표시가 글자 하나(¹)여도 손가락이 닿는 네모([minTouch], 48)는 넉넉하게 —
     * 그 안이면 그 링크다. 여럿이 겹치면 가운데가 가장 가까운 것.
     */
    internal fun linkAt(page: Page, text: String, measurer: CoreTextMeasurer, links: List<Link>, x: Float, y: Float, minTouch: Float = 48f): Link? {
        var best: Link? = null
        var bestDistance = Float.MAX_VALUE
        for (link in links) {
            if (link.endExclusive <= page.startChar || link.start >= page.endCharExclusive) continue
            for (box in rangeBoxes(page, text, measurer, link.start, link.endExclusive)) {
                val w = maxOf(box.right - box.left, minTouch)
                val h = maxOf(box.bottom - box.top, minTouch)
                if (x < box.centerX - w / 2 || x > box.centerX + w / 2 || y < box.centerY - h / 2 || y > box.centerY + h / 2) continue
                val dx = box.centerX - x
                val dy = box.centerY - y
                val d = dx * dx + dy * dy
                if (d < bestDistance) {
                    best = link
                    bestDistance = d
                }
            }
        }
        return best
    }

    /** [color](0xRRGGBB)를 [background] 위에서 대비 [min] 이 될 때까지 [toward] 쪽으로 짙게(안드로이드 `readableOn`). */
    internal fun readableOn(color: Int, background: Int, toward: Int, min: Double = 4.5): Int {
        var t = 0.0
        var c = color
        while (contrast(c, background) < min && t < 1.0) {
            t += 0.05
            c = lerp(color, toward, t)
        }
        return c
    }

    private fun lerp(a: Int, b: Int, t: Double): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) + (((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * t).toInt().coerceIn(0, 255)
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    internal fun luminanceOf(rgb: Int): Double = luminance(rgb)

    private fun luminance(rgb: Int): Double {
        fun lin(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * lin((rgb shr 16) and 0xFF) + 0.7152 * lin((rgb shr 8) and 0xFF) + 0.0722 * lin(rgb and 0xFF)
    }

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** 위가 0 인(UIKit) 좌표계의 [context] 에 그린다. 시험이 비트맵에 직접 그려 글자 자리를 잰다. */
    internal fun draw(
        context: CGContextRef,
        page: Page,
        text: String,
        measurer: CoreTextMeasurer,
        ink: Int,
        noteMarks: List<IntRange> = emptyList(),
        noteColor: Int = ink,
    ) {
        for (run in page.runs) {
            val start = run.start.coerceIn(0, text.length)
            val end = run.endExclusive.coerceIn(start, text.length)
            if (end <= start) continue
            val face = measurer.face(run.style)
            val size = face.sizePx.toDouble()
            val shift = when (run.style.vertical) {
                VerticalAlign.Superscript -> -0.35 * size
                VerticalAlign.Subscript -> 0.2 * size
                VerticalAlign.Baseline -> 0.0
            }
            val x = run.xPx.toDouble()
            val y = run.baselineYPx + shift
            CGContextSaveGState(context)
            // CoreText 는 위가 + 인 글자 공간에서 그린다. UIKit 좌표(아래가 +)에서는 y 를 뒤집어야 글자가 바로 선다.
            // 기울임은 같은 행렬에 넣는다: 글자 공간의 위쪽일수록 오른쪽으로(안드로이드 textSkewX −0.25 와 같은 모양).
            val skew = if (face.italic) CoreTextMeasurer.ITALIC_SKEW else 0.0
            CGContextSetTextMatrix(context, CGAffineTransformMake(1.0, 0.0, skew, -1.0, 0.0, 0.0))
            // 각주 표시가 든 조각은 표시 부분만 강조 글자색으로 나눠 그린다. 나눈 자리의 x 는 앞 글자 폭을 더해 구한다 — 조각
            // 안에는 양쪽정렬의 벌림이 없다(안드로이드와 같다).
            val cuts = noteMarks.filter { it.first < end && it.last + 1 > start }
                .flatMap { listOf(it.first, it.last + 1) }.filter { it in (start + 1) until end }.sorted()
            var from = start
            for (cut in cuts + end) {
                if (cut <= from) continue
                val inNote = noteMarks.any { from >= it.first && from <= it.last }
                val color = if (inNote) noteColor else ink
                fill(context, color, 1.0)
                stroke(context, color)
                val piece = face.line(text, from, cut)
                if (piece != null) {
                    CGContextSetTextPosition(context, x + if (from == start) 0.0 else face.width(text, start, from).toDouble(), y)
                    CTLineDraw(piece, context)
                    CFRelease(piece)
                }
                from = cut
            }
            fill(context, ink, 1.0)
            if (run.style.underline || run.style.strikethrough) {
                val width = face.width(text, start, end).toDouble()
                val thickness = maxOf(size / 16.0, 1.0)
                if (run.style.underline) CGContextFillRect(context, CGRectMake(x, y + size * 0.12, width, thickness))
                if (run.style.strikethrough) CGContextFillRect(context, CGRectMake(x, y - size * 0.3, width, thickness))
            }
            CGContextRestoreGState(context)
        }
        for (rule in page.rules) {
            fill(context, ink, 0.5)
            CGContextFillRect(
                context,
                CGRectMake(rule.xPx.toDouble(), rule.yPx.toDouble(), rule.widthPx.toDouble(), maxOf(rule.thicknessPx, 1f).toDouble()),
            )
        }
    }

    /** 어두운 지면에서 그림 밝기(안드로이드와 같다). 흰 바탕이 검은 지면 옆에서 눈부시지 않으면서 어두운 곳이 뭉개지지 않는다. */
    private const val DARK_DIM = 0.78

    private fun fill(context: CGContextRef, rgb: Int, alpha: Double) =
        CGContextSetRGBFillColor(context, red(rgb), green(rgb), blue(rgb), alpha)

    private fun stroke(context: CGContextRef, rgb: Int) =
        CGContextSetRGBStrokeColor(context, red(rgb), green(rgb), blue(rgb), 1.0)

    private fun red(rgb: Int) = ((rgb shr 16) and 0xFF) / 255.0
    private fun green(rgb: Int) = ((rgb shr 8) and 0xFF) / 255.0
    private fun blue(rgb: Int) = (rgb and 0xFF) / 255.0
}

/** "그림 흰 바탕"(보기 판). 그림의 흰 바탕이 지면색 위에서 흰 네모로 튀지 않게 한다. */
enum class PictureBlend {
    /** 그대로 · 흰 지면. */
    None,

    /** 밝은 지면: 그림에 지면색을 곱한다. */
    Multiply,

    /** 어두운 지면: 그림을 조금 어둡게. */
    Dim,
    ;

    companion object {
        /** [paper] 지면에서 "배경색으로" 를 골랐을 때의 방식. 흰 지면은 할 일이 없다. */
        fun forPaper(paper: Int, blend: Boolean): PictureBlend {
            if (!blend || paper == 0xFFFFFF) return None
            val r = (paper shr 16) and 0xFF
            val g = (paper shr 8) and 0xFF
            val b = paper and 0xFF
            return if (0.299 * r + 0.587 * g + 0.114 * b < 128) Dim else Multiply
        }
    }
}
