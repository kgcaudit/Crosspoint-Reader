package io.github.kgcaudit.reader.ui.design

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 아래에서 올라오는 판(0.29.0, 구상안 가안). 메모 · 각주 · 듣기 판이 따로 그리던 것을 하나로 모았다 — 판마다 뒤 화면이
 * 어두워지는 정도(0x66 · 0x44)와 좌우 여백이 달라, 같은 종류의 판이 다른 것처럼 보였다.
 *
 * 바깥을 누르거나 뒤로 가기는 [onDismiss]. 판 안을 눌러도 닫히지 않는다.
 *
 * @param horizontalPadding 판 안 좌우 여백. 행마다 스스로 여백을 두는 목록(듣기 판의 설정 행)을 담으면 0.
 * @param content 판 높이를 알아야 하는 내용(긴 각주를 화면의 60% 까지)은 [maxHeight] 로 받는다.
 */
@Composable
fun CpBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = CpTheme.metrics.gutter,
    content: @Composable ColumnScope.(maxHeight: Dp) -> Unit,
) {
    val c = CpTheme.colors
    BackHandler(onBack = onDismiss)
    BoxWithConstraints(
        Modifier.fillMaxSize().background(SHEET_SCRIM)
            .clickable(indication = null, interactionSource = null, onClick = onDismiss),
    ) {
        val height = maxHeight
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .then(modifier)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(c.surface)
                .blockTouches()
                .padding(horizontal = horizontalPadding).padding(top = 10.dp, bottom = 24.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(50)).background(c.divider))
            content(height)
        }
    }
}

/** 판 뒤 막. 팝업(CpPopup)과 같은 짙기 — 뒤 글이 비쳐 판의 글과 섞이지 않게. */
private val SHEET_SCRIM = Color(0x66000000)
