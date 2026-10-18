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

    /** 한 줄에서 바탕([Backdrop])이 아닌 점을 이만큼까지 봐준다. 스캔 먼지 · 얼룩 몇 점 때문에 여백이 여백이 아니게 되면 안 된다. */
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
     * 남길 상자를 쪽 크기에 대한 비율(0~1)로. 같은 쪽을 다른 크기로 풀어도 같은 자리를 자르고, 같은 크기의 쪽끼리 비교 · 합칠 수
     * 있다. [WHOLE] 은 자르지 않음.
     */
    data class Frame(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val whole: Boolean get() = this == WHOLE

        companion object {
            val WHOLE = Frame(0f, 0f, 1f, 1f)

            /** [width]×[height] 에서 찾은 [box](없으면 [WHOLE]). */
            fun of(box: Box?, width: Int, height: Int): Frame =
                if (box == null || width <= 0 || height <= 0) WHOLE
                else Frame(box.left.toFloat() / width, box.top.toFloat() / height, box.right.toFloat() / width, box.bottom.toFloat() / height)
        }
    }

    /**
     * 함께 보이는 쪽들(두 쪽 보기의 한 판)이 같이 쓸 상자: 변마다 가장 적게 걷은 쪽을 따른다 — 어느 쪽도 그림을 잃지 않는다.
     * 여백이 없는 쪽이 하나라도 있으면 [Frame.WHOLE] 이다(그 쪽은 한 변도 걷지 않으니).
     *
     * 쪽마다 따로 자르면, 흰 여백이 있는 쪽과 그림이 끝까지 찬 짝이 다른 배율로 늘어나 두 쪽의 크기가 달라지고 가운데
     * 이음매의 그림(구름 · 땅)이 어긋났다. 판마다 자른 정도도 달라 판을 넘길 때마다 쪽 크기 · 위 띠가 흔들렸다.
     * 합쳐서 거의 걷히지 않으면(네 변 합 2% 미만, [find] 와 같은 기준) 자르지 않는다.
     */
    fun shared(frames: List<Frame>): Frame {
        if (frames.isEmpty()) return Frame.WHOLE
        val f = Frame(
            left = frames.minOf { it.left },
            top = frames.minOf { it.top },
            right = frames.maxOf { it.right },
            bottom = frames.maxOf { it.bottom },
        )
        return if (1f - f.width * f.height < MIN_GAIN) Frame.WHOLE else f
    }

    /**
     * [pixels] 는 [width]×[height] 의 ARGB(가로줄 차례). 자를 것이 없으면 null.
     *
     * 바탕은 네 모서리 중 둘 이상이 같은 **종이색**([paper]: 흰색 · 회색 · 스캐너 검정 · 누렇게 바랜 종이)이어야 정한다.
     * 모서리가 다 다르면(그림이 끝까지 찬 쪽 · 펼침 그림) 여백이 없는 쪽이다. 종이색만 보는 까닭: 위 두 모서리가 같은 하늘색인
     * 그림에서 하늘을 여백으로 걷으면 그림이 위로 잘려 보인다 — 스캔 여백은 색이 없거나 누렇다.
     */
    fun find(width: Int, height: Int, pixels: IntArray): Box? {
        if (width < 8 || height < 8 || pixels.size < width * height) return null
        val corners = listOf(pixels[0], pixels[width - 1], pixels[(height - 1) * width], pixels[height * width - 1])
        val background = corners.firstOrNull { c -> paper(c) && corners.count { Backdrop.near(it, c) } >= 2 } ?: return null

        fun blankRow(y: Int): Boolean {
            var off = 0
            val allowed = (width * SPECK).toInt()
            for (x in 0 until width) if (!Backdrop.near(pixels[y * width + x], background) && ++off > allowed) return false
            return true
        }
        fun blankColumn(x: Int): Boolean {
            var off = 0
            val allowed = (height * SPECK).toInt()
            for (y in 0 until height) if (!Backdrop.near(pixels[y * width + x], background) && ++off > allowed) return false
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

    /**
     * 종이 여백으로 볼 수 있는 색인가. 두 갈래뿐이다.
     *
     * - 거의 무채색(채널 차 [NEUTRAL] 이하): 흰 종이 · JPEG 의 250 언저리 흰색 · 회색 · 스캐너 검정.
     * - 누렇게 바랜 종이: 빨강 ≥ 초록 ≥ 파랑, 차가 [YELLOWED] 이하, 파랑도 [YELLOWED_MIN_BLUE] 이상(밝다), 그리고 빨강과
     *   초록이 초록과 파랑보다 가깝다(노란 쪽으로 바랬지 분홍 · 살구 쪽이 아니다). E9DDBC · F0E8D8 같은 옛 스캔 종이.
     *
     * 0.52 까지는 "채널 차 40 이하" 하나로 봤다. 그러면 연한 하늘색(D6EEF2, 차 28)도 종이가 되어, 위 두 모서리가 하늘인
     * 그림 쪽(먼작귀 컬러판)에서 하늘을 여백으로 걷었다 — 두 쪽 보기에서 그 쪽만 커져 펼친 두 쪽의 크기가 어긋나고
     * 가운데 이음매의 구름이 맞지 않았다. 연한 하늘 · 풀빛 · 분홍 · 보라 바탕은 그림이지 종이가 아니다.
     */
    internal fun paper(c: Int): Boolean {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        val spread = maxOf(r, g, b) - minOf(r, g, b)
        if (spread <= NEUTRAL) return true
        return r >= g && g >= b && spread <= YELLOWED && b >= YELLOWED_MIN_BLUE && r - g <= g - b
    }

    /** 무채색으로 보는 채널 차. 스캔 · JPEG 의 색 번짐이 흰 바탕에 남기는 차는 한 자리 수다 — 12 를 넘으면 색이 있는 바탕이다. */
    private const val NEUTRAL = 12

    /** 바랜 종이의 채널 차 한도. 가장 누런 축의 옛 스캔 종이(E9DDBC)가 45 다. */
    private const val YELLOWED = 48

    /** 바랜 종이의 파랑 하한 — 진한 황토 · 갈색 바탕(그림)은 종이가 아니다. */
    private const val YELLOWED_MIN_BLUE = 150
}
