package io.github.kgcaudit.reader.ui.design

import androidx.compose.ui.graphics.Color

/**
 * 조판과 상관없는 보기 설정 — EPUB · TXT 와 PDF 가 **함께** 쓴다.
 *
 * 여기 있는 것은 모두 그리는 방식이나 조작만 바꾼다. 글자 배치를 바꾸는 설정(여백 · 정렬 …)은
 * 리플로우 쪽 `ReaderPrefs` 에 있고 조판 설정에 들어간다(규칙 4). 두 리더가 따로 가지면 PDF 에서 고른
 * 배경이 EPUB 을 열 때 풀린다.
 */
data class ScreenPrefs(
    val theme: PaperTheme = PaperTheme.System,
    /** 화면 밝기 0..1. null 은 시스템 밝기를 따른다(자동 밝기 포함). */
    val brightness: Float? = null,
    val keepScreenOn: KeepScreenOn = KeepScreenOn.System,
    /** 켜면 음량↓ = 다음 쪽, 음량↑ = 앞 쪽. 기본은 끔 — 켜 두면 읽는 동안 소리를 줄일 수 없다(듣는 동안은 음량으로 돌려준다). */
    val volumeKeys: Boolean = false,
    val touch: TouchZones = TouchZones.Default,
    val footer: Footer = Footer(),
    val rotation: ScreenRotation = ScreenRotation.Auto,
    /** 가로에서 두쪽보기. 기본 켬(T1) — 가로 한 쪽은 한 줄이 50자 안팎이라 읽기 힘들다. */
    val twoPagesLandscape: Boolean = true,
    /**
     * 세로에서 두쪽보기. 넓은 화면(태블릿 · 폴더블)에서만 듣는다(T2). 기본 켬(0.30.0) — 폴더블 본 화면 세로(732dp)에
     * 한 쪽을 놓으면 한 줄이 40자를 넘어 줄 끝에서 다음 줄 첫머리를 놓쳤다. 휴대폰에서는 켜 있어도 한 쪽이다.
     */
    val twoPagesPortrait: Boolean = true,
    /** PDF 두쪽보기에서 표지(1쪽)를 따로 한 장으로(T4). 잡지의 양면 기사 · 광고가 제짝으로 맞붙는다. */
    val pdfCoverAlone: Boolean = true,
    /**
     * 쪽 넘김 효과(E7). 기본 말림(0.32.0, 사용자 결정 — 구상안 가안). "없음" 이 기본이던 때는 쪽이 딱딱 끊겨 바뀌어
     * 종이책을 넘기는 맛이 없었다.
     */
    val pageTurn: PageTurn = PageTurn.Curl,
    /** 넘길 때의 소리(0.32.0). 기본 끔 — 다른 전자책 앱도 대개 소리가 없고, 지하철 · 도서관에서 갑자기 나면 곤란하다. */
    val turnSound: TurnSound = TurnSound.Off,
    /** 넘길 때 가볍게 톡(0.32.0). 기본 끔. */
    val turnHaptic: Boolean = false,
    /** 왼쪽 끝을 위아래로 밀어 밝기(E6). 기본 켬(리디와 같다). 옆으로 미는 넘김과는 방향으로 가른다. */
    val brightnessGesture: Boolean = true,
    /** n초마다 다음 쪽(L7). 기본 끔. 듣기가 켜져 있으면 쉰다 — 듣기가 쪽을 따라 넘긴다. */
    val autoTurn: AutoTurn = AutoTurn.Off,
    /**
     * 본문에 형광펜 · 메모 쪽지를 보일지(3-5). 숨겨도 지우지 않는다 — 독서노트에는 그대로 있다. 칠 없이 깨끗한 쪽으로
     * 다시 읽고 싶을 때.
     */
    val showHighlights: Boolean = true,
    /** PDF 쪽 맞춤: 쪽 전체(기본) · 폭. 가로 화면에서 잡지 글자가 작아 폭에 맞춰 위아래로 밀어 읽는다. */
    val pdfFit: PdfFit = PdfFit.Page,
    /** 책 속 그림 · PDF 쪽의 흰 바탕을 지면색에 맞출지(0.24.0). 기본 켬 — 흰 네모가 따로 떠 보이지 않게. */
    val imageBlend: ImageBlend = ImageBlend.Paper,
    /**
     * 웹툰 기둥 폭(넓은 화면의 %, 0.35.0 — 사용자 결정 7). 태블릿 · 가로 화면에서 웹툰을 꽉 채우면 한 컷이 화면보다 커져
     * 한 화면에 말풍선 하나만 보인다. 휴대폰 세로에서는 늘 꽉 채운다.
     */
    val webtoonColumn: Int = DEFAULT_WEBTOON_COLUMN,
) {
    /**
     * 지금 화면에서 두 쪽을 펼칠지. 세로 두쪽은 기기의 가장 짧은 폭이 [WIDE_SCREEN_DP] 이상일 때만 — 휴대폰
     * 세로에 두 쪽을 놓으면 한 쪽이 170dp 남짓이라 한 줄에 여덟 글자다.
     */
    fun twoPages(widthDp: Float, heightDp: Float, smallestWidthDp: Int): Boolean =
        if (widthDp > heightDp) twoPagesLandscape else twoPagesPortrait && smallestWidthDp >= WIDE_SCREEN_DP

    companion object {
        /** 태블릿 · 펼친 폴더블. 안드로이드가 "넓은 화면" 으로 보는 경계(sw600dp)와 같다. */
        const val WIDE_SCREEN_DP = 600

        /** 웹툰 기둥 폭 기본 · 범위(%). 70% 면 폴더블 본 화면에서 휴대폰 한 대 폭쯤이다. */
        const val DEFAULT_WEBTOON_COLUMN = 70
        val WEBTOON_COLUMN_RANGE = 40..100
    }

    /**
     * 웹툰을 그릴 기둥 폭(px). 휴대폰 세로는 꽉 채우고, 가로 · 넓은 화면은 [webtoonColumn]% (사용자 결정 7).
     */
    fun webtoonWidth(viewWidth: Float, viewHeight: Float, smallestWidthDp: Int): Float =
        if (viewWidth > viewHeight || smallestWidthDp >= WIDE_SCREEN_DP) {
            viewWidth * webtoonColumn.coerceIn(WEBTOON_COLUMN_RANGE) / 100f
        } else {
            viewWidth
        }
}

