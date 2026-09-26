package io.github.kgcaudit.reader.app

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule

/**
 * 잠깐(1.5초) 떴다 사라지는 안내(CpToast)를 놓치지 않고 본다: 화면 시계를 멈춘 채 [action] 을 하고, 한 프레임씩
 * 돌리며 [matcher] 가 보이는지 찾는다.
 *
 * `waitUntil` 로 기다리면 안 되는 까닭: 기다리는 동안 시험의 화면 시계는 앱이 한가해질 때까지 저절로 흐른다. CPU 가
 * 바쁠 때(전체 점검) 그 사이에 1.5초가 한꺼번에 지나, 안내가 떴다 사라진 뒤에야 확인하게 된다 — 독서노트 시험이
 * 전체 점검에서만 가끔 30초를 기다리다 실패했고, CPU 에 부하를 걸어 같은 실패를 재현했다.
 * 한 프레임(16ms)씩만 돌리면 안내가 뜬 다음 프레임에 반드시 본다.
 */
internal fun ComposeTestRule.seeBriefly(matcher: SemanticsMatcher, timeoutMs: Long = 30_000, action: () -> Unit) {
    mainClock.autoAdvance = false
    try {
        action()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            check(System.currentTimeMillis() < deadline) { "never saw the brief message: ${matcher.description}" }
            mainClock.advanceTimeByFrame()
            // 입출력 스레드(저장 · 파일 열기)가 끝날 틈. 화면 시계는 멈춰 있어 이 동안 안내가 사라지지 않는다.
            Thread.sleep(5)
        }
    } finally {
        mainClock.autoAdvance = true
    }
}
