package io.github.kgcaudit.reader.layout.css

/** 셀렉터가 가리키는 요소 하나. 태그 이름·클래스·id 를 함께 본다. */
data class ElementInfo(
    val tag: String,
    val classes: Set<String> = emptySet(),
    val id: String? = null,
) {
    companion object {
        fun of(tag: String, classAttribute: String?, id: String?): ElementInfo = ElementInfo(
            tag = tag.lowercase(),
            classes = classAttribute
                ?.split(' ', '\t', '\n')
                ?.filter { it.isNotBlank() }
                ?.toSet()
                ?: emptySet(),
            id = id?.takeIf { it.isNotBlank() },
        )
    }
}

/**
 * 지원하는 셀렉터: 타입(`p`) · 클래스(`.x`) · id(`#x`) · 후손(`div p`) · 그룹(`,`).
 *
 * 자식 결합자(`>`)는 **후손으로 취급한다.** 규칙을 버리는 것보다 조금 넓게 맞히는
 * 편이 낫다 — EPUB 에서 `div > p` 로 적힌 문단 서식을 통째로 잃으면 본문 전체가
 * 밋밋해진다. 의사 클래스(`:first-child`)와 속성 셀렉터(`[lang]`)도 같은 이유로
 * 떼어 내고 나머지를 쓴다.
 */
data class CssSelector(val parts: List<Part>) {

    /** 후손 연쇄의 한 마디. [tag] 가 null 이면 전체 선택자(`*`). */
    data class Part(
        val tag: String? = null,
        val classes: Set<String> = emptySet(),
        val id: String? = null,
    ) {
        fun matches(element: ElementInfo): Boolean {
            if (tag != null && tag != element.tag) return false
            if (id != null && id != element.id) return false
            return element.classes.containsAll(classes)
        }
    }

    /**
     * 우선순위. id > 클래스 > 타입 순이며, 자릿수를 크게 벌려 낮은 항목이 높은 항목을
     * 넘어설 수 없게 한다.
     */
    val specificity: Int
        get() = parts.sumOf { part ->
            (if (part.id != null) 1_000_000 else 0) +
                part.classes.size * 1_000 +
                (if (part.tag != null) 1 else 0)
        }

    /**
     * [stack] 은 문서 루트부터 현재 요소까지의 조상 목록이며 마지막이 현재 요소다.
     * 마지막 마디는 현재 요소에 맞아야 하고, 앞 마디들은 조상 중 어디서든 순서대로
     * 맞으면 된다.
     */
    fun matches(stack: List<ElementInfo>): Boolean {
        if (parts.isEmpty() || stack.isEmpty()) return false
        if (!parts.last().matches(stack.last())) return false

        var partIndex = parts.size - 2
        var stackIndex = stack.size - 2
        while (partIndex >= 0) {
            if (stackIndex < 0) return false
            if (parts[partIndex].matches(stack[stackIndex])) partIndex--
            stackIndex--
        }
        return true
    }

