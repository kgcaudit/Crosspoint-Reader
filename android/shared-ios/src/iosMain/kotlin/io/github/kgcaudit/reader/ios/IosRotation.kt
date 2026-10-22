package io.github.kgcaudit.reader.ios

/**
 * 화면 회전(구상안 45 · 1A 2A, 안드로이드 `ScreenRotation`): 자동 · 세로 · 가로. 앱 전체에 하나 — 책 종류마다 따로 두면 PDF 를
 * 가로로 보다가 EPUB 을 열 때 방향이 제멋대로 바뀐 것처럼 느껴진다(안드로이드와 같은 까닭).
 */
object IosRotation {
    val LABELS: List<String> = listOf("자동", "세로", "가로")

    const val AUTO = 0
    const val PORTRAIT = 1
    const val LANDSCAPE = 2

    /**
     * iOS 의 '자동' 은 휴대폰의 화면 방향 잠금을 넘지 못한다 — 안드로이드는 잠금과 상관없이 돌아, 같은 '자동' 을 골랐는데 iPhone
     * 에서는 돌지 않으면 고장으로 보인다(구상안 45 · 1A).
     */
    const val AUTO_NOTE: String = "자동은 iPhone 의 화면 방향 잠금을 따릅니다. 잠금을 켜 두면 자동이어도 돌지 않습니다."

    /**
     * 실제로 걸 방향. 모르는 저장 값은 자동 — 한 방향에 갇히면 설정 화면까지 그 방향이라 풀기 어렵다. iPad 는 늘 자동: 창 크기를
     * 바꾸는 앱은 방향을 고정할 수 없어 줄을 두지 않는다(45 · 2A). 휴대폰에서 고른 값이 백업으로 iPad 에 와도 걸지 않는다.
     */
    fun applied(saved: Int, phone: Boolean): Int = if (phone && saved in AUTO..LANDSCAPE) saved else AUTO
}
