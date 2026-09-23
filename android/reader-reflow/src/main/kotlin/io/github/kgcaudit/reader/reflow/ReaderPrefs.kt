package io.github.kgcaudit.reader.reflow

import io.github.kgcaudit.reader.layout.Insets
import io.github.kgcaudit.reader.layout.LayoutSpec
import io.github.kgcaudit.reader.layout.TextAlign

/**
 * 사용자가 고르는 보기 설정.
 *
 * 전부 조판 결과를 바꾸므로 [toSpec] 을 거쳐 [LayoutSpec] 에 들어간다(규칙 4).
 */
data class ReaderPrefs(
    val fontSizeSp: Int = DEFAULT_SIZE_SP,
    /** 글꼴 키([io.github.kgcaudit.reader.text.FontOption.key]). null 은 "기기 기본" — 명조가 있으면 명조. */
    val font: String? = null,
    val lineSpacing: LineSpacing = LineSpacing.Normal,
) {
    enum class LineSpacing(val multiplier: Float, val label: String) {
        Tight(1.4f, "좁게"),
        Normal(1.65f, "보통"),
        Loose(1.9f, "넓게"),
    }

    fun withSize(delta: Int) = copy(fontSizeSp = (fontSizeSp + delta).coerceIn(MIN_SIZE_SP, MAX_SIZE_SP))

    companion object {
        const val DEFAULT_SIZE_SP = 18
        const val MIN_SIZE_SP = 12
        const val MAX_SIZE_SP = 36
    }
}

/**
 * 화면 크기와 보기 설정에서 조판 설정을 만든다.
 *
 * 여백은 px 로 받는다(밀도 환산은 화면 쪽 일). 아래 여백은 상태바 자리를 포함한다 —
 * 본문이 상태바 밑으로 들어가면 마지막 줄이 가려진다.
 */
fun ReaderPrefs.toSpec(
    widthPx: Float,
    heightPx: Float,
    margin: Insets,
    pxPerSp: Float,
    /** 1dp 가 몇 px 인가. 그림의 CSS px 을 dp 로 옮기는 데 쓴다. */
    pxPerDp: Float,
    /** [io.github.kgcaudit.reader.text.FontCatalog.layoutFontId] 의 값. 글자 폭 지문이 붙어 있다. */
    fontId: String,
): LayoutSpec = LayoutSpec(
    viewportWidthPx = widthPx,
    viewportHeightPx = heightPx,
    margin = margin,
    baseSizePx = fontSizeSp * pxPerSp,
    lineHeightMultiplier = lineSpacing.multiplier,
    align = TextAlign.Justify,
    paragraphIndentEm = 1f,
    paragraphSpacingEm = 0.25f,
    fontId = fontId,
    cssPxScale = pxPerDp,
)
