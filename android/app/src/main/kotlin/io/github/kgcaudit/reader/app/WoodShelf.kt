package io.github.kgcaudit.reader.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.kgcaudit.reader.ui.design.CpTheme

/*
 * 책장 보기(0.39.0, docs/LIBRARY_UNIFY_PLAN.md 결정 4·5 — 나무 책장). 표지만 판 위에 세운다. 제목 · 진도는 격자 · 목록
 * 보기에 있다 — 책장은 "꽂아 둔 모습" 을 보는 보기라, 글자를 얹으면 판 사이가 벌어져 책장으로 읽히지 않는다.
 */

/** 나무 결 색. 밝은 테마 · 어두운 테마에 따로 — 어두운 화면에서 밝은 나무는 눈부시다. */
internal class Wood(val back: Brush, val plank: Brush, val label: Color)

@Composable
internal fun wood(): Wood =
    if (CpTheme.colors.background.luminance() < 0.5f) {
        Wood(
            back = Brush.verticalGradient(listOf(Color(0xFF4A3220), Color(0xFF3A2718))),
            plank = Brush.verticalGradient(listOf(Color(0xFF7A5534), Color(0xFF55391F))),
            label = Color(0xFFE3CFB8),
        )
    } else {
        Wood(
            back = Brush.verticalGradient(listOf(Color(0xFF9C6B3F), Color(0xFF7E5230))),
            plank = Brush.verticalGradient(listOf(Color(0xFFB98552), Color(0xFF8A5A31))),
            label = Color(0xFFF6E6D2),
        )
    }

/** 판 아래 그림자. 판이 벽에서 튀어나와 보이게 한다. */
private val PLANK_SHADOW = Brush.verticalGradient(listOf(Color(0x66000000), Color(0x00000000)))

/**
 * 판 한 칸: 표지들이 판 위에 밑면을 맞춰 선다(높이가 다른 표지도 판에 닿는다). 칸 폭은 격자와 같은 셈 — 보기를 바꿔도 한
 * 줄의 권 수가 같다.
 */
@Composable
internal fun <T> WoodRow(items: List<T>, cell: @Composable (T, Dp) -> Unit) {
    val gutter = CpTheme.metrics.gutter
    val columns = shelfColumns()
    val width = (LocalShelfWidth.current - gutter * 2 - SHELF_GAP * (columns - 1)) / columns
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = gutter, end = gutter, top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(SHELF_GAP),
            verticalAlignment = Alignment.Bottom,
        ) {
            items.forEach { cell(it, width) }
        }
        Box(Modifier.fillMaxWidth().height(14.dp).background(wood().plank))
        Box(Modifier.fillMaxWidth().height(6.dp).background(PLANK_SHADOW))
    }
}

/** 책장 보기면 목록 뒤를 나무 벽으로 칠한다. 다른 보기는 그대로. */
internal fun Modifier.shelfBackground(layout: LibraryLayout, wood: Wood): Modifier =
    if (layout == LibraryLayout.Shelf) background(wood.back) else this
