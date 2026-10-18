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

/**
 * 형광펜 색. 이름이 DB 에 그대로 들어간다 — 순서를 바꾸거나 이름을 고치면 저장된 색이 바뀐다.
 * 화면의 실제 색값은 디자인 모듈이 정한다(지면 색에 따라 옅기가 달라서).
 */
enum class HighlightColor { Yellow, Green, Blue, Pink }

/**
 * 형광펜 한 줄(메모가 붙을 수 있다).
 *
 * 자리는 글자 오프셋([Locator.Reflow])으로 둔다. 쪽 번호로 두면 글자 크기를 한 번 바꾸는 순간 칠한 자리가
 * 다른 글자로 미끄러진다. [end] 는 끝 글자 **다음** 자리다(빈 구간이 없게 start < end).
 *
 * [note] 가 null 이면 메모가 없는 것이다. 빈 문자열 메모는 만들지 않는다 — 메모 칸을 비우고 저장하면 메모를
 * 지운 것으로 본다(목록에 빈 메모 상자가 남으면 "무엇을 적었었나" 로 읽힌다).
 *
 * [snippet] 은 칠한 글 그대로다. 목록 · 내보내기가 책을 다시 조판하지 않고 보여 주려고 떠 둔다.
 */
data class Annotation(
    val id: Long,
    val bookId: BookId,
    val start: Locator.Reflow,
    val end: Locator.Reflow,
    val color: HighlightColor,
    val note: String?,
    val snippet: String,
    val createdAtEpochMs: Long,
) {
    init {
        require(start.spine < end.spine || (start.spine == end.spine && start.charOffset < end.charOffset)) {
            "empty or reversed range: $start..$end"
        }
    }

    /** 메모를 고친 사본. 빈칸 · 공백뿐이면 메모를 없앤다. */
    fun withNote(text: String?): Annotation = copy(note = text?.trim()?.takeIf { it.isNotEmpty() })

    companion object {
        const val NO_ID: Long = 0L
    }
}
