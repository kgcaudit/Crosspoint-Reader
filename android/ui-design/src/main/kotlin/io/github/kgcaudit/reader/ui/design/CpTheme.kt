package io.github.kgcaudit.reader.ui.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kgcaudit.reader.text.R as TextR

/**
 * 색 토큰.
 *
 * CrossPoint 의 5단계 흑백(Clear/White/LightGray/DarkGray/Black)을 그대로 역할로 옮겼다.
 * 화면은 역할 이름만 쓰고 색 값은 모른다 — 그래야 다크 모드가 부품 한 곳에서 끝난다.
 */
@Immutable
data class CpColors(
    val background: Color,
    val surface: Color,
    val text: Color,
    val textMuted: Color,
    val divider: Color,
    val accent: Color,
    val onAccent: Color,
    val selection: Color,
    /** 리더 지면. 화면 배경과 따로 둔다 — 종이색은 UI 배경보다 따뜻해야 오래 읽힌다. */
    val paper: Color,
    val ink: Color,
    val inkMuted: Color,
)

/** 앱 아이콘과 같은 주황. 강조는 이 한 색뿐이다. */
val OloOrange = Color(0xFFD4704C)

val LightColors = CpColors(
    background = Color(0xFFF6F4F1),
    surface = Color(0xFFFFFFFF),
    text = Color(0xFF1E1E1E),
    textMuted = Color(0xFF6E6A66),
    divider = Color(0xFFE3DED8),
    accent = OloOrange,
    onAccent = Color.White,
    selection = Color(0x1FD4704C),
    paper = Color(0xFFFBF9F5),
    ink = Color(0xFF232323),
    inkMuted = Color(0xFF8A857F),
)

val DarkColors = CpColors(
    background = Color(0xFF121212),
    surface = Color(0xFF1C1C1C),
    text = Color(0xFFE8E6E3),
    textMuted = Color(0xFF9C9893),
    divider = Color(0xFF2E2C2A),
    accent = Color(0xFFE08463),
    onAccent = Color(0xFF1A1A1A),
    selection = Color(0x33E08463),
    paper = Color(0xFF151515),
    ink = Color(0xFFD9D6D2),
    inkMuted = Color(0xFF807C77),
)

/** 치수. 원본은 480×800 e-ink 픽셀이라 비율로 옮기고, 터치 타깃은 48dp 아래로 내리지 않는다. */
@Immutable
data class CpMetrics(
    val gutter: Dp = 16.dp,
    val headerHeight: Dp = 64.dp,
    val rowHeight: Dp = 64.dp,
    val touchTarget: Dp = 48.dp,
    val statusBarHeight: Dp = 28.dp,
    val corner: Dp = 14.dp,
)

@Immutable
data class CpType(
    val title: TextStyle,
    val subtitle: TextStyle,
    val body: TextStyle,
    val caption: TextStyle,
    val label: TextStyle,
)

private val Pretendard = FontFamily(
    Font(TextR.font.pretendard_regular, FontWeight.Normal),
    Font(TextR.font.pretendard_bold, FontWeight.Bold),
)

private val DefaultType = CpType(
    title = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    subtitle = TextStyle(fontFamily = Pretendard, fontSize = 13.sp, lineHeight = 18.sp),
    body = TextStyle(fontFamily = Pretendard, fontSize = 16.sp, lineHeight = 22.sp),
    caption = TextStyle(fontFamily = Pretendard, fontSize = 12.sp, lineHeight = 16.sp),
    label = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 18.sp),
)

private val LocalCpColors = staticCompositionLocalOf { LightColors }
private val LocalCpMetrics = staticCompositionLocalOf { CpMetrics() }
private val LocalCpType = staticCompositionLocalOf { DefaultType }

object CpTheme {
    val colors: CpColors @Composable get() = LocalCpColors.current
    val metrics: CpMetrics @Composable get() = LocalCpMetrics.current
    val type: CpType @Composable get() = LocalCpType.current
}

@Composable
fun CpTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalCpColors provides if (dark) DarkColors else LightColors,
        LocalCpMetrics provides CpMetrics(),
        LocalCpType provides DefaultType,
        content = content,
    )
}
