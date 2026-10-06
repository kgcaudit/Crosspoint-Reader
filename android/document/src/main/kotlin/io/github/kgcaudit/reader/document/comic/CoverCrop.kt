package io.github.kgcaudit.reader.document.comic

import io.github.kgcaudit.reader.document.image.ImageSize

/**
 * 긴 그림에서 표지로 쓸 부분을 고른다(0.47.0, 사용자 결정 ⑩). 웹툰 1화의 첫 그림은 800×9000 같은 띠라 통째로 표지 칸에
 * 넣으면 높이에 맞춰 줄어 가는 막대로 선다 — 무엇의 표지인지 알아볼 수 없다. 위쪽 빈 바탕을 건너뛰고 첫 칸부터 표지
 * 비율로 잘라 쓴다.
 *
 * 픽셀은 ARGB 정수로만 받는다 — 그림을 푸는 일은 앱이 하고, 어디를 자를지는 기기 없이 시험한다.
 */
object CoverCrop {

    /** 표지 칸의 세로 ÷ 가로(`COVER_ASPECT` 의 역수). 이 비율로 자른다. */
    const val ASPECT: Float = 1.45f

    /**
     * 이만큼 표지 칸보다 길어야 자른다. 1:1.6 정도의 보통 만화 표지까지 자르면 위아래가 잘려 제목이 날아간다 —
     * 칸에 넣어도 거의 꽉 차는 그림은 그대로 둔다.
     */
    private const val TALL_SLACK: Float = 1.25f

    /** 첫 내용 위로 남기는 여백(그림 폭의 비율). 칸 테두리가 표지 끝에 딱 붙으면 잘린 것처럼 보인다. */
    private const val MARGIN = 0.03f

    /** 잘라야 하는 긴 그림인가. 크기를 모르면(깨진 머리) 자르지 않는다. */
    fun isTall(size: ImageSize?): Boolean =
        size != null && size.height > size.width * ASPECT * TALL_SLACK

    /**
     * 위에서부터 줄을 보며 바탕이 아닌 것이 처음 나온 줄. 바탕은 첫 줄 첫 점의 색이다 — 웹툰 바탕은 대개 흰색이지만
     * 검은 바탕 작품도 있다. 칸 테두리처럼 한 색으로 꽉 찬 줄도 바탕색이 아니면 내용이다. 끝까지 바탕뿐이면 0.
     */
    fun firstContentRow(rows: List<IntArray>): Int = firstContentRow(rows.size) { rows[it] }

    /**
     * [firstContentRow] 를 줄마다 꺼내 가며: [row] 는 y 번째 줄의 화소. 첫 내용 줄에서 멈춘다 — 긴 띠의 모든 줄을 한꺼번에
     * 들고 있으면 1440×100000 그림 하나에 수십 MB 가 들었다.
     */
    fun firstContentRow(count: Int, row: (Int) -> IntArray): Int {
        if (count <= 0) return 0
        val background = row(0).firstOrNull() ?: return 0
        for (y in 0 until count) {
            if (row(y).any { !Backdrop.near(it, background) }) return y
        }
        return 0
    }

    /**
     * 원본 그림에서 표지로 쓸 줄 범위. [contentTop] 은 원본 줄 번호(줄여 풀어 찾았으면 다시 늘린 값). 그림 끝을 넘지
     * 않게 위로 당긴다 — 내용이 맨 아래에 붙은 그림이어도 표지 비율 한 장은 나온다.
     */
    fun window(size: ImageSize, contentTop: Int): IntRange {
        val height = (size.width * ASPECT).toInt().coerceIn(1, size.height)
        val margin = (size.width * MARGIN).toInt()
        val top = (contentTop - margin).coerceIn(0, size.height - height)
        return top until top + height
    }
}
