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
 * 쪽의 글과 글자마다의 네모. 엔진은 글자 하나의 네모를 따로 주지 않아, 글자 하나씩 "고르기"([PdfRenderer.Page.selectContent])
 * 를 불러 그 네모를 얻는다. 한 쪽에 수천 번이지만 쪽마다 한 번이고 그리기 스레드에서 돈다.
 *
 * 고르기의 끝이 "그 글자까지" 인지 "그 글자 앞까지" 인지 문서가 말하지 않는다. 앞의 뜻으로 (i, i+1) 을 부르고,
 * 이웃 글자의 네모가 대부분 겹치면(두 글자씩 잡힌 것) 뒤의 뜻으로 (i, i) 를 다시 부른다 — 어느 쪽이든 글자
 * 하나의 네모가 남는다.
 */
@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
@RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
private fun layerOf(page: PdfRenderer.Page): PageText {
    val text = textOf(page)
    if (text.isEmpty()) return PageText.EMPTY
    val w = page.width.toFloat().coerceAtLeast(1f)
    val h = page.height.toFloat().coerceAtLeast(1f)
    fun boxes(stopOffset: Int): FloatArray {
        val out = FloatArray(text.length * 4) { Float.NaN }
        for (i in text.indices) {
            if (text[i].isWhitespace()) continue
            val rect: RectF = runCatching {
                page.selectContent(SelectionBoundary(i), SelectionBoundary(i + stopOffset))
                    ?.selectedTextContents?.firstNotNullOfOrNull { it.bounds.firstOrNull() }
            }.getOrNull() ?: continue
            out[i * 4] = rect.left / w
            out[i * 4 + 1] = rect.top / h
            out[i * 4 + 2] = rect.right / w
            out[i * 4 + 3] = rect.bottom / h
        }
        return out
    }
    val exclusive = boxes(1)
    return if (mostlyDoubled(text, exclusive)) PageText(text, boxes(0)) else PageText(text, exclusive)
}

/** 이웃한 두 글자의 네모가 대부분 겹치는가 — 글자 하나를 달라 했는데 둘씩 온 것이다. */
internal fun mostlyDoubled(text: String, boxes: FloatArray): Boolean {
    var pairs = 0
    var overlapping = 0
    for (i in 0 until text.length - 1) {
        val a = i * 4
        val b = (i + 1) * 4
        if (boxes[a].isNaN() || boxes[b].isNaN()) continue
        // 같은 줄(가운데 높이가 비슷)인 이웃만 센다.
        val midA = (boxes[a + 1] + boxes[a + 3]) / 2
        val midB = (boxes[b + 1] + boxes[b + 3]) / 2
        if (kotlin.math.abs(midA - midB) > (boxes[a + 3] - boxes[a + 1]) / 2) continue
        pairs++
        val widthA = boxes[a + 2] - boxes[a]
        if (boxes[a + 2] - boxes[b] > widthA * 0.3f) overlapping++
    }
    return pairs >= 4 && overlapping * 2 > pairs
}
