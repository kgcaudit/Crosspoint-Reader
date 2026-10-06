package io.github.kgcaudit.reader.document.comic

/**
 * 스캔본 쪽의 바깥 여백을 찾는다(0.49.0, 사용자 결정 3-가~라). 스캔한 만화는 종이 가장자리 · 스캐너 바탕이 사방에 남아,
 * 휴대폰 화면에 맞추면 그림이 그만큼 작아진다. 바깥에서 안으로 줄을 보며 바탕뿐인 줄을 걷어 낸다.
 *
 * 픽셀은 ARGB 정수 배열(가로줄 차례)로만 받는다 — 그림을 푸는 일은 앱이 하고, 어디를 자를지는 기기 없이 시험한다.
 */
object MarginTrim {

    /** 한 변에서 걷어 내는 한도(그 변 방향 길이의 비율, 사용자 결정 3-다). 넘으면 흰 바탕의 그림까지 먹어 들어간다. */
    const val MAX_SIDE: Float = 0.15f

    /** 바탕과 같다고 보는 색 차(채널마다). 스캔 종이는 고르게 하얗지 않다 — 표지 자르기([CoverCrop])와 같은 값. */
    private const val TOLERANCE = 24

    /** 한 줄에서 바탕이 아닌 점을 이만큼까지 봐준다. 스캔 먼지 · 얼룩 몇 점 때문에 여백이 여백이 아니게 되면 안 된다. */
    private const val SPECK = 0.01f

    /** 걷어 낸 자리 안쪽에 남기는 여유(그 변 방향 길이의 비율). 칸 테두리가 화면 끝에 딱 붙으면 잘린 것처럼 보인다. */
    private const val PAD = 0.01f

    /** 네 변을 다 합해 이보다 적게 걷히면 자르지 않는다 — 1~2 픽셀 자르자고 그림을 새로 만들면 쪽마다 비만 흔들린다. */
    private const val MIN_GAIN = 0.02f

    /** 남길 상자: [left], [top] 포함, [right], [bottom] 미포함. */
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /**
     * [pixels] 는 [width]×[height] 의 ARGB(가로줄 차례). 자를 것이 없으면 null.
     *
     * 바탕은 네 모서리 중 둘 이상이 같은 **무채색**(종이 흰색 · 회색 · 스캐너 검정)이어야 정한다. 모서리가 다 다르면(그림이
     * 끝까지 찬 쪽 · 펼침 그림) 여백이 없는 쪽이다. 무채색만 보는 까닭: 위 두 모서리가 같은 하늘색인 그림에서 하늘을 여백으로
     * 걷으면 그림이 위로 잘려 보인다 — 스캔 여백은 색이 없다.
     */
    fun find(width: Int, height: Int, pixels: IntArray): Box? {
        if (width < 8 || height < 8 || pixels.size < width * height) return null
        val corners = listOf(pixels[0], pixels[width - 1], pixels[(height - 1) * width], pixels[height * width - 1])
        val background = corners.firstOrNull { c -> neutral(c) && corners.count { near(it, c) } >= 2 } ?: return null

        fun blankRow(y: Int): Boolean {
            var off = 0
            val allowed = (width * SPECK).toInt()
            for (x in 0 until width) if (!near(pixels[y * width + x], background) && ++off > allowed) return false
            return true
        }
        fun blankColumn(x: Int): Boolean {
            var off = 0
            val allowed = (height * SPECK).toInt()
            for (y in 0 until height) if (!near(pixels[y * width + x], background) && ++off > allowed) return false
            return true
        }

        val capY = (height * MAX_SIDE).toInt()
        val capX = (width * MAX_SIDE).toInt()
        var top = 0
        while (top < capY && blankRow(top)) top++
        var bottom = height
        while (height - bottom < capY && blankRow(bottom - 1)) bottom--
        // 쪽 전체가 바탕이면(빈 쪽) 한도까지 걷혀 가운데만 남는다 — 빈 쪽을 늘려 봐야 빈 쪽이니 자르지 않는다.
        if (top == capY && height - bottom == capY && (top until bottom).all(::blankRow)) return null
        // 여백에 찍힌 쪽 번호 · 글씨는 먼지보다 크다 — 그 자리에서 멈춰 잘라 내지 않는다(쪽 번호가 반쯤 잘려 보이면 고장으로 읽힌다).
        var left = 0
        while (left < capX && blankColumn(left)) left++
        var right = width
        while (width - right < capX && blankColumn(right - 1)) right--

        val padY = (height * PAD).toInt()
        val padX = (width * PAD).toInt()
        val box = Box(
            left = (left - padX).coerceAtLeast(0),
            top = (top - padY).coerceAtLeast(0),
            right = (right + padX).coerceAtMost(width),
            bottom = (bottom + padY).coerceAtMost(height),
        )
        val gained = 1f - box.width.toFloat() * box.height / (width.toFloat() * height)
        return box.takeIf { gained >= MIN_GAIN }
    }

    /** 채널끼리 차가 작은 색. 누렇게 바랜 종이(F0E8D8 정도)까지는 무채색으로 친다. */
    private fun neutral(c: Int): Boolean {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return maxOf(r, g, b) - minOf(r, g, b) <= NEUTRAL
    }

    private const val NEUTRAL = 40

    private fun near(a: Int, b: Int): Boolean {
        fun ch(c: Int, shift: Int) = (c shr shift) and 0xFF
        return kotlin.math.abs(ch(a, 16) - ch(b, 16)) <= TOLERANCE &&
            kotlin.math.abs(ch(a, 8) - ch(b, 8)) <= TOLERANCE &&
            kotlin.math.abs(ch(a, 0) - ch(b, 0)) <= TOLERANCE
    }
}
