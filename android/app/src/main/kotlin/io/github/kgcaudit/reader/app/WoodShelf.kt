package io.github.kgcaudit.reader.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.kgcaudit.reader.ui.design.COVER_ASPECT
import io.github.kgcaudit.reader.ui.design.CpText
import io.github.kgcaudit.reader.ui.design.CpTheme
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * 책장 보기(0.39.0, 다시 그림 0.44.0 — docs/CONSISTENCY_PLAN.md, 사용자 참고 그림의 원목 책장 · 0.62.0 꿀빛 애쉬와 널빤지 결). 뒷벽은 원목 결, 양옆에 기둥,
 * 칸마다 위에 칸막이(판)를 두고 그 앞면에 **그 칸의 이름**을 새긴다(사용자 정정: 이름이 책 아래에 있으면 어느 칸 것인지
 * 헷갈린다). 이름 · 첫 표지는 격자 · 목록 보기와 같은 시작선(gutter) — 보기를 바꿔도 글자가 옆으로 움직이지 않는다.
 * 책이 적어도 빈 판을 화면 끝까지 잇는다.
 *
 * 표지만 판 위에 세운다. 제목 · 진도는 격자 · 목록 보기에 있다 — 글자를 얹으면 판 사이가 벌어져 책장으로 읽히지 않는다.
 */

/**
 * 나무 한 벌. 밝은 테마는 꿀빛 애쉬, 어두운 테마는 같은 결을 낮춘 색 — 밤에 밝은 원목은 눈부시다.
 *
 * 꿀빛 애쉬(0.62.0, 사용자 결정 A — 사용자가 보낸 원목 사진에서 뽑은 금빛 꿀색, 색상 약 38°). 0.61 까지의 주황 메이플(28°)은
 * 브랜드 클레이(#B95B3B, 15°)와 색상이 가까워 강조색 · 표지가 나무에 묻혔다. 테라코타 · 클레이에는 허니 · 내추럴 오크가 흔한
 * 짝이다(조사). 값은 `docs/design-handoff/README.md` 의 책장 표.
 */
internal class Wood(
    val base: Color,
    val light: Color,
    val grain: Color,
    val edge: Color,
    val plankTop: Color,
    val plankFront: Color,
    val plankLow: Color,
    val post: Color,
    val label: Color,
    val dark: Boolean,
)

private val HONEY_ASH = Wood(
    Color(0xFFB98F4E), Color(0xFFD4AE6E), Color(0xFF6E4E1E), Color(0xCC3E2A0C),
    Color(0xFFD8B57A), Color(0xFFA67D3E), Color(0xFF6E4E22), Color(0xFF5C3F18), Color(0xFFFFF3DC), dark = false,
)
private val NIGHT = Wood(
    Color(0xFF4A361A), Color(0xFF634A26), Color(0xFF22170A), Color(0xDD0E0904),
    Color(0xFF7A5C32), Color(0xFF4E3818), Color(0xFF2E2010), Color(0xFF22180A), Color(0xFFEADBC2), dark = true,
)

@Composable
internal fun wood(): Wood = if (CpTheme.colors.background.luminance() < 0.5f) NIGHT else HONEY_ASH

/**
 * 판은 얇게(윗면 4 + 빛 1 + 이름 한 줄 20 + 아랫선 1 = 26dp, 2026-10-04 사용자 결정 — 45dp 는 투박했다). 칸 이름 글자만 격자
 * 보기([ShelfLabel] 위 18)와 같은 높이에 둔다: 맨 위 테두리 12 + 그늘선 1 + 윗면 4 + 빛 1 = 18. 테두리가 없으면 얇아진 만큼
 * 이름이 위로 올라가 격자 ↔ 책장을 바꿀 때 글자가 튄다. 표지 높이는 맞추지 않는다(판이 얇아 격자와 2dp 다르다).
 */
