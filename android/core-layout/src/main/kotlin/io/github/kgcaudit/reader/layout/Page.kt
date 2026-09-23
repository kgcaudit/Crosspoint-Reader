package io.github.kgcaudit.reader.layout

/** 페이지에 놓인 글자 구간. [baselineYPx] 는 지면 위쪽 기준 베이스라인 위치다. */
data class PlacedRun(
    val start: Int,
    val endExclusive: Int,
    val style: TextStyle,
    val xPx: Float,
    val baselineYPx: Float,
)

/** 페이지에 놓인 그림. 크기는 이미 지면에 맞춰 조정된 값이다. */
data class PlacedImage(
    val href: String,
    val xPx: Float,
    val yPx: Float,
    val widthPx: Float,
    val heightPx: Float,
)

/** 페이지에 놓인 구분선. */
data class PlacedRule(
    val xPx: Float,
    val yPx: Float,
    val widthPx: Float,
    val thicknessPx: Float,
)

/**
 * 조판이 끝난 한 페이지.
 *
 * 좌표는 지면 왼쪽 위 기준 **절대값**이고 여백이 이미 반영돼 있다. 그리는 쪽은
 * 계산 없이 그대로 찍으면 된다 — 페이지 넘김 경로에 산술을 남기지 않는 것이
 * 16ms 예산의 전제다.
 *
 * [startChar] 는 진도와 책갈피의 기준이다. 페이지 번호가 아니라 이 값을 저장하므로
 * 글꼴·여백이 바뀌어 재조판돼도 같은 글자로 돌아온다.
 */
data class Page(
    val index: Int,
    val startChar: Int,
    val endCharExclusive: Int,
    val runs: List<PlacedRun> = emptyList(),
    val images: List<PlacedImage> = emptyList(),
    val rules: List<PlacedRule> = emptyList(),
) {
    val isEmpty: Boolean get() = runs.isEmpty() && images.isEmpty() && rules.isEmpty()
}
