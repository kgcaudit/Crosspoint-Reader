package io.github.kgcaudit.reader.layout.css

import io.github.kgcaudit.reader.layout.TextAlign

enum class CssUnit { Em, Rem, Px, Pt, Percent }

/**
 * 아직 픽셀로 바뀌지 않은 CSS 길이.
 *
 * 파서가 단위를 보존하는 이유: `1.5em` 이 몇 픽셀인지는 **기준 글자 크기를 아는 조판
 * 시점**에야 정해진다. 파싱할 때 px 로 굳히면 사용자가 글자 크기를 바꿔도 여백이
 * 따라오지 않아, 글자만 커지고 문단이 붙어 버린다.
 */
data class CssLength(val value: Float, val unit: CssUnit) {

    /**
     * em 으로 환산한다.
     *
     * @param baseSizePx 기준 글자 크기(px). px·pt 를 em 으로 되돌리는 데 쓴다.
     * @param containerWidthPx 퍼센트의 기준이 되는 폭. 0 이면 퍼센트는 0 으로 본다
     *   (해석할 수 없는 값으로 엉뚱한 여백을 만드는 것보다 없는 편이 낫다).
     */
    fun toEm(baseSizePx: Float, containerWidthPx: Float = 0f): Float = when (unit) {
        CssUnit.Em, CssUnit.Rem -> value
        CssUnit.Px -> if (baseSizePx > 0f) value / baseSizePx else 0f
        // 1pt = 1/72 인치, 1px = 1/96 인치 → 1pt = 1.333px
        CssUnit.Pt -> if (baseSizePx > 0f) value * PT_TO_PX / baseSizePx else 0f
        CssUnit.Percent ->
            if (containerWidthPx > 0f && baseSizePx > 0f) {
                value / 100f * containerWidthPx / baseSizePx
            } else {
                0f
            }
    }

    companion object {
        const val PT_TO_PX: Float = 96f / 72f

        /** `1.5em` `12pt` `20px` `5%` `0` 을 해석한다. 알아볼 수 없으면 null. */
        fun parse(raw: String): CssLength? {
            val text = raw.trim().lowercase()
            if (text.isEmpty()) return null

            val unit = when {
                text.endsWith("em") -> CssUnit.Em
                text.endsWith("rem") -> CssUnit.Rem
                text.endsWith("px") -> CssUnit.Px
                text.endsWith("pt") -> CssUnit.Pt
                text.endsWith("%") -> CssUnit.Percent
                else -> null
            }
            // rem 은 em 으로도 끝나므로 먼저 본다.
            val resolvedUnit = if (text.endsWith("rem")) CssUnit.Rem else unit

            val numberText = when (resolvedUnit) {
                CssUnit.Rem -> text.dropLast(3)
                CssUnit.Em, CssUnit.Px, CssUnit.Pt -> text.dropLast(2)
                CssUnit.Percent -> text.dropLast(1)
                // 단위 없는 0 만 허용한다. 단위 없는 다른 숫자는 CSS 에서 무효다.
                null -> text
            }
            val number = numberText.trim().toFloatOrNull() ?: return null
            if (resolvedUnit == null) return if (number == 0f) CssLength(0f, CssUnit.Em) else null
            return CssLength(number, resolvedUnit)
        }
    }
}

/**
 * 한 요소에 선언된 CSS 속성들.
 *
 * 모든 값이 nullable 인 이유: null 은 **"이 규칙이 그 속성을 건드리지 않는다"** 는
 * 뜻이고, 0 이나 false 와는 다르다. 캐스케이드가 우선순위 순서대로 덮어쓸 때 이
 * 구분이 없으면 낮은 우선순위 규칙이 높은 우선순위 값을 0 으로 지워 버린다.
 */
data class CssDeclarations(
    val textAlign: TextAlign? = null,
    val textIndent: CssLength? = null,
    val italic: Boolean? = null,
    val bold: Boolean? = null,
    val fontSizeScale: Float? = null,
    val underline: Boolean? = null,
    val strikethrough: Boolean? = null,
    val marginTop: CssLength? = null,
    val marginBottom: CssLength? = null,
    val marginLeft: CssLength? = null,
    val marginRight: CssLength? = null,
    val hidden: Boolean? = null,
    val pageBreakBefore: Boolean? = null,
) {
    /** [other] 의 지정된 값으로 덮어쓴다. 지정되지 않은(null) 값은 이쪽 것을 남긴다. */
    fun mergedWith(other: CssDeclarations): CssDeclarations = CssDeclarations(
        textAlign = other.textAlign ?: textAlign,
        textIndent = other.textIndent ?: textIndent,
        italic = other.italic ?: italic,
        bold = other.bold ?: bold,
        fontSizeScale = other.fontSizeScale ?: fontSizeScale,
        underline = other.underline ?: underline,
        strikethrough = other.strikethrough ?: strikethrough,
        marginTop = other.marginTop ?: marginTop,
        marginBottom = other.marginBottom ?: marginBottom,
        marginLeft = other.marginLeft ?: marginLeft,
        marginRight = other.marginRight ?: marginRight,
        hidden = other.hidden ?: hidden,
        pageBreakBefore = other.pageBreakBefore ?: pageBreakBefore,
    )

    val isEmpty: Boolean
        get() = this == EMPTY

    companion object {
        val EMPTY: CssDeclarations = CssDeclarations()
    }
}
