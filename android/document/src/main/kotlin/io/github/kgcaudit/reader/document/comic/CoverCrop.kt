package io.github.kgcaudit.reader.document.comic

import io.github.kgcaudit.reader.document.image.ImageSize
import kotlin.math.roundToInt

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

    /**
     * 펼침면 표지에서 앞표지만(사용자 결정, KCC `--smartcovercrop` · Folio 와 같은 규칙). 스캔한 첫 그림이 앞 · 뒤표지를 함께
     * 담은 한 장이면 표지 칸에 넣었을 때 두 쪽이 납작한 띠로 서서, 서재 · 작품 화면 · 위젯에서 무슨 책인지 알아볼 수 없었다.
     *
     * 원본의 열 범위를 준다(높이는 늘 전부). 앞표지는 왼→오 책이면 오른쪽, 오→왼 책이면 왼쪽이다.
     * - 펼침면이 아니면([SpreadSplit.WIDE] 이하) 통째로. 펼침면 나누기와 같은 문턱이라, 나눠 읽는 쪽과 반으로 자르는 표지가 같다.
     * - 펼침면이면 앞쪽 반에서 책등 쪽 [SPINE] 만큼 더 깎는다 — 책등의 글자 · 그림자가 표지 가장자리에 띠로 남는다.
     * - 아주 넓으면([JACKET] 초과: 날개 · 띠지까지 펼친 겉싸개) 앞 가장자리에 붙여 표지 칸 비율만큼만. 가장 바깥 [SPINE] 은
     *   날개 끝이라 뺀다. 반으로만 자르면 반이 여전히 가로로 길어 다시 납작해진다.
     *
     * 크기가 깨졌으면(0 · 음수) 통째로 — 표지 하나 때문에 서재가 멈추면 안 된다(규칙 6). 부르는 쪽은 빈 범위를 "못 자름" 으로 본다.
     * [ImageSize] 가 아니라 정수로 받는 것도 그래서다: 그림 머리에서 읽은 깨진 크기를 그대로 넘겨도 예외가 나지 않는다.
     */
    fun front(width: Int, height: Int, rightToLeft: Boolean): IntRange {
        val w = width
        val h = height
        if (w <= 0 || h <= 0 || !SpreadSplit.isWide(ImageSize(w, h))) return 0 until w.coerceAtLeast(0)
        val edge = (w * SPINE).roundToInt()
        return if (w > h * JACKET) {
            val span = (h / ASPECT).roundToInt().coerceIn(1, w - 2 * edge)
            if (rightToLeft) edge until edge + span else (w - edge - span) until (w - edge)
        } else if (rightToLeft) {
            0 until (w / 2 - edge).coerceAtLeast(1)
        } else {
            (w / 2 + edge).coerceAtMost(w - 1) until w
        }
    }

    /** 펼침면 표지에서 책등 쪽(겉싸개면 날개 끝)으로 깎는 폭, 그림 전체 폭의 비율. */
    const val SPINE: Float = 0.02f

    /** 이보다 넓으면(가로 ÷ 세로) 반이 아니라 표지 칸 비율로 자른다. 앞 · 뒤표지 한 장은 1.3~1.5, 날개까지 펼치면 2 를 넘는다. */
    const val JACKET: Float = 2.0f
}
