package io.github.kgcaudit.reader.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import io.github.kgcaudit.reader.ui.design.sampleWithin

// 긴 그림의 한 부분만 푸는 길. 만화 띠(웹툰) · 장면에서 표지 고르기 · 웹툰 표지가 같은 "띠 풀기를 해 보고, 안 되면 전체를
// 줄여 풀어 자르기" 를 세 벌 적고 있었다 — 한 벌만 고치면 같은 그림이 화면마다 다르게 풀린다.

/**
 * [rect](원본 픽셀)만 [sample] 배로 솎아 띠 풀기(BitmapRegionDecoder)로 푼다. 긴 그림(800×30000 = 96MB)을 통째로 풀지
 * 않으려고 쓴다. 띠 풀기를 못 하는 형식이거나 실패하면 null — 부르는 쪽이 다른 길로 푼다.
 */
internal fun decodeRegion(bytes: ByteArray, rect: Rect, sample: Int): Bitmap? = runCatching {
    @Suppress("DEPRECATION")
    val decoder = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
    try {
        decoder?.decodeRegion(rect, BitmapFactory.Options().apply { inSampleSize = sample })
    } finally {
        decoder?.recycle()
    }
}.getOrNull()

/**
 * 원본 [srcWidth]×[srcHeight] 그림의 [rect] 를 [sample] 배로 솎아 푼다. [regions] 면 먼저 띠 풀기를 해 보고, 안 되면 전체를
 * 높이 [maxWholeHeight] 안으로 더 줄여 풀어 그 자리만 자른다 — 띠 풀기가 안 되는 그림도 메모리를 넘치지 않고 보인다(규칙 6).
 * 못 풀면 null.
 */
internal fun decodeRect(
    bytes: ByteArray,
    srcWidth: Int,
    srcHeight: Int,
    rect: Rect,
    sample: Int,
    regions: Boolean,
    maxWholeHeight: Int,
): Bitmap? {
    if (regions) decodeRegion(bytes, rect, sample)?.let { return it }
    val wholeSample = sampleWithin(srcHeight.toLong(), maxWholeHeight.toLong(), sample)
    val whole = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = wholeSample })
        ?: return null
    // 원본 자리를 솎은 그림 자리로. 정수로 셈한다 — 실수로 셈하면 긴 그림 아래쪽에서 반올림이 한 줄씩 어긋난다.
    val x0 = (rect.left.toLong() * whole.width / srcWidth).toInt().coerceIn(0, whole.width - 1)
    val y0 = (rect.top.toLong() * whole.height / srcHeight).toInt().coerceIn(0, whole.height - 1)
    val x1 = (rect.right.toLong() * whole.width / srcWidth).toInt().coerceIn(x0 + 1, whole.width)
    val y1 = (rect.bottom.toLong() * whole.height / srcHeight).toInt().coerceIn(y0 + 1, whole.height)
    return Bitmap.createBitmap(whole, x0, y0, x1 - x0, y1 - y0)
}
