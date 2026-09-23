package io.github.kgcaudit.reader.layout.html

import io.github.kgcaudit.reader.layout.BlockStyle
import io.github.kgcaudit.reader.layout.LayoutSpec
import io.github.kgcaudit.reader.layout.TextAlign
import io.github.kgcaudit.reader.layout.TextStyle
import io.github.kgcaudit.reader.layout.css.CssDeclarations
import io.github.kgcaudit.reader.layout.css.CssLength
import io.github.kgcaudit.reader.layout.css.CssParser
import io.github.kgcaudit.reader.layout.css.ElementInfo
import io.github.kgcaudit.reader.layout.css.Stylesheet

/**
 * 스타일 해석에 필요한 조판 쪽 정보.
 *
 * [LayoutSpec] 을 통째로 받지 않는 이유: 해석기는 지면 크기나 줄 간격을 몰라도 되고,
 * 모르면 테스트에서 지면을 꾸며 낼 필요가 없다.
 */
data class StyleContext(
    /** 기준 글자 크기(px). px·pt 로 적힌 길이를 em 으로 되돌리는 데만 쓴다. */
    val baseSizePx: Float = 16f,
    /** 퍼센트 길이의 기준이 되는 본문 폭(px). 0 이면 퍼센트는 0 으로 본다. */
    val contentWidthPx: Float = 0f,
    /** 책이 정렬을 지정하지 않았을 때 쓰는 정렬. */
    val defaultAlign: TextAlign = TextAlign.Justify,
    /** 출판사 CSS 를 따를지. 끄면 태그 기본값과 사용자 설정만 남는다. */
    val usePublisherStyles: Boolean = true,
) {
    companion object {
        fun of(spec: LayoutSpec): StyleContext = StyleContext(
            baseSizePx = spec.baseSizePx,
            contentWidthPx = spec.contentWidthPx,
            defaultAlign = spec.align,
            usePublisherStyles = spec.usePublisherStyles,
        )
    }
}

/**
 * 요소 하나의 최종 서식을 정한다.
 *
 * 캐스케이드 순서는 CSS 의 출처(origin) 규칙 그대로다:
 * **태그 기본값 → 책의 CSS → `style` 속성.** 세 단계를 우선순위 계산에 섞지 않고
 * 차례로 덮어쓰는 이유는, 섞으면 태그 기본값 `h1`(우선순위 1)이 책의 `*`(우선순위 0)
 * 을 이겨 버리는 등 출처가 뒤집히기 때문이다.
 *
 * 글자 크기는 **부모에 곱한다.** `1.2em` 같은 상대 단위가 EPUB 의 거의 전부이고,
 * 드물게 나오는 `14px` 도 곱셈으로 다루면 사용자가 글자를 키울 때 함께 커진다 —
 * 절대 크기를 지키는 것보다 그쪽이 리더에서 옳다.
 */
class StyleResolver(
    private val publisherStyles: Stylesheet = Stylesheet.EMPTY,
    private val context: StyleContext = StyleContext(),
) {

    private val author: Stylesheet =
        if (context.usePublisherStyles) publisherStyles else Stylesheet.EMPTY

    /** [stack] 의 마지막 요소에 적용되는 선언을 캐스케이드해 구한다. */
    fun declarationsFor(stack: List<ElementInfo>, styleAttribute: String? = null): CssDeclarations {
        var result = TagDefaults.stylesheet.declarationsFor(stack)
        if (author.rules.isNotEmpty()) {
            result = result.mergedWith(author.declarationsFor(stack))
        }
        if (context.usePublisherStyles && !styleAttribute.isNullOrBlank()) {
            result = result.mergedWith(CssParser.parseDeclarations(styleAttribute))
        }
        return result
    }

    /** 부모의 인라인 서식 위에 [declarations] 를 얹는다. */
    fun textStyle(parent: TextStyle, declarations: CssDeclarations): TextStyle = TextStyle(
        bold = declarations.bold ?: parent.bold,
        italic = declarations.italic ?: parent.italic,
        sizeScale = parent.sizeScale * (declarations.fontSizeScale ?: 1f),
        // 밑줄·취소선은 CSS 에서 부모가 그은 선을 자식이 지울 수 없다(text-decoration
        // 은 상속이 아니라 "그려진다"). 여기서도 한 번 켜지면 블록 끝까지 간다.
        underline = parent.underline || (declarations.underline ?: false),
        strikethrough = parent.strikethrough || (declarations.strikethrough ?: false),
        vertical = declarations.verticalAlign ?: parent.vertical,
    )

    /**
     * 블록에서 블록으로 **상속되는** 값들을 한 단계 내린다.
     *
     * CSS 에서 `text-align` 과 `text-indent` 는 상속 속성이다. 요소마다 따로 캐스케이드만
     * 하면 `div { text-align: center }` 안의 `<p>` 가 가운데로 오지 않는다 — 실제 책에서
     * 표제지와 인용이 이 모양으로 적혀 있다.
     *
     * 좌우 여백은 상속이 아니라 **누적**이다. 인용문 안의 인용문, 목록 안의 목록이
     * 단계마다 더 들어가야 하는데, 조판은 이들을 한 겹의 블록으로 펴기 때문에 여기서
     * 더해 두지 않으면 두 단계가 한 단계처럼 보인다.
     */
    fun inherit(parent: InheritedStyle, declarations: CssDeclarations): InheritedStyle =
        InheritedStyle(
            align = declarations.textAlign ?: parent.align,
            firstLineIndentEm = declarations.textIndent?.let { em(it) } ?: parent.firstLineIndentEm,
            indentStartEm = parent.indentStartEm + (declarations.marginLeft?.let { em(it) } ?: 0f),
            indentEndEm = parent.indentEndEm + (declarations.marginRight?.let { em(it) } ?: 0f),
            text = textStyle(parent.text, declarations),
        )

    /** 상속된 값과 이 요소의 선언으로 최종 블록 서식을 만든다. */
    fun blockStyle(inherited: InheritedStyle, declarations: CssDeclarations): BlockStyle =
        BlockStyle(
            align = inherited.align ?: context.defaultAlign,
            firstLineIndentEm = inherited.firstLineIndentEm,
            marginTopEm = declarations.marginTop?.let { em(it) } ?: 0f,
            marginBottomEm = declarations.marginBottom?.let { em(it) } ?: 0f,
            indentStartEm = inherited.indentStartEm,
            indentEndEm = inherited.indentEndEm,
        )

    private fun em(length: CssLength): Float =
        length.toEm(context.baseSizePx, context.contentWidthPx)
}

/**
 * 조상에게서 물려받은 서식. [StyleResolver.inherit] 로 한 단계씩 내려간다.
 *
 * [align] 과 [firstLineIndentEm] 이 nullable 인 이유는 CssDeclarations 와 같다 —
 * "아무도 정하지 않았다" 와 "0 으로 정했다" 는 다르다. 전자는 사용자 설정을 쓰고
 * 후자는 쓰지 않는다.
 */
data class InheritedStyle(
    val align: TextAlign? = null,
    val firstLineIndentEm: Float? = null,
    val indentStartEm: Float = 0f,
    val indentEndEm: Float = 0f,
    val text: TextStyle = TextStyle.Default,
) {
    companion object {
        val Root: InheritedStyle = InheritedStyle()
    }
}
