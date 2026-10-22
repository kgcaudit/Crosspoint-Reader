package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.document.comic.Webtoon
import io.github.kgcaudit.reader.document.comic.WebtoonChain
import io.github.kgcaudit.reader.document.comic.WebtoonColumn
import io.github.kgcaudit.reader.document.image.ImageSize

/**
 * 웹툰 기둥의 칸 하나(구상안 39). [episode] 화의 그림 [index] 의 원본 행 [rowFrom] ~ [rowTo](미포함)가 기둥 [top] 에서 [height] 만큼.
 * 경계 띠([seam])는 [episode] 화 앞에 끼는 띠다. 크기를 모르는 그림은 행 없이(-1) 정사각 한 칸 — "그리지 못했습니다" 를 보일 자리.
 */
class WebtoonCell(
    val episode: Int,
    val index: Int,
    val rowFrom: Int,
    val rowTo: Int,
    val top: Float,
    val height: Float,
    val seam: Boolean,
) {
    val bottom: Float get() = top + height
    val known: Boolean get() = rowFrom >= 0
}

/**
 * 웹툰 보기(구상안 39 · 2A)의 셈: 화마다 그림 크기 → 기둥의 칸들(긴 그림은 공통 `Webtoon.strips` 의 띠로), 그림 폭, 자동 스크롤.
 * 자리 · 진도는 공통 `WebtoonChain` 이 정한다. 화면(UIScrollView)은 이 칸들 중 보이는 것만 푼다 — 800×30000 한 장을 통째로 풀면
 * 메모리 · 텍스처 한도에 걸린다.
 */
object IosWebtoon {
    /** 화와 화 사이 경계 띠 높이(pt). 안드로이드 seam 과 같은 150dp. */
    const val SEAM: Float = 150f

    /** 한 번 누를 때 내리는 양(화면 높이의 비율, 안드로이드 `SCREEN_STEP`). 조금 겹쳐야 어디까지 읽었는지 이어진다. */
    const val SCREEN_STEP: Float = 0.85f

    fun chain(episodes: List<List<ImageSize?>>, width: Float): WebtoonChain =
        WebtoonChain(episodes.map { WebtoonColumn(it, width) }, SEAM)

    /**
     * 기둥의 칸들(위에서 아래로). [pixelScale] 은 화면의 한 pt 가 몇 픽셀인가 — 띠가 화면에서도 2048 픽셀을 넘지 않게 그만큼 잘게 나눈다.
     */
    fun cells(episodes: List<List<ImageSize?>>, width: Float, pixelScale: Float): List<WebtoonCell> {
        val chain = chain(episodes, width)
        val out = ArrayList<WebtoonCell>()
        episodes.forEachIndexed { k, sizes ->
            if (k > 0) out += WebtoonCell(k, -1, -1, -1, chain.starts[k] - SEAM, SEAM, seam = true)
            val column = chain.columns[k]
            sizes.forEachIndexed { i, size ->
                val top = chain.starts[k] + column.tops[i]
                val height = column.heights[i]
                if (size == null || size.width <= 0 || size.height <= 0) {
                    out += WebtoonCell(k, i, -1, -1, top, height, seam = false)
                } else {
                    val scale = width * pixelScale / size.width
                    for (rows in Webtoon.strips(size, Webtoon.MAX_STRIP, scale)) {
                        out += WebtoonCell(
                            k, i, rows.first, rows.last + 1,
                            top + height * rows.first / size.height, height * (rows.last + 1 - rows.first) / size.height, seam = false,
                        )
                    }
                }
            }
        }
        return out
    }

    /** [top] ~ [bottom] 에 걸친 칸들의 번호. */
    fun visible(cells: List<WebtoonCell>, top: Float, bottom: Float): List<Int> =
        cells.indices.filter { cells[it].bottom > top && cells[it].top < bottom }

    /** 그림 폭(%) 범위 · 한 칸 · 기본값(안드로이드 `WEBTOON_COLUMN_*`): 휴대폰 세로는 꽉 채워 시작, 넓은 화면은 70%. */
    const val MIN_PERCENT: Int = 40
    const val MAX_PERCENT: Int = 100
    const val STEP_PERCENT: Int = 5
    const val DEFAULT_NARROW: Int = 100
    const val DEFAULT_WIDE: Int = 70

    /** 저장된 값 → 쓸 값. 없거나(−1) 범위 밖(손상)이면 그 등급의 기본값, 칸에 맞지 않으면 가까운 칸으로. */
    fun percent(saved: Int, wide: Boolean): Int {
        if (saved < MIN_PERCENT || saved > MAX_PERCENT) return if (wide) DEFAULT_WIDE else DEFAULT_NARROW
        return ((saved + STEP_PERCENT / 2) / STEP_PERCENT * STEP_PERCENT).coerceIn(MIN_PERCENT, MAX_PERCENT)
    }

    /** 밀대 자리(0..1) → 그림 폭(%, 칸에 맞춤). */
    fun percentAt(fraction: Float): Int {
        val raw = MIN_PERCENT + fraction.coerceIn(0f, 1f) * (MAX_PERCENT - MIN_PERCENT)
        return (kotlin.math.round(raw / STEP_PERCENT) * STEP_PERCENT).toInt().coerceIn(MIN_PERCENT, MAX_PERCENT)
    }

    /** 자동 스크롤 빠르기(안드로이드 `AutoScrollSpeed`): 이름과 한 화면에 걸리는 초. */
    val speedLabels: List<String> = listOf("아주 느림", "느림", "보통", "빠름", "아주 빠름")
    val secondsPerScreen: List<Float> = listOf(25f, 15f, 10f, 7f, 5f)

    /** 저장된 빠르기 번호 → 쓸 번호(모르면 보통). */
    fun speed(saved: Int): Int = saved.takeIf { it in secondsPerScreen.indices } ?: 2
}
