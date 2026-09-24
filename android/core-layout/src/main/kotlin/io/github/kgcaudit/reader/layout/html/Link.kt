package io.github.kgcaudit.reader.layout.html

/**
 * 본문의 링크 하나. [start]..[endExclusive] 는 챕터 텍스트([Chapter.text])의 글자 구간이다.
 *
 * 페이지 캐시에는 넣지 않는다 — 링크는 누를 때와 칠할 때만 필요하고, 그때 챕터를 한 번 읽어 얻는다.
 * 캐시 형식을 바꾸면 모든 책을 다시 조판해야 한다.
 */
data class Link(
    val start: Int,
    val endExclusive: Int,
    val href: String,
    /** 책이 각주 참조라고 밝혔다(`epub:type="noteref"` · `role="doc-noteref"`). */
    val noteRef: Boolean = false,
    /** 위첨자 안에 있다(`<sup><a>1</a></sup>`). 표시를 밝히지 않은 책의 각주는 거의 이 모양이다. */
    val superscript: Boolean = false,
) {
    /** 책 밖(인터넷 · 메일)을 가리킨다. */
    val isExternal: Boolean get() = EXTERNAL.containsMatchIn(href)

    /**
     * 각주 참조로 볼지. 책이 밝혔거나([noteRef]), 가리키는 곳이 각주 내용이거나([targetIsNote]), 위첨자이거나,
     * 글자가 "1" · "[2]" · "3)" · "*" · "①" 처럼 짧은 표시일 때.
     *
     * 짧은 표시만 보는 이유: "제3장 참고" 같은 본문 속 링크를 판으로 띄우면 장 하나가 통째로 판에 들어간다 —
     * 그런 링크는 그 자리로 간다(F5).
     */
    fun isFootnote(chapterText: CharSequence, targetIsNote: Boolean = false): Boolean {
        if (isExternal) return false
        if (noteRef || targetIsNote || superscript) return true
        val label = chapterText.subSequence(start.coerceIn(0, chapterText.length), endExclusive.coerceIn(0, chapterText.length))
        return MARKER.matches(label.trim())
    }

    private companion object {
        val EXTERNAL = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
        /** 1 · 12 · [3] · (4) · 5) · * · ** · † · ‡ · ① … ⑳ · 주1 · 註2 */
        val MARKER = Regex("""^(?:[\[(]?(?:[0-9０-９]{1,3}|[*†‡]{1,3}|[①-⑳]|[주註]\s?[0-9]{1,3})[\])\.]?)$""")
    }
}