private val CROWN = 12.dp
private val BOARD_TOP = 4.dp
private val ROW_TOP = 12.dp
private val ROW_BOTTOM = 0.dp
/** 빈 판 수를 셀 때 쓰는 판 높이(윗면 4 + 빛 1 + 이름 줄 20 + 아랫선 1). 실제 높이는 글자 크기를 따른다. */
private val BOARD = 26.dp

/**
 * 원목 결 그림(뒷벽). 화면 크기와 상관없이 한 장을 그려 늘려 쓴다 — 결이 세로라 늘려도 티가 나지 않는다. 테마마다 한 번만
 * 그리고(앱 전체에서 둔다), 그리는 동안은 바탕색만 칠한다. 화면 스레드에서 그리면 책장으로 바꿀 때 한 박자 멈춘다.
 */
@Composable
private fun rememberWoodTexture(wood: Wood): ImageBitmap? {
    var texture by androidx.compose.runtime.remember(wood) { androidx.compose.runtime.mutableStateOf(TEXTURES[wood.dark]) }
    androidx.compose.runtime.LaunchedEffect(wood) {
        if (texture == null) {
            texture = withContext(Dispatchers.Default) { woodTexture(TEXTURE_W, TEXTURE_H, wood) }.also { TEXTURES[wood.dark] = it }
        }
    }
    return texture
}

private val TEXTURES = java.util.concurrent.ConcurrentHashMap<Boolean, ImageBitmap>()
private const val TEXTURE_W = 600
private const val TEXTURE_H = 1400

/**
 * 결 그리기(0.62.0, 사용자 결정: 새 결 — 참고 원목 사진). 한 장짜리 결은 단조로웠다. 이제 사진처럼:
 * - 널빤지 여러 장: 폭(화면의 17~29%)과 밝기가 판마다 조금씩 다르고, 판 사이에 이음매(그늘 한 줄 + 빛 한 줄).
 * - 판마다 곧은결: 가는 줄이 길게 조금씩 흔들린다.
 * - 불꽃결(널결): 넓은 띠(여름 나무)와 그 가장자리의 짙은 선이 겹친 아치 — 판 폭 대부분을 덮는다. 판마다 한두 개.
 * 가장자리는 어둡게(그늘). 씨앗을 고정해 열 때마다 같은 결이다.
 */
internal fun woodTexture(w: Int, h: Int, t: Wood, seed: Long = 11): ImageBitmap {
    val rnd = java.util.Random(seed)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawColor(t.base.toArgb())
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    val fill = Paint()
    var x = 0f
    var board = 0
    while (x < w) {
        val bw = (w * (0.17f + rnd.nextFloat() * 0.12f)).coerceAtMost(w - x)
        fill.color = (if (rnd.nextBoolean()) t.light else t.grain).toArgb()
        fill.alpha = 18 + rnd.nextInt(34)
        c.drawRect(x, 0f, x + bw, h.toFloat(), fill)
        c.save()
        c.clipRect(x, 0f, x + bw, h.toFloat())
        repeat((bw / 3).toInt()) {
            val lx = x + rnd.nextFloat() * bw
            val ph = rnd.nextDouble() * 6.28
            val amp = 1.0 + rnd.nextDouble() * 2.5
            val path = Path().apply {
                moveTo(lx, -10f)
                var y = -10f
                while (y < h + 10) { y += 24f; lineTo(lx + (sin(y * 0.004 + ph) * amp).toFloat(), y) }
            }
            p.color = t.grain.toArgb(); p.alpha = 25 + rnd.nextInt(55); p.strokeWidth = 0.6f + rnd.nextFloat() * 1.6f
            c.drawPath(path, p)
        }
        repeat(if (rnd.nextFloat() < 0.7f) 2 else 1) { k ->
            val cx = x + bw * (0.35f + rnd.nextFloat() * 0.3f)
            val top = h * (k * 0.5f + rnd.nextFloat() * 0.25f)
            val rings = 7 + rnd.nextInt(5)
            var gap = 0f
            for (j in 0 until rings) {
                gap += 22f + rnd.nextFloat() * 26f
                val half = 8f + gap * 0.55f
                val apex = top + gap * 0.9f
                val bottom = apex + 700f + gap
                val wob = (rnd.nextFloat() - 0.5f) * 14f
                val path = Path().apply {
                    moveTo(cx - half * 2.4f + wob, bottom)
                    cubicTo(cx - half * 2.0f, apex + 220f, cx - half * 0.7f, apex + 6f, cx, apex)
                    cubicTo(cx + half * 0.7f, apex + 6f, cx + half * 2.0f, apex + 220f, cx + half * 2.4f - wob, bottom)
                }
                p.color = t.grain.toArgb(); p.alpha = 34 + rnd.nextInt(26); p.strokeWidth = 9f + rnd.nextFloat() * 8f
                c.drawPath(path, p)
                p.alpha = 80 + rnd.nextInt(50); p.strokeWidth = 1.4f + rnd.nextFloat() * 1.2f
                c.drawPath(path, p)
                p.color = t.light.toArgb(); p.alpha = 45; p.strokeWidth = 5f
                c.save(); c.translate(0f, 7f); c.drawPath(path, p); c.restore()
            }
        }
        c.restore()
        if (board > 0) {
            fill.color = 0x55000000; c.drawRect(x, 0f, x + 1.5f, h.toFloat(), fill)
            fill.color = 0x22FFFFFF; c.drawRect(x + 1.5f, 0f, x + 2.5f, h.toFloat(), fill)
        }
        x += bw
        board++
    }
    val vignette = Paint().apply {
        shader = RadialGradient(
            w / 2f, h * 0.45f, maxOf(w, h) * 0.75f,
            intArrayOf(0x00000000, 0x00000000, t.edge.toArgb()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
    }
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), vignette)
    return bmp.asImageBitmap()
}

