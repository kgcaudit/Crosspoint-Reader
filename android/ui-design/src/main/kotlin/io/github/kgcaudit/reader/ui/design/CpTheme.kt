package io.github.kgcaudit.reader.ui.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    /**
     * 강조색 **글자**(설정 값 · 고른 행의 값 · 팝업 안 링크 · 탭 · "다 읽음"). 채운 단추 · 막대 · 리본은 브랜드 [accent] 그대로.
     * 브랜드 클레이로 글자를 쓰면 팝업(3.72) · 고른 행(3.58)에서 본문 기준 4.5:1 에 못 미쳤다 — 글자에만 한 단계 짙게
     * (다크는 밝게) 둔다. 브랜드 색째 짙게 하면 단추가 다른 OLO 앱과 달라진다(구상안 나를 버린 까닭).
     */
    val accentText: Color,
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
 * EPUB 은 따로 색이 없다 — 계열 FILEKIND 의 EBOOK 은 [document] 색에 책 그림([cpBookGlyph])으로 가른다.
 */
@Immutable
data class CpTiles(
    val folder: Color,
    val document: Color,
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
    accentText = Color(0xFF9A4A2C),
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
    accentText = Color(0xFFF6C4AE),
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
        other = Color(0xFF938A80),
    ),
)

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
    /** 글자 꼬리표 같은 작은 칩(OLO-Design extraSmall). 22dp 높이에 10dp 를 주면 다시 알약이 된다. */
    val cornerChip: Dp = 6.dp,
    val cornerSmall: Dp = 10.dp,
    val cornerMedium: Dp = 14.dp,
    /** 아래에서 올라오는 판(OLO-Design large). 팝업(20dp)보다 한 단 작다 — 화면 폭을 다 쓰는 판이 팝업처럼 부풀어 보이지 않게. */
    val cornerSheet: Dp = 18.dp,
    val cornerDialog: Dp = 20.dp,
    val tileSize: Dp = 40.dp,
    val tileCorner: Dp = 12.dp,
    /**
     * **위계 규칙** — 어떤 목록의 자식 행은 **부모 행의 글자가 시작하는 자리**에서 시작한다(CLAUDE.md "UI 규칙").
     * 나란히 두면 자식이 부모와 같은 급의 선택지로 읽힌다(글꼴 목록에서 넣은 글꼴이 네 번째 선택지로 보였다).
     *
     * 앞머리(아이콘 24dp + 간격 14dp, 동그라미 22dp + 간격 16dp)가 있는 행의 자식: 38dp. 두 앞머리 폭이
     * 같아서 자식의 동그라미 · 아이콘이 부모 글자 아래에 오고 자식의 글자도 한 단 안쪽에서 맞는다.
     */
    val childIndent: Dp = 38.dp,
    /** 앞머리 없이 글자만 있는 행(목차)의 한 단. 부모 글자가 여백에서 시작하므로 한 단씩 이만큼 들인다. */
    val levelIndent: Dp = 16.dp,
)

@Immutable
data class CpType(
    val title: TextStyle,
    val subtitle: TextStyle,
    val body: TextStyle,
    val caption: TextStyle,
    val label: TextStyle,
)

/**
 * 화면 글자는 시스템 글꼴이다. 한때 Pretendard 를 실었지만 본문 글꼴을 시스템으로 돌리면서
 * 뺐다 — UI 만을 위해 수 MB 를 싣는 건 과하고, 삼성 "글꼴 스타일" 을 바꾼 사람에게는 앱만
 * 다른 글꼴로 보이는 게 오히려 어색하다.
 */
private val UiFont = FontFamily.Default

/**
 * 글자 크기. OLO 처럼 **제목만 줄이고** 본문은 Material 크기를 쓴다 — 화면 제목이 너무 크면
 * 확인 팝업이 주변보다 두 배 큰 글자로 뜬다.
 */
private val DefaultType = CpType(
    title = TextStyle(fontFamily = UiFont, fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 25.sp),
    subtitle = TextStyle(fontFamily = UiFont, fontSize = 14.sp, lineHeight = 20.sp),
    body = TextStyle(fontFamily = UiFont, fontSize = 16.sp, lineHeight = 24.sp),
    caption = TextStyle(fontFamily = UiFont, fontSize = 12.sp, lineHeight = 16.sp),
    label = TextStyle(fontFamily = UiFont, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 20.sp),
)

private val LocalCpColors = staticCompositionLocalOf { LightColors }

internal fun CpColors.withPaper(paper: CpPaper?): CpColors =
    if (paper == null) this else copy(paper = paper.paper, ink = paper.ink, inkMuted = paper.inkMuted)
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

/**
 * 리더의 테마: 고른 지면색([PaperTheme])을 깔고, 메뉴도 그 밝기를 따른다. [PaperTheme.System] 이면 [CpTheme]
 * 과 같다.
 *
 * 라이브러리 화면에는 쓰지 않는다 — 지면색은 책을 읽을 때의 선택이다(리디도 뷰어 안에서만 바뀐다).
 */
@Composable
fun CpReaderTheme(theme: PaperTheme, content: @Composable () -> Unit) {
    val paper = theme.paper
    val base = if (paper?.dark ?: isSystemInDarkTheme()) DarkColors else LightColors
    CompositionLocalProvider(
        LocalCpColors provides base.withPaper(paper),
        LocalCpMetrics provides CpMetrics(),
        LocalCpType provides DefaultType,
        content = content,
    )
}

/** WCAG 대비(1..21). */
fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/**
 * [color] 가 [background] 위에서 [min] 대비에 못 미치면 [toward](그 지면의 본문 색) 쪽으로 조금씩 옮겨 기준을 넘긴다.
 * 지면 위 강조색 글자(각주 번호)에 쓴다 — accentText 도 회색 지면에서는 4.35 로 모자랐다(0.29.0). 색조는 남기고
 * 필요한 만큼만 짙게 한다.
 */
fun readableOn(color: Color, background: Color, toward: Color, min: Float = 4.5f): Color {
    var t = 0f
    var c = color
    while (contrastRatio(c, background) < min && t < 1f) {
        t += 0.05f
        c = androidx.compose.ui.graphics.lerp(color, toward, t)
    }
    return c
}
