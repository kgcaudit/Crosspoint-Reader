package io.github.kgcaudit.reader.pdf

/**
 * 글자 대응표(ToUnicode) 없이 들어간 한글 글꼴의 글을 되살린다.
 *
 * 잡지 · 한글 조판기(윤디자인 · 산돌 글꼴을 Identity-H 로 넣은 PDF)는 글꼴에 "이 모양은 어느 글자" 표를 넣지 않는
 * 일이 흔하다. 그러면 엔진(pdfium)은 글자 대신 **글꼴 안의 모양 번호**를 그대로 글자로 내준다 — "이" 가 U+1BD5,
 * 공백이 U+0001 로 온다. 이 글을 그대로 쓰면 찾기에 걸리지 않고, 듣기는 "읽을 글이 없는 쪽" 으로 보고 건너뛴다
 * (좋은생각 2026년 8월호: 77쪽을 읽고 118쪽으로 뛰었다 — 사이 40쪽이 모두 이랬다).
 *
 * 이 글꼴들의 모양 순서는 같다: 1–95 가 ASCII(공백부터 "~"), 97 부터 한글 11,172자가 유니코드 순. 그래서
 * 번호 − 96 + U+AC00 이 곧 그 한글이다. 확인은 글자 빈도로 한다(되살린 글자에 "이 · 다 · 는 · 의" 처럼 흔한 글자가
 * 많아야 한다) — 번호가 우연히 이 범위에 든 멀쩡한 글(기호 · 외국 글자)은 건드리지 않는다.
 *
 * 한 쪽에 멀쩡한 글꼴과 망가진 글꼴이 섞이므로(제목은 멀쩡, 본문은 망가짐) **망가진 표시가 있는 낱말만** 바꾼다.
 * 멀쩡한 "2024" 는 그대로 두고, "2024֥" 의 "֥" 만 "년" 으로. 글 길이는 바꾸지 않는다 — 글자마다의 네모 · 저장한
 * 형광펜 자리가 같은 글자를 가리켜야 한다.
 */
object BrokenHangul {

    fun repair(text: String): String {
        var candidates = 0
        var common = 0
        for (c in text) {
            if (!candidate(c)) continue
            candidates++
            if (c.code in HANGUL_GLYPHS && hangulOf(c) in COMMON) common++
        }
        // 흔한 글자가 적으면 망가진 글이 아니다(기호가 많은 쪽 · 다른 방식으로 망가진 글꼴).
        if (candidates < MIN_CANDIDATES || common < candidates * MIN_COMMON_SHARE) return text
        val out = text.toCharArray()
        var i = 0
        while (i < text.length) {
            if (text[i].isSeparator()) { i++; continue }
            var end = i
            while (end < text.length && !text[end].isSeparator()) end++
            if ((i until end).any { brokenMark(text[it]) }) {
                repairWord(text, i, end, out)
                hideUnlikely(text, i, end, out)
            }
            i = end
        }
        return String(out)
    }

    /** 낱말 하나([from]..[to]) 안의 망가진 글자를 되살린다. */
    private fun repairWord(text: String, from: Int, to: Int, out: CharArray) {
        var i = from
        while (i < to) {
            val c = text[i]
            if (c in 'a'..'z') {
                // 소문자는 "가" · "각" … 의 번호이기도 하고(97 = 가), 망가진 글 사이에 낀 진짜 영어("‘oppa’")이기도
                // 하다. 둘 이상 이어진 소문자에 한글로는 거의 안 쓰는 글자(갂 · 갃 · 갎 …)가 들어 있으면 영어로 둔다.
                var end = i
                while (end < to && text[end] in 'a'..'z') end++
                val english = end - i >= 2 && (i until end).any { text[it] in RARE_AS_HANGUL }
                if (!english) for (k in i until end) out[k] = hangulOf(text[k])
                i = end
                continue
            }
            out[i] = when {
                c.code in 1..31 && c != '\r' && c != '\n' -> (c.code + ASCII_SHIFT).toChar()
                c.code in 123..126 -> hangulOf(c)
                candidate(c) -> decode(c)
                c.code >= HANGUL_END && c.code < SYMBOL_END -> SYMBOLS[c.code] ?: HIDDEN
                else -> c
            }
            i++
        }
    }

