package io.github.kgcaudit.reader.data.db

import io.github.kgcaudit.reader.document.Locator

/**
 * 위치를 "읽는 순서" 로 비교할 수 있는 정수 세 개로 바꾼다.
 *
 * 리플로우는 (챕터, 글자), 고정 페이지는 (페이지, 세로, 가로) 순이다. 한 페이지 안에서
 * 세로를 먼저 보는 이유: 같은 페이지의 위쪽 책갈피가 먼저 읽힌다. 가로를 먼저 보면
 * 2단 PDF 가 아닌 한 거의 모든 경우에 순서가 뒤집힌다.
 */
internal data class LocatorOrder(val major: Int, val minor: Int, val patch: Int) {
    companion object {
        fun of(locator: Locator): LocatorOrder = when (locator) {
            is Locator.Reflow -> LocatorOrder(locator.spine, locator.charOffset, 0)
            is Locator.FixedPage -> LocatorOrder(locator.page, locator.yPermille, locator.xPermille)
        }
    }
}
