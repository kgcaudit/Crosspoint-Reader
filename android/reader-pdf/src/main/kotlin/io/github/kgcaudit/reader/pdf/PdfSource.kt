package io.github.kgcaudit.reader.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.graphics.RectF
import android.graphics.pdf.models.selection.SelectionBoundary
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresExtension
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

    /**
     * 글자를 꺼낼 수 있는 엔진인가(안드로이드 15 이상의 PdfRenderer). 아니면 찾기 · 형광펜 · 듣기 단추를 두지
     * 않는다 — 누를 때마다 "이 휴대폰에서는 안 됩니다" 를 보는 것보다 없는 편이 낫다.
     */
    val readsText: Boolean get() = false

    /** [index] 쪽의 글(찾기). 글자가 없는 쪽(스캔본)은 빈 글. 엔진이 못 하면 null. 쪽마다 한 번 부르는 값싼 일. */
    fun pageText(index: Int): String? = null

    /** [index] 쪽의 글과 글자마다의 네모(고르기 · 칠 · 문장 칠). 비싸다 — 부르는 쪽이 쪽마다 한 번만 부르고 둔다. */
    fun textLayer(index: Int): PageText? = null
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

    override val readsText: Boolean = textApi()

    @Synchronized
    override fun pageText(index: Int): String? {
        if (!readsText) return null
        return runCatching { renderer.openPage(index).use { page -> textOf(page) } }.getOrNull()
    }

    @Synchronized
    override fun textLayer(index: Int): PageText? {
        if (!readsText) return null
        return runCatching { renderer.openPage(index).use { page -> layerOf(page) } }.getOrNull()
    }

    @Synchronized
    override fun close() {
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }
}

/**
 * PdfRenderer 의 글자 API 가 있는가. 안드로이드 15(API 35) 에 들어왔고, 그 기능은 SDK 확장 13 부터라 둘 다 본다 —
 * 15 초기 빌드 중 확장이 모자란 기기에서 부르면 메서드가 없어 죽는다.
 */
private fun textApi(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
        SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13

@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
@RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
private fun textOf(page: PdfRenderer.Page): String = page.textContents.joinToString("") { it.text }

/**
 * 쪽의 글과 글자마다의 네모. 엔진은 글자 하나의 네모를 따로 주지 않아, 글자 번호마다 "고르기"([PdfRenderer.Page.selectContent])
 * 를 불러 그 글자와 네모를 함께 받는다([assembleLayer] — 쪽 전체 글과 고르기는 번호가 달라, 따로 받으면 네모가 밀린다).
 * 한 쪽에 수천 번이지만 쪽마다 한 번이고 그리기 스레드에서 돈다. 끝 번호는 끝을 빼고 센다(pdfClient 의 GetTextUtf8 ·
 * GetTextBounds 가 [시작, 끝) 이다).
 */
@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
@RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
private fun layerOf(page: PdfRenderer.Page): PageText {
    val whole = textOf(page)
    if (whole.isEmpty()) return PageText.EMPTY
    val w = page.width.toFloat().coerceAtLeast(1f)
    val h = page.height.toFloat().coerceAtLeast(1f)
    // 잘린 앞머리(공백 · 하이픈)와 번호 2 의 늘어남을 넉넉히 덮는다. 끝을 넘은 번호는 빈 글이라 헛돌기만 한다.
    return assembleLayer(whole.length + LAYER_SLACK) { from, to ->
        runCatching {
            val content = page.selectContent(SelectionBoundary(from), SelectionBoundary(to))?.selectedTextContents
            val text = content?.joinToString("") { it.text }.orEmpty()
            val rect: RectF? = content?.firstNotNullOfOrNull { it.bounds.firstOrNull() }
            text to rect?.let { floatArrayOf(it.left / w, it.top / h, it.right / w, it.bottom / h) }
        }.getOrNull()
    }
}

private const val LAYER_SLACK = 64
