package io.github.kgcaudit.reader.document.comic

/**
 * 그림 가장자리의 바탕색 비교 — 표지 자르기([CoverCrop])와 스캔 여백 걷기([MarginTrim])가 같은 눈금을 쓴다. 따로 두면 한쪽만
 * 고쳐 "표지는 잘리는데 여백은 안 걷힌다" 같은 어긋남이 생긴다.
 */
internal object Backdrop {

    /** 바탕과 같다고 보는 색 차(채널마다). JPEG 의 흰 바탕 · 스캔 종이는 250~255 를 오간다. */
    const val TOLERANCE: Int = 24

    /**
     * 두 ARGB 색이 채널마다 [TOLERANCE] 안인가. 할당 없이 비교한다 — 표지 자르기의 옛 판은 비교마다 채널 목록을 만들었는데,
     * 여백 걷기는 쪽마다 수백만 화소를 보므로 그 판을 함께 쓸 수 없었다.
     */
    fun near(a: Int, b: Int): Boolean =
        kotlin.math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) <= TOLERANCE &&
            kotlin.math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) <= TOLERANCE &&
            kotlin.math.abs((a and 0xFF) - (b and 0xFF)) <= TOLERANCE
}