/**
 * 책장 보기면 [content](목록) 뒤에 원목 뒷벽, 위에 양옆 기둥을 둔다. 다른 보기는 그대로. 뒷벽은 목록과 함께 굴러가지 않는다 —
 * 벽은 서 있고 판과 책이 지나간다.
 */
@Composable
internal fun ShelfWall(layout: LibraryLayout, content: @Composable () -> Unit) {
    if (layout != LibraryLayout.Shelf) return content()
    val w = wood()
    Box(Modifier.fillMaxSize().background(w.base).clipToBounds()) {
        rememberWoodTexture(w)?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
        content()
        Posts(w)
    }
}

/** 양옆 기둥: 짙은 나무 + 안쪽 그늘. 표지 · 글자는 gutter(16dp) 안쪽에서 시작해 기둥(12dp)에 가리지 않는다. */
@Composable
private fun BoxScope.Posts(w: Wood) {
    Box(Modifier.align(Alignment.CenterStart).width(POST).fillMaxHeight().background(Brush.horizontalGradient(listOf(w.post, w.plankLow, Color(0x66000000)))))
    Box(Modifier.align(Alignment.CenterEnd).width(POST).fillMaxHeight().background(Brush.horizontalGradient(listOf(Color(0x66000000), w.plankLow, w.post))))
}

private val POST = 12.dp

/**
 * 칸막이(판) 하나. [label] 은 이 판 **아래** 칸의 이름 — 판 앞면에 새긴다(밝은 글자 + 아래 1dp 그늘). 판 밑은 그늘이 져서
 * 아래 칸 뒷벽 위쪽이 어둡다.
 */
