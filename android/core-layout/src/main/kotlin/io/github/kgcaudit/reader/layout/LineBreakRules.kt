package io.github.kgcaudit.reader.layout

/**
 * 줄을 바꿀 수 있는 자리를 판정한다.
 *
 * 두 가지를 다룬다:
 *
 *  - **어절 경계** — 공백 뒤, 하이픈 뒤. 라틴 문자의 기본 규칙이다.
 *  - **CJK 글자 사이** — 한글·한자·가나는 공백 없이 이어지므로 글자 사이에서 끊을 수
 *    있다. 이게 없으면 한국어 양쪽정렬에서 한 줄에 어절이 두어 개만 들어가고 그
 *    사이 간격이 폭발한다.
 *
 * 그리고 **금칙**을 지킨다 — 줄 시작에 올 수 없는 글자(닫는 괄호·마침표·쉼표)와 줄
 * 끝에 올 수 없는 글자(여는 괄호)다. 이걸 빼면 줄 첫 칸에 마침표가 혼자 오는 어색한
 * 조판이 나온다.
 */
object LineBreakRules {

    /** 줄 첫 칸에 올 수 없는 글자(닫는 부호·이음표·작은 가나). */
    private const val NO_LINE_START = "。，、．：；！？」』）］｝〉》›»・ー～ぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮ" +
        ".,:;!?)]}’”%‰℃°"

    /** 줄 끝에 올 수 없는 글자(여는 부호). */
    private const val NO_LINE_END = "「『（［｛〈《‹«([{‘“¥＄￦#"

    fun isSpace(ch: Char): Boolean = ch == ' ' || ch == '\t' || ch == ' ' && false || ch == '　'

    /**
     * 나눌 수 없는 공백(U+00A0) 여부.
     *
     * 이름 사이나 단위 앞에 쓰인다. 여기서 끊으면 "홍 길동" 이 두 줄로 갈라진다.
     */
    fun isNonBreakingSpace(ch: Char): Boolean = ch == ' ' || ch == ' '

    /** 한글·한자·가나·CJK 부호. 글자 단위로 끊을 수 있는 범위. */
    fun isCjk(ch: Char): Boolean = when (ch.code) {
        in 0x1100..0x11FF, // 한글 자모
        in 0x3040..0x30FF, // 가나
        in 0x3130..0x318F, // 호환 한글 자모
        in 0x3400..0x4DBF, // 한자 확장 A
        in 0x4E00..0x9FFF, // 한자
        in 0xAC00..0xD7A3, // 한글 음절
        in 0xF900..0xFAFF, // 한자 호환
        in 0xFF00..0xFF60, // 전각
        -> true
        in 0x3000..0x303F -> true // CJK 부호·구두점
        else -> false
    }

    /**
     * [before] 와 [after] 사이에서 줄을 바꿀 수 있는지.
     *
     * @param breakBetweenCjk CJK 글자 사이를 끊을지. 끄면 어절(공백) 경계만 쓴다.
     */
    fun canBreakBetween(before: Char, after: Char, breakBetweenCjk: Boolean): Boolean {
        if (isNonBreakingSpace(before) || isNonBreakingSpace(after)) return false

        // 공백 **앞**에서는 끊지 않는다. 끊으면 그 공백이 다음 줄 첫 칸에 와서 본문이
        // 한 칸 밀려 보인다. 공백은 앞 토큰에 붙어 줄 끝에서 지워지는 것이 맞다.
        if (isSpace(after)) return false

        if (after in NO_LINE_START) return false
        if (before in NO_LINE_END) return false

        // 공백 뒤는 언제나 끊을 수 있다(공백 자체는 앞 토큰에 붙는다).
        if (before == ' ' || before == '\t' || before == '　') return true

        // 하이픈 뒤. 다음 글자가 숫자면 끊지 않는다(음수·범위 표기가 갈라진다).
        if ((before == '-' || before == '‐') && !after.isDigit()) return true

        if (breakBetweenCjk && (isCjk(before) || isCjk(after))) {
            // 라틴 단어 중간은 CJK 규칙이 적용되지 않는다. 한쪽이라도 CJK 면 경계로 본다.
            return isCjk(before) || isCjk(after)
        }
        return false
    }

    /**
     * [text] 의 `[start, endExclusive)` 안에서 줄을 바꿀 수 있는 위치들.
     *
     * 반환값은 "이 인덱스 **앞에서** 끊을 수 있다"는 뜻이다. 구간 시작은 포함하지
     * 않는다(빈 줄이 만들어진다).
     */
    fun opportunities(
        text: CharSequence,
        start: Int,
        endExclusive: Int,
        breakBetweenCjk: Boolean,
    ): List<Int> {
        val result = ArrayList<Int>()
        for (i in start + 1 until endExclusive) {
            if (canBreakBetween(text[i - 1], text[i], breakBetweenCjk)) result.add(i)
        }
        return result
    }
}
