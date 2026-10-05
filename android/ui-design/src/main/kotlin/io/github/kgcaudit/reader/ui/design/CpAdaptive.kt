package io.github.kgcaudit.reader.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/**
 * 태블릿 모드(0.30.0, 폴더블 구상안 확정): 기기의 가장 짧은 폭이 600dp 이상 — 폴더블 본 화면(약 732dp) · 태블릿.
 * 바깥 화면(약 466dp) · 휴대폰은 휴대폰 모드다.
 *
 * 창의 지금 폭이 아니라 가장 짧은 폭으로 가른다. 지금 폭으로 가르면 바깥 화면을 가로로 돌리는 순간(737dp) 태블릿
 * 모드가 되어, 높이 466dp 화면에 옆 판이 뜬다.
 */
@Composable
fun cpTablet(): Boolean = LocalConfiguration.current.smallestScreenWidthDp >= ScreenPrefs.WIDE_SCREEN_DP

/**
 * 창 폭 등급(0.46.0, Material 3 창 크기 등급 — docs/RESPONSIVE_PLAN.md). 화면의 배치는 이것으로 고른다:
 * 좁음(600 미만) 한 판 목록 · 중간(600–839) 한 판, 목록은 표지 격자 · 넓음(840 이상) 두 판.
 * [cpTablet](가장 짧은 변)은 "기기가 태블릿인가" — 리더의 옆 판 · 큰 표지처럼 기기에 딸린 것에만 쓴다.
 */
enum class CpWidthClass { Compact, Medium, Expanded }

internal fun widthClassOf(widthDp: Int): CpWidthClass = when {
    widthDp < 600 -> CpWidthClass.Compact
    widthDp < 840 -> CpWidthClass.Medium
    else -> CpWidthClass.Expanded
}

/** 두 판을 펼 만한가: 넓음이면서 높이도 480dp 이상. 폴더블 바깥 화면 · 휴대폰을 가로로 돌리면 폭은 넓어도 높이가 393dp
 * 안팎이라, 두 판을 펴면 왼쪽 정보 판이 잘린다(0.30.0 에 가장 짧은 변으로 태블릿을 가른 까닭과 같다). */
internal fun twoPaneOf(widthDp: Int, heightDp: Int): Boolean = widthClassOf(widthDp) == CpWidthClass.Expanded && heightDp >= TWO_PANE_MIN_HEIGHT

/**
 * 넓은 배치(격자 · 두 판)를 쓸 만한가: 좁음이 아니고 높이도 480dp 이상. 휴대폰을 가로로 돌리면 폭은 중간 이상이어도
 * 높이가 393dp 라, 위에 작품 정보를 얹은 격자는 권 칸이 화면 밖으로 밀린다 — 그때는 좁은 화면처럼 줄 목록이다.
 */
internal fun roomyOf(widthDp: Int, heightDp: Int): Boolean = widthClassOf(widthDp) != CpWidthClass.Compact && heightDp >= TWO_PANE_MIN_HEIGHT

@Composable
fun cpRoomy(): Boolean = LocalConfiguration.current.let { roomyOf(it.screenWidthDp, it.screenHeightDp) }

private const val TWO_PANE_MIN_HEIGHT = 480

@Composable
fun cpWidthClass(): CpWidthClass = widthClassOf(LocalConfiguration.current.screenWidthDp)

@Composable
fun cpTwoPane(): Boolean = LocalConfiguration.current.let { twoPaneOf(it.screenWidthDp, it.screenHeightDp) }

/** 두 판의 왼쪽(고정 정보) 판 폭. 휴대폰 한 화면보다 좁게 — 오른쪽 목록이 넓어야 두 판을 편 보람이 있다. */
val TWO_PANE_LEFT_WIDTH = 320.dp

/** 태블릿 모드의 옆 판 폭(목차 · 독서노트 · 찾기 · 설정). 휴대폰 한 화면 폭 — 휴대폰에서 보던 모양 그대로 옆에 선다. */
val SIDE_PANEL_WIDTH = 400.dp

/** 태블릿 모드에서 설정 · 앱 정보처럼 글과 단추가 줄지어 선 화면의 최대 폭. 넘으면 이름과 단추 사이가 한참 멀다. */
val CONTENT_MAX_WIDTH = 600.dp

/** 태블릿 모드의 보기 판(아래에서 오르는 짧은 판) 최대 폭. */
val VIEW_PANEL_MAX_WIDTH = 560.dp
