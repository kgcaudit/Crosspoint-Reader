package io.github.kgcaudit.reader.layout.pdf

import io.github.kgcaudit.reader.layout.book.findAll

/**
 * 글자 층에서 [query] 가 나오는 구간들. 찾기와 같은 규칙([findAll]: 대소문자 · 공백 개수를 가리지 않음)이라 차례가 맞는다.
 * 공통에 둔다(2026-10-22, iOS 세션 요청) — 두 앱이 찾은 구간이 다르면 PDF 형광펜 자리가 백업으로 오갈 때 어긋난다.
 */
fun layerHits(layer: PageText, query: String): List<IntRange> =
    if (query.isBlank()) emptyList() else findAll(layer.text, query, 0).map { it.start until it.endExclusive }

/**
 * PDF 칠의 자리를 칠한 글([snippet])로 다시 잡는다(0.61.1, iOS 요청 · 사용자 결정). 칠은 (쪽, 층 글자 번호)인데, 층은 플랫폼
 * 엔진(안드로이드 PdfRenderer · iOS PDFKit)이 낸 글을 다듬은 것이라 두 엔진이 같은 쪽을 다르게 나누면(공백을 넣거나 합자를
 * 풀거나) 백업 · 동기화로 옮긴 칠이 몇 글자 밀린다. 칠한 글은 이미 [snippet] 으로 들고 있어 잴 것 없이 고칠 수 있다.
 *
 * 공백 · 줄바꿈은 아예 빼고 견준다 — 한 엔진만 글자 사이에 공백을 넣으면 공백 수를 접는 것으로는 맞지 않는다.
 * - 그 자리의 글이 [snippet] 과 같으면 그대로.
 * - 다르면 쪽에서 [snippet] 을 찾아 원래 시작에 가장 가까운 자리.
 * - [snippet] 이 비었거나 쪽에 없으면 번호 그대로(지금과 같다).
 */
fun anchorHighlight(layer: PageText, start: Int, endExclusive: Int, snippet: String): IntRange {
    val same = start until endExclusive
    val wanted = snippet.filterNot(Char::isWhitespace)
    if (wanted.isEmpty()) return same
    val text = layer.text
    val from = start.coerceIn(0, text.length)
    val to = endExclusive.coerceIn(from, text.length)
    if (text.substring(from, to).filterNot(Char::isWhitespace) == wanted) return same

    // 공백을 뺀 글과, 그 글자마다 원래 번호.
    val stripped = StringBuilder(text.length)
    val origin = IntArray(text.length)
    for (i in text.indices) {
        if (text[i].isWhitespace()) continue
        origin[stripped.length] = i
        stripped.append(text[i])
    }
    var best: IntRange? = null
    var at = stripped.indexOf(wanted)
    while (at >= 0) {
        val found = origin[at]..origin[at + wanted.length - 1]
        if (best == null || kotlin.math.abs(found.first - start) < kotlin.math.abs(best.first - start)) best = found
        at = stripped.indexOf(wanted, at + 1)
    }
    return best ?: same
}
