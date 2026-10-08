package io.github.kgcaudit.reader.reflow

import io.github.kgcaudit.reader.layout.LayoutSpec
import io.github.kgcaudit.reader.layout.book.ReadingPosition

/**
 * 읽는 속도를 잴 때의 "보이는 쪽" 열쇠(왼쪽 쪽 기준). 조판 설정([layout])까지 넣는다 — 글자 크기를 바꿔 다시 짠 쪽은 번호가
 * 하나 늘어도 넘긴 것이 아니다. 빼면 글자를 줄인 순간 "다음 쪽으로 넘겼다" 로 읽혀 앞 쪽 분량이 속도에 들어갔다.
 */
internal data class ShownPages(val position: ReadingPosition, val spread: Boolean, val layout: LayoutSpec?)

/**
 * [next] 가 이 쪽(펼침)의 바로 다음인가 — 사람이 "다음 쪽" 을 누른 것과 같은 걸음. 같은 장이면 한 쪽(두쪽보기면 두 쪽) 뒤,
 * 장 끝이면 다음 장의 첫 쪽. 목차 · 찾기 · 진행 막대로 건너뛴 것은 여기에 들지 않는다.
 */
internal fun ShownPages.isFollowedBy(next: ShownPages): Boolean {
    if (next.layout != layout || next.spread != spread) return false
    val step = if (spread) 2 else 1
    val here = position
    val there = next.position
    return when (there.spineIndex) {
        here.spineIndex -> there.pageIndex == here.pageIndex + step
        here.spineIndex + 1 -> there.pageIndex == 0 && here.pageIndex + step >= here.pageCount
        else -> false
    }
}
