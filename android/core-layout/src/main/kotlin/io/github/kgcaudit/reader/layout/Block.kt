package io.github.kgcaudit.reader.layout

/**
 * 한 가지 서식이 걸린 글자 구간.
 *
 * 글자를 복사해 담지 않고 챕터 정규화 텍스트의 **구간만** 가리킨다. 텍스트는 한 번만
 * 존재하고([io.github.kgcaudit.reader.layout.Page] 캐시의 `.txt`), 조판·책갈피·검색·
 * 낭독이 모두 같은 좌표계를 쓴다.
 */
data class InlineRun(
    val start: Int,
    val endExclusive: Int,
    val style: TextStyle = TextStyle.Default,
) {
    init {
        require(start >= 0) { "start must be >= 0, was $start" }
        require(endExclusive >= start) { "endExclusive ($endExclusive) < start ($start)" }
    }

    val length: Int get() = endExclusive - start
    val isEmpty: Boolean get() = length == 0
}

/**
 * 조판 단위.
 *
 * 새 종류(표·수식·각주 박스)를 더하려면 하위 클래스 하나와 그것을 그리는 렌더러
 * 하나면 된다 — 조판기와 캐시 포맷은 손대지 않는다.
 */
sealed interface Block {

    /** 블록 위/아래 여백. 페이지 채우기에서 쓴다. */
    val style: BlockStyle

    data class Paragraph(
        val runs: List<InlineRun>,
        override val style: BlockStyle = BlockStyle.Default,
    ) : Block {
        val startChar: Int get() = runs.firstOrNull()?.start ?: 0
        val endChar: Int get() = runs.lastOrNull()?.endExclusive ?: 0
        val isBlank: Boolean get() = runs.all { it.isEmpty }
    }

    /**
     * 그림.
     *
     * @param intrinsicWidth 원본 픽셀 크기. 0 이면 모름(파일을 열어야 알 수 있는 경우).
     *   조판은 모를 때 지면 폭에 맞추는 쪽으로 가정한다.
     */
    data class Image(
        val href: String,
        val intrinsicWidth: Int = 0,
        val intrinsicHeight: Int = 0,
        override val style: BlockStyle = BlockStyle.Default,
    ) : Block

    /** 구분선(`<hr>`). */
    data class Rule(
        override val style: BlockStyle = BlockStyle.Default,
    ) : Block
}
