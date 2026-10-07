package io.github.kgcaudit.reader.document.comic

import io.github.kgcaudit.reader.document.image.ImageSize

/**
 * 펼침면 나누기(0.50.0, 사용자 결정 3 권고안). 스캔 만화에는 두 쪽이 한 장(가로로 긴 그림)에 든 것이 흔해, 휴대폰 세로 한 쪽
 * 보기에서는 화면 위에 작게 선다. 그런 쪽을 반으로 나눠 차례로 넘긴다 — 왼→오 책은 왼쪽 반이, 오→왼 책은 오른쪽 반이 먼저.
 *
 * 화면이 보이는 단위(조각)와 책의 쪽은 다르다. 읽던 자리 · 책갈피는 언제나 책의 쪽으로 적는다 — 조각 번호로 적으면 나누기를
 * 켜고 끌 때마다 자리가 밀린다(규칙 3 과 같은 이유).
 */
object SpreadSplit {

    enum class Half { WHOLE, LEFT, RIGHT }

    /** 화면에 한 번에 보이는 조각: 책의 [page] 쪽 전체 또는 그 반. */
    data class Part(val page: Int, val half: Half)

    /**
     * 가로가 세로의 이만큼보다 길면 펼침면이다. 1 에 가깝게 두면 조금 넓은 표지 · 정사각 그림까지 반으로 잘린다 — 스캔 펼침면은
     * 1.3~1.5 이다.
     */
    const val WIDE: Float = 1.15f

    fun isWide(size: ImageSize?): Boolean = size != null && size.width > size.height * WIDE

    /** 쪽 크기들로 조각을 만든다. 크기를 모르는 쪽(깨짐 · 아직 모름)은 나누지 않는다. */
    fun parts(sizes: List<ImageSize?>, rightToLeft: Boolean): List<Part> = buildList {
        sizes.forEachIndexed { page, size ->
            if (!isWide(size)) {
                add(Part(page, Half.WHOLE))
            } else if (rightToLeft) {
                add(Part(page, Half.RIGHT)); add(Part(page, Half.LEFT))
            } else {
                add(Part(page, Half.LEFT)); add(Part(page, Half.RIGHT))
            }
        }
    }

    /** 나누지 않을 때의 조각(쪽 하나에 조각 하나). */
    fun whole(count: Int): List<Part> = List(count) { Part(it, Half.WHOLE) }

    /** 책의 [page] 쪽이 시작하는 조각. 없으면 가장 가까운 앞 조각. */
    fun partOf(parts: List<Part>, page: Int): Int =
        parts.indexOfFirst { it.page >= page }.let { if (it < 0) parts.lastIndex.coerceAtLeast(0) else it }

    /** 조각이 보이는 쪽의 크기(반이면 폭이 반). */
    fun sizeOf(part: Part, size: ImageSize?): ImageSize? = when {
        size == null -> null
        part.half == Half.WHOLE -> size
        else -> ImageSize((size.width / 2).coerceAtLeast(1), size.height)
    }
}
