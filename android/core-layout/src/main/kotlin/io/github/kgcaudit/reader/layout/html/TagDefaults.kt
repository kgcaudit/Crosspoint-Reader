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

    /** 공백을 그대로 보존하는 태그. */
    val PREFORMATTED_TAGS: Set<String> = setOf("pre")

    private val CSS = """
        h1 { font-size: 2em;    font-weight: bold; margin: 0.67em 0; text-align: left; text-indent: 0 }
        h2 { font-size: 1.5em;  font-weight: bold; margin: 0.83em 0; text-align: left; text-indent: 0 }
        h3 { font-size: 1.17em; font-weight: bold; margin: 1em 0;    text-align: left; text-indent: 0 }
        h4 { font-weight: bold; margin: 1.33em 0; text-align: left; text-indent: 0 }
        h5 { font-size: 0.83em; font-weight: bold; margin: 1.67em 0; text-align: left; text-indent: 0 }
        h6 { font-size: 0.67em; font-weight: bold; margin: 2.33em 0; text-align: left; text-indent: 0 }

        blockquote { margin: 1em 2em; text-indent: 0 }
        pre { margin: 1em 0; text-align: left; text-indent: 0 }
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