/**
 * 지면 색. [System] 은 휴대폰의 다크 모드를 따른다(기본).
 *
 * 색을 고르면 메뉴 · 도구줄도 그 밝기를 따른다(검정이면 어두운 메뉴). 지면만 검정이고 메뉴가 흰색이면
 * 가운데를 누를 때마다 눈이 부시다.
 */
enum class PaperTheme(val label: String, internal val paper: CpPaper?) {
    // 견본 동그라미 아래 이름이라 "휴대폰 설정" 은 칸을 넘는다(0.28.3). 화면 읽기는 "배경 휴대폰 설정" 으로 듣는다.
    System("휴대폰", null),
    White("흰색", CpPaper(Color(0xFFFFFFFF), Color(0xFF1D1A16), Color(0xFF6B625A), dark = false)),
    Ivory("아이보리", CpPaper(Color(0xFFF4ECD8), Color(0xFF3A2E22), Color(0xFF6E5F4E), dark = false)),
    // 아이보리보다 누런 빛이 한 단계 짙은 종이(0.22.1, 구상안 가안). 본문 대비 10.1 — 나안(#DCC7A1, 8.9)은 낮에 어둡다.
    // 저장은 이름으로 하므로 사이에 끼워도 이미 고른 회색 · 검정이 밀리지 않는다.
    Sepia("세피아", CpPaper(Color(0xFFE9DCC0), Color(0xFF3B2A1A), Color(0xFF6C5842), dark = false)),
    Gray("회색", CpPaper(Color(0xFFDAD8D3), Color(0xFF1F1D1A), Color(0xFF55504A), dark = false)),
    Black("검은색", CpPaper(Color(0xFF121110), Color(0xFFD9D3C9), Color(0xFF9C948A), dark = true)),
    ;

    /** 견본 동그라미에 칠할 색. [System] 은 null(반반 칠). */
    val swatch: Color? get() = paper?.paper
}

/** 지면 한 벌. [dark] 는 그 위에 뜨는 메뉴를 어두운 판으로 그릴지. */
data class CpPaper(val paper: Color, val ink: Color, val inkMuted: Color, val dark: Boolean)

enum class KeepScreenOn(val label: String) {
    /** 휴대폰의 화면 꺼짐 시간을 따른다. */
    System("휴대폰"),
    /** 마지막으로 넘긴 뒤 10분. 읽다 잠들어도 밤새 켜져 있지 않다. */
    TenMinutes("10분"),
    Always("항상"),
}