@Composable
internal fun ShelfBoard(label: String?) {
    val w = wood()
    Column(Modifier.fillMaxWidth()) {
        // 윗면: 벽 쪽이 어둡고 앞이 밝다 — 위에서 비춘 빛.
        Box(Modifier.fillMaxWidth().height(BOARD_TOP).background(Brush.verticalGradient(listOf(w.plankLow.copy(alpha = 0.9f), w.plankTop))))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x66FFF0D8)))
        // 앞면 높이는 이름 한 줄이 정한다 — 고정 높이에 가운데 맞추면 글자 크기에 따라 이름이 격자와 다른 높이에 선다.
        // 이름 없는 판도 보이지 않는 한 줄로 같은 두께를 지킨다(판마다 두께가 다르면 책장이 삐뚤어 보인다).
        Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(w.plankFront, w.plankLow))).padding(horizontal = CpTheme.metrics.gutter)) {
            if (label != null) {
                // 그림자 글자는 보이기만 한다 — 의미에 남기면 화면 읽기가 칸 이름을 두 번 읽는다.
                CpText(label, CpTheme.type.label, Color(0x88000000), Modifier.offset(y = 1.dp).clearAndSetSemantics {})
                CpText(label, CpTheme.type.label, w.label)
            } else {
                CpText(" ", CpTheme.type.label, Color.Transparent)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x55000000)))
    }
}

/**
 * 책장 맨 위 테두리: 양옆 기둥과 같은 짙은 나무로 책장 틀을 닫는다(2026-10-04 사용자 결정 나안). 목록 맨 앞에 한 번만 —
 * 높이는 그 아래 첫 판의 이름이 격자 이름과 같은 높이에 서게 하는 몫이다([CROWN]).
 */
@Composable
internal fun ShelfCrown() {
    val w = wood()
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(CROWN).background(Brush.verticalGradient(listOf(w.post, w.plankLow))))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x55000000)))
    }
}

/** 판 밑그늘: 아래 칸 뒷벽 위쪽. 칸의 높이를 차지하지 않고 칸 위에 겹친다 — 표지 윗부분에도 그늘이 진다. */
@Composable
private fun BoxScope.UnderBoardShadow() {
    Box(Modifier.align(Alignment.TopStart).fillMaxWidth().height(30.dp).background(Brush.verticalGradient(listOf(Color(0x99000000), Color(0x33000000), Color(0x00000000)))))
}

/** 판 사이의 한 줄: 표지들이 밑면을 맞춰 선다. 칸 폭은 격자와 같은 셈 — 보기를 바꿔도 한 줄의 권 수가 같다. */
@Composable
internal fun <T> WoodRow(items: List<T>, cell: @Composable (T, Dp) -> Unit) {
    val gutter = CpTheme.metrics.gutter
    val width = shelfCellWidth()
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = gutter, end = gutter, top = ROW_TOP, bottom = ROW_BOTTOM),
            horizontalArrangement = Arrangement.spacedBy(SHELF_GAP),
            verticalAlignment = Alignment.Bottom,
        ) {
            items.forEach { item ->
                Box(Modifier.width(width)) {
                    // 표지 그림자는 표지가 제 그림 크기로 깐다(LocalCoverShadow) — 칸 크기로 깔면 낮은 · 좁은 표지 둘레에 빈 그림자가 뜬다.
                    androidx.compose.runtime.CompositionLocalProvider(io.github.kgcaudit.reader.ui.design.LocalCoverShadow provides true) {
                        cell(item, width)
                    }
                }
            }
        }
        UnderBoardShadow()
    }
}

@Composable
private fun shelfCellWidth(): Dp {
    val gutter = CpTheme.metrics.gutter
    val columns = shelfColumns()
    return (LocalShelfWidth.current - gutter * 2 - SHELF_GAP * (columns - 1)) / columns
}

/**
 * 마지막 칸 아래: 판 하나와 빈 칸들을 화면 끝까지. 책이 적어도 책장 모양이 남는다(참고 그림). [minHeight] 는 목록이 보이는
 * 높이 — 그만큼 채운다.
 */
@Composable
internal fun EmptyShelves(minHeight: Dp) {
    val row = shelfCellWidth() / COVER_ASPECT + ROW_TOP + ROW_BOTTOM
    val count = (minHeight / (row + BOARD)).toInt().coerceAtLeast(1) + 1
    Column(Modifier.fillMaxWidth()) {
        repeat(count) {
            ShelfBoard(null)
            Box(Modifier.fillMaxWidth().height(row)) { UnderBoardShadow() }
        }
    }
}
