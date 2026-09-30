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

/** 태블릿 모드의 옆 판 폭(목차 · 독서노트 · 찾기 · 설정). 휴대폰 한 화면 폭 — 휴대폰에서 보던 모양 그대로 옆에 선다. */
val SIDE_PANEL_WIDTH = 400.dp

/** 태블릿 모드에서 설정 · 앱 정보처럼 글과 단추가 줄지어 선 화면의 최대 폭. 넘으면 이름과 단추 사이가 한참 멀다. */
val CONTENT_MAX_WIDTH = 600.dp

/** 태블릿 모드의 보기 판(아래에서 오르는 짧은 판) 최대 폭. */
val VIEW_PANEL_MAX_WIDTH = 560.dp
