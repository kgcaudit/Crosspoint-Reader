package io.github.kgcaudit.reader.layout.css

/**
 * 셀렉터가 가리키는 요소 하나. 태그 이름·클래스·id 를 함께 본다.
 *
 * [previousSibling] 은 바로 앞 형제 요소다(인접 결합자 `h2 + p` 용). 그 형제의 형제는 담지 않는다 — 담으면 한 장의
 * 문단 수천 개가 사슬로 이어져, 같음 비교 · 해시가 사슬 끝까지 재귀하다 스택이 넘친다. 그래서 `a + b + c` 는 맞히지 않는다.
 */
data class ElementInfo(
    val tag: String,
    val classes: Set<String> = emptySet(),
    val id: String? = null,
    val previousSibling: ElementInfo? = null,
) {
    companion object {
        fun of(tag: String, classAttribute: String?, id: String?, previousSibling: ElementInfo? = null): ElementInfo = ElementInfo(
            tag = tag.lowercase(),
            classes = classAttribute
                ?.split(' ', '\t', '\n')
                ?.filter { it.isNotBlank() }
                ?.toSet()
                ?: emptySet(),
            id = id?.takeIf { it.isNotBlank() },
            previousSibling = previousSibling?.copy(previousSibling = null),
        )
    }
}

/**
 * 지원하는 셀렉터: 타입(`p`) · 클래스(`.x`) · id(`#x`) · 후손(`div p`) · 인접 형제(`h2 + p`) · 그룹(`,`).
 *
 * 자식 결합자(`>`)는 **후손으로 취급한다.** 규칙을 버리는 것보다 조금 넓게 맞히는
 * 편이 낫다 — EPUB 에서 `div > p` 로 적힌 문단 서식을 통째로 잃으면 본문 전체가
 * 밋밋해진다. 의사 클래스(`:first-child`)와 속성 셀렉터(`[lang]`)도 같은 이유로
 * 떼어 내고 나머지를 쓴다.
 *
 * 형제 결합자는 넓게 맞히면 안 된다. `h2 + p { text-indent: 0 }`(제목 바로 뒤 문단만 들여쓰지 않기)를 후손으로 낮추던
 * 때는 `p { text-indent: 0 }` 으로 읽혀 **모든 문단**의 들여쓰기가 사라졌다. 그래서 `+` 는 바로 앞 형제를 보고 맞히고,
 * 앞 형제를 모두 기억해야 하는 `~` 는 규칙을 버린다.
 */
data class CssSelector(val parts: List<Part>) {

    /**
     * 연쇄의 한 마디. [tag] 가 null 이면 전체 선택자(`*`). [adjacent] 면 다음 마디의 **바로 앞 형제**여야 하고,
     * 아니면 다음 마디의 조상이면 된다.
     */
    data class Part(
        val tag: String? = null,
        val classes: Set<String> = emptySet(),
        val id: String? = null,
        val adjacent: Boolean = false,
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
     * 맞으면 된다(인접 마디는 그 자리 요소의 바로 앞 형제에).
     */
    fun matches(stack: List<ElementInfo>): Boolean {
        if (parts.isEmpty() || stack.isEmpty()) return false
        if (!parts.last().matches(stack.last())) return false
        return matchesBefore(stack, parts.size - 1, stack.size - 1, stack.last())
    }

    /**
     * [partIndex] 마디가 [element] 에 맞았다. 그 앞 마디들을 맞춘다. [element] 의 조상은 `stack[0 until depth]` 다 —
     * 형제는 같은 부모를 두므로 형제로 건너가도 [depth] 는 그대로다.
     *
     * 후손 마디는 되짚어 본다: `h2 + p span` 에서 가장 가까운 `p` 가 제목 뒤가 아니어도 더 바깥 `p` 가 맞을 수 있다.
     */
    private fun matchesBefore(stack: List<ElementInfo>, partIndex: Int, depth: Int, element: ElementInfo): Boolean {
        if (partIndex == 0) return true
        val previous = parts[partIndex - 1]
        if (previous.adjacent) {
            val sibling = element.previousSibling ?: return false
            return previous.matches(sibling) && matchesBefore(stack, partIndex - 1, depth, sibling)
        }
        for (k in depth - 1 downTo 0) {
            if (previous.matches(stack[k]) && matchesBefore(stack, partIndex - 1, k, stack[k])) return true
        }
        return false
    }

    companion object {
        /** 셀렉터 하나를 해석한다. 마디가 하나도 안 나오면 null. */
        fun parse(raw: String): CssSelector? {
            // 의사 **요소**(::first-letter, ::before…)는 요소의 일부나 없던 상자를 가리킨다. 떼어 내고
            // 요소 전체에 적용하면 `p::first-letter { font-size: 3em }`(드롭 캡)이 모든 문단을 3배로,
            // `::before { display: none }` 가 문단 자체를 지운다. 규칙을 버린다.
            if (PSEUDO_ELEMENT.containsMatchIn(raw)) return null
            // 괄호 · 대괄호 안을 먼저 지운다. `:nth-child(2n+1)` 의 `+`, `[class~=x]` 의 `~` 를 결합자로 읽으면 안 된다.
            val tokens = raw.replace(BRACKETED, "")
                // 자식 결합자는 후손으로 낮춘다(§ 클래스 주석 참조).
                .replace('>', ' ')
                .replace("+", " + ")
                .replace("~", " ~ ")
                .split(' ', '\t', '\n')
                .filter { it.isNotBlank() }
            // 앞 형제를 모두 봐야 하는 `~` 는 맞힐 수 없다. 후손으로 낮추면 넓게 맞아 해가 되므로 규칙을 버린다.
            if ("~" in tokens) return null
            val parts = ArrayList<Part>()
            for (token in tokens) {
                if (token == "+") {
                    // 앞 마디가 없거나(`+ p`) 버려진 마디 뒤면 맞힐 형제가 없다 — 넓게 맞히지 않게 규칙째 버린다.
                    val last = parts.lastOrNull() ?: return null
                    parts[parts.size - 1] = last.copy(adjacent = true)
                    continue
                }
                parsePart(token)?.let(parts::add)
            }
            if (parts.isEmpty() || parts.last().adjacent) return null
            return CssSelector(parts)
        }

        private val BRACKETED = Regex("""\([^)]*\)|\[[^\]]*\]""")

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
