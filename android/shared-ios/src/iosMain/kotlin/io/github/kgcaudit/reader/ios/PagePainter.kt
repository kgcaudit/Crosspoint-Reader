@file:OptIn(ExperimentalForeignApi::class)

package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.layout.Page
import io.github.kgcaudit.reader.layout.VerticalAlign
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGAffineTransformMake
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextRef
import platform.CoreGraphics.CGContextRestoreGState
import platform.CoreGraphics.CGContextSaveGState
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
    ): UIImage {
        val format = UIGraphicsImageRendererFormat.preferredFormat().apply { opaque = true }
        val renderer = UIGraphicsImageRenderer(size = CGSizeMake(widthPt, heightPt), format = format)
        return renderer.imageWithActions { context ->
            val cg = context?.CGContext ?: return@imageWithActions
            fill(cg, paper, 1.0)
            CGContextFillRect(cg, CGRectMake(0.0, 0.0, widthPt, heightPt))
            draw(cg, page, text, measurer, ink)
            drawPictures(page, pictures)
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
    private fun drawPictures(page: Page, pictures: Map<String, UIImage>) {
        for (placed in page.images) {
            val image = pictures[placed.href] ?: continue
            val (iw, ih) = image.size.useContents { width to height }
            if (iw <= 0.0 || ih <= 0.0) continue
            val boxW = placed.widthPx.toDouble()
            val boxH = placed.heightPx.toDouble()
            val scale = minOf(boxW / iw, boxH / ih)
            val w = iw * scale
            val h = ih * scale
            image.drawInRect(CGRectMake(placed.xPx + (boxW - w) / 2, placed.yPx + (boxH - h) / 2, w, h))
        }
    }

    /** 위가 0 인(UIKit) 좌표계의 [context] 에 그린다. 시험이 비트맵에 직접 그려 글자 자리를 잰다. */
    internal fun draw(context: CGContextRef, page: Page, text: String, measurer: CoreTextMeasurer, ink: Int) {
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
            val line = face.line(text, start, end) ?: continue
            CGContextSaveGState(context)
            fill(context, ink, 1.0)
            stroke(context, ink)
            // CoreText 는 위가 + 인 글자 공간에서 그린다. UIKit 좌표(아래가 +)에서는 y 를 뒤집어야 글자가 바로 선다.
            // 기울임은 같은 행렬에 넣는다: 글자 공간의 위쪽일수록 오른쪽으로(안드로이드 textSkewX −0.25 와 같은 모양).
            val skew = if (face.italic) CoreTextMeasurer.ITALIC_SKEW else 0.0
            CGContextSetTextMatrix(context, CGAffineTransformMake(1.0, 0.0, skew, -1.0, 0.0, 0.0))
            CGContextSetTextPosition(context, x, y)
            CTLineDraw(line, context)
            CFRelease(line)
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

    private fun fill(context: CGContextRef, rgb: Int, alpha: Double) =
        CGContextSetRGBFillColor(context, red(rgb), green(rgb), blue(rgb), alpha)

    private fun stroke(context: CGContextRef, rgb: Int) =
        CGContextSetRGBStrokeColor(context, red(rgb), green(rgb), blue(rgb), 1.0)

    private fun red(rgb: Int) = ((rgb shr 16) and 0xFF) / 255.0
    private fun green(rgb: Int) = ((rgb shr 8) and 0xFF) / 255.0
    private fun blue(rgb: Int) = (rgb and 0xFF) / 255.0
}
