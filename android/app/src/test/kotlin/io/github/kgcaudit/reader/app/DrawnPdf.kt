package io.github.kgcaudit.reader.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.ParcelFileDescriptor
import io.github.kgcaudit.reader.pdf.PageRegion
import io.github.kgcaudit.reader.pdf.PdfSource

/**
 * Robolectric 에는 PDF 엔진이 없어 쪽을 손으로 그리는 가짜. 제목과 본문 줄을 A4 좌표(포인트)에 그리고,
 * 확대 구역([PageRegion])은 PdfRenderer 와 같은 변환으로 옮긴다 — 확대 화면 스크린샷이 실제와 같은
 * 자리를 보여야 한다.
 */
class DrawnPdf(private val descriptor: ParcelFileDescriptor, override val pageCount: Int) : PdfSource {
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 28f; isFakeBoldText = true }
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(40, 40, 40); textSize = 11f }

    override fun pageSize(index: Int): Pair<Int, Int> = 595 to 842

    override fun render(index: Int, target: Bitmap, region: PageRegion) {
        val canvas = Canvas(target)
        canvas.drawColor(Color.WHITE)
        canvas.scale(target.width / (region.width * 595f), target.height / (region.height * 842f))
        canvas.translate(-region.left * 595f, -region.top * 842f)
        canvas.drawText("제 ${index + 1} 쪽 · 사용 설명서", 60f, 110f, title)
        for (line in 0 until 40) {
            canvas.drawText("${line + 1}. 작은 글자는 두 손가락으로 벌리거나 두 번 눌러 확대해서 읽습니다.", 60f, 160f + line * 16f, body)
        }
    }

    override fun close() = descriptor.close()
}
