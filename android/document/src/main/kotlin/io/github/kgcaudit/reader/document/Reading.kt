package io.github.kgcaudit.reader.document

/**
 * 책갈피 한 개.
 *
 * [snippet]은 목록에서 어디였는지 알아보라고 붙이는 본문 한 토막이다. 리플로우
 * 문서는 페이지 캐시의 텍스트에서 떠 오고, PDF는 텍스트를 뽑지 않으므로 null이다.
 */
data class Bookmark(
    val id: Long,
    val bookId: BookId,
    val locator: Locator,
    val snippet: String? = null,
    val createdAtEpochMs: Long,
) {
    companion object {
        /** DB에 넣기 전(아직 id가 없는) 책갈피에 쓰는 값. */
        const val NO_ID: Long = 0L
    }
}

/**
 * 이어읽기 위치.
 *
 * [percent]는 표시용 파생값이다. 진도의 진짜 원천은 [locator]이며, 퍼센트는
 * 조판이 바뀔 때마다 다시 계산된다. 둘을 반대로 두면(퍼센트를 원천으로) 회전
 * 한 번에 위치가 미끄러진다.
 */
data class ReadingProgress(
    val bookId: BookId,
    val locator: Locator,
    val percent: Float,
    val updatedAtEpochMs: Long,
) {
    init { require(percent in 0f..100f) { "percent must be in 0..100, was $percent" } }
}
