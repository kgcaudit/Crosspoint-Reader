package io.github.kgcaudit.reader.document.comic

import io.github.kgcaudit.reader.document.image.ImageSize

/** 보는 방식. */
enum class ComicView { PAGE, WEBTOON }

/**
 * 웹툰(긴 세로 그림을 이어 내려 보는 것)의 순수 계산(0.35.0, docs/COMIC_PLAN.md C2). 그림을 풀지 않고 머리의 크기만으로
 * 판별 · 띠 나누기 · 위치를 셈해, 30000px 그림 · 깨진 머리 같은 경우를 기기 없이 시험한다.
 */
object Webtoon {

    /** 세로 ÷ 가로가 이만큼이면 긴 그림(웹툰 한 칸)이다(OLO Explorer `Webtoon.kt` 와 같은 값). */
    const val TALL_RATIO: Float = 2f

    /**
     * 한 번에 푸는 띠의 최대 높이(원본 픽셀). 800×30000 한 장을 통째로 풀면 96MB 라 그리기 한도(약 100MB)와 텍스처 한도
     * (4096/8192)에 걸려 꺼지거나 검게 나온다(Mihon 이슈 #830 · #1910). 띠로만 풀고 화면 밖 띠는 내려놓는다.
     */
    const val MAX_STRIP: Int = 2048

    /**
     * 쪽 모양으로 판별: 크기를 아는 쪽 중 **절반 넘게** 길면 웹툰. 표지 한두 장이 보통 모양이어도 웹툰이고, 펼침면 몇 장이
     * 길쭉해도 만화다. 아는 쪽이 하나도 없으면 null(판별 못 함).
     */
    fun detect(sizes: List<ImageSize?>): Boolean? {
        val known = sizes.filterNotNull()
        if (known.isEmpty()) return null
        return known.count { it.height >= it.width * TALL_RATIO } * 2 > known.size
    }

    /**
     * 보는 방식을 정한다(2026-10-02 사용자 결정 2). 앞이 "정하지 않음"(null)이면 다음으로(규칙 5):
     * 사람이 고른 값 → ComicInfo Format("Webtoon") → 쪽 모양 판별 → 쪽 넘김.
     */
    fun view(chosen: ComicView?, info: ComicInfo?, sizes: List<ImageSize?>): ComicView {
        chosen?.let { return it }
        if (info?.format?.contains("webtoon", ignoreCase = true) == true) return ComicView.WEBTOON
        return if (detect(sizes) == true) ComicView.WEBTOON else ComicView.PAGE
    }

    /**
     * 그림 하나를 [maxStrip] 이하의 띠로 나눈다(원본 픽셀 행 범위). [scale] 은 화면 폭 ÷ 원본 폭 — 늘려 그리면 화면에서도
     * [maxStrip] 을 넘지 않게 더 잘게 나눈다. 띠 높이는 고르게 — 마지막 띠만 짧으면 그 띠가 작게
     * 풀려 이음매가 흐려 보인다.
     */
    fun strips(size: ImageSize, maxStrip: Int = MAX_STRIP, scale: Float = 1f): List<IntRange> {
        // 화면에 그려지는 높이도 한도 안으로: 좁은 그림을 넓은 화면에 늘리면(60px → 786px) 원본 400줄 띠 하나가 화면에서
        // 5240px 이 되어, 그리기 층 한도를 넘은 띠가 검게 나왔다(웹툰 시험에서 찾음).
        val rowsPerStrip = if (scale > 1f) (maxStrip / scale).toInt().coerceIn(1, maxStrip) else maxStrip
        val count = (size.height + rowsPerStrip - 1) / rowsPerStrip
        return (0 until count).map { i ->
            val top = (size.height.toLong() * i / count).toInt()
            val bottom = (size.height.toLong() * (i + 1) / count).toInt()
            top until bottom
        }
    }
}

/**
 * 그림들을 [width] 폭 기둥에 세로로 이어 놓은 배치. 그림 사이 틈은 없다(구상안 ⑤ — 웹툰은 칸이 이어져야 한다).
 * 크기를 모르는 그림(깨진 머리)은 정사각형 자리를 준다 — 0 높이로 두면 "그리지 못했습니다" 안내조차 보이지 않는다.
 */
class WebtoonColumn(sizes: List<ImageSize?>, val width: Float) {
    val heights: List<Float> = sizes.map { s -> if (s == null) width else width * s.height / s.width }
    val tops: List<Float> = heights.runningFold(0f) { acc, h -> acc + h }.dropLast(1)
    val total: Float = heights.sum()

    /** 기둥 위에서 [offset] 만큼 내려온 곳: (그림 번호, 그 그림 안의 비율 0..1). 위치 저장은 이것으로 한다 — 폭이 바뀌어도 미끄러지지 않는다. */
    fun at(offset: Float): Pair<Int, Float> {
        if (heights.isEmpty()) return 0 to 0f
        val y = offset.coerceIn(0f, total)
        var i = tops.indexOfLast { it <= y }.coerceAtLeast(0)
        // 맨 끝(y == total)은 마지막 그림의 끝이다.
        if (i >= heights.size) i = heights.size - 1
        val h = heights[i]
        return i to if (h <= 0f) 0f else ((y - tops[i]) / h).coerceIn(0f, 1f)
    }

    /** [at] 의 반대: 그림 [index] 의 [fraction] 자리까지의 높이. 범위를 벗어나면 가둔다. */
    fun offsetOf(index: Int, fraction: Float): Float {
        if (heights.isEmpty()) return 0f
        val i = index.coerceIn(0, heights.size - 1)
        return tops[i] + heights[i] * fraction.coerceIn(0f, 1f)
    }
}
