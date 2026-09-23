package io.github.kgcaudit.reader.layout

/**
 * 결정적인 측정기. 조판 테스트의 토대다.
 *
 * 모든 글자를 같은 폭으로 재므로 줄바꿈 결과를 손으로 계산할 수 있고, 페이지 분할을
 * 골든 스냅샷으로 고정할 수 있다. 기기도 폰트도 필요 없다 — 실제 `Paint` 로 테스트하면
 * 폰트 버전이 바뀔 때마다 골든이 흔들려 회귀 감지 기능을 잃는다.
 *
 * 전각(CJK)을 2배로 두는 것은 실제 폰트의 성질을 닮게 하려는 것이다. 한국어 줄바꿈이
 * 반각 폭 가정에서만 맞는 일이 없게 한다.
 */
class FakeMeasurer(
    override val baseSizePx: Float = 10f,
    private val lineHeightRatio: Float = 1.2f,
) : TextMeasurer {

    override fun advance(text: CharSequence, start: Int, endExclusive: Int, style: TextStyle): Float {
        var total = 0f
        for (i in start until endExclusive) {
            total += charWidth(text[i]) * style.sizeScale
        }
        return total * baseSizePx / 10f
    }

    override fun lineHeight(style: TextStyle): Float = baseSizePx * style.sizeScale * lineHeightRatio

    override fun ascent(style: TextStyle): Float = baseSizePx * style.sizeScale * 0.8f

    override fun spaceAdvance(style: TextStyle): Float = 5f * style.sizeScale * baseSizePx / 10f

    /** 기준 크기 10px 에서의 폭: 전각 20, 공백 5, 그 외 10. */
    private fun charWidth(ch: Char): Float = when {
        LineBreakRules.isCjk(ch) -> 20f
        ch == ' ' || ch == '\t' -> 5f
        else -> 10f
    }
}
