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
 * 나무 한 벌. 밝은 테마는 내추럴 라이트 오크, 어두운 테마는 같은 결을 따뜻한 짙은 갈색으로 — 밤에 밝은 원목은 눈부시다.
 *
 * 내추럴 라이트 오크(0.62.0, 사용자 결정 A — 조사: 2026 흐름은 자연 오크 · 밝은 허니 · 따뜻한 블론드, 결이 보이는 낮은 광택).
 * 0.61 까지의 주황 메이플(색상 28°)은 브랜드 클레이(15°)와 가까워 강조색 · 표지가 나무에 묻혔고, 그 뒤 견준 꿀빛 애쉬는 짙은 결 ·
 * 거의 검은 가장자리 그늘 · 짙은 기둥 때문에 회색 기운이 돌아 "깔끔하지 않다" 고 했다. 그래서 모든 색을 같은 따뜻한 색상(약 36°)
 * 에서 밝기만 달리하고, 그늘도 검정이 아니라 호박색으로 옅게 둔다. 밝은 나무 위 칸 이름은 짙은 갈색(흰 글자는 읽히지 않는다).
 * 값은 `docs/design-handoff/README.md` 의 책장 표.
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

private val NATURAL_OAK = Wood(
    Color(0xFFD7AE73), Color(0xFFE8C893), Color(0xFFA97A3E), Color(0x558A5A2A),
    Color(0xFFEBD0A4), Color(0xFFC99B60), Color(0xFFA87A42), Color(0xFF9C6E3A), Color(0xFF3A250F), dark = false,
)
private val NIGHT = Wood(
    Color(0xFF5A3F1E), Color(0xFF7A5630), Color(0xFF3A2610), Color(0xCC1A0F05),
    Color(0xFF8A6A3E), Color(0xFF5E4220), Color(0xFF3E2A14), Color(0xFF3A2612), Color(0xFFF2E3C8), dark = true,
)

@Composable
internal fun wood(): Wood = if (CpTheme.colors.background.luminance() < 0.5f) NIGHT else NATURAL_OAK

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
 * 결 그리기(0.62.0). 결을 선으로 긋지 않고 **나무를 잘라 본다** — 절차적 나무결의 표준 방식(Liu 외 「Procedural wood
 * textures」, 블렌더 · 후디니의 나무 텍스처). 처음 판(선으로 그은 아치)은 "인위적" 이었다.
 * - 나이테는 줄기 둘레의 동심 원통. 판 면이 줄기와 조금 비스듬히 지나가면(위로 갈수록 깊어짐) 원통이 잘린 자리가 위로 선
 *   아치(널결)가 되고, 가장자리로 갈수록 곧은결이 된다. 판이 심을 지나지 않게 깊이를 둔다 — 지나면 닫힌 타원(옹이처럼)이 생겼다.
 * - **통판 한 장**(사용자 결정: 널빤지를 이어 붙인 것이 아니라 통나무로 짠 책장). 줄기 하나에서 넓게 켠 판이라 가운데 큰 아치,
 *   양옆은 곧은결이다. 이음매 · 판마다 밝기 차이는 두지 않는다.
 * - 나무는 곧게 자라지 않는다: 줄기 가운데가 천천히 옆으로 흔들리고, 반지름 · 나이테 간격이 잡음으로 고르지 않다.
 * - 나이테 하나 = 밝은 봄 나무 + 고리 끝의 옅은 여름 나무 띠(사용자 결정: 연하게). 나이테마다 짙기가 다르다.
 * - 섬유: 가로로 촘촘하고 세로로 긴 잡음. 넓은 밝기 물결(빛). 가장자리 그늘은 검정이 아닌 호박색으로 옅게.
 * 씨앗을 고정해 열 때마다 같은 결이다. 한 번 그려 앱 전체에서 쓴다(백그라운드).
 */
