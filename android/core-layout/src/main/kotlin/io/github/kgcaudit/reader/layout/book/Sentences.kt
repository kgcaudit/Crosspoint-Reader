package io.github.kgcaudit.reader.layout.book

/**
 * 소리 내어 읽을 한 토막(듣기, L2 · L3). 장 텍스트의 글자 구간이다 — 지금 읽는 문장을 칠하고, 멈춘 자리를
 * 글자 위치로 저장하는 데 그대로 쓴다.
 */
data class Sentence(val start: Int, val endExclusive: Int)

/**
 * 장 텍스트를 문장으로 나눈다.
 *
 * - 문단 시작([paragraphStarts])은 언제나 끊는다. 장 텍스트는 문단을 구분자 없이 잇기 때문에, 끊지 않으면
 *   "삼킨다.어른들은" 이 한 문장으로 읽힌다(마침표 뒤에 공백이 없다).
 * - 문장 끝은 . ! ? … 。 ！ ？ 과 그 뒤에 붙은 닫는 따옴표 · 괄호, 그리고 **그 뒤가 공백이거나 끝**일 때다.
 *   "3.14" · "U.S.A." 의 점에서 끊으면 숫자를 둘로 나눠 읽는다.
 * - 글자나 숫자가 하나도 없는 토막(그림 자리, "* * *")은 버린다. 음성 엔진이 기호를 읽거나 멈춰 버린다.
 * - [maxChars] 보다 긴 문장은 쉼표 · 공백에서 더 자른다. 한 번에 넘기는 글이 길면 엔진이 거절하고(안드로이드는
 *   4000자), 짧아도 한 문장이 쪽을 넘어가면 칠한 곳이 화면 밖에 오래 남는다.
 */
fun splitSentences(text: CharSequence, paragraphStarts: Set<Int> = emptySet(), maxChars: Int = 300): List<Sentence> {
    val out = ArrayList<Sentence>()
    var start = 0
    var i = 0
    fun close(end: Int) {
        emit(text, start, end, maxChars, out)
        start = end
    }
    while (i < text.length) {
        if (i > start && i in paragraphStarts) {
            close(i)
            continue
        }
        val c = text[i]
        if (c in TERMINATORS) {
            var j = i + 1
            while (j < text.length && (text[j] in TERMINATORS || text[j] in CLOSERS)) j++
            val ends = j >= text.length || text[j].isWhitespace() || j in paragraphStarts
            if (ends && !isInitial(text, i)) {
                close(j)
                i = j
                continue
            }
            i = j
            continue
        }
        i++
    }
    close(text.length)
    return out
}

/**
 * "U.S.A." · "J. R. R." 처럼 로마자 한 글자 뒤의 점인가. 그 점 뒤에 공백이 와도 문장 끝이 아니다 — 끊으면
 * 약어 한가운데서 쉬었다 읽는다. 문단 끝이면 끊는다(문단 시작은 이 검사보다 먼저 본다).
 */
private fun isInitial(text: CharSequence, dot: Int): Boolean {
    if (text[dot] != '.' || dot < 1) return false
    val letter = text[dot - 1]
    if (letter !in 'A'..'Z' && letter !in 'a'..'z') return false
    return dot < 2 || text[dot - 2] == '.' || text[dot - 2].isWhitespace()
}

/** [offset] 이 든 문장의 번호. 문장 사이(공백 · 버린 토막)면 그 뒤 첫 문장. 없으면 -1. */
fun List<Sentence>.indexAt(offset: Int): Int {
    for ((n, s) in withIndex()) if (offset < s.endExclusive) return n
    return -1
}

/** 엔진에 넘길 글: 제어 문자 · 그림 자리 글자(U+FFFC)는 공백으로, 겹친 공백은 한 칸으로. */
fun speakable(text: CharSequence, sentence: Sentence): String {
    val sb = StringBuilder(sentence.endExclusive - sentence.start)
    for (i in sentence.start until sentence.endExclusive.coerceAtMost(text.length)) {
        val c = text[i]
        sb.append(if (c < ' ' || c == '￼') ' ' else c)
    }
    return sb.replace(SPACES, " ").trim()
}

private fun emit(text: CharSequence, from: Int, to: Int, maxChars: Int, out: MutableList<Sentence>) {
    var s = from
    while (s < to && text[s].isWhitespace()) s++
    var e = to
    while (e > s && text[e - 1].isWhitespace()) e--
    if (s >= e) return
    // 너무 긴 문장은 쉼표 → 공백 순으로 찾아 앞에서부터 자른다.
    while (e - s > maxChars) {
        val limit = s + maxChars
        val cut = (limit downTo s + maxChars / 2).firstOrNull { text[it - 1] in SOFT_BREAKS }
            ?: (limit downTo s + maxChars / 2).firstOrNull { text[it - 1].isWhitespace() }
            ?: limit
        addIfSpoken(text, s, cut, out)
        s = cut
        while (s < e && text[s].isWhitespace()) s++
    }
    addIfSpoken(text, s, e, out)
}

private fun addIfSpoken(text: CharSequence, s: Int, e: Int, out: MutableList<Sentence>) {
    var end = e
    while (end > s && text[end - 1].isWhitespace()) end--
    if (end <= s) return
    if ((s until end).none { text[it].isLetterOrDigit() }) return
    out.add(Sentence(s, end))
}

private const val TERMINATORS = ".!?…。！？"
private const val CLOSERS = "\"'”’)]」』〉》"
private const val SOFT_BREAKS = ",，、;:"
private val SPACES = Regex("\\s+")
