package io.github.kgcaudit.reader.layout

/**
 * 인라인 텍스트의 서식. 측정과 그리기에 영향을 주는 것만 담는다.
 *
 * 크기를 절대값이 아니라 [sizeScale] 로 두는 이유: 사용자가 글자 크기를 바꾸면
 * 본문과 제목이 **같은 비율로** 움직여야 한다. 절대값을 박아 두면 h1 이 본문보다
 * 작아지는 조합이 생긴다.
 */
data class TextStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    /** 기준 글자 크기에 대한 배율. h1 ≈ 1.5, 각주 ≈ 0.85. */
    val sizeScale: Float = 1f,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val vertical: VerticalAlign = VerticalAlign.Baseline,
) {
    companion object {
        val Default: TextStyle = TextStyle()
    }
}

enum class VerticalAlign { Baseline, Superscript, Subscript }

enum class TextAlign {
    /** 글의 방향에 따라 왼쪽 또는 오른쪽. */
    Start,
    End,
    Center,
    Justify,
}

/**
 * 블록(문단·인용·제목)의 서식. 길이는 모두 **em** 단위다.
 *
 * px 이 아니라 em 인 이유: 글자 크기를 바꿀 때 여백이 함께 움직여야 문단 사이 간격이
 * 일정해 보인다. px 로 저장하면 크기를 키울 때 문단이 붙어 버린다. 조판 시점에
 * 기준 크기를 곱해 px 로 바꾼다.
 */
data class BlockStyle(
    val align: TextAlign = TextAlign.Start,
    /** 첫 줄 들여쓰기. 음수면 내어쓰기. */
    val firstLineIndentEm: Float = 0f,
    val marginTopEm: Float = 0f,
    val marginBottomEm: Float = 0f,
    /** 좌우 들여쓰기(인용문·목록). 글의 방향 기준. */
    val indentStartEm: Float = 0f,
    val indentEndEm: Float = 0f,
) {
    companion object {
        val Default: BlockStyle = BlockStyle()
    }
}