    companion object {
        /** 셀렉터 하나를 해석한다. 마디가 하나도 안 나오면 null. */
        fun parse(raw: String): CssSelector? {
            // 의사 **요소**(::first-letter, ::before…)는 요소의 일부나 없던 상자를 가리킨다. 떼어 내고
            // 요소 전체에 적용하면 `p::first-letter { font-size: 3em }`(드롭 캡)이 모든 문단을 3배로,
            // `::before { display: none }` 가 문단 자체를 지운다. 규칙을 버린다.
            if (PSEUDO_ELEMENT.containsMatchIn(raw)) return null
            val parts = raw.trim()
                // 자식·인접 결합자는 후손으로 낮춘다(§ 클래스 주석 참조).
                .replace('>', ' ')
                .replace('+', ' ')
                .replace('~', ' ')
                .split(' ', '\t', '\n')
                .filter { it.isNotBlank() }
                .mapNotNull(::parsePart)
            return if (parts.isEmpty()) null else CssSelector(parts)
        }

        private val PSEUDO_ELEMENT = Regex("::|:(first-letter|first-line|before|after|marker|selection)\\b", RegexOption.IGNORE_CASE)

        private fun parsePart(raw: String): Part? {
            // 의사 클래스와 속성 셀렉터를 떼어 낸다(의사 요소는 parse 에서 규칙째 버렸다). 지원하지 않지만, 규칙을
            // 버리기보다 나머지 조건으로 맞히는 편이 실제 책에서 낫다.
            var text = raw.substringBefore(':')
            while (true) {
                val open = text.indexOf('[')
                if (open < 0) break
                val close = text.indexOf(']', open)
                text = if (close < 0) text.substring(0, open) else text.removeRange(open, close + 1)
            }
            if (text.isBlank()) return null

            var tag: String? = null
            val classes = LinkedHashSet<String>()
            var id: String? = null

            var cursor = 0
            var token = StringBuilder()
            var kind = '\u0000' // 0 = 타입, '.' = 클래스, '#' = id

            fun flush() {
                val name = token.toString()
                token = StringBuilder()
                if (name.isEmpty()) return
                when (kind) {
                    '.' -> classes.add(name)
                    '#' -> id = name
                    else -> if (name != "*") tag = name.lowercase()
                }
            }

            while (cursor < text.length) {
                val ch = text[cursor]
                if (ch == '.' || ch == '#') {
                    flush()
                    kind = ch
                } else {
                    token.append(ch)
                }
                cursor++
            }
            flush()

            return if (tag == null && classes.isEmpty() && id == null) {
                // `*` 만 있는 경우. 전체 선택자로 남긴다.
                if (text.contains('*')) Part() else null
            } else {
                Part(tag, classes, id)
            }
        }
    }
}

/** 셀렉터 하나와 그 선언. [order] 는 문서 순서로, 우선순위가 같을 때 뒤의 것이 이긴다. */
data class CssRule(
    val selector: CssSelector,
    val declarations: CssDeclarations,
    val order: Int,
)

/**
 * 파싱된 스타일시트.
 *
 * 캐스케이드: 맞는 규칙을 (우선순위, 문서 순서) 오름차순으로 정렬해 차례로 덮어쓴다.
 * 정렬이 안정적이어야 같은 입력에 늘 같은 결과가 나오고, 그래야 페이지 캐시와 화면이
 * 어긋나지 않는다.
 */
class Stylesheet(
    val rules: List<CssRule>,
    /** `@font-face` 규칙들. 경로(`src`)는 이 CSS 파일 기준 그대로다 — 푸는 것은 파일을 아는 쪽의 일. */
    val fontFaces: List<CssFontFace> = emptyList(),
) {

    /**
     * 두 스타일시트를 문서 순서대로 잇는다. 한 챕터가 외부 CSS 여러 개와 `<style>`
     * 블록을 함께 쓰는 일이 흔한데, 그냥 합치면 각자 0부터 매긴 순서가 겹쳐
     * 나중 시트가 앞 시트에 지는 일이 생긴다. 순서를 다시 매겨 그걸 막는다.
     */
    operator fun plus(other: Stylesheet): Stylesheet {
        if (other.rules.isEmpty() && other.fontFaces.isEmpty()) return this
        if (rules.isEmpty() && fontFaces.isEmpty()) return other
        val base = rules.size
        return Stylesheet(rules + other.rules.map { it.copy(order = base + it.order) }, fontFaces + other.fontFaces)
    }

    fun declarationsFor(stack: List<ElementInfo>): CssDeclarations {
        val matched = rules.filter { it.selector.matches(stack) }
        if (matched.isEmpty()) return CssDeclarations.EMPTY

        return matched
            .sortedWith(compareBy({ it.selector.specificity }, { it.order }))
            .fold(CssDeclarations.EMPTY) { acc, rule -> acc.mergedWith(rule.declarations) }
    }

    companion object {
        val EMPTY: Stylesheet = Stylesheet(emptyList())
    }
}

/**
 * `@font-face` 하나.
 *
 * @property family 소문자·따옴표 없는 이름. `font-family` 선언과 대소문자 무시로 맞춘다.
 * @property weight 적혀 있으면 100~900. 없으면 null — 그때는 폰트 파일의 굵기를 본다.
 */
data class CssFontFace(
    val family: String,
    val src: String,
    val weight: Int? = null,
    val italic: Boolean? = null,
)
