package io.github.kgcaudit.reader.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp

/**
 * 아이콘. CrossPoint 의 1bit 아이콘을 24dp 선 아이콘으로 다시 그렸다.
 *
 * material-icons 를 쓰지 않는 이유: 그 라이브러리는 아이콘 수천 개를 담아 R8 이
 * 걷어내기 전 빌드를 무겁게 하고, 우리는 열 개 남짓만 쓴다.
 */
object CpIcons {
    val Back = line("M15 5 L8 12 L15 19")
    val Forward = line("M9 5 L16 12 L9 19")
    val Plus = line("M12 5 V19 M5 12 H19")
    /** 새로고침. 계열 메뉴 그림. */
    val Refresh: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_menu_refresh)
    val Folder = line("M3.5 7.5 V18 A1.5 1.5 0 0 0 5 19.5 H19 A1.5 1.5 0 0 0 20.5 18 V9.5 A1.5 1.5 0 0 0 19 8 H11.5 L9.5 5.5 H5 A1.5 1.5 0 0 0 3.5 7 Z")
    val Book = line("M4 5.5 C6.5 4.5 9.5 4.5 12 6 C14.5 4.5 17.5 4.5 20 5.5 V18.5 C17.5 17.5 14.5 17.5 12 19 C9.5 17.5 6.5 17.5 4 18.5 Z M12 6 V19")
    val Text = line("M6 3.5 H14.5 L18.5 7.5 V20.5 H6 Z M14.5 3.5 V7.5 H18.5 M9 11 H15.5 M9 14 H15.5 M9 17 H13")
    val Pdf = line("M6 3.5 H14.5 L18.5 7.5 V20.5 H6 Z M14.5 3.5 V7.5 H18.5 M8.5 16.5 C11 15 13 12 13 10 C13 8.5 11.5 8.5 11.5 10 C11.5 13 14 15.5 16 15.5")
    val Bookmark = line("M7 4 H17 V20 L12 16 L7 20 Z")
    val BookmarkFilled = fill("M7 4 H17 V20 L12 16 L7 20 Z")
    val Toc = line("M8.5 7 H19 M8.5 12 H19 M8.5 17 H19 M5 7 H5.1 M5 12 H5.1 M5 17 H5.1")
    val Minus = line("M5 12 H19")
    val Close = line("M6 6 L18 18 M18 6 L6 18")
    /** 화면 회전: 비스듬히 누운 휴대폰과 둥근 화살표. */
    val Rotate = line("M5 8 H12.5 A1 1 0 0 1 13.5 9 V20 A1 1 0 0 1 12.5 21 H5 A1 1 0 0 1 4 20 V9 A1 1 0 0 1 5 8 Z M14 3.5 A6.5 6.5 0 0 1 20.5 10 M20.5 10 L22.5 8 M20.5 10 L18.5 8")
    // 찾기 · 새로고침 · 격자 · 목록은 계열(OLO-Design `icons/control_symbols.py`, 0.32.3 까지는 menu_symbols.py) 메뉴
    // 그림이다(0.32.3 사용자 결정: 가안, 0.32.4 계열 개정 7c5cfd7 을 그대로 받음).
    // OLO 앱마다 이 넷이 같은 모양이어야 한 식구로 읽힌다. 그림 파일(res/drawable/ic_menu_*)은 생성물을 그대로 넣고
    // 손으로 고치지 않는다 — 계열이 바꾸면 다시 만들어 넣는다. 단색(선 · 채움)이라 [CpIcon] 이 줄 글자색으로 칠한다. 리소스에서
    // 읽으므로 이것들만 @Composable 이다. 정렬 그림은 쓰지 않는다 — 순서 판은 라디오 동그라미가 이미 고른 것을 보여 준다.
    /** 찾기(돋보기). */
    val Search: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_menu_search)
    /** 밝기(해). */
    val Sun = line("M12 8 A4 4 0 1 0 12 16 A4 4 0 1 0 12 8 Z M12 2.5 V5 M12 19 V21.5 M2.5 12 H5 M19 12 H21.5 M5.3 5.3 L7 7 M17 17 L18.7 18.7 M5.3 18.7 L7 17 M17 7 L18.7 5.3")
    /** 독서노트: 줄 쳐진 쪽지 + 접힌 귀(칠한 글 · 메모 · 책갈피를 모은 곳). */
    val Note = line("M5 4 H19 V15 L14 20 H5 Z M14 20 V15 H19 M8.5 8.5 H15.5 M8.5 11.5 H13")
    /** 듣기(헤드폰, L1). */
    val Headphones = line("M4.5 15 V12 A7.5 7.5 0 0 1 19.5 12 V15 M4.5 14 H7.5 V20 H5.5 A1 1 0 0 1 4.5 19 Z M19.5 14 H16.5 V20 H18.5 A1 1 0 0 0 19.5 19 Z")
    val Play = fill("M8 5 L19 12 L8 19 Z")
    val Pause = fill("M7 5 H10 V19 H7 Z M14 5 H17 V19 H14 Z")
    /** 앞 · 다음 문장(듣기). */
    val SkipBack = fill("M6 5 V19 M18 5 L9 12 L18 19 Z")
    val SkipForward = fill("M18 5 V19 M6 5 L15 12 L6 19 Z")
    /** 잠자기 타이머. */
    val Timer = line("M12 8 V13 L15 15 M9.5 3 H14.5 M12 21 A8 8 0 1 0 12 5 A8 8 0 1 0 12 21 Z")
    /** 앱 정보(동그라미 안 i). 판 번호 · 라이선스로 가는 단추. */
    val Info = line("M12 3.5 A8.5 8.5 0 1 0 12 20.5 A8.5 8.5 0 1 0 12 3.5 Z M12 11 V16.5 M12 7.9 V8")
    /**
     * 보기 설정(0.29.0): 조절 막대 셋. EPUB 은 "큰 가 · 작은 가", PDF 는 회전 그림이라 같은 자리 같은 이름의 단추가 책
     * 종류에 따라 다른 단추로 보였다. PDF 에는 글자 크기가 없어 글자 그림으로 맞출 수 없다.
     */
    val View = line("M4 7 H20 M4 12 H20 M4 17 H20 M9 5 V9 M15 10 V14 M7.5 15 V19")
    /** 더 보기(⋮). 글자 "⋮" 로 그리면 삼성 글꼴 스타일에 따라 모양 · 굵기가 바뀌었다. */
    val More = fill("M10.4 5.5 A1.6 1.6 0 1 0 13.6 5.5 A1.6 1.6 0 1 0 10.4 5.5 Z M10.4 12 A1.6 1.6 0 1 0 13.6 12 A1.6 1.6 0 1 0 10.4 12 Z M10.4 18.5 A1.6 1.6 0 1 0 13.6 18.5 A1.6 1.6 0 1 0 10.4 18.5 Z")
    /** 펼치기(▾). */
    val ChevronDown = line("M7 10 L12 15 L17 10")

    /** 격자로 보기. 계열 메뉴 그림. */
    val Grid: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_menu_view_grid)
    /** 목록으로 보기. 계열 메뉴 그림. */
    val Rows: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_menu_view_list)
    /**
     * 책장으로 보기(0.39.0): 선반 위에 선 책 둘과 기댄 책 하나. 계열 메뉴 그림에는 책장이 없다 — 목록 그림(줄 셋)을
     * 빌려 쓰면 옆의 목록 단추와 구별되지 않는다.
     */
    val Shelf = line("M5 5 V17.5 M5 5 H8.5 V17.5 M10.5 7 V17.5 M10.5 7 H13.5 V17.5 M15.5 8.5 L19 16.5 M3 20 H21")


    private fun line(d: String): ImageVector = ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).addPath(
        pathData = PathParser().parsePathString(d).toNodes(),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ).build()

    private fun fill(d: String): ImageVector = ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).addPath(
        pathData = PathParser().parsePathString(d).toNodes(),
        fill = SolidColor(Color.Black),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.8f,
        strokeLineJoin = StrokeJoin.Round,
    ).build()
}
