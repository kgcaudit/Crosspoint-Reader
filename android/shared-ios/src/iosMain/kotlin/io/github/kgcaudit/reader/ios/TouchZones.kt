package io.github.kgcaudit.reader.ios

/**
 * 지면을 누를 때 어디가 앞 쪽 · 다음 쪽인가(구상안 37, 안드로이드 `ui-design` 의 `TouchZones` 와 같은 규칙). 가운데 40% 는 언제나
 * 메뉴, 오른쪽 위 모서리는 어느 것을 골라도 책갈피(화면이 먼저 가른다). 안드로이드 쪽은 Compose 모듈에 있어 iOS 가 쓸 수 없다 —
 * 글 · 판단을 여기 옮기고 같은 시험으로 지킨다.
 */
enum class TouchZones(val label: String) {
    STANDARD("기본"),
    REVERSED("좌우 바꾸기"),
    /** 큰 휴대폰을 한 손으로 들고 엄지가 닿는 쪽만 누르는 사람. 앞 쪽은 밀어서 간다. */
    ONE_HAND("한 손(양쪽 다음)"),
    ;

    /**
     * 고르는 화면의 설명. 오→왼 만화에서는 자리가 뒤집히므로([forward]) 글도 뒤집는다 — 안드로이드 0.54.8 까지는 글이 그대로라
     * 실제 동작과 반대였다. 한 손은 앞 쪽으로 가는 미는 방향까지 적는다: 읽는 방향의 반대로 민다.
     */
    fun description(rightToLeft: Boolean): String {
        val prevSide = if (rightToLeft) "오른쪽" else "왼쪽"
        val nextSide = if (rightToLeft) "왼쪽" else "오른쪽"
        return when (this) {
            STANDARD -> "$prevSide 앞 쪽 · $nextSide 다음 쪽"
            REVERSED -> "$prevSide 다음 쪽 · $nextSide 앞 쪽"
            ONE_HAND -> "어느 쪽을 눌러도 다음 쪽 · 앞 쪽은 ${if (rightToLeft) "왼쪽" else "오른쪽"}으로 밀어서"
        }
    }

    /**
     * 누른 자리([x] 는 지면 폭에 대한 0..1)가 읽는 순서로 몇 쪽 가는가: 1 다음, -1 앞, 0 메뉴.
     *
     * 기본 · 좌우 바꾸기는 화면 자리를 뜻한다 — 오→왼 책이면 뒤집어 왼쪽이 다음 쪽이다. 한 손은 자리가 아니라 "다음" 그
     * 자체라 뒤집지 않는다: 안드로이드 0.54.7 까지 한 손도 뒤집혀, 오→왼 만화에서 어느 쪽을 눌러도 앞 쪽으로 갔다.
     */
    fun step(x: Double, rightToLeft: Boolean): Int {
        val left = x < 0.3
        val right = x > 0.7
        if (!left && !right) return 0
        if (this == ONE_HAND) return 1
        // 화면 자리로: 기본은 오른쪽이 다음, 좌우 바꾸기는 왼쪽이 다음.
        val screenNext = if (this == REVERSED) left else right
        val next = screenNext != rightToLeft
        return if (next) 1 else -1
    }

    companion object {
        /** 저장된 번호 → 고른 것. 모르는 번호(손상 · 다음 판이 더한 것)는 기본 — 넘기기가 엉뚱한 쪽으로 가면 책을 읽을 수 없다. */
        fun of(index: Int): TouchZones = entries.getOrNull(index) ?: STANDARD

        val all: List<TouchZones> get() = entries
    }
}