    /**
     * 되살린 어절 가운데 우리말 같지 않은 것은 소리 내지 않게 가린다. 한 쪽에 번호 체계가 다른 망가진 글꼴(쪽 머리의
     * 장식 글씨 등)이 섞이면 같은 규칙으로 풀어도 "댦뗴꿹 뎚뎹" 이 되고, 듣기가 그것을 그대로 읽었다. 흔히 쓰는 한글
     * 2,350자([KsHangul]) 밖의 글자가 되살린 글자의 1/3 을 넘으면 그 어절은 잘못 푼 것으로 본다.
     */
    private fun hideUnlikely(text: String, from: Int, to: Int, out: CharArray) {
        var i = from
        while (i < to) {
            if (out[i] == ' ') { i++; continue }
            var end = i
            while (end < to && out[end] != ' ') end++
            val changed = (i until end).filter { out[it] != text[it] && out[it] in '가'..'힣' }
            val odd = changed.count { !KsHangul.contains(out[it]) }
            if (changed.isNotEmpty() && odd * 3 > changed.size) for (k in i until end) if (out[k] != text[k]) out[k] = HIDDEN
            i = end
        }
    }

    private fun decode(c: Char): Char {
        val h = hangulOf(c)
        // 한컴 바탕(HCR Batang) 따옴표. 같은 번호 체계로 풀면 "썅 · 썆 · 썉 · 썊" 이 되는데, 이 넷은 우리말 글에 거의
        // 나오지 않는 글자라 따옴표로 본다(잡지 본문의 ‘ ’ “ ” 가 모두 이 글꼴이었다).
        return QUOTES[h] ?: h
    }

    private fun hangulOf(c: Char): Char = ('가'.code + c.code - HANGUL_START).toChar()

    /** 망가진 한글일 수 있는 글자: 한글 번호 범위이고, 진짜 한글도 흔히 쓰는 기호도 아니다. */
    private fun candidate(c: Char): Boolean =
        c.code in 127 until HANGUL_END && c !in '가'..'힣' && c !in KEEP

    /** 이 글자가 낱말에 있으면 그 낱말은 망가진 글꼴의 것이다. 제어 문자는 멀쩡한 글에 나오지 않는다. */
    private fun brokenMark(c: Char): Boolean =
        (c.code in 1..31 && c != '\r' && c != '\n' && c != '\t') || candidate(c)

    /** 낱말을 가르는 글자. 망가진 글의 공백은 U+0001 이라 가르지 않는다 — 망가진 줄 하나가 통째로 한 낱말이 된다. */
    private fun Char.isSeparator() = this == ' ' || this == '\r' || this == '\n'

    private const val HANGUL_START = 97
    private const val HANGUL_END = HANGUL_START + 11172
    private val HANGUL_GLYPHS = HANGUL_START until HANGUL_END
    private const val ASCII_SHIFT = 31
    private const val SYMBOL_END = 12000
    private const val MIN_CANDIDATES = 8
    private const val MIN_COMMON_SHARE = 0.15f

    /** 한글 모양 다음 자리의 기호 중 본문에서 확인한 것(『 』 ‘ ’). 모르는 것은 보이지 않는 글자로 — 듣기가 읽지 않는다. */
    private val SYMBOLS = mapOf(11745 to '『', 11746 to '』', 11441 to '‘', 11442 to '’')
    private const val HIDDEN = '​'

    private val QUOTES = mapOf('썅' to '‘', '썆' to '’', '썉' to '“', '썊' to '”')

    /** 한글 번호 범위 안의 흔한 문장부호. 번호로 풀면 "쮷 · 촱" 처럼 쓰이지 않는 글자라, 망가진 낱말 안에서도 그대로 둔다. */
    private val KEEP = "‘’“”·…→←↑↓–—―".toSet()

    /** 소문자를 한글 번호로 풀었을 때 거의 쓰이지 않는 글자가 되는 것(c = 갂, d = 갃, o = 갎 …). */
    private val RARE_AS_HANGUL = "cdfgjklmnopyz".toSet()

    /** 우리말 글에 가장 흔한 글자들. 되살린 글이 이것으로 차 있어야 망가진 글로 본다. */
    private val COMMON = "이다는의에을고하가한지로서기리도게사대어자아수나들인일요있것그".toSet()
}
