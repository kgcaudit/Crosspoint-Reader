package io.github.kgcaudit.reader.pdf

import kotlin.math.max
import kotlin.math.min

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
) {
    private val fitWidth: Float = if (viewWidth / viewHeight > pageAspect) viewHeight * pageAspect else viewWidth
    private val fitHeight: Float = fitWidth / pageAspect

    val width: Float get() = fitWidth * scale
    val height: Float get() = fitHeight * scale

    val isZoomed: Boolean get() = scale > 1.01f

    /** [factor] 배 확대(1 보다 작으면 축소). 손가락 사이의 점([focusX], [focusY])이 제자리에 있게 한다. */
    fun zoom(factor: Float, focusX: Float, focusY: Float): PageViewport {
        val next = (scale * factor).coerceIn(1f, MAX_SCALE)
        val ratio = next / scale
        return clamped(next, focusX - (focusX - left) * ratio, focusY - (focusY - top) * ratio)
    }

    fun pan(dx: Float, dy: Float): PageViewport = clamped(scale, left + dx, top + dy)

    /** 두 번 누르기: 확대돼 있으면 전체로, 아니면 그 점을 중심으로 [DOUBLE_TAP_SCALE] 배. */
    fun toggleZoom(focusX: Float, focusY: Float): PageViewport =
        if (isZoomed) fit(viewWidth, viewHeight, pageAspect) else zoom(DOUBLE_TAP_SCALE, focusX, focusY)

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
        return PageViewport(viewWidth, viewHeight, pageAspect, scale, l, t)
    }

    override fun equals(other: Any?): Boolean = other is PageViewport &&
        viewWidth == other.viewWidth && viewHeight == other.viewHeight && pageAspect == other.pageAspect &&
        scale == other.scale && left == other.left && top == other.top

    override fun hashCode(): Int = listOf(viewWidth, viewHeight, pageAspect, scale, left, top).hashCode()

    override fun toString(): String = "PageViewport(scale=$scale, left=$left, top=$top, ${width}x$height in ${viewWidth}x$viewHeight)"

    companion object {
        /** 이보다 크게는 확대하지 않는다. 각주의 작은 글자도 이 정도면 읽힌다. */
        const val MAX_SCALE = 5f
        const val DOUBLE_TAP_SCALE = 2.5f
        private const val EPS = 0.5f

        fun fit(viewWidth: Float, viewHeight: Float, pageAspect: Float): PageViewport {
            require(viewWidth > 0f && viewHeight > 0f) { "viewport must be positive" }
            val aspect = if (pageAspect.isFinite() && pageAspect > 0f) pageAspect else DEFAULT_ASPECT
            return PageViewport(viewWidth, viewHeight, aspect, 1f, 0f, 0f).clamped(1f, 0f, 0f)
        }

        /** 크기를 모를 때(깨진 페이지). A 판형 세로. */
        const val DEFAULT_ASPECT = 0.7071f
    }
}
