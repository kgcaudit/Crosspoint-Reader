package io.github.kgcaudit.reader.pdf

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 페이지 안의 한 구역. 모두 페이지 폭·높이에 대한 비율(0..1)이다. */
data class PageRegion(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    companion object {
        val WHOLE = PageRegion(0f, 0f, 1f, 1f)
    }
}

/**
 * 한 페이지를 화면에 놓는 계산. 좌표는 모두 화면 px 이다.
 *
 * 확대 1 은 **페이지 전체가 보이는 크기**다. 폭에 맞추면 가로 PDF(슬라이드)는 아래가 잘리지 않지만
 * 세로 A4 는 휴대폰에서 위아래가 잘린다 — 한 장씩 넘기는 리더에서는 넘길 때마다 한 장이 온전히
 * 보여야 "다음 쪽" 이 무엇인지 분명하다. 작은 글자는 확대해서 본다.
 *
 * 화면보다 작은 축은 가운데, 큰 축은 가장자리를 넘어 끌 수 없게 가둔다. 가두지 않으면 끌다가
 * 페이지를 화면 밖으로 날려 빈 화면만 남는다.
 *
 * 폭 맞춤([fitWidth])은 쉬는 크기([base])가 1 보다 큰 배치다. 가로 화면에서 세로 쪽을 전체로 보면 글자가
 * 읽히지 않을 만큼 작아, 폭에 맞추고 위아래로 내려 읽는다. 확대 · 두 번 누르기는 이 쉬는 크기를 기준으로 한다 —
 * 1 을 기준으로 하면 폭 맞춤이 곧 "확대 중" 이 되어 두 번 누르기가 쪽 전체로 줄여 버린다.
 */
