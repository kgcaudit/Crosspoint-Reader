package io.github.kgcaudit.reader.layout

/**
 * 한 가지 서식이 걸린 글자 구간.
 *
 * 글자를 복사해 담지 않고 챕터 정규화 텍스트의 **구간만** 가리킨다. 텍스트는 한 번만
 * 존재하고, 조판·책갈피·검색·낭독이 모두 같은 좌표계를 쓴다.
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
 * 모든 블록이 글자 구간을 갖는다. 문단은 당연하지만 그림과 구분선도 갖는 이유:
 * 페이지마다 "여기서 시작한다"는 글자 오프셋이 있어야 진도와 책갈피가 성립하는데,
 * 그림으로 시작하는 페이지에 그 값이 없으면 그 페이지에 책갈피를 꽂을 수 없다.
 * 파서는 정규화 텍스트에 U+FFFC(객체 대체 문자)를 한 글자 넣어 그림 자리를 표시하므로,
 * 오프셋이 빈틈 없이 단조 증가한다.
 *
 * 새 종류(표·수식·각주 박스)를 더하려면 하위 클래스 하나와 렌더러 하나면 된다 —
 * 조판기와 캐시 포맷은 손대지 않는다.
 */
sealed interface Block {

    val style: BlockStyle

    /** 이 블록이 덮는 챕터 텍스트의 시작. */
    val charStart: Int

    /** 이 블록이 덮는 챕터 텍스트의 끝(제외). */
    val charEndExclusive: Int

    data class Paragraph(
        val runs: List<InlineRun>,
        override val style: BlockStyle = BlockStyle.Default,
    ) : Block {
        override val charStart: Int get() = runs.firstOrNull()?.start ?: 0
        override val charEndExclusive: Int get() = runs.lastOrNull()?.endExclusive ?: 0
        val isBlank: Boolean get() = runs.all { it.isEmpty }
    }

    /**
     * 그림.
     *
     * @param intrinsicWidth 원본 픽셀 크기. 0 이면 모름 — 파서가 채우지 못한 경우다
     *   (안드로이드에서는 `BitmapFactory` 의 `inJustDecodeBounds` 로 싸게 알 수 있다).
     *   모를 때 조판은 지면 폭에 3:4 비율로 자리를 잡는다. 그림이 그보다 납작하면
     *   아래에 빈 공간이 남지만, 넘쳐서 잘리는 것보다 낫다.
     */
    data class Image(
        val href: String,
        override val charStart: Int,
        override val charEndExclusive: Int,
        val intrinsicWidth: Int = 0,
        val intrinsicHeight: Int = 0,
        override val style: BlockStyle = BlockStyle.Default,
    ) : Block {
        val hasIntrinsicSize: Boolean get() = intrinsicWidth > 0 && intrinsicHeight > 0
    }

    /** 구분선(`<hr>`). */
    data class Rule(
        override val charStart: Int,
        override val charEndExclusive: Int,
        override val style: BlockStyle = BlockStyle.Default,
    ) : Block
}
