package io.github.kgcaudit.reader.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable

/**
 * PDF 를 그리는 쪽. 플랫폼 [PdfRenderer] 가 구현이고, 테스트는 가짜를 쓴다(Robolectric 에는 PDF
 * 엔진이 없다). Pdfium 으로 바꿀 때 바꾸는 곳이 이 인터페이스 하나다.
 *
 * **한 스레드에서만 부른다.** PdfRenderer 는 한 번에 한 페이지만 열 수 있고 스레드 안전하지 않다.
 */
interface PdfSource : Closeable {
    val pageCount: Int

    /** 페이지 크기(포인트, 1/72 인치). 폭 / 높이. */
    fun pageSize(index: Int): Pair<Int, Int>

    /** [index] 쪽의 [region] 부분을 [target] 에 가득 그린다. 배경은 흰색이다. */
    fun render(index: Int, target: Bitmap, region: PageRegion)
}

/**
 * 플랫폼 PdfRenderer.
 *
 * 파일 디스크립터는 **되감을 수 있어야** 한다(파이프면 PdfRenderer 가 거절한다). 여는 쪽이 필요하면
 * 캐시로 옮겨 준다. 암호가 걸린 PDF 는 여기서 [SecurityException] 이 난다.
 */
class PlatformPdfSource(private val descriptor: ParcelFileDescriptor) : PdfSource {
    private val renderer = try {
        PdfRenderer(descriptor)
    } catch (e: Exception) {
        descriptor.close()
        throw e
    }

    override val pageCount: Int get() = renderer.pageCount

    @Synchronized
    override fun pageSize(index: Int): Pair<Int, Int> = renderer.openPage(index).use { it.width to it.height }

    @Synchronized
    override fun render(index: Int, target: Bitmap, region: PageRegion) {
        renderer.openPage(index).use { page ->
            // 페이지 좌표(포인트) → 비트맵 px. 구역의 왼쪽 위가 비트맵의 (0,0) 에 오게 옮긴다.
            val sx = target.width / (region.width * page.width)
            val sy = target.height / (region.height * page.height)
            val transform = Matrix().apply {
                setScale(sx, sy)
                postTranslate(-region.left * page.width * sx, -region.top * page.height * sy)
            }
            // PdfRenderer 는 내용이 없는 곳을 투명으로 둔다. 다크 모드 지면 위에서 글자만 떠 보이지
            // 않게 종이를 먼저 칠한다.
            target.eraseColor(Color.WHITE)
            page.render(target, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
    }

    @Synchronized
    override fun close() {
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }
}
