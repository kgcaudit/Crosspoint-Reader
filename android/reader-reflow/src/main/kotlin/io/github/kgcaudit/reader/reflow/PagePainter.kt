package io.github.kgcaudit.reader.reflow

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.github.kgcaudit.reader.layout.Page
import io.github.kgcaudit.reader.layout.PlacedImage
import io.github.kgcaudit.reader.layout.VerticalAlign
import io.github.kgcaudit.reader.text.AndroidTextMeasurer

/**
 * 조판된 페이지를 좌표 그대로 찍는다.
 *
 * 여기에는 줄바꿈도 폭 계산도 없다(밑줄 길이만 예외). 페이지 넘김 16ms 예산은 이 함수가
 * "찍기만" 한다는 전제 위에 있다.
 *
 * [measurer] 는 조판에 쓴 것과 **같은 설정으로 만든 그리기 전용** 인스턴스여야 한다.
 * 조판용과 공유하면 두 스레드가 한 Paint 를 쓰게 되고, 다른 설정으로 만들면 잰 폭과
 * 그린 폭이 달라 양쪽정렬된 줄 끝이 들쭉날쭉해진다.
 */
fun DrawScope.drawPage(
    page: Page,
    text: String,
    measurer: AndroidTextMeasurer,
    ink: Color,
    images: Map<PlacedImage, ImageBitmap>,
) {
    val argb = ink.toArgb()

    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        for (run in page.runs) {
            val start = run.start.coerceIn(0, text.length)
            val end = run.endExclusive.coerceIn(start, text.length)
            if (start == end) continue

            // 그리기 전용 인스턴스라 색을 바꿔도 된다. 색은 폭에 영향을 주지 않는다.
            val paint = measurer.paintFor(run.style)
            paint.color = argb

            // 조판기는 위·아래첨자를 작은 글자로만 재고 베이스라인은 줄과 같게 둔다.
            // 올리고 내리는 것은 여기서 한다(줄 높이에는 영향이 없다).
            val shift = when (run.style.vertical) {
                VerticalAlign.Superscript -> -0.35f * paint.textSize
                VerticalAlign.Subscript -> 0.2f * paint.textSize
                VerticalAlign.Baseline -> 0f
            }
            val y = run.baselineYPx + shift
            native.drawText(text, start, end, run.xPx, y, paint)

            if (run.style.underline || run.style.strikethrough) {
                val width = paint.measureText(text, start, end)
                val thickness = (paint.textSize / 16f).coerceAtLeast(1f)
                if (run.style.underline) {
                    val uy = y + paint.textSize * 0.12f
                    drawLine(ink, Offset(run.xPx, uy), Offset(run.xPx + width, uy), thickness)
                }
                if (run.style.strikethrough) {
                    val sy = y - paint.textSize * 0.3f
                    drawLine(ink, Offset(run.xPx, sy), Offset(run.xPx + width, sy), thickness)
                }
            }
        }
    }

    for (rule in page.rules) {
        drawRect(ink.copy(alpha = 0.5f), Offset(rule.xPx, rule.yPx), Size(rule.widthPx, rule.thicknessPx.coerceAtLeast(1f)))
    }

    for (placed in page.images) {
        val bitmap = images[placed] ?: continue
        // 상자 안에 비율대로 넣는다(object-fit: contain). 조판이 파일 크기를 읽었다면 상자가
        // 이미 같은 비율이지만, 크기를 못 읽은 그림은 3:4 상자를 받는다 — 거기에 늘려
        // 채우면 세로 표지가 납작해지는 증상이 그대로 돌아온다.
        val scale = minOf(placed.widthPx / bitmap.width, placed.heightPx / bitmap.height)
        val w = (bitmap.width * scale).coerceAtLeast(1f)
        val h = (bitmap.height * scale).coerceAtLeast(1f)
        drawImage(
            bitmap,
            dstOffset = IntOffset((placed.xPx + (placed.widthPx - w) / 2f).toInt(), (placed.yPx + (placed.heightPx - h) / 2f).toInt()),
            dstSize = IntSize(w.toInt(), h.toInt()),
        )
    }
}
