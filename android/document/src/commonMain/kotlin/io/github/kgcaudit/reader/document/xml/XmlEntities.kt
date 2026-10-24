package io.github.kgcaudit.reader.document.xml

import io.github.kgcaudit.reader.document.io.codePointToString

/**
 * 엔티티 참조 해석.
 *
 * XML이 기본으로 정의하는 것은 `&amp; &lt; &gt; &quot; &apos;` 다섯 개뿐이다. 그런데
 * 실제 EPUB의 XHTML은 DTD 없이 `&nbsp;` `&mdash;` `&hellip;` 같은 HTML 엔티티를
 * 그냥 쓴다 — 엄격히는 잘못된 문서지만 매우 흔하다. 이걸 처리하지 않으면 시중의 책
 * 상당수에서 본문에 `&nbsp;` 가 그대로 박혀 보인다.
 *
 * 모르는 엔티티는 **원문 그대로 둔다**. 지우면 글자가 조용히 사라지고, 예외를 던지면
 * 책이 안 열린다. 그대로 두면 최소한 무슨 일이 있었는지 보인다.
 */
internal object XmlEntities {

    /**
     * HTML 의 라틴-1 엔티티 96 개(U+00A0 ~ U+00FF 차례). XHTML DTD 를 쓴 EPUB · HTML 에서 옮긴 책에 흔하다 — 빠졌던 때는
     * "Pok&eacute;mon" 처럼 원문 그대로 보였다.
     */
    private val LATIN1 = (
        "nbsp iexcl cent pound curren yen brvbar sect uml copy ordf laquo not shy reg macr " +
            "deg plusmn sup2 sup3 acute micro para middot cedil sup1 ordm raquo frac14 frac12 frac34 iquest " +
            "Agrave Aacute Acirc Atilde Auml Aring AElig Ccedil Egrave Eacute Ecirc Euml Igrave Iacute Icirc Iuml " +
            "ETH Ntilde Ograve Oacute Ocirc Otilde Ouml times Oslash Ugrave Uacute Ucirc Uuml Yacute THORN szlig " +
            "agrave aacute acirc atilde auml aring aelig ccedil egrave eacute ecirc euml igrave iacute icirc iuml " +
            "eth ntilde ograve oacute ocirc otilde ouml divide oslash ugrave uacute ucirc uuml yacute thorn yuml"
        ).split(' ').mapIndexed { i, name -> name to (0xA0 + i).toChar().toString() }

    /** 전자책 본문에 실제로 등장하는 명명 엔티티. 없는 것은 숫자 참조로 쓰이므로 충분하다. */
    private val named: Map<String, String> = LATIN1.toMap() + mapOf(
        // 폭 없는 이음 · 방향 표시(아랍어 · 히브리어가 섞인 책, 이모지 이음)
        "zwj" to "\u200D", "zwnj" to "\u200C", "lrm" to "\u200E", "rlm" to "\u200F",
        // 라틴 확장(프랑스어 · 체코어 이름)
        "OElig" to "Œ", "oelig" to "œ", "Scaron" to "Š", "scaron" to "š", "Yuml" to "Ÿ", "fnof" to "ƒ",
        "circ" to "ˆ", "tilde" to "˜",
        // XML 기본
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        // 공백 · 하이픈
        "nbsp" to " ", "ensp" to " ", "emsp" to " ", "thinsp" to " ",
        "shy" to "­", "hyphen" to "‐", "ndash" to "–", "mdash" to "—",
        "horbar" to "―",
        // 인용부호 · 구두점
        "lsquo" to "‘", "rsquo" to "’", "sbquo" to "‚",
        "ldquo" to "“", "rdquo" to "”", "bdquo" to "„",
        "laquo" to "«", "raquo" to "»", "lsaquo" to "‹", "rsaquo" to "›",
        "hellip" to "…", "bull" to "•", "middot" to "·",
        "dagger" to "†", "Dagger" to "‡", "prime" to "′", "Prime" to "″",
        "sect" to "§", "para" to "¶", "iexcl" to "¡", "iquest" to "¿",
        // 기호
        "copy" to "©", "reg" to "®", "trade" to "™", "deg" to "°",
        "permil" to "‰", "micro" to "µ",
        // 통화
        "euro" to "€", "pound" to "£", "yen" to "¥", "cent" to "¢",
        "curren" to "¤",
        // 수식 · 화살표
        "times" to "×", "divide" to "÷", "plusmn" to "±", "minus" to "−",
        "ne" to "≠", "le" to "≤", "ge" to "≥", "infin" to "∞",
        "frac12" to "½", "frac14" to "¼", "frac34" to "¾",
        "sup1" to "¹", "sup2" to "²", "sup3" to "³",
        "larr" to "←", "rarr" to "→", "uarr" to "↑", "darr" to "↓",
        "harr" to "↔",
    )

    /**
     * `&` 다음에 오는 참조 본문([body], `&` 와 `;` 를 제외한 부분)을 글자로 바꾼다.
     *
     * @return 풀린 문자열, 또는 해석할 수 없으면 null(호출부가 원문을 그대로 남긴다)
     */
    fun resolve(body: String): String? {
        if (body.isEmpty()) return null
        if (body[0] != '#') return named[body]

        val digits = body.substring(1)
        val codePoint = if (digits.startsWith("x") || digits.startsWith("X")) {
            digits.substring(1).toIntOrNull(16)
        } else {
            digits.toIntOrNull()
        } ?: return null

        // 유니코드 범위를 벗어난 값, 서로게이트, NUL 은 거부한다. 문자열에 넣으면
        // 이후 조판·측정 단계에서 엉뚱한 곳에서 터진다.
        if (codePoint <= 0 || codePoint > 0x10FFFF || codePoint in 0xD800..0xDFFF) return null
        return codePointToString(codePoint)
    }
}
