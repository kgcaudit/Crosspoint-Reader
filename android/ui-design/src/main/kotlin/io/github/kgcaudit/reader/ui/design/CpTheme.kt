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
 * 색 토큰 — **OLO 디자인 시스템**을 따른다.
 *
 * 원본은 같은 사용자의 OLO Explorer 앱이다(kgcaudit/Filezilla-Client
 * `docs/OLO-Design-System.md`, `ui/theme/Theme.kt`). 두 앱이 한 식구로 보여야 하므로 값을
 * 바꾸지 않고 옮겼다. 요지:
 * - 따뜻한 **클레이** 브랜드 + **아이보리 웜톤** 중립색. 차가운 회색 위의 따뜻한 강조색은
 *   "실수처럼" 보인다.
 * - 클레이는 #B95B3B. 원래의 #C5613F 는 흰 글자가 4.07:1 로 WCAG AA(4.5:1)에 못 미쳐
 *   5% 어둡게 했다. [ContrastTest] 가 그 값을 붙들고 있다.
 * - 색은 "다르기만 한 색" 과 "뜻이 있는 색" 을 나눈다. 파일 종류 타일색([CpTiles])과
 *   진행바 트랙은 역할 색이 아니라 따로 둔다.
 *
 * CrossPoint 의 5단계 흑백(Clear/White/LightGray/DarkGray/Black)은 역할 이름으로만
 * 남았다. 화면은 역할만 쓰고 값은 모른다 — 다크 모드가 이 파일 한 곳에서 끝난다.
 */
@Immutable
data class CpColors(
    /** 화면 바탕(OLO background). */
    val background: Color,
    /** 바탕 위에 한 겹 올린 판 — 리더 메뉴(OLO surfaceContainerLow). */
    val surface: Color,
    /** 팝업(OLO surfaceContainerHigh — Material 다이얼로그가 쓰는 자리). */
    val dialog: Color,
    val text: Color,
    val textMuted: Color,
    /** 구분선(OLO outlineVariant). */
    val divider: Color,
    /** 테두리 단추의 선(OLO outline). */
    val outline: Color,
    val accent: Color,
    val onAccent: Color,
    /** 고른 행의 바탕(OLO primaryContainer). */
    val accentContainer: Color,
    val onAccentContainer: Color,
    /** 크림슨. 브랜드(클레이)와 **색상**으로 갈라 둔다 — 이웃한 주황빨강이면 삭제 단추가 브랜드처럼 보인다. */
    val error: Color,
    /** 진행바의 빈 부분. 안 보이면 진행바가 "얼마 남았나" 를 말하지 못한다. */
    val progressTrack: Color,
    /** 리더 지면과 글자. 지면은 UI 바탕보다 한 단계 밝게(OLO surfaceBright) 둔다. */
    val paper: Color,
    val ink: Color,
    val inkMuted: Color,
    val tiles: CpTiles,
)

/**
 * 목록 행 타일색. 뜻이 있는 색이라 쓰는 자리에서 고르지 않는다 — OLO Explorer 에서 이
 * 슬레이트가 "문서" 였으면 여기서도 문서다. 흰 글리프를 얹으므로 다크에서는 **더 밝다**.
 */
@Immutable
data class CpTiles(
    val folder: Color,
    val document: Color,
    /** EPUB. Explorer 표에는 EPUB 이 없어, 팔레트의 3차색(틸, Explorer 의 code 타일)을 쓴다. */
    val book: Color,
    val other: Color,
)

private val Clay = Color(0xFFB95B3B)
private val ClayLight = Color(0xFFE8A183)

val LightColors = CpColors(
    background = Color(0xFFF7F4EF),
    surface = Color(0xFFFCFAF6),
    dialog = Color(0xFFEDE8DF),
    text = Color(0xFF1D1A16),
    textMuted = Color(0xFF574D45),
    divider = Color(0xFFD6CCC1),
    outline = Color(0xFF8B7F74),
    accent = Clay,
    onAccent = Color(0xFFFFFFFF),
    accentContainer = Color(0xFFF6E0D6),
    onAccentContainer = Color(0xFF4A1E0C),
    error = Color(0xFFA50E2E),
    progressTrack = Color(0xFFDCD3C6),
    paper = Color(0xFFFBF9F5),
    ink = Color(0xFF1D1A16),
    inkMuted = Color(0xFF574D45),
    tiles = CpTiles(
        folder = Clay,
        document = Color(0xFF55606B),
        book = Color(0xFF3E7F80),
        other = Color(0xFF7A7168),
    ),
)

val DarkColors = CpColors(
    background = Color(0xFF181613),
    surface = Color(0xFF1C1A16),
    dialog = Color(0xFF2B2723),
    text = Color(0xFFE8E3DA),
    textMuted = Color(0xFFCFC5B9),
    divider = Color(0xFF49423A),
    outline = Color(0xFF978C80),
    accent = ClayLight,
    onAccent = Color(0xFF4A1E0C),
    accentContainer = Color(0xFF8A3E22),
    onAccentContainer = Color(0xFFFBE0D4),
    error = Color(0xFFFFB0BE),
    progressTrack = Color(0xFF39434D),
    paper = Color(0xFF181613),
    ink = Color(0xFFE8E3DA),
    inkMuted = Color(0xFFCFC5B9),
    tiles = CpTiles(
        folder = Color(0xFFD1734F),
        document = Color(0xFF6E7A86),
        book = Color(0xFF55A0A1),
        other = Color(0xFF938A80),
    ),
)

/** 브랜드색. 앱 아이콘 바탕과 같은 계열이다. */
val OloClay: Color = Clay

/**
 * 치수. 원본은 480×800 e-ink 픽셀이라 비율로 옮기고, 터치 타깃은 48dp 아래로 내리지 않는다.
 *
 * 모서리는 OLO 의 사다리(6·10·14·18·20dp)를 쓴다. Material 기본(다이얼로그 28dp)보다 한
 * 단계 타이트해서, 팝업이 행·칩과 "다른 앱" 처럼 보이지 않는다.
 */
@Immutable
data class CpMetrics(
    val gutter: Dp = 16.dp,
    val headerHeight: Dp = 64.dp,
    val rowHeight: Dp = 64.dp,
    val touchTarget: Dp = 48.dp,
    val statusBarHeight: Dp = 28.dp,
    val cornerSmall: Dp = 10.dp,
    val cornerMedium: Dp = 14.dp,
    val cornerDialog: Dp = 20.dp,
    val tileSize: Dp = 40.dp,
    val tileCorner: Dp = 12.dp,
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

/**
 * 글자 크기. OLO 처럼 **제목만 줄이고** 본문은 Material 크기를 쓴다 — 화면 제목이 너무 크면
 * 확인 팝업이 주변보다 두 배 큰 글자로 뜬다.
 */
private val DefaultType = CpType(
    title = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 25.sp),
    subtitle = TextStyle(fontFamily = Pretendard, fontSize = 14.sp, lineHeight = 20.sp),
    body = TextStyle(fontFamily = Pretendard, fontSize = 16.sp, lineHeight = 24.sp),
    caption = TextStyle(fontFamily = Pretendard, fontSize = 12.sp, lineHeight = 16.sp),
    label = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 20.sp),
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
