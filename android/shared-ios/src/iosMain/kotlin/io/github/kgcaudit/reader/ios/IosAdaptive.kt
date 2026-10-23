package io.github.kgcaudit.reader.ios

/**
 * 창 폭 등급(구상안 48, 안드로이드 `CpAdaptive` · docs/RESPONSIVE_PLAN.md): 좁음(600 미만) 한 판 줄 목록 · 중간(600–839) 한 판,
 * 목록은 표지 격자 · 넓음(840 이상) 두 판. 기기가 아니라 **지금 창**의 크기로 고른다 — iPad 나란히 보기에서 창이 좁아지면 그
 * 폭으로 다시 고른다.
 */
object IosAdaptive {
    const val COMPACT = 0
    const val MEDIUM = 1
    const val EXPANDED = 2

    /** 좁음 · 중간의 경계. 리더의 "넓은 화면"(옆 판 · 세로 두 쪽)과 같은 값 — 두 곳에 적으면 한쪽만 바뀐다. */
    const val MEDIUM_MIN = 600.0
    const val EXPANDED_MIN = 840.0

    /** 격자 · 두 판을 펼 만한 높이. iPhone 을 가로로 돌리면 폭은 넓어도 높이가 400 안팎이라, 위에 정보를 얹은 격자는 칸이 화면 밖으로 밀린다. */
    const val ROOMY_MIN_HEIGHT = 480.0

    /** 두 판의 왼쪽(고정 정보) 판 폭 — 휴대폰 한 화면보다 좁게, 오른쪽 목록이 넓어야 두 판을 편 보람이 있다. */
    const val TWO_PANE_LEFT = 320.0

    /** 줄이 늘어선 화면(책 찾기 · 작품 정리 · 앱 정보)의 최대 폭 — 넘으면 이름과 단추 · 꼬리표가 한참 멀다. */
    const val CONTENT_MAX = 600.0

    /** 작품 화면 권 격자의 칸 최소 폭. */
    const val CELL_MIN = 120.0

    fun widthClass(width: Double): Int = when {
        width < MEDIUM_MIN -> COMPACT
        width < EXPANDED_MIN -> MEDIUM
        else -> EXPANDED
    }

    /** 격자를 쓸 만한가: 좁음이 아니고 높이도 [ROOMY_MIN_HEIGHT] 이상. */
    fun roomy(width: Double, height: Double): Boolean = widthClass(width) != COMPACT && height >= ROOMY_MIN_HEIGHT

    /** 두 판을 펼 만한가: 넓음이면서 높이도 [ROOMY_MIN_HEIGHT] 이상. */
    fun twoPane(width: Double, height: Double): Boolean = widthClass(width) == EXPANDED && height >= ROOMY_MIN_HEIGHT

    /** 격자 칸 수: 칸 최소 폭과 사이 [gap] 으로 [width] 에 몇 칸 드나(적어도 하나). */
    fun columns(width: Double, gap: Double): Int = maxOf(1, ((width + gap) / (CELL_MIN + gap)).toInt())
}
