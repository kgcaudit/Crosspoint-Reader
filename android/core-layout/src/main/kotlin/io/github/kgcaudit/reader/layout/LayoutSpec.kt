package io.github.kgcaudit.reader.layout

/** 지면 여백(px). */
data class Insets(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
) {
    companion object {
        fun all(value: Float): Insets = Insets(value, value, value, value)
    }
}

/**
 * 조판에 영향을 주는 모든 값.
 *
 * 이 값들의 [cacheKey] 가 페이지 캐시의 디렉터리 이름이 된다. 하나라도 바뀌면 다른
 * 캐시가 되므로 옛 페이지가 화면에 섞이지 않는다. **조판 결과를 바꾸는 설정을 여기
 * 넣지 않으면** 설정을 바꿨는데 옛 페이지가 그대로 보이는 버그가 된다 — 새 조판 옵션을
 * 더할 때 가장 먼저 확인할 자리다.
 */
data class LayoutSpec(
    val viewportWidthPx: Float,
    val viewportHeightPx: Float,
    val margin: Insets,
    val baseSizePx: Float,
    val lineHeightMultiplier: Float = 1.2f,
    val align: TextAlign = TextAlign.Justify,
    /** 문단 첫 줄 들여쓰기(em). 0 이면 없음. */
    val paragraphIndentEm: Float = 0f,
    /** 문단 사이 간격(em). 들여쓰기와 독립이다 — 둘 다 쓰거나 하나만 쓸 수 있다. */
    val paragraphSpacingEm: Float = 0f,
    val breakBetweenCjk: Boolean = true,
    val imagesEnabled: Boolean = true,
    /**
     * 출판사 CSS 를 따를지 여부.
     *
     * 끄면 책이 지정한 정렬·들여쓰기·여백·글자 크기를 무시하고 사용자 설정으로만
     * 조판한다. 서식이 제각각인 책들을 한 모양으로 읽고 싶을 때 쓰는 흔한 기능이라
     * 조판 결과가 통째로 달라진다 — 그래서 여기(캐시 키 안)에 있어야 한다.
     */
    val usePublisherStyles: Boolean = true,
    /**
     * 측정기가 쓰는 글꼴의 식별자.
     *
     * 코어는 이 값의 뜻을 모른다 — 글꼴을 고르는 것은 플랫폼 쪽 [TextMeasurer] 의 일이다.
     * 그래도 여기 있어야 하는 이유: 글꼴을 바꾸면 글자 폭이 달라져 페이지 경계가 전부
     * 움직이는데, 캐시 키에 글꼴이 없으면 바탕으로 잰 페이지를 고딕으로 그리게 된다.
     * 증상은 "줄 끝이 지면을 넘거나 오른쪽이 들쭉날쭉하다" 로 나타난다.
     *
     * 폰트 파일을 바꿀 때도 이 값을 바꿔야 한다(같은 이름이라도 폭이 다르다).
     */
    val fontId: String = DEFAULT_FONT_ID,
    /**
     * CSS 1px 이 화면 몇 px 인가 — 안드로이드 밀도(dp 배율)다.
     *
     * 그림의 원래 크기(파일 픽셀)와 책이 적은 `width: 300px` 는 CSS px 이다. 브라우저·
     * WebView 처럼 1 CSS px = 1dp 로 옮기지 않으면, 고밀도 폰(약 3배)에서 118px 로고가
     * 손톱만 하게, SVG 표지가 화면 3분의 1 크기로 나온다.
     */
    val cssPxScale: Float = 1f,
    /**
     * 책에 든 글꼴(출판사 글꼴, CSS `@font-face`)로 조판할지.
     *
     * 끄면 모든 글자가 [fontId] 의 글꼴이다. 켜면 책이 `font-family` 로 지정한 곳만 책 글꼴이고
     * 나머지는 [fontId] 다. 글꼴이 바뀌면 폭이 바뀌므로 캐시 키에 들어가야 한다.
     */
    val useBookFonts: Boolean = false,
) {
    init {
        require(viewportWidthPx > 0f && viewportHeightPx > 0f) { "viewport must be positive" }
        require(baseSizePx > 0f) { "baseSizePx must be positive" }
        require(cssPxScale > 0f) { "cssPxScale must be positive" }
    }

    /** 본문이 놓이는 폭. */
    val contentWidthPx: Float get() = viewportWidthPx - margin.left - margin.right

    /** 본문이 놓이는 높이. */
    val contentHeightPx: Float get() = viewportHeightPx - margin.top - margin.bottom

    /**
     * 이 설정의 안정적 식별자.
     *
     * FNV-1a 를 쓴다 — 짧고, 플랫폼·버전에 무관하게 같은 값이 나온다. 해시 알고리즘을
     * 바꾸면 모든 캐시가 한 번 무효화되므로(동작은 정상, 첫 열기만 느려짐) 바꿀 이유가
     * 없는 한 두는 게 낫다.
     */
    val cacheKey: String get() = fnv1a(canonical())

    private fun canonical(): String = buildString {
        append(viewportWidthPx).append('|').append(viewportHeightPx).append('|')
        append(margin.left).append(',').append(margin.top).append(',')
        append(margin.right).append(',').append(margin.bottom).append('|')
        append(baseSizePx).append('|').append(lineHeightMultiplier).append('|')
        append(align.name).append('|')
        append(paragraphIndentEm).append('|').append(paragraphSpacingEm).append('|')
        append(breakBetweenCjk).append('|').append(imagesEnabled).append('|')
        append(usePublisherStyles).append('|').append(fontId).append('|').append(cssPxScale)
        // 끈 상태(기본)의 키는 예전과 같게 둔다. 안 그러면 책 글꼴 없는 책까지 한 번씩 다시 조판한다.
        if (useBookFonts) append("|bookfonts")
    }

    companion object {
        /** 글꼴을 따로 정하지 않았을 때. 테스트의 가짜 측정기가 이 값으로 돈다. */
        const val DEFAULT_FONT_ID: String = "default"

        private fun fnv1a(text: String): String {
            var hash = -0x340d631b7bdddcdbL // 14695981039346656037 (FNV offset basis)
            for (ch in text) {
                hash = hash xor ch.code.toLong()
                hash *= 0x100000001b3L
            }
            return hash.toULong().toString(16).padStart(16, '0')
        }
    }
}
