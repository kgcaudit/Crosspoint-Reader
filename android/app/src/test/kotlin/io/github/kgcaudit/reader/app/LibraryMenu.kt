package io.github.kgcaudit.reader.app

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performClick

/**
 * 서재의 새로고침 · 책 폴더 · 앱 정보를 누른다(0.44.0 — 머리 줄을 없애며 이 셋은 "더 보기" 판 안으로 갔다). 폴더가 없을 때는
 * 앱 정보가 머리에 그대로 있다 — 그때는 바로 누른다.
 */
fun ComposeTestRule.libraryMenu(label: String) {
    fun has(desc: String) = onAllNodes(hasContentDescription(desc), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    waitUntil(30_000) { has("더 보기") || has(label) }
    if (!has(label)) {
        onAllNodes(hasContentDescription("더 보기"), useUnmergedTree = true)[0].performClick()
        waitUntil(10_000) { has(label) }
    }
    onAllNodes(hasContentDescription(label), useUnmergedTree = true)[0].performClick()
}
