package io.github.kgcaudit.reader.document.comic

/** 서재에서 작품이 서는 갈래. 책의 읽는 중 · 읽은 책 · 읽을 책과 같다(2026-10-03 사용자 결정 1). */
enum class WorkShelf { READING, FINISHED, TO_READ }

/**
 * 사람이 길게 눌러 옮긴 갈래(0.38.0). 집계가 틀리는 경우를 바로잡는다 — 1권만 들춰 보고 그만둔 작품, 종이책으로 다 읽은 작품.
 * [atEpochMs] 는 옮긴 때: 그 뒤에 일어난 일(다시 펼침 · 새 권)이 표시를 이긴다.
 */
sealed interface ShelfMark {
    val atEpochMs: Long

    /** 다 읽은 작품으로. [volumes] 는 그때의 권 수 — 그보다 늘면(새 권이 들어옴) 다시 읽는 중이 된다. */
    data class Finished(override val atEpochMs: Long, val volumes: Int) : ShelfMark

    /** 다 읽은 작품을 읽는 중으로 되돌림. */
    data class Reading(override val atEpochMs: Long) : ShelfMark

    /** 읽을 작품으로 되돌림. 그 뒤에 한 권이라도 펼치면 다시 집계를 따른다. */
    data class ToRead(override val atEpochMs: Long) : ShelfMark

    companion object {
        /**
         * 저장 글("DONE:at:n" · "READING:at" · "UNREAD:at")을 읽는다. 깨졌으면 null — 표시가 없는 것과 같아 집계를
         * 따른다(규칙 6: 손상된 한 줄 때문에 작품이 서재에서 이상한 갈래에 붙박이지 않게).
         */
        fun parse(raw: String): ShelfMark? {
            val p = raw.split(':')
            val at = p.getOrNull(1)?.toLongOrNull() ?: return null
            return when (p[0]) {
                "DONE" -> p.getOrNull(2)?.toIntOrNull()?.takeIf { it >= 0 && p.size == 3 }?.let { Finished(at, it) }
                "READING" -> Reading(at).takeIf { p.size == 2 }
                "UNREAD" -> ToRead(at).takeIf { p.size == 2 }
                else -> null
            }
        }

        fun format(mark: ShelfMark): String = when (mark) {
            is Finished -> "DONE:${mark.atEpochMs}:${mark.volumes}"
            is Reading -> "READING:${mark.atEpochMs}"
            is ToRead -> "UNREAD:${mark.atEpochMs}"
        }
    }
}

/**
 * 작품 하나의 서재 상태.
 *
 * @param newVolumes 다 읽었던 작품에 그 뒤로 새 권이 들어왔다. 읽는 중 칸에 "새 권" 을 단다 — 달지 않으면 다 읽은 작품이
 *   왜 읽는 중으로 돌아왔는지 알 수 없다.
 * @param lastReadAtEpochMs 가장 최근에 펼친 때. "최근 읽은 순" 의 열쇠. 펼친 적이 없으면 null.
 * @param finishedAtEpochMs 다 읽은 때(다 읽은 작품의 "다 읽음 · 날짜"). 모르면 null.
 */
data class WorkStatus(
    val shelf: WorkShelf,
    val newVolumes: Boolean = false,
    val lastReadAtEpochMs: Long? = null,
    val finishedAtEpochMs: Long? = null,
)

object WorkStatuses {

    /**
     * 권들의 진도를 모아 작품의 갈래를 정한다(Komga 식 집계, 2026-10-03 사용자 결정 2):
     * - 한 권도 펼치지 않음 → 읽을
     * - 모든 권을 다 읽음 → 다 읽은
     * - 그 밖(펼친 권이 있고 남은 권이 있음) → 읽는 중
     *
     * 권마다 갈래를 나누면 한 작품이 세 갈래에 흩어진다(1~3권 다 읽음, 4권 읽는 중, 5권~ 읽을). 같은 권 사본으로 읽은 것도
     * 그 권을 읽은 것이다. 사람이 옮긴 표시([mark])가 집계보다 앞서되, 표시 뒤에 일어난 일(새 권 · 다시 펼침)이 표시를 이긴다.
     */
    fun of(work: Work, progress: Map<String, ComicProgress>, mark: ShelfMark? = work.shelfMark): WorkStatus {
        val perEntry = work.entries.map { e -> (listOf(e.unit) + e.copies).mapNotNull { progress[it.id] } }
        val opened = perEntry.count { it.isNotEmpty() }
        val finishedEntries = perEntry.count { list -> list.any { it.finished } }
        val lastRead = perEntry.flatten().maxOfOrNull { it.updatedAtEpochMs }
        val lastFinish = perEntry.flatten().mapNotNull { it.finishedAtEpochMs }.maxOrNull()
        val all = work.entries.isNotEmpty() && finishedEntries == work.entries.size

        val auto = when {
            opened == 0 -> WorkShelf.TO_READ
            all -> WorkShelf.FINISHED
            else -> WorkShelf.READING
        }
        // 펼치지 않은 권 중 마지막으로 끝낸 때보다 늦게 들어온 것이 있으면 새 권이다. 원래 있던 4권을 아직 안 펼친 것은 새 권이
        // 아니다 — 그건 그냥 다음 권이다.
        val unopened = work.entries.filterIndexed { i, _ -> perEntry[i].isEmpty() }
        fun arrivedAfter(t: Long?) = t != null && unopened.any { e -> (listOf(e.unit) + e.copies).any { (it.addedAtEpochMs ?: Long.MIN_VALUE) > t } }
        val autoNew = auto == WorkShelf.READING && opened == finishedEntries && arrivedAfter(lastFinish)

        val base = WorkStatus(auto, autoNew, lastRead, if (auto == WorkShelf.FINISHED) lastFinish else null)
        return when (mark) {
            null -> base
            is ShelfMark.Finished ->
                if (work.volumeCount <= mark.volumes) {
                    WorkStatus(WorkShelf.FINISHED, false, lastRead, maxOf(mark.atEpochMs, lastFinish ?: Long.MIN_VALUE))
                } else if (auto == WorkShelf.FINISHED) {
                    // 새 권까지 이미 다 읽었다.
                    base
                } else {
                    // 다 읽었다고 옮긴 뒤 새 권이 들어왔다.
                    WorkStatus(WorkShelf.READING, unopened.isNotEmpty(), lastRead)
                }
            is ShelfMark.Reading -> if (auto == WorkShelf.FINISHED) WorkStatus(WorkShelf.READING, false, lastRead) else base.copy(shelf = WorkShelf.READING)
            // 읽을 작품으로 옮긴 뒤 다시 펼쳤으면 집계를 따른다 — 펼친 작품이 읽을 칸에 남아 있으면 이어 볼 길이 사라진다.
            is ShelfMark.ToRead -> if ((lastRead ?: Long.MIN_VALUE) > mark.atEpochMs) base else WorkStatus(WorkShelf.TO_READ, false, lastRead)
        }
    }
}
