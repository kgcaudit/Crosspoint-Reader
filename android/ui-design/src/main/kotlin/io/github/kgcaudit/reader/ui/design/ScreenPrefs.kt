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
    /** 켜면 음량↓ = 다음 쪽, 음량↑ = 앞 쪽. 기본은 끔 — 켜 두면 읽는 동안 소리를 줄일 수 없다. */
    val volumeKeys: Boolean = false,
    val touch: TouchZones = TouchZones.Default,
    val footer: Footer = Footer(),
    val rotation: ScreenRotation = ScreenRotation.Auto,
)

/**
 * 지면 색. [System] 은 휴대폰의 다크 모드를 따른다(기본).
 *
 * 색을 고르면 메뉴 · 도구줄도 그 밝기를 따른다(검정이면 어두운 메뉴). 지면만 검정이고 메뉴가 흰색이면
 * 가운데를 누를 때마다 눈이 부시다.
 */
enum class PaperTheme(val label: String, internal val paper: CpPaper?) {
    System("시스템", null),
    White("흰색", CpPaper(Color(0xFFFFFFFF), Color(0xFF1D1A16), Color(0xFF6B625A), dark = false)),
    Ivory("아이보리", CpPaper(Color(0xFFF4ECD8), Color(0xFF3A2E22), Color(0xFF6E5F4E), dark = false)),
    Gray("회색", CpPaper(Color(0xFFDAD8D3), Color(0xFF1F1D1A), Color(0xFF55504A), dark = false)),
    Black("검정", CpPaper(Color(0xFF121110), Color(0xFFD9D3C9), Color(0xFF9C948A), dark = true)),
    ;

    /** 견본 동그라미에 칠할 색. [System] 은 null(반반 칠). */
    val swatch: Color? get() = paper?.paper
}

/** 지면 한 벌. [dark] 는 그 위에 뜨는 메뉴를 어두운 판으로 그릴지. */
data class CpPaper(val paper: Color, val ink: Color, val inkMuted: Color, val dark: Boolean)

enum class KeepScreenOn(val label: String) {
    /** 휴대폰의 화면 꺼짐 시간을 따른다. */
    System("시스템"),
    /** 마지막으로 넘긴 뒤 10분. 읽다 잠들어도 밤새 켜져 있지 않다. */
    TenMinutes("10분"),
    Always("항상"),
}

/** 지면을 누를 때 어디가 앞 쪽 · 다음 쪽인가. 가운데 40% 는 언제나 메뉴다. */
enum class TouchZones(val label: String, val description: String) {
    Default("기본", "왼쪽 앞 쪽 · 오른쪽 다음 쪽"),
    Reversed("좌우 바꾸기", "왼쪽 다음 쪽 · 오른쪽 앞 쪽"),
    /** 큰 휴대폰을 한 손으로 들고 엄지가 닿는 쪽만 누르는 사람. 앞 쪽은 밀어서 간다. */
    OneHand("한 손(양쪽 다음)", "어느 쪽을 눌러도 다음 쪽 · 앞 쪽은 밀어서"),
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

/** 하단 정보 한 자리에 보일 것. */
enum class FooterItem(val label: String) {
    None("없음"),
    BookTitle("책 제목"),
    ChapterTitle("장 제목"),
    Clock("시계"),
    Battery("배터리"),
    Page("쪽"),
    Percent("진행률(%)"),
    ChapterLeft("이 장 남은 쪽"),
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
