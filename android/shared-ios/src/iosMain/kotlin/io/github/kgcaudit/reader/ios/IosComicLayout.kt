@file:OptIn(ExperimentalForeignApi::class)

package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.document.comic.ComicSpreads
import io.github.kgcaudit.reader.document.comic.MarginTrim
import io.github.kgcaudit.reader.document.comic.SpreadSplit
import io.github.kgcaudit.reader.document.image.ImageSize
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import platform.Foundation.NSData

/**
 * 만화 쪽 다듬기(구상안 38): 화면 단위(판) · 여백 상자 · 색 행렬. 펼침면을 나눌지 · 어디를 걷을지는 공통 코드(`SpreadSplit` ·
 * `MarginTrim`)가 정하고, 여기는 그것을 iOS 화면이 쓰는 모양으로 묶는다 — 그림을 자르고 칠하는 일만 Swift(CoreGraphics ·
 * CoreImage)가 한다. 이 묶음을 Swift 에 두면 기기 없이 시험할 수 없다.
 */
object IosComicLayout {

    /**
     * 화면에 차례로 보이는 판들. 두 쪽 보기면 쪽 짝(공통 `ComicSpreads`)이고 나누지 않는다 — 펼침면은 원래 한 판이다. 한 쪽 보기에서
     * [split] 이면 펼침면을 반씩(왼→오 책은 왼쪽 반이 먼저, 오→왼은 오른쪽 반이 먼저).
     */
    fun units(sizes: List<ImageSize?>, twoPages: Boolean, coverAlone: Boolean, split: Boolean, rightToLeft: Boolean): List<List<SpreadSplit.Part>> =
        if (twoPages && sizes.size > 1) {
            ComicSpreads.of(sizes, coverAlone).map { pages -> pages.map { SpreadSplit.Part(it, SpreadSplit.Half.WHOLE) } }
        } else {
            (if (split) SpreadSplit.parts(sizes, rightToLeft) else SpreadSplit.whole(sizes.size)).map { listOf(it) }
        }

    /**
     * 책의 [page] 쪽([half] 반)이 든 판. 반이 맞는 판이 없으면(나누기를 껐다 · 두 쪽 보기 · 다른 휴대폰이 적은 반) 그 쪽이 든 첫 판,
     * 그 쪽이 없으면(쪽 수가 줄었다) 그보다 앞의 가장 가까운 판.
     */
    fun indexOf(units: List<List<SpreadSplit.Part>>, page: Int, half: SpreadSplit.Half?): Int {
        if (units.isEmpty()) return 0
        if (half != null && half != SpreadSplit.Half.WHOLE) {
            val exact = units.indexOfFirst { u -> u.any { it.page == page && it.half == half } }
            if (exact >= 0) return exact
        }
        val holding = units.indexOfFirst { u -> u.any { it.page == page } }
        if (holding >= 0) return holding
        return (units.indexOfLast { u -> u.first().page < page }).coerceAtLeast(0)
    }

    /**
     * 판의 조각마다 걸 여백 상자(조각 그림 크기에 대한 비율). [frames] 는 조각의 쪽마다 찾은 상자(자르기를 끄면 모두 WHOLE).
     * 두 쪽 판은 한 상자를 함께 쓴다(공통 `MarginTrim.shared` — 쪽마다 따로 자르면 두 쪽의 크기가 달라 이음매가 어긋났다), 반 조각은
     * 펼침면의 접힌 자리를 지키는 상자(`SpreadSplit.halfFrame`).
     */
    fun partFrames(parts: List<SpreadSplit.Part>, frames: List<MarginTrim.Frame>): List<MarginTrim.Frame> {
        if (parts.size != frames.size || parts.isEmpty()) return parts.map { MarginTrim.Frame.WHOLE }
        if (parts.size > 1) return MarginTrim.shared(frames).let { s -> parts.map { s } }
        val part = parts.single()
        return listOf(if (part.half == SpreadSplit.Half.WHOLE) frames.single() else SpreadSplit.halfFrame(frames.single(), part.half))
    }

