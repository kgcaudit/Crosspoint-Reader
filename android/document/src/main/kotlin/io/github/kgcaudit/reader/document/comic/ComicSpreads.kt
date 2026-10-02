package io.github.kgcaudit.reader.document.comic

import io.github.kgcaudit.reader.document.image.ImageSize

/**
 * 두 쪽 보기의 짝(0.36.0, docs/COMIC_PLAN.md C3). 쪽 번호 목록들 — 한 판에 한 쪽 또는 두 쪽, 읽는 순서.
 *
 * 규칙(PDF 두쪽보기와 같은 바탕에 만화의 것 하나를 더함):
 * - 표지(0쪽)는 혼자다. 종이 만화책도 표지 다음 쪽부터 양면이 마주 본다.
 * - **가로가 긴 쪽(펼침면 그림)은 혼자다.** 스캔본은 양면에 걸친 큰 그림을 한 장으로 넣는다 — 이것을 옆 쪽과 붙이면
 *   한 판에 세 쪽 폭이 들어가 그림이 작아진다. 혼자 선 뒤에는 짝을 그 다음 쪽부터 다시 센다 — 이어서 세면 뒤의 모든 짝이
 *   한 쪽씩 어긋나 말풍선이 옆 쪽과 엇갈린다.
 * - 끝에 남은 한 쪽은 혼자다.
 * 크기를 모르는 쪽(깨진 머리)은 세로 쪽으로 친다 — 짝을 깨지 않는 편이 덜 어긋난다.
 */
object ComicSpreads {

    fun of(sizes: List<ImageSize?>, coverAlone: Boolean = true): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        var i = 0
        while (i < sizes.size) {
            when {
                (coverAlone && i == 0) || wide(sizes[i]) -> { out += listOf(i); i++ }
                i + 1 < sizes.size && !wide(sizes[i + 1]) -> { out += listOf(i, i + 1); i += 2 }
                else -> { out += listOf(i); i++ }
            }
        }
        return out
    }

    /** [page] 쪽이 든 판의 번호. 범위 밖이면 가장 가까운 판. */
    fun indexOf(spreads: List<List<Int>>, page: Int): Int {
        if (spreads.isEmpty()) return 0
        val i = spreads.indexOfFirst { page in it }
        return if (i >= 0) i else if (page < 0) 0 else spreads.size - 1
    }

    private fun wide(size: ImageSize?): Boolean = size != null && size.width > size.height
}