class PageViewport private constructor(
    val viewWidth: Float,
    val viewHeight: Float,
    /** 페이지의 가로/세로 비. */
    val pageAspect: Float,
    val scale: Float,
    /** 페이지 왼쪽 위의 화면 좌표. */
    val left: Float,
    val top: Float,
    /** 쉬는 크기. 쪽 전체면 1, 폭 맞춤이면 화면 폭 ÷ 쪽 전체의 폭. */
    val base: Float = 1f,
) {
    private val fitWidth: Float = if (viewWidth / viewHeight > pageAspect) viewHeight * pageAspect else viewWidth
    private val fitHeight: Float = fitWidth / pageAspect

    val width: Float get() = fitWidth * scale
    val height: Float get() = fitHeight * scale

    val isZoomed: Boolean get() = scale > base * 1.01f

    /** [factor] 배 확대(1 보다 작으면 축소). 손가락 사이의 점([focusX], [focusY])이 제자리에 있게 한다. */
    fun zoom(factor: Float, focusX: Float, focusY: Float): PageViewport =
        scaleTo((scale * factor).coerceIn(base, MAX_SCALE * base), focusX, focusY)

    private fun scaleTo(next: Float, focusX: Float, focusY: Float): PageViewport {
        val ratio = next / scale
        return clamped(next, focusX - (focusX - left) * ratio, focusY - (focusY - top) * ratio)
    }

    fun pan(dx: Float, dy: Float): PageViewport = clamped(scale, left + dx, top + dy)

    /**
     * 두 번 누르기: 확대돼 있으면 쉬는 크기로, 아니면 그 점을 중심으로 [DOUBLE_TAP_SCALE] 배. 폭 맞춤에서 줄일 때
     * 누른 점을 붙잡아 두어, 쪽 아래쪽을 확대했다 줄여도 쪽 머리로 튀어 오르지 않는다.
     */
    fun toggleZoom(focusX: Float, focusY: Float): PageViewport =
        if (isZoomed) scaleTo(base, focusX, focusY) else zoom(DOUBLE_TAP_SCALE, focusX, focusY)

    /** 쪽 머리 · 쪽 끝이 화면에 닿았는가. 쪽이 화면보다 낮으면 둘 다 참. */
    val atTop: Boolean get() = height <= viewHeight + EPS || top >= -EPS
    val atBottom: Boolean get() = height <= viewHeight + EPS || top <= viewHeight - height + EPS

    /**
     * 한 화면 아래(앞으로)나 위로. 이미 그 끝이면 null — 그때 부르는 쪽이 쪽을 넘긴다.
     * 한 화면을 다 옮기지 않고 [OVERLAP] 만큼 겹친다: 딱 한 화면씩 옮기면 화면 끝에 걸린 줄이 반씩 잘려 두 번 다
     * 읽히지 않는다.
     */
    fun scroll(forward: Boolean): PageViewport? = when {
        forward && atBottom -> null
        !forward && atTop -> null
        else -> clamped(scale, left, top + if (forward) -scrollStep else scrollStep)
    }

    private val scrollStep: Float get() = viewHeight * (1f - OVERLAP)

    /**
     * 쪽 안에서 몇 번째 화면인가(1부터)와 모두 몇 화면인가. 누름으로 내리면 딱 떨어지고, 손으로 끌어 중간에
     * 있으면 가까운 쪽으로 센다. 쪽 끝에 닿았으면 언제나 마지막 — "3/3" 인데 더 내려가는 일이 없게.
     */
    fun screen(): Pair<Int, Int> {
        if (height <= viewHeight + EPS) return 1 to 1
        val total = 1 + ceil((height - viewHeight - EPS) / scrollStep).toInt()
        val index = if (atBottom) total else (1 + (-top / scrollStep).roundToInt()).coerceIn(1, total)
        return index to total
    }

    /** 화면에 보이는 부분(페이지 비율). 확대했을 때 이 부분만 선명하게 다시 그린다. */
    fun visible(): PageRegion = PageRegion(
        left = (max(0f, -left) / width).coerceIn(0f, 1f),
        top = (max(0f, -top) / height).coerceIn(0f, 1f),
        right = (min(width, viewWidth - left) / width).coerceIn(0f, 1f),
        bottom = (min(height, viewHeight - top) / height).coerceIn(0f, 1f),
    )

    /** 오른쪽 끝까지 끌었는가. 확대 중에 더 밀면 다음 쪽으로 넘기는 판단에 쓴다. */
    val atRightEdge: Boolean get() = width <= viewWidth + EPS || left <= viewWidth - width + EPS
    val atLeftEdge: Boolean get() = width <= viewWidth + EPS || left >= -EPS

    private fun clamped(scale: Float, left: Float, top: Float): PageViewport {
        val w = fitWidth * scale
        val h = fitHeight * scale
        val l = if (w <= viewWidth) (viewWidth - w) / 2 else left.coerceIn(viewWidth - w, 0f)
        val t = if (h <= viewHeight) (viewHeight - h) / 2 else top.coerceIn(viewHeight - h, 0f)
        return PageViewport(viewWidth, viewHeight, pageAspect, scale, l, t, base)
    }

    override fun equals(other: Any?): Boolean = other is PageViewport &&
        viewWidth == other.viewWidth && viewHeight == other.viewHeight && pageAspect == other.pageAspect &&
        scale == other.scale && left == other.left && top == other.top && base == other.base

    override fun hashCode(): Int = listOf(viewWidth, viewHeight, pageAspect, scale, left, top, base).hashCode()

    override fun toString(): String =
        "PageViewport(scale=$scale/base=$base, left=$left, top=$top, ${width}x$height in ${viewWidth}x$viewHeight)"

    companion object {
        /** 이보다 크게는 확대하지 않는다. 각주의 작은 글자도 이 정도면 읽힌다. */
        const val MAX_SCALE = 5f
        const val DOUBLE_TAP_SCALE = 2.5f
        private const val EPS = 0.5f
        /** 한 화면씩 내릴 때 겹치는 몫. 앞 화면의 마지막 한두 줄이 새 화면 머리에 다시 보여 이어 읽힌다. */
        const val OVERLAP = 0.1f

        fun fit(viewWidth: Float, viewHeight: Float, pageAspect: Float): PageViewport {
            require(viewWidth > 0f && viewHeight > 0f) { "viewport must be positive" }
            val aspect = if (pageAspect.isFinite() && pageAspect > 0f) pageAspect else DEFAULT_ASPECT
            return PageViewport(viewWidth, viewHeight, aspect, 1f, 0f, 0f).clamped(1f, 0f, 0f)
        }

        /**
         * 쪽을 화면 폭에 맞춘다. [fromBottom] 이면 쪽 끝이 보이게 — 앞 쪽으로 돌아갈 때 그 쪽의 끝(방금 읽던 곳의
         * 바로 앞)에서 시작해야 이어 읽힌다. 쪽이 화면보다 넓은 비(가로 슬라이드)면 쪽 전체와 같다.
         */
        fun fitWidth(viewWidth: Float, viewHeight: Float, pageAspect: Float, fromBottom: Boolean = false): PageViewport {
            val whole = fit(viewWidth, viewHeight, pageAspect)
            val base = (viewWidth / whole.width).coerceAtLeast(1f)
            return PageViewport(viewWidth, viewHeight, whole.pageAspect, base, 0f, 0f, base)
                .clamped(base, 0f, if (fromBottom) -Float.MAX_VALUE / 4 else 0f)
        }

        /** 크기를 모를 때(깨진 페이지). A 판형 세로. */
        const val DEFAULT_ASPECT = 0.7071f
    }
}
