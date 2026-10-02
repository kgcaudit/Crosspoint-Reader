package io.github.kgcaudit.reader.document.comic

/**
 * 한 권(단위)의 읽은 자리. 자리는 쪽 번호다 — 만화 쪽은 글자처럼 다시 짜이지 않아 글자 크기를 바꿔도 미끄러지지 않는다.
 *
 * @param page 0부터.
 * @param pageCount 저장할 때의 쪽 수. 진도 막대를 그 권을 열지 않고 그린다.
 * @param finishedAtEpochMs 다 읽은 때. null 은 "아직 다 읽지 않음"(규칙 5 — 0 과 다르다).
 */
data class ComicProgress(
    val page: Int,
    val pageCount: Int,
    val updatedAtEpochMs: Long,
    val finishedAtEpochMs: Long? = null,
    /**
     * 웹툰에서 그 그림 안의 비율(0..1, 0.35.0). null 은 "쪽 넘김으로 읽음 · 모름". 화면 폭이 바뀌어도(회전 · 기둥 폭) 같은
     * 칸으로 돌아오도록 픽셀이 아니라 비율로 둔다.
     */
    val offset: Float? = null,
) {
    val finished: Boolean get() = finishedAtEpochMs != null

    /** 0..1. 마지막 쪽에 닿으면 1. */
    val fraction: Float get() = when {
        // 웹툰: 그림 수로 나눈 자리 + 그 그림 안의 비율. 한 화가 긴 그림 몇 장뿐이라 그림 번호만으로는 막대가 듬성듬성 뛴다.
        offset != null && pageCount > 0 -> ((page + offset.coerceIn(0f, 1f)) / pageCount).coerceIn(0f, 1f)
        pageCount <= 1 -> if (finished) 1f else 0f
        else -> (page.toFloat() / (pageCount - 1)).coerceIn(0f, 1f)
    }
}

/** 만화를 읽는 동안의 순수 규칙들 — 방향 · 다음 권 · 이어 볼 권. */
object ComicReading {

    /**
     * 화면 쪽(누른 곳 · 미는 방향)을 읽는 쪽으로 바꾼다. [screenNext] 는 "화면 오른쪽 · 왼쪽으로 밀기" — 왼→오 책의 다음.
     * 오→왼 책은 뒤집는다: 왼쪽을 누르면 다음 쪽이다. 뒤집지 않으면 일본 만화에서 다음 쪽을 보려고 누른 곳이 앞 쪽으로
     * 가, 사용자가 가장 헷갈려한다(계획안 §2).
     */
    fun forward(screenNext: Boolean, rightToLeft: Boolean): Boolean = screenNext != rightToLeft

    /**
     * 넘기는 방향. 정한 순서: 이 작품에 사람이 고른 값 → ComicInfo(Manga=YesAndRightToLeft) → 왼→오.
     * [Work.rightToLeft] 가 앞의 둘을 이미 합쳐 둔다(null 은 "아무도 정하지 않음").
     */
    fun rightToLeft(work: Work?): Boolean = work?.rightToLeft ?: false

    /** 작품 안에서 [unitId] 다음에 볼 줄. 마지막이거나 작품에 없으면 null. 같은 권 사본으로 읽고 있어도 찾는다. */
    fun nextAfter(work: Work, unitId: String): WorkEntry? {
        val i = indexOf(work, unitId)
        return if (i < 0) null else work.entries.getOrNull(i + 1)
    }

    /** [unitId] 가 든 줄. */
    fun entryOf(work: Work, unitId: String): WorkEntry? = work.entries.getOrNull(indexOf(work, unitId))

    private fun indexOf(work: Work, unitId: String): Int =
        work.entries.indexOfFirst { e -> e.unit.id == unitId || e.copies.any { it.id == unitId } }

    /**
     * "이어 보기" 할 줄: 가장 최근에 펼친 권. 그 권을 다 읽었으면 다음 권(없으면 그 권 그대로 — 다시 볼 수 있게).
     * 아무것도 펼치지 않았으면 null — 처음부터라면 이어 보기가 아니다.
     */
    fun resume(work: Work, progress: Map<String, ComicProgress>): Pair<WorkEntry, ComicProgress?>? {
        val latest = work.entries.flatMap { e -> (listOf(e.unit) + e.copies).mapNotNull { u -> progress[u.id]?.let { e to it } } }
            .maxByOrNull { it.second.updatedAtEpochMs } ?: return null
        val (entry, p) = latest
        if (!p.finished) return entry to p
        val next = nextAfter(work, entry.unit.id) ?: return entry to p
        return next to progress[next.unit.id]
    }
}
