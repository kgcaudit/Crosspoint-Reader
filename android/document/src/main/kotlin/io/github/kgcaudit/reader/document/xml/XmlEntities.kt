package io.github.kgcaudit.reader.document.xml

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

    /** 전자책 본문에 실제로 등장하는 명명 엔티티. 없는 것은 숫자 참조로 쓰이므로 충분하다. */
    private val named: Map<String, String> = mapOf(
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
        return String(Character.toChars(codePoint))
    }
}
