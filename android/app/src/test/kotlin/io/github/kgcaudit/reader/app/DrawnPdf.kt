package io.github.kgcaudit.reader.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.os.ParcelFileDescriptor
import io.github.kgcaudit.reader.pdf.PageRegion
import io.github.kgcaudit.reader.pdf.PageText
import io.github.kgcaudit.reader.pdf.PdfSource

/**
 * Robolectric 에는 PDF 엔진이 없어 쪽을 손으로 그리는 가짜. 제목과 본문 줄을 A4 좌표(포인트)에 그리고,
 * 확대 구역([PageRegion])은 PdfRenderer 와 같은 변환으로 옮긴다 — 확대 화면 스크린샷이 실제와 같은
 * 자리를 보여야 한다.
 */
class DrawnPdf(
    /** 엔진이 받은 파일. 시험이 글자 자리만 셈할 때는 없다. */
    private val descriptor: ParcelFileDescriptor?,
    override val pageCount: Int,
    /** 글자 층을 내주는가(안드로이드 15+ 흉내). false 면 14 이하 기기처럼 글자 API 가 없다. */
    override val readsText: Boolean = true,
    /** 글자가 그림인 쪽(스캔본). 그리기는 같고 글자 층만 비었다. */
    private val scanned: (Int) -> Boolean = { false },
    /** 쪽 머리말 · 쪽 번호를 그린다(듣기가 건너뛰는지 보려고). */
    private val runningHead: Boolean = false,
    /** 쪽마다 본문 줄. 기본은 확대 설명 40줄. */
    private val body: (Int) -> List<String> = { _ -> (1..40).map { "$it. 작은 글자는 두 손가락으로 벌리거나 두 번 눌러 확대해서 읽습니다." } },
) : PdfSource {
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 28f; isFakeBoldText = true }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(40, 40, 40); textSize = 11f }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GRAY; textSize = 8f }

    /** 쪽의 줄들: (글, 왼쪽, 기준선, 붓). 그리기와 글자 층이 같은 표를 써야 칠이 글자 위에 맞는다. */
    private fun lines(index: Int): List<Triple<String, PointF, Paint>> = buildList {
        if (runningHead) add(Triple("OLO 사용 설명서", PointF(60f, 36f), headPaint))
        add(Triple("제 ${index + 1} 쪽 · 사용 설명서", PointF(60f, 110f), title))
        body(index).forEachIndexed { k, line -> add(Triple(line, PointF(60f, 160f + k * 16f), bodyPaint)) }
        if (runningHead) add(Triple("${index + 1}", PointF(292f, 815f), headPaint))
    }

    override fun pageSize(index: Int): Pair<Int, Int> = 595 to 842

    override fun render(index: Int, target: Bitmap, region: PageRegion) {
        val canvas = Canvas(target)
        canvas.drawColor(Color.WHITE)
        canvas.scale(target.width / (region.width * 595f), target.height / (region.height * 842f))
        canvas.translate(-region.left * 595f, -region.top * 842f)
        for ((text, at, paint) in lines(index)) canvas.drawText(text, at.x, at.y, paint)
    }

    override fun pageText(index: Int): String? = if (!readsText) null else textLayer(index)?.text

    /** PdfRenderer 처럼 줄 사이를 "\r\n" 으로 잇고, 글자마다 그린 자리의 네모를 준다. */
    override fun textLayer(index: Int): PageText? {
        if (!readsText) return null
        if (scanned(index)) return PageText.EMPTY
        val text = StringBuilder()
        val boxes = ArrayList<Float>()
        lines(index).forEachIndexed { k, (line, at, paint) ->
            if (k > 0) {
                text.append("\r\n")
                repeat(8) { boxes += Float.NaN }
            }
            val metrics = paint.fontMetrics
            for (i in line.indices) {
                text.append(line[i])
                val left = at.x + paint.measureText(line, 0, i)
                val right = at.x + paint.measureText(line, 0, i + 1)
                boxes += listOf(left / 595f, (at.y + metrics.ascent) / 842f, right / 595f, (at.y + metrics.descent) / 842f)
            }
        }
        return PageText(text.toString(), boxes.toFloatArray())
    }

    override fun close() = descriptor?.close() ?: Unit
}
