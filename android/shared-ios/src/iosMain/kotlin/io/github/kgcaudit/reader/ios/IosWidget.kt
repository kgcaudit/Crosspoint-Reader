package io.github.kgcaudit.reader.ios

/** 위젯 후보 하나: 읽던 책 · 만화 권(안드로이드 `ContinueCandidate`). [readAt] 은 마지막으로 읽은 때(연 때 · 자리를 적은 때 가운데 늦은 것). */
data class WidgetCandidate(
    val comic: Boolean,
    val id: String,
    val title: String,
    val byline: String?,
    /** 0~100. */
    val percent: Float,
    val readAt: Long,
    val finishedAt: Long?,
)

/** 위젯의 한 줄(맨 위 책 또는 목록 줄). [progress] 는 막대(0~1000). */
data class WidgetItem(
    val comic: Boolean,
    val id: String,
    val title: String,
    val byline: String?,
    val percentText: String,
    val progress: Int,
)

/** 위젯에 보일 것. [top] 이 null 이면 읽은 것이 없다 — "OLO eBook 열기". */
data class WidgetModel(val top: WidgetItem?, val recent: List<WidgetItem>)

/**
 * 이어 읽기 위젯(구상안 47, 안드로이드 0.56.0 `continueModel` 과 같은 규칙). 위젯은 앱과 따로 도는 작은 프로그램이라 규칙을 위젯에
 * 두지 않고, 앱이 이것으로 셈한 결과만 함께 쓰는 칸에 적는다.
 */
object IosWidget {
    /** 목록 줄 수(넓은 · 큰 판). */
    const val RECENT_LIMIT = 3

    /** 최근 목록 거르기(위젯 설정): 책 · 만화 / 책만 / 만화만. */
    const val ALL = 0
    const val BOOKS = 1
    const val COMICS = 2

    /** 끝까지 읽음(진도 저장소와 같은 문턱 — 부동소수로 100 이 99.99… 가 되는 것을 받아 준다). */
    private const val FINISHED_PERCENT = 99.95f

    /** 다 읽은 때와 이만큼 안에 마지막으로 읽었으면 "다 읽고 덮은 것" 이다. 넘으면 다시 펼쳐 읽는 중이다. */
    private const val REOPEN_GRACE_MS = 60_000L

    /**
     * 맨 위 = 다 읽지 않은 것 가운데 가장 최근, 목록 = 그다음 최근의 다 읽지 않은 것(많아야 [RECENT_LIMIT], 맨 위 것은 다시 올리지
     * 않음). 방금 다 읽은 책을 맨 위에 두면 "이어 읽기" 가 할 일이 없다. [kind] 거르기는 목록에만 — 맨 위 한 권에는 닿지 않는다.
     */
    fun model(candidates: List<WidgetCandidate>, kind: Int): WidgetModel {
        val open = candidates.filter { !finished(it) }.sortedByDescending { it.readAt }
        val top = open.firstOrNull() ?: return WidgetModel(null, emptyList())
        val recent = open.drop(1).filter {
            when (kind) {
                BOOKS -> !it.comic
                COMICS -> it.comic
                else -> true
            }
        }.take(RECENT_LIMIT)
        return WidgetModel(item(top), recent.map(::item))
    }

    private fun finished(c: WidgetCandidate): Boolean =
        c.percent >= FINISHED_PERCENT || (c.finishedAt != null && c.finishedAt >= c.readAt - REOPEN_GRACE_MS)

    private fun item(c: WidgetCandidate): WidgetItem {
        val p = c.percent.coerceIn(0f, 100f)
        // 내려 센다: 99.6% 를 "100%" 로 올리면 다 읽지 않은 책이 다 읽은 것처럼 보인다.
        return WidgetItem(c.comic, c.id, c.title, c.byline?.takeIf { it.isNotBlank() }, "${p.toInt()}%", (p * 10).toInt())
    }
}
