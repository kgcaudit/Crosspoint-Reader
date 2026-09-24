package io.github.kgcaudit.reader.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
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
    val Refresh = line("M19 12 A7 7 0 1 1 16.5 6.6 M19 4 V8 H15")
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
    /** 본문에서 찾기(돋보기). */
    val Search = line("M10.5 4 A6.5 6.5 0 1 0 10.5 17 A6.5 6.5 0 1 0 10.5 4 Z M15.5 15.5 L20 20")
    /** 밝기(해). */
    val Sun = line("M12 8 A4 4 0 1 0 12 16 A4 4 0 1 0 12 8 Z M12 2.5 V5 M12 19 V21.5 M2.5 12 H5 M19 12 H21.5 M5.3 5.3 L7 7 M17 17 L18.7 18.7 M5.3 18.7 L7 17 M17 7 L18.7 5.3")
    /** 독서노트: 줄 쳐진 쪽지 + 접힌 귀(칠한 글 · 메모 · 책갈피를 모은 곳). */
    val Note = line("M5 4 H19 V15 L14 20 H5 Z M14 20 V15 H19 M8.5 8.5 H15.5 M8.5 11.5 H13")
    /** 보기 설정. 큰 가·작은 가 — 글자 크기를 바꾸는 곳이라는 뜻. */
    val TextSize = line("M3 19 L8.5 5 L14 19 M5 14 H12 M15 19 L18 11.5 L21 19 M16.2 16.5 H19.8")

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
