package io.github.kgcaudit.reader.reflow

import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.document.TocEntry

/**
 * 지금 읽는 곳에 해당하는 목차 항목. 없으면 -1.
 *
 * "지금 챕터와 **같은** 항목" 만 찾으면 안 된다. 실제 책은 목차가 챕터보다 훨씬 적어서
 * (항목 7개가 챕터 113개를, 8개가 56개를 덮는다) 대부분의 페이지에서 아무 항목도 표시되지
 * 않는다. 그래서 **지금 위치 이전의 마지막 항목**을 고른다 — 종이책에서 "지금 몇 장인가" 를
 * 읽는 방식과 같다.
 *
 * 같은 챕터를 가리키는 항목이 여럿이면(한 파일에 여러 절, `#앵커`) 그 챕터의 **첫** 항목을
 * 고른다. 앵커가 몇 번째 글자인지는 조판해 봐야 알고, 목차를 열 때마다 그걸 하지는 않는다.
 */
fun currentTocIndex(entries: List<TocEntry>, spineIndex: Int): Int {
    var best = -1
    var bestSpine = -1
    entries.forEachIndexed { i, entry ->
        val spine = (entry.locator as? Locator.Reflow)?.spine ?: return@forEachIndexed
        if (spine <= spineIndex && spine > bestSpine) {
            best = i
            bestSpine = spine
        }
    }
    return best
}
