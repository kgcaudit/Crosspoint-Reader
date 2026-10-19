package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.layout.Insets
import io.github.kgcaudit.reader.layout.LayoutSpec
import io.github.kgcaudit.reader.layout.Paginator
import io.github.kgcaudit.reader.layout.TextAlign
import io.github.kgcaudit.reader.layout.TextMeasurer

/**
 * 화면 크기에서 [LayoutSpec] 을 만든다. 값은 안드로이드의 기본 읽기 설정(`ReaderPrefs` · `ReaderScreen`)과 같다 — 같은 책이
 * 같은 크기의 화면에서 같은 쪽 수가 나와야 두 앱의 진도가 맞는다.
 *
 * Swift 는 Kotlin 의 기본 매개변수를 쓸 수 없어 [LayoutSpec] 을 바로 만들면 18개를 다 적어야 한다. 그 사이에 값이 갈라지지
 * 않게 여기 한 곳에서 만든다. 설정 화면이 생기면 매개변수가 늘어난다.
 */
object ReadingSpec {
    /** 글자 크기(pt). 안드로이드 `DEFAULT_SIZE_SP`. */
    const val DEFAULT_SIZE = 18f

    /** 줄 간격 "보통". */
    const val DEFAULT_LINE_SPACING = 1.65f

    /** 좌우 여백 "보통"(36dp). */
    const val SIDE_MARGIN = 36f

    /** 위 여백(34dp). */
    const val TOP_MARGIN = 34f

    /** 아래 여백: 아래 상태 막대(28dp) + 숨 쉴 틈(28dp). 본문이 상태 막대 밑으로 들어가면 마지막 줄이 가려진다. */
    const val BOTTOM_MARGIN = 56f

    /**
     * [widthPt] × [heightPt] 화면의 기본 조판 설정. [safeLeft] 등은 화면의 안전 영역(노치 · 홈 막대) — 안드로이드가 디스플레이
     * 잘림(cutout)을 여백에 더하는 것과 같다.
     */
    fun of(
        widthPt: Float,
        heightPt: Float,
        safeLeft: Float,
        safeTop: Float,
        safeRight: Float,
        safeBottom: Float,
        fontSize: Float = DEFAULT_SIZE,
    ): LayoutSpec = LayoutSpec(
        viewportWidthPx = widthPt,
        viewportHeightPx = heightPt,
        margin = Insets(
            left = SIDE_MARGIN + safeLeft,
            top = TOP_MARGIN + safeTop,
            right = SIDE_MARGIN + safeRight,
            bottom = BOTTOM_MARGIN + safeBottom,
        ),
        baseSizePx = fontSize,
        lineHeightMultiplier = DEFAULT_LINE_SPACING,
        align = TextAlign.Justify,
        paragraphIndentEm = 1f,
        paragraphSpacingEm = 0.25f,
        // CSS 1px = 1pt. 안드로이드는 1px 을 1dp 로 옮긴다(cssPxScale = 밀도) — iOS 의 pt 가 dp 자리다.
        cssPxScale = 1f,
        fontId = "ios-system",
    )

    /** 기본 줄 나누기 규칙의 [Paginator]. 같은 까닭(Swift 는 기본 매개변수를 못 쓴다)으로 여기서 만든다 — 규칙을 Swift 에서 따로 고르면 안드로이드와 줄 나눔이 갈라진다. */
    fun paginator(spec: LayoutSpec, measurer: TextMeasurer): Paginator = Paginator(spec, measurer)
}
