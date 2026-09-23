package io.github.kgcaudit.reader.layout.css

import io.github.kgcaudit.reader.layout.TextAlign
import io.github.kgcaudit.reader.layout.VerticalAlign

/**
 * EPUB 용 CSS 파서.
 *
 * 범용 CSS 엔진이 아니라 **조판에 실제로 쓰는 속성만** 읽는다. 지원 목록은
 * `docs/ANDROID_BUILD_SPEC.md` §8 에 있고, 그 밖의 것은 조용히 지나간다 —
 * 무시하는 목록을 명시해 둔 이유는 "지원 안 하는 게 뭔지 모르는 상태" 가
 * 가장 비싸기 때문이다.
 *
 * 깨진 CSS 에 관대하다. 닫히지 않은 블록, 값이 없는 선언, 모르는 단위가 나와도
 * 그 부분만 버리고 계속 읽는다 — 시중의 책에 흔하고, 여기서 멈추면 책 한 권의
 * 서식이 통째로 사라진다.
 */
object CssParser {

    fun parse(css: String): Stylesheet {
        val text = stripComments(css)
        val rules = ArrayList<CssRule>()
        val fontFaces = ArrayList<CssFontFace>()
        var cursor = 0
        var order = 0

        while (cursor < text.length) {
            while (cursor < text.length && text[cursor].isWhitespace()) cursor++
            if (cursor >= text.length) break

            // @font-face 는 글꼴표로 모으고, @media 등 나머지는 통째로 건너뛴다(§8 무시 목록).
            // 앞의 공백을 먼저 넘기지 않으면 직전 규칙의 줄바꿈 때문에 이 검사가
            // 빗나가고, @media 가 선택자로 읽혀 안쪽 규칙이 통째로 망가진다.
            if (text[cursor] == '@') {
                val end = skipAtRule(text, cursor)
                if (text.startsWith("@font-face", cursor, ignoreCase = true)) {
                    val open = text.indexOf('{', cursor)
                    if (open in cursor until end) {
                        fontFace(text.substring(open + 1, (end - 1).coerceAtLeast(open + 1)))?.let(fontFaces::add)
                    }
                }
                cursor = end
                continue
            }

            val braceOpen = text.indexOf('{', cursor)
            if (braceOpen < 0) break
            val braceClose = findBlockEnd(text, braceOpen)

            val selectorText = text.substring(cursor, braceOpen)
            val body = text.substring(braceOpen + 1, braceClose.coerceAtMost(text.length))
            val declarations = parseDeclarations(body)

            if (!declarations.isEmpty) {
                for (selector in selectorText.split(',')) {
                    CssSelector.parse(selector)?.let {
                        rules.add(CssRule(it, declarations, order++))
                    }
                }
            }
            cursor = (braceClose + 1).coerceAtLeast(braceOpen + 1)
        }
        return Stylesheet(rules, fontFaces)
    }

    /**
     * `@font-face` 본문. 선언 단위로 쪼개지 않고 **이름과 `url(...)` 을 따로 찾는다.**
     *
     * 실제 책에 `font-family: "kofb2":src:url(../Fonts/KoPubBatangMedium.ttf);` 처럼 세미콜론을
     * 빠뜨린 규칙이 있다. 선언 단위로 읽으면 이 글꼴이 통째로 사라져, 그 글꼴을 쓰는 문단만
     * 기본 글꼴로 나온다. 브라우저도 버리는 규칙이지만 출판사의 의도는 분명하다.
     */
    private fun fontFace(body: String): CssFontFace? {
        val familyAt = Regex("font-family\\s*:", RegexOption.IGNORE_CASE).find(body) ?: return null
        val family = firstName(body.substring(familyAt.range.last + 1)) ?: return null
        val urls = Regex("url\\(\\s*(['\"]?)(.*?)\\1\\s*\\)", RegexOption.IGNORE_CASE).findAll(body)
            .map { it.groupValues[2].trim() }.filter { it.isNotEmpty() }.toList()
        // src 에 여러 형식이 오면(woff2, woff, ttf) 안드로이드가 읽는 것을 고른다.
        val src = urls.firstOrNull { it.substringBefore('?').substringAfterLast('.').lowercase() in READABLE_FONTS }
            ?: urls.firstOrNull() ?: return null
        val weight = Regex("font-weight\\s*:\\s*([a-z0-9]+)", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)?.lowercase()?.let {
            when (it) {
                "normal" -> 400
                "bold" -> 700
                else -> it.toIntOrNull()?.takeIf { w -> w in 1..1000 }
            }
        }
        val italic = Regex("font-style\\s*:\\s*([a-z]+)", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)?.lowercase()?.let {
            it == "italic" || it == "oblique"
        }
        return CssFontFace(family, src, weight, italic)
    }

