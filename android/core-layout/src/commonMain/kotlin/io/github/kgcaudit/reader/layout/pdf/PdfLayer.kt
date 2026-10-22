package io.github.kgcaudit.reader.layout.pdf

import io.github.kgcaudit.reader.layout.book.findAll

/**
 * 글자 층에서 [query] 가 나오는 구간들. 찾기와 같은 규칙([findAll]: 대소문자 · 공백 개수를 가리지 않음)이라 차례가 맞는다.
 * 공통에 둔다(2026-10-22, iOS 세션 요청) — 두 앱이 찾은 구간이 다르면 PDF 형광펜 자리가 백업으로 오갈 때 어긋난다.
 */
fun layerHits(layer: PageText, query: String): List<IntRange> =
    if (query.isBlank()) emptyList() else findAll(layer.text, query, 0).map { it.start until it.endExclusive }