/** 지면을 누를 때 어디가 앞 쪽 · 다음 쪽인가. 가운데 40% 는 언제나 메뉴다. */
enum class TouchZones(val label: String, val description: String) {
    Default("기본", "왼쪽 앞 쪽 · 오른쪽 다음 쪽"),
    Reversed("좌우 바꾸기", "왼쪽 다음 쪽 · 오른쪽 앞 쪽"),
    /** 큰 휴대폰을 한 손으로 들고 엄지가 닿는 쪽만 누르는 사람. 앞 쪽은 밀어서 간다. */
    OneHand("한 손(양쪽 다음)", "어느 쪽을 눌러도 다음 쪽, 앞 쪽은 밀어서"),
}

enum class TapAction { Previous, Next, Menu, Bookmark }

/**
 * 누른 자리의 동작.
 *
 * 오른쪽 위 모서리([cornerPx] 네모)는 책갈피다(리본이 있는 자리). 그 네모 안에서만 "다음 쪽" 보다 먼저
 * 받는다 — 넓히면 다음 쪽으로 넘기려던 손가락이 책갈피를 꽂는다.
 */
fun TouchZones.actionAt(x: Float, y: Float, width: Float, cornerPx: Float): TapAction {
    if (x >= width - cornerPx && y <= cornerPx) return TapAction.Bookmark
    val left = x < width * 0.3f
    val right = x > width * 0.7f
    return when {
        !left && !right -> TapAction.Menu
        this == TouchZones.OneHand -> TapAction.Next
        this == TouchZones.Reversed -> if (left) TapAction.Next else TapAction.Previous
        else -> if (left) TapAction.Previous else TapAction.Next
    }
}

/** 쪽 넘김 효과. 덮기 · 말림은 0.32.0. */
enum class PageTurn(val label: String) {
    None("없음"),
    Fade("서서히"),
    Slide("밀기"),
    /** 다음 쪽이 오른쪽에서 들어와 지금 쪽을 덮는다. */
    Cover("덮기"),
    /** 종이 모서리가 원통처럼 말려 넘어간다. 끌면 손가락을 따라간다. */
    Curl("말림"),
}

/** 넘김 소리. 앱이 합성한 소리다(android/tools/turn_sounds.py) — 사용 조건을 따질 외부 녹음이 없다. */
enum class TurnSound(val label: String) {
    Off("끔"),
    /** 종이가 스치는 부드러운 바스락, 0.30초. */
    Rustle("사락"),
    /** 휙 + 끝에 종이가 내려앉는 툭, 0.42초. */
    Swish("휙"),
    /** 아주 짧은 톡, 0.09초. */
    Tap("톡"),
}

enum class FooterItem(val label: String) {
    None("없음"),
    BookTitle("책 제목"),
    ChapterTitle("장 제목"),
    Clock("시계"),
    Battery("배터리"),
    Page("쪽"),
    Percent("진행률(%)"),
    ChapterLeft("이 장 남은 쪽"),
    /** 읽는 속도(기기에서 잰다)로 센 남은 시간(E5). 속도를 모르는 동안은 빈칸. */
    ChapterTime("이 장 남은 시간"),
    BookTime("책 남은 시간"),
}

/**
 * 하단 정보 세 자리. 기본값은 0.11.0 까지의 모양(책 제목 · 쪽 · %) 그대로다.
 *
 * 읽는 동안 시스템 바를 숨기므로 시각 · 배터리는 여기서만 보인다 — 그래서 시계를 따로 켜고 끄는 대신 세
 * 자리 중 하나에 고르게 했다.
 */
data class Footer(
    val left: FooterItem = FooterItem.BookTitle,
    val center: FooterItem = FooterItem.Page,
    val right: FooterItem = FooterItem.Percent,
) {
    val summary: String
        get() = listOf(left, center, right).filter { it != FooterItem.None }
            .joinToString(" · ") { if (it == FooterItem.Percent) "%" else it.label }
            .ifEmpty { FooterItem.None.label }
}

/** PDF 쪽을 화면에 맞추는 방식. */
enum class PdfFit(val label: String) {
    /** 쪽 전체가 보인다(두쪽보기 가능). */
    Page("쪽 전체"),
    /** 쪽 폭을 화면 폭에 맞춘다. 한 쪽씩, 위아래로 밀어 본다. */
    Width("폭"),
}

/** 그림 흰 바탕을 어떻게 보일지. 저장은 이름으로 한다. */
enum class ImageBlend(val label: String) {
    Paper("배경색으로"),
    Original("그대로"),
}
