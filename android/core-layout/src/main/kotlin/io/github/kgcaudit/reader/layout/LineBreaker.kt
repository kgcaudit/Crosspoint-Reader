package io.github.kgcaudit.reader.layout

/** 한 줄이 놓일 수 있는 폭과 첫 줄 들여쓰기. 조판기가 블록 스타일에서 계산해 넘긴다. */
data class LineConstraints(
    val widthPx: Float,
    val firstLineIndentPx: Float = 0f,
) {
    init { require(widthPx > 0f) { "widthPx must be > 0, was $widthPx" } }
}

/** 줄 안에 배치된 글자 구간. [xPx] 는 줄 왼쪽 기준 위치다. */
data class PlacedPiece(
    val start: Int,
    val endExclusive: Int,
    val style: TextStyle,
    val xPx: Float,
)

/**
 * 배치가 끝난 한 줄.
 *
 * [startChar]/[endCharExclusive] 는 이 줄이 덮는 챕터 텍스트 구간이다. 진도와
 * 책갈피가 이 값으로 기록되므로, 줄을 만들 때 구간을 빠뜨리거나 겹치면 이어읽기가
 * 어긋난다.
 */
data class LaidLine(
    val pieces: List<PlacedPiece>,
    val startChar: Int,
    val endCharExclusive: Int,
    val heightPx: Float,
    val ascentPx: Float,
    /** 이 줄이 블록의 마지막 줄인지. 양쪽정렬에서 마지막 줄은 늘리지 않는다. */
    val isLastLine: Boolean,
)

/**
 * 줄바꿈 정책. **교체 가능한 전략**이다.
 *
 * 구현이 갈리는 지점: 어디서 끊을지, 그리고 남는 폭을 어떻게 나눌지. 측정은 언제나
 * [TextMeasurer] 가 맡으므로 구현이 글자 폭을 스스로 계산하지는 않는다.
 *
 * 지금은 [GreedyLineBreaker] 하나이고, 한국어 어절 간격을 공백폭의 1.0–1.5배로
 * 제한하는 변형이 나중에 같은 자리에 들어온다 — 위층은 손대지 않는다.
 */
interface LineBreaker {
    fun breakLines(
        text: CharSequence,
        runs: List<InlineRun>,
        style: BlockStyle,
        constraints: LineConstraints,
        measurer: TextMeasurer,
    ): List<LaidLine>
}
