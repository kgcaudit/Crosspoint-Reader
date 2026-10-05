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
        // height + rows - 1 은 깨진 머리(높이 21억)에서 넘쳐 음수 → 띠가 하나도 없었다.
        val count = (size.height - 1) / rowsPerStrip + 1
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

/**
 * 여러 화를 한 기둥에 잇는 배치(0.47.0, 사용자 결정 ③ B): 화마다 [WebtoonColumn], 화와 화 사이에 [seam] 높이의 경계 띠.
 * 경계 띠는 **다음 화의 머리**로 친다 — 띠가 화면 위에 걸리면 이미 다음 화를 보는 것이다(구상안: "4화 · 0%").
 *
 * 자리는 늘 (화, 그림, 그 안의 비율)로 바꿔 적는다. 폭이 바뀌어도 · 앞의 화를 내려놓아도 같은 칸으로 돌아온다.
 */
class WebtoonChain(val columns: List<WebtoonColumn>, val seam: Float) {
    /** 화 [k] 의 첫 그림이 시작하는 높이. */
    val starts: List<Float> = columns.indices.map { k -> columns.take(k).sumOf { it.total.toDouble() }.toFloat() + seam * k }

    /** 마지막 화의 끝. 그 뒤의 끝 판은 셈에 넣지 않는다. */
    val total: Float = if (columns.isEmpty()) 0f else starts.last() + columns.last().total

    /** 화 [k] 의 끝(마지막 그림 아래). */
    fun endOf(k: Int): Float = starts[k] + columns[k].total

    /** [y] 높이를 보는 화. 경계 띠는 다음 화에 든다. 끝을 넘으면 마지막 화. */
    fun episodeAt(y: Float): Int {
        if (columns.isEmpty()) return 0
        return columns.indices.lastOrNull { k -> (if (k == 0) 0f else starts[k] - seam) <= y } ?: 0
    }

    /** [y] 높이의 (화, 그림, 비율). 경계 띠 안이면 다음 화의 맨 처음. */
    fun at(y: Float): Triple<Int, Int, Float> {
        val k = episodeAt(y)
        val (i, f) = columns.getOrNull(k)?.at((y - starts[k]).coerceAtLeast(0f)) ?: (0 to 0f)
        return Triple(k, i, f)
    }

    /** [at] 의 반대. */
    fun offsetOf(k: Int, index: Int, fraction: Float): Float {
        if (columns.isEmpty()) return 0f
        val e = k.coerceIn(0, columns.size - 1)
        return starts[e] + columns[e].offsetOf(index, fraction)
    }

    /** 화 [k] 안에서 막대가 움직이는 길이. 한 화면보다 짧은 화도 0 으로 나누지 않게 1 이상. */
    fun readable(k: Int, viewHeight: Float): Float = (columns[k].total - viewHeight).coerceAtLeast(1f)

    /** 화면 위 [y] 가 그 화의 몇 %(0..1)인가. */
    fun fraction(y: Float, viewHeight: Float): Float {
        if (columns.isEmpty()) return 0f
        val k = episodeAt(y)
        return ((y - starts[k]) / readable(k, viewHeight)).coerceIn(0f, 1f)
    }

    /** 화면 아래가 화 [k] 의 끝에 닿았는가(다 읽음). 1px 덜 닿아도 끝으로 본다 — 반올림으로 끝까지 안 내려가는 일이 있다. */
    fun reachedEnd(k: Int, y: Float, viewHeight: Float): Boolean = y + viewHeight >= endOf(k) - 1f
}