internal fun woodTexture(w: Int, h: Int, t: Wood, seed: Long = 11): ImageBitmap {
    val rnd = java.util.Random(seed)
    val noise = Perlin(rnd)
    val early = lerp(t.base, t.light, 0.45f)
    val late = lerp(t.base, t.grain, 0.6f)
    val pith = w * 0.44f
    val depth = 140f
    val tilt = 0.035f
    val spacing = 6f
    val off = rnd.nextFloat() * 100f
    val pixels = IntArray(w * h)
    for (x in 0 until w) {
        for (y in 0 until h) {
            // 그림은 벽 높이(폰에서 약 2,800px)로 세로 2배쯤 늘어난다 — 결을 그만큼 촘촘히 그려 늘어난 뒤 사진의 결 간격이 되게.
            val yy = y * STRETCH
            val dx = x - pith - 40f * noise.fbm(off * 0.7f + yy * 0.00175f, 2.2f, 3)
            val d = depth + (h * STRETCH - yy) * tilt + 30f * noise.fbm(off + yy * 0.0015f, off * 0.3f + 0.5f, 3)
            val r = kotlin.math.sqrt(dx * dx + d * d) + 12f * noise.fbm(off + x * 0.006f, yy * 0.00125f, 3) + 1.6f * noise.fbm(off + x * 0.025f, yy * 0.005f, 2)
            val ring = r / spacing + 3f * noise.fbm(off + r * 0.008f, 7.3f, 3)
            val f = ring - kotlin.math.floor(ring)
            val latewood = ((f - 0.55f) / 0.30f).coerceIn(0f, 1f) * ((1f - f) / 0.10f).coerceIn(0f, 1f)
            val band = 0.55f + 0.45f * noise.fbm(off + kotlin.math.floor(ring) * 0.37f, 3.1f, 1)
            val fiber = noise.fbm(off + x * 0.55f, yy * 0.005f, 2)
            val amount = (latewood.pow(0.8f) * 0.5f * band + fiber * 0.18f + 0.10f + 0.08f * noise.fbm(off + x * 0.005f, yy * 0.001f, 2)).coerceIn(0f, 1f)
            val light = 1f + 0.06f * noise.fbm(x * 0.002f + off * 0.01f, yy * 0.00075f, 2)
            var color = lerp(early, late, amount)
            color = Color((color.red * light).coerceIn(0f, 1f), (color.green * light).coerceIn(0f, 1f), (color.blue * light).coerceIn(0f, 1f))
            pixels[y * w + x] = color.toArgb()
        }
    }
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    bmp.setPixels(pixels, 0, w, 0, 0, w, h)
    val vignette = Paint().apply {
        shader = RadialGradient(
            w / 2f, h * 0.45f, maxOf(w, h) * 0.75f,
            intArrayOf(0x00000000, 0x00000000, t.edge.toArgb()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
    }
    Canvas(bmp).drawRect(0f, 0f, w.toFloat(), h.toFloat(), vignette)
    return bmp.asImageBitmap()
}

/** 결 그림이 벽에 늘어나는 세로 배율(어림). */
private const val STRETCH = 2f

private fun lerp(a: Color, b: Color, t: Float) = Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t)

private fun Float.pow(e: Float): Float = Math.pow(toDouble(), e.toDouble()).toFloat()

/** 펄린 잡음(2차원, -1~1 언저리). 씨앗으로 섞은 순열 — 같은 씨앗이면 같은 결. */
private class Perlin(rnd: java.util.Random) {
    private val perm = IntArray(512)
    private val gx = FloatArray(256)
    private val gy = FloatArray(256)

    init {
        val p = (0 until 256).toMutableList().also { java.util.Collections.shuffle(it, rnd) }
        for (i in 0 until 512) perm[i] = p[i and 255]
        for (i in 0 until 256) {
            val a = rnd.nextFloat() * 6.2831855f
            gx[i] = kotlin.math.cos(a); gy[i] = kotlin.math.sin(a)
        }
    }

    private fun fade(t: Float) = t * t * t * (t * (t * 6 - 15) + 10)

    private fun grad(ix: Int, iy: Int, dx: Float, dy: Float): Float {
        val g = perm[perm[ix] + iy]
        return gx[g] * dx + gy[g] * dy
    }

    fun at(x: Float, y: Float): Float {
        val fx = kotlin.math.floor(x); val fy = kotlin.math.floor(y)
        val xi = fx.toInt() and 255; val yi = fy.toInt() and 255
        val xf = x - fx; val yf = y - fy
        val u = fade(xf); val v = fade(yf)
        val a = grad(xi, yi, xf, yf); val b = grad(xi + 1, yi, xf - 1, yf)
        val c = grad(xi, yi + 1, xf, yf - 1); val d = grad(xi + 1, yi + 1, xf - 1, yf - 1)
        val top = a + (b - a) * u
        return top + ((c + (d - c) * u) - top) * v
    }

    /** 겹친 잡음(옥타브 [octaves]). 큰 물결에 잔물결이 얹힌다. */
    fun fbm(x: Float, y: Float, octaves: Int): Float {
        var sum = 0f; var amp = 1f; var freq = 1f; var norm = 0f
        repeat(octaves) { sum += at(x * freq, y * freq) * amp; norm += amp; amp *= 0.5f; freq *= 2f }
        return sum / norm
    }
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
    // 안쪽 그늘: 밝은 나무에서는 옅게 — 짙은 그늘이 회색 기운을 만들었다.
    val shade = if (w.dark) Color(0x66000000) else Color(0x22000000)
    Box(Modifier.align(Alignment.CenterStart).width(POST).fillMaxHeight().background(Brush.horizontalGradient(listOf(w.post, w.plankLow, shade))))
    Box(Modifier.align(Alignment.CenterEnd).width(POST).fillMaxHeight().background(Brush.horizontalGradient(listOf(shade, w.plankLow, w.post))))
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
                // 짙은 글자(밝은 나무)는 빛 그림자, 밝은 글자(밤)는 짙은 그림자 — 새긴 글자처럼.
                val shadow = if (w.dark) Color(0x88000000) else Color(0x55FFFFFF)
                CpText(label, CpTheme.type.label, shadow, Modifier.offset(y = 1.dp).clearAndSetSemantics {})
                CpText(label, CpTheme.type.label, w.label)
            } else {
                CpText(" ", CpTheme.type.label, Color.Transparent)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(if (w.dark) Color(0x55000000) else w.plankLow.copy(alpha = 0.8f)))
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
    // 밝은 나무에서는 호박색으로 옅게 — 검은 그늘이 회색 기운을 만들었다.
    val colors = if (wood().dark) listOf(Color(0x99000000), Color(0x33000000), Color(0x00000000)) else listOf(Color(0x553A2410), Color(0x1A3A2410), Color(0x003A2410))
    Box(Modifier.align(Alignment.TopStart).fillMaxWidth().height(30.dp).background(Brush.verticalGradient(colors)))
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
