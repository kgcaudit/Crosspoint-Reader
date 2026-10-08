package io.github.kgcaudit.reader.ui.design

import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix

/*
 * 만화 색 보정(사용자 결정 2-1 · 2-2, 구상안 확정 "권고안대로").
 *
 * 오래된 스캔본은 종이가 누렇고 먹이 흐려 휴대폰에서 칙칙하다. 그림 한 장 한 장을 분석해 고치면(자동 수준) 쪽마다 밝기가
 * 들쭉날쭉해지고 풀 때마다 픽셀을 훑어야 한다 — 그래서 모든 쪽에 같은 고정 행렬을 씌운다. 그리는 단계의 색 거르개라
 * 풀어 둔 그림 · 여백 자르기 분석 · 그림 저장소의 열쇠는 그대로다(켜고 끌 때 다시 풀지 않는다).
 *
 * 책 · PDF 의 [paperImageFilter] 와는 따로다: 그쪽은 흰 바탕을 지면색에 맞추는 것이고, 만화는 지면색을 쓰지 않는다.
 */

/** 수준 맞추기의 검은 점: 이보다 어두운 것은 검정. 흐린 먹(회색 #40 남짓)이 이 아래로 내려간다. */
internal const val COMIC_BLACK_POINT = 0.25f

/**
 * 흰 점: 이보다 밝은 것은 흰색. 누렇게 바랜 종이의 밝은 두 채널(빨강 · 초록 #D9~#E6)이 이 위로 올라 하얗게 된다. 파랑은
 * 종이에서 가장 어두운 채널이라 다 오르지 못한다 — 선명하게는 옅은 미색까지, 색을 빼는 흑백이 완전히 희다(구상안 그대로).
 * 채널마다 흰 점을 따로 두면 종이는 희어지지만 컬러 만화의 하늘 · 옷이 푸르게 뜬다.
 */
internal const val COMIC_WHITE_POINT = 0.86f

/** 흑백의 밝기 무게(Rec.709). Compose `ColorMatrix.setToSaturation(0f)` 과 같은 값 — 둘이 다르면 흑백이 쪽마다 미묘하게 다르다. */
private const val LUMA_R = 0.213f
private const val LUMA_G = 0.715f
private const val LUMA_B = 0.072f

/**
 * [mode] 의 4×5 색 행렬(행 우선, 다섯째 열은 0..255 더하기 — 안드로이드 · Compose 의 약속). [ComicColor.Off] 는 null:
 * 단위 행렬을 씌워도 그림은 같지만 그릴 때마다 거르개를 거친다. 아무것도 하지 않는 설정은 비용도 없어야 한다.
 */
fun comicColorMatrix(mode: ComicColor): FloatArray? {
    val k = 1f / (COMIC_WHITE_POINT - COMIC_BLACK_POINT)
    val o = -COMIC_BLACK_POINT * 255f * k
    return when (mode) {
        ComicColor.Off -> null
        ComicColor.Clear -> floatArrayOf(
            k, 0f, 0f, 0f, o,
            0f, k, 0f, 0f, o,
            0f, 0f, k, 0f, o,
            0f, 0f, 0f, 1f, 0f,
        )
        // 채도 0 뒤에 같은 수준. 수준이 세 채널에 똑같으므로 순서를 바꿔도 결과는 같다 — 한 행렬로 접는다.
        ComicColor.Gray -> FloatArray(20).also { m ->
            for (row in 0..2) {
                m[row * 5] = LUMA_R * k
                m[row * 5 + 1] = LUMA_G * k
                m[row * 5 + 2] = LUMA_B * k
                m[row * 5 + 4] = o
            }
            m[18] = 1f
        }
    }
}

/**
 * 그릴 때 씌울 거르개. 값마다 하나만 만들어 둔다 — 띠 · 쪽을 그릴 때마다 새로 만들면 웹툰을 밀어 내리는 동안 프레임마다
 * 행렬이 쌓인다.
 */
val ComicColor.colorFilter: ColorFilter?
    get() = COMIC_FILTERS[ordinal]

private val COMIC_FILTERS: List<ColorFilter?> = ComicColor.entries.map { mode -> comicColorMatrix(mode)?.let { ColorFilter.colorMatrix(ColorMatrix(it)) } }