    /** `"KoPub 바탕", serif` 에서 첫 이름. 따옴표가 없으면 쉼표·세미콜론·콜론까지. */
    private fun firstName(value: String): String? {
        val trimmed = value.trimStart()
        if (trimmed.isEmpty()) return null
        val quote = trimmed[0]
        val name = if (quote == '"' || quote == '\'') {
            val close = trimmed.indexOf(quote, 1)
            if (close < 0) return null
            trimmed.substring(1, close)
        } else {
            trimmed.takeWhile { it != ',' && it != ';' && it != ':' && it != '}' }
        }
        return name.trim().lowercase().takeIf { it.isNotEmpty() }
    }

    /** `font-family` 목록. `inherit` 류는 "정하지 않음"(null) 이다. 따옴표 안의 쉼표는 이름의 일부다. */
    private fun fontFamilies(value: String): List<String>? {
        if (value in setOf("inherit", "initial", "unset", "revert")) return null
        val names = ArrayList<String>()
        val current = StringBuilder()
        var quote: Char? = null
        fun flush() {
            current.toString().trim().takeIf { it.isNotEmpty() }?.let(names::add)
            current.setLength(0)
        }
        for (c in value) {
            when {
                quote != null -> if (c == quote) quote = null else current.append(c)
                c == '"' || c == '\'' -> quote = c
                c == ',' -> flush()
                else -> current.append(c)
            }
        }
        flush()
        return names.takeIf { it.isNotEmpty() }
    }

    // ── 선언 ────────────────────────────────────────────────────────

    fun parseDeclarations(body: String): CssDeclarations {
        var result = CssDeclarations.EMPTY
        for (piece in body.split(';')) {
            val colon = piece.indexOf(':')
            if (colon <= 0) continue
            val property = piece.substring(0, colon).trim().lowercase()
            val value = piece.substring(colon + 1).trim().removeSuffix("!important").trim()
            if (value.isEmpty()) continue
            result = result.mergedWith(declaration(property, value))
        }
        return result
    }

    private fun declaration(property: String, raw: String): CssDeclarations {
        val value = raw.lowercase()
        return when (property) {
            "text-align" -> CssDeclarations(textAlign = textAlign(value))
            "text-indent" -> CssDeclarations(textIndent = CssLength.parse(value))

            "font-style" -> when (value) {
                "italic", "oblique" -> CssDeclarations(italic = true)
                "normal" -> CssDeclarations(italic = false)
                else -> CssDeclarations.EMPTY
            }

            "font-weight" -> CssDeclarations(bold = fontWeightIsBold(value))
            "font-size" -> CssDeclarations(fontSizeScale = fontSizeScale(value))
            "font-family" -> CssDeclarations(fontFamilies = fontFamilies(value))

            // 여러 값이 한 줄에 겹쳐 올 수 있다: "underline line-through"
            "text-decoration", "text-decoration-line" -> CssDeclarations(
                underline = value.contains("underline"),
                strikethrough = value.contains("line-through"),
            )

            // sup·sub 는 태그로도 알 수 있지만, CSS 로 같은 효과를 내는 책이 흔하다.
            // 한 곳에서 해석해야 두 경로가 갈라지지 않는다.
            "vertical-align" -> when (value) {
                "super" -> CssDeclarations(verticalAlign = VerticalAlign.Superscript)
                "sub" -> CssDeclarations(verticalAlign = VerticalAlign.Subscript)
                "baseline" -> CssDeclarations(verticalAlign = VerticalAlign.Baseline)
                else -> CssDeclarations.EMPTY
            }

            "margin" -> shorthandMargins(value)
            "margin-top" -> CssDeclarations(marginTop = CssLength.parse(value))
            "margin-bottom" -> CssDeclarations(marginBottom = CssLength.parse(value))
            "margin-left" -> CssDeclarations(marginLeft = CssLength.parse(value))
            "margin-right" -> CssDeclarations(marginRight = CssLength.parse(value))

            // padding 은 조판에서 margin 과 구분할 필요가 없다(배경·테두리를 그리지
            // 않으므로 눈에 보이는 결과가 같다). 같은 값으로 접어 넣는다.
            "padding" -> shorthandMargins(value)
            "padding-top" -> CssDeclarations(marginTop = CssLength.parse(value))
            "padding-bottom" -> CssDeclarations(marginBottom = CssLength.parse(value))
            "padding-left" -> CssDeclarations(marginLeft = CssLength.parse(value))
            "padding-right" -> CssDeclarations(marginRight = CssLength.parse(value))

            "display" -> if (value == "none") CssDeclarations(hidden = true) else CssDeclarations.EMPTY

            "page-break-before", "break-before" -> when (value) {
                "always", "page", "left", "right", "recto", "verso" ->
                    CssDeclarations(pageBreakBefore = true)
                else -> CssDeclarations.EMPTY
            }

            // auto·none 은 "정하지 않음" 이다. CssLength.parse 가 null 을 돌려주므로 그대로 둔다.
            "width" -> CssDeclarations(width = positiveLength(value))
            "height" -> CssDeclarations(height = positiveLength(value))
            "max-width" -> CssDeclarations(maxWidth = positiveLength(value))
            "max-height" -> CssDeclarations(maxHeight = positiveLength(value))

            else -> CssDeclarations.EMPTY
        }
    }