    /**
     * 줄여 본 쪽 그림(RGBA 바이트, 한 줄 [bytesPerRow])의 여백 상자. 바이트가 모자라거나 이상하면 자르지 않는다 — 깨진 그림 하나 때문에
     * 쪽이 엉뚱하게 잘리면 안 된다(규칙 6).
     */
    fun frameOfRgba(width: Int, height: Int, bytesPerRow: Int, rgba: NSData): MarginTrim.Frame {
        if (width <= 0 || height <= 0 || bytesPerRow < width * 4 || rgba.length.toLong() < bytesPerRow.toLong() * height) return MarginTrim.Frame.WHOLE
        val bytes = rgba.bytes?.reinterpret<ByteVar>() ?: return MarginTrim.Frame.WHOLE
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val row = y * bytesPerRow
            for (x in 0 until width) {
                val i = row + x * 4
                val r = bytes[i].toInt() and 0xFF
                val g = bytes[i + 1].toInt() and 0xFF
                val b = bytes[i + 2].toInt() and 0xFF
                pixels[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return MarginTrim.Frame.of(MarginTrim.find(width, height, pixels), width, height)
    }

    /** 여백 찾기에 쓰는 줄인 그림의 폭(안드로이드 `TRIM_PROBE`). 화면 크기 그림을 통째로 훑으면 쪽마다 수 MB 를 더 쓴다. */
    const val TRIM_PROBE: Int = 360

    /** 색 보정(끔 · 선명하게 · 흑백 — 안드로이드 `ComicColor` 차례). 모르는 번호는 끔. */
    fun colorMode(index: Int): Int = index.takeIf { it in 0..2 } ?: 0

    /**
     * 색 보정의 4×5 행렬(행 우선, 다섯째 열은 0..1 더하기 — CoreImage `CIColorMatrix` 의 bias). 끔은 null: 아무것도 하지 않는 설정은
     * 그리는 비용도 없어야 한다. 값은 안드로이드 `comicColorMatrix` 와 같다(그쪽은 더하기가 0..255) — 두 앱에서 같은 스캔본이 같은 색이어야
     * 한다.
     */
    fun colorMatrix(mode: Int): List<Float>? {
        val k = 1f / (WHITE_POINT - BLACK_POINT)
        val o = -BLACK_POINT * k
        return when (colorMode(mode)) {
            1 -> listOf(
                k, 0f, 0f, 0f, o,
                0f, k, 0f, 0f, o,
                0f, 0f, k, 0f, o,
                0f, 0f, 0f, 1f, 0f,
            )
            2 -> List(20) { 0f }.toMutableList().also { m ->
                for (row in 0..2) {
                    m[row * 5] = LUMA_R * k
                    m[row * 5 + 1] = LUMA_G * k
                    m[row * 5 + 2] = LUMA_B * k
                    m[row * 5 + 4] = o
                }
                m[18] = 1f
            }
            else -> null
        }
    }

    /** 위 막대의 반 표시(안드로이드 `halfLabel`): 같은 "4 / 8쪽" 이 두 번 이어지면 넘김이 안 된 것처럼 보인다. */
    fun halfLabel(half: SpreadSplit.Half?): String = when (half) {
        SpreadSplit.Half.LEFT -> " · 왼쪽 반"
        SpreadSplit.Half.RIGHT -> " · 오른쪽 반"
        else -> ""
    }

    // 안드로이드 `COMIC_BLACK_POINT` · `COMIC_WHITE_POINT` · Rec.709 밝기 무게.
    private const val BLACK_POINT = 0.25f
    private const val WHITE_POINT = 0.86f
    private const val LUMA_R = 0.213f
    private const val LUMA_G = 0.715f
    private const val LUMA_B = 0.072f
}
