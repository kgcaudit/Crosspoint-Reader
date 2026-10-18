package io.github.kgcaudit.reader.document

/**
 * 두 쪽 보기에서 한 판이 화면의 어디에 서는지(0.46.0, 2026-10-05 사용자 결정 — 종이책의 규칙).
 *
 * 종이책은 표지가 펼침의 "뒤 쪽 자리"(왼→오 책은 오른쪽, 오→왼 만화는 왼쪽)에 서고, 마지막에 혼자 남은 쪽은 "앞 쪽
 * 자리"(왼→오는 왼쪽)에 선다. 0.45 까지는 혼자인 쪽을 모두 화면 가운데에 두어, 표지에서 다음 펼침으로 넘길 때 쪽이 반
 * 칸씩 옆으로 튀었고 두 쪽 보기인데 한 쪽 보기처럼 보였다. 가로로 긴 펼침면 그림은 그 자체가 두 쪽이라 가운데다.
 */
enum class SpreadSlot {
    /** 두 쪽이 나란히(책등이 가운데). */
    Pair,

    /** 가운데 한 장(펼침면 그림 · 한 쪽 보기). */
    Center,

    /** 읽는 순서로 앞 쪽 자리 — 왼→오 책은 왼쪽, 오→왼 만화는 오른쪽. */
    Earlier,

    /** 읽는 순서로 뒤 쪽 자리 — 왼→오 책은 오른쪽, 오→왼 만화는 왼쪽. */
    Later,
    ;

    /** 화면 왼쪽 반에 서는지(혼자인 쪽만 뜻이 있다). */
    fun onLeft(rightToLeft: Boolean): Boolean = (this == Earlier) != rightToLeft

    companion object {
        /**
         * [pages] 판의 자리. [first] 는 권의 첫 쪽(표지)인지, [wide] 는 혼자인 쪽이 가로로 긴 펼침면 그림인지.
         * 혼자인 표지는 [coverAlone] 일 때만 뒤 쪽 자리 — 표지를 혼자 두지 않는 설정에서 혼자 남은 첫 쪽은 한 쪽짜리 책뿐이다.
         */
        fun of(pages: List<Int>, first: Boolean, wide: Boolean, coverAlone: Boolean): SpreadSlot = when {
            pages.size >= 2 -> Pair
            pages.isEmpty() || wide -> Center
            first && coverAlone -> Later
            else -> Earlier
        }
    }
}
