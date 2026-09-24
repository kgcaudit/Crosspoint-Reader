package io.github.kgcaudit.reader.layout.book

import io.github.kgcaudit.reader.layout.Block

/** 링크를 눌렀을 때 갈 곳. */
sealed interface LinkTarget {
    /** 각주: 판에 [text] 를 띄운다. [spine] · [anchor] 는 "각주 자리로 가기" 가 가는 곳. */
    data class Footnote(val text: String, val spine: Int, val anchor: String?) : LinkTarget

    /** 각주가 아닌 책 안 링크: 판 없이 그 자리로 간다(F5). */
    data class Jump(val spine: Int, val anchor: String?) : LinkTarget

    /** 책 밖(인터넷 주소 등): 브라우저로 열지 묻는다(F6). */
    data class External(val url: String) : LinkTarget

    /** 가리키는 곳을 책에서 찾지 못했다(깨진 책). 넘기지 않고 짧게 알린다. */
    data object Missing : LinkTarget
}

/** 찾은 자리 하나. [start]..[endExclusive] 는 그 장 텍스트의 구간, [context] 는 앞뒤 문맥. */
data class SearchHit(
    val spine: Int,
    val start: Int,
    val endExclusive: Int,
    val context: String,
    /** [context] 안에서 찾은 말이 시작하는 자리. 목록에서 그 부분만 칠한다. */
    val contextMatchStart: Int,
)

/**
 * 각주 내용을 뽑는다: [offset](각주의 id 자리)부터, 그 각주가 끝날 때까지.
 *
 * 끝은 "다음 id 가 붙은 문단" 이다 — 주석 장은 각주마다 id 를 달아 두는 게 보통이라, 그 앞까지가 한 각주다.
 * 그런 표시가 없는 책에서 장 끝까지 끌려오지 않게 문단 [MAX_BLOCKS] 개 · [MAX_CHARS] 글자에서 끊는다.
 * 되돌아가기 표시(↩ ↑ ^)는 판에서 누를 수 없으니 뺀다.
 */
internal fun noteText(text: String, blocks: List<Block>, anchorOffsets: Collection<Int>, offset: Int): String {
    val out = StringBuilder()
    var taken = 0
    for (block in blocks) {
        if (block !is Block.Paragraph || block.runs.isEmpty()) continue
        val start = block.runs.first().start
        val end = block.runs.last().endExclusive
        if (end <= offset) continue
        if (taken > 0 && anchorOffsets.any { it in start until end.coerceAtLeast(start + 1) && it > offset }) break
        val from = maxOf(start, offset)
        if (from < end) {
            if (out.isNotEmpty()) out.append('\n')
            out.append(text, from.coerceIn(0, text.length), end.coerceIn(0, text.length))
            taken++
        }
        if (taken >= MAX_BLOCKS || out.length >= MAX_CHARS) break
    }
    return out.toString()
        .replace(BACKLINKS, "")
        .lines().joinToString("\n") { it.trim() }
        .trim()
        .let { if (it.length > MAX_CHARS) it.take(MAX_CHARS).trimEnd() + "…" else it }
}

/**
 * [query] 가 [text] 에 나오는 자리들. 대소문자와 공백의 개수는 가리지 않는다("Boa  Constrictor" = "boa constrictor").
 * 겹치는 자리는 세지 않는다(한 번 찾은 뒤 그 끝부터 다시).
 */
internal fun findAll(text: String, query: String, spine: Int, paragraphStarts: Collection<Int> = emptyList()): List<SearchHit> {
    val words = query.trim().split(WHITESPACE).filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    val pattern = Regex(words.joinToString("\\s+") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
    return pattern.findAll(text).map { m ->
        val from = (m.range.first - CONTEXT).coerceAtLeast(0)
        val to = (m.range.last + 1 + CONTEXT).coerceAtMost(text.length)
        val lead = if (from > 0) "…" else ""
        val tail = if (to < text.length) "…" else ""
        // 문단 사이에는 글자가 없다(챕터 텍스트는 문단을 이어 붙인다). 문맥에서는 한 칸을 띄운다 — 안 띄우면
        // "링크.누리집" 처럼 두 문단이 한 낱말로 붙어 보인다.
        val body = StringBuilder()
        var matchAt = -1
        for (i in from until to) {
            if (i > from && i in paragraphStarts && body.lastOrNull() != ' ') body.append(' ')
            if (i == m.range.first) matchAt = body.length
            body.append(if (text[i] == '\n') ' ' else text[i])
        }
        SearchHit(spine, m.range.first, m.range.last + 1, lead + body + tail, lead.length + matchAt)
    }.toList()
}

private const val MAX_BLOCKS = 6
private const val MAX_CHARS = 1500
private const val CONTEXT = 28
private val BACKLINKS = Regex("[↩↑⤴]|\\^\\s*$")
private val WHITESPACE = Regex("\\s+")

/**
 * 장 텍스트의 [from]..[to] 를 목록 한 줄로 읽히게 뜬다(책갈피 미리보기 · 칠한 글).
 *
 * 장 텍스트는 문단을 구분자 없이 잇는다. 그대로 뜨면 "삼킨다.어른들은" 처럼 두 문단이 한 낱말로 붙는다 — 문단이
 * 시작하는 자리([paragraphStarts])마다 한 칸을 넣는다. 제어 문자 · 그림 자리 글자(U+FFFC)도 한 칸으로.
 */
internal fun excerpt(text: String, from: Int, to: Int, paragraphStarts: Set<Int>): String {
    val start = from.coerceIn(0, text.length)
    val end = to.coerceIn(start, text.length)
    val out = StringBuilder(end - start + 8)
    for (i in start until end) {
        if (i > start && i in paragraphStarts) out.append(' ')
        val c = text[i]
        out.append(if (c < ' ' || c == '￼') ' ' else c)
    }
    return out.toString().replace(EXCERPT_SPACE, " ").trim()
}

private val EXCERPT_SPACE = Regex("\\s+")