    /**
     * 0 보다 큰 길이만. `width: 0` 이나 음수는 그림을 지우거나 조판을 0 으로 나누게 한다 —
     * 저작 도구가 남긴 찌꺼기로 보고 "정하지 않음" 으로 둔다.
     */
    private fun positiveLength(value: String): CssLength? =
        CssLength.parse(value)?.takeIf { it.value > 0f }

    private fun textAlign(value: String): TextAlign? = when (value) {
        "left", "start" -> TextAlign.Start
        "right", "end" -> TextAlign.End
        "center" -> TextAlign.Center
        "justify" -> TextAlign.Justify
        else -> null
    }

    private fun fontWeightIsBold(value: String): Boolean? = when (value) {
        "bold", "bolder" -> true
        "normal", "lighter" -> false
        else -> value.toIntOrNull()?.let { it >= 600 }
    }

    /**
     * 글자 크기를 **기준 대비 배율**로 바꾼다.
     *
     * 절대 크기(`14pt`)도 배율로 환산해야 사용자 글자 크기 설정이 제목까지 함께
     * 움직인다. 그렇지 않으면 본문만 커지고 제목이 상대적으로 작아진다.
     */
    private fun fontSizeScale(value: String): Float? {
        KEYWORD_SIZES[value]?.let { return it }
        val length = CssLength.parse(value) ?: return null
        return when (length.unit) {
            CssUnit.Em, CssUnit.Rem -> length.value
            CssUnit.Percent -> length.value / 100f
            // px·pt 는 기본 크기(16px 상당)를 기준으로 배율을 잡는다. 정확한 px 를
            // 지키는 것보다 사용자 설정에 따라 함께 커지는 편이 리더에서는 옳다.
            CssUnit.Px -> length.value / DEFAULT_FONT_PX
            CssUnit.Pt -> length.value * CssLength.PT_TO_PX / DEFAULT_FONT_PX
        }.takeIf { it > 0f }
    }

    /** `margin: 1em 2em` 처럼 1~4개 값이 오는 축약형. CSS 규칙대로 펼친다. */
    private fun shorthandMargins(value: String): CssDeclarations {
        val parts = value.split(' ', '\t').filter { it.isNotBlank() }.mapNotNull(CssLength::parse)
        // CSS 축약형: 1개=모두, 2개=세로/가로, 3개=위·가로·아래, 4개=위·오른쪽·아래·왼쪽.
        val (top, right, bottom, left) = when (parts.size) {
            1 -> listOf(parts[0], parts[0], parts[0], parts[0])
            2 -> listOf(parts[0], parts[1], parts[0], parts[1])
            3 -> listOf(parts[0], parts[1], parts[2], parts[1])
            4 -> listOf(parts[0], parts[1], parts[2], parts[3])
            else -> return CssDeclarations.EMPTY
        }
        return CssDeclarations(
            marginTop = top,
            marginBottom = bottom,
            marginLeft = left,
            marginRight = right,
        )
    }

    // ── 훑기 ────────────────────────────────────────────────────────

    private fun stripComments(css: String): String {
        if (!css.contains("/*")) return css
        val out = StringBuilder(css.length)
        var cursor = 0
        while (cursor < css.length) {
            val open = css.indexOf("/*", cursor)
            if (open < 0) {
                out.append(css, cursor, css.length)
                break
            }
            out.append(css, cursor, open)
            val close = css.indexOf("*/", open + 2)
            // 닫히지 않은 주석은 문서 끝까지로 본다.
            cursor = if (close < 0) css.length else close + 2
        }
        return out.toString()
    }

    /** `@...;` 또는 `@... { ... }` 를 건너뛰고 그다음 위치를 돌려준다. */
    private fun skipAtRule(text: String, start: Int): Int {
        val brace = text.indexOf('{', start)
        val semicolon = text.indexOf(';', start)
        if (brace < 0 || (semicolon in 0 until brace)) {
            return if (semicolon < 0) text.length else semicolon + 1
        }
        return findBlockEnd(text, brace) + 1
    }

    /** 중첩 중괄호를 세어 블록의 끝을 찾는다. 닫히지 않았으면 문서 끝. */
    private fun findBlockEnd(text: String, open: Int): Int {
        var depth = 0
        var cursor = open
        while (cursor < text.length) {
            when (text[cursor]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return cursor
                }
            }
            cursor++
        }
        return text.length
    }

    private const val DEFAULT_FONT_PX = 16f

    private val READABLE_FONTS = setOf("ttf", "otf", "ttc")

    private val KEYWORD_SIZES = mapOf(
        "xx-small" to 0.6f, "x-small" to 0.75f, "small" to 0.89f,
        "medium" to 1f, "large" to 1.2f, "x-large" to 1.5f,
        "xx-large" to 2f, "smaller" to 0.83f, "larger" to 1.2f,
    )
}
