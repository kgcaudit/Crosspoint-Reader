package io.github.kgcaudit.reader.layout.html

import io.github.kgcaudit.reader.layout.css.CssParser
import io.github.kgcaudit.reader.layout.css.Stylesheet

/**
 * 태그의 기본 서식 — 브라우저의 사용자 에이전트 스타일시트에 해당한다.
 *
 * CSS 로 적는 이유: 태그 이름을 `when` 으로 나열하면 "굵게" 가 태그 경로와 CSS 경로
 * 두 군데서 정해지고, 둘이 조금씩 어긋나기 시작한다. 여기도 스타일시트로 두면
 * 캐스케이드 한 줄기만 남고, 기본값을 고치는 일이 CSS 한 줄 고치는 일이 된다.
 *
 * 책의 CSS 보다 **항상 약하다**. [StyleResolver] 가 이걸 먼저 적용한 뒤 책의 규칙을
 * 덮어쓰므로, 우선순위 계산에 섞이지 않는다 — CSS 의 출처(origin) 규칙과 같다.
 *
 * 문단 여백이 0 인 것은 의도다. 문단 사이 간격은 사용자 설정
 * (`LayoutSpec.paragraphSpacingEm`)이 맡는다. 여기서 1em 을 주면 설정을 0 으로 해도
 * 간격이 남는다.
 *
 * 제목 · `pre` 에 `text-align` 을 주지 않는 것도 의도다. `text-align` 은 상속 속성이라, 여기서 `left` 로 정하면
 * 가운데 정렬한 표제지 `<div>` 안의 `<h1>` 이 왼쪽으로 쏠린다(브라우저 기본 스타일시트에도 없다). 아무도 정하지
 * 않았을 때 이들을 왼쪽에 두는 일은 [START_ALIGNED_TAGS] 로 [StyleResolver.blockStyle] 이 맡는다.
 */
object TagDefaults {

    val stylesheet: Stylesheet by lazy { CssParser.parse(CSS) }

    /** 블록으로 조판하는 태그. 여기 없는 태그는 인라인으로 본다. */
    val BLOCK_TAGS: Set<String> = setOf(
        "address", "article", "aside", "blockquote", "body", "center", "dd", "div", "dl", "dt",
        "figcaption", "figure", "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6", "header",
        "hgroup", "li", "main", "nav", "ol", "p", "pre", "section", "table", "tbody", "td",
        "tfoot", "th", "thead", "tr", "ul",
    )

    /**
     * 내용을 통째로 버리는 태그. 본문이 아니다.
     *
     * `head` 는 여기 없다 — 안에 `<style>` 이 들어 있고, 그게 책 서식의 상당 부분이다.
     * `head` 의 나머지(`meta`·`link`)는 글자를 담지 않아 그냥 지나가도 해가 없다.
     */
    val SKIPPED_TAGS: Set<String> = setOf("script", "title", "noscript")

    /**
     * 닫는 태그가 없는 요소(HTML 의 빈 요소). XHTML 은 `<br/>` 로 닫지만 규격에 덜 맞는 책은 `<br>` 로 쓴다 — 그때 닫힘
     * 사건이 오지 않는다. 이 요소들을 스택에 쌓으면 닫는 태그가 한 칸씩 어긋나, 뒤 문단 전부가 인용문 들여쓰기를 받거나
     * 숨긴 요소 안이면 장의 나머지가 통째로 사라졌다. 그래서 쌓지 않고, `<br/>` 의 닫힘 사건은 버린다.
     */
    val VOID_TAGS: Set<String> = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "image", "input", "link", "meta", "param", "source", "track", "wbr",
    )

    /** 부모도 책도 정렬을 정하지 않았을 때 사용자 정렬(보통 양쪽) 대신 왼쪽(시작)에 두는 태그. */
    val START_ALIGNED_TAGS: Set<String> = setOf("h1", "h2", "h3", "h4", "h5", "h6", "pre")

    /** 공백을 그대로 보존하는 태그. */
    val PREFORMATTED_TAGS: Set<String> = setOf("pre")

    private val CSS = """
        h1 { font-size: 2em;    font-weight: bold; margin: 0.67em 0; text-indent: 0 }
        h2 { font-size: 1.5em;  font-weight: bold; margin: 0.83em 0; text-indent: 0 }
        h3 { font-size: 1.17em; font-weight: bold; margin: 1em 0;    text-indent: 0 }
        h4 { font-weight: bold; margin: 1.33em 0; text-indent: 0 }
        h5 { font-size: 0.83em; font-weight: bold; margin: 1.67em 0; text-indent: 0 }
        h6 { font-size: 0.67em; font-weight: bold; margin: 2.33em 0; text-indent: 0 }

        blockquote { margin: 1em 2em; text-indent: 0 }
        pre { margin: 1em 0; text-indent: 0 }
        figure { margin: 1em 0 }
        figcaption { font-size: 0.9em; text-align: center; text-indent: 0 }
        hr { margin: 1em 0 }
        center { text-align: center; text-indent: 0 }
        ul, ol { margin: 1em 0 }
        li { margin-left: 1.5em; text-indent: 0 }
        dd { margin-left: 2em }
        dt { font-weight: bold }
        th { font-weight: bold; text-align: center }

        b, strong { font-weight: bold }
        i, em, cite, dfn, var, address { font-style: italic }
        u, ins { text-decoration: underline }
        s, strike, del { text-decoration: line-through }
        small { font-size: 0.85em }
        big { font-size: 1.15em }
        sup { font-size: 0.75em; vertical-align: super }
        sub { font-size: 0.75em; vertical-align: sub }
        code, kbd, samp, tt { font-size: 0.95em }
    """.trimIndent()
}
