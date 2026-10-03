package io.github.kgcaudit.reader.document.comic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 작품의 서재 갈래(0.38.0, 2026-10-03 사용자 결정 2): 권 진도 집계 + 사람이 옮긴 표시. */
class WorkStatusTest {

    private fun archive(id: String, name: String, added: Long? = 1L) =
        ComicUnit(id, name, listOf("C"), ComicUnitKind.ARCHIVE, addedAtEpochMs = added)

    private val three = listOf(archive("1", "별 01권.cbz"), archive("2", "별 02권.cbz"), archive("3", "별 03권.cbz"))
    private fun work(units: List<ComicUnit> = three, mark: ShelfMark? = null): Work {
        val key = ComicShelf.group(units).single().key
        return ComicShelf.group(units, ComicOverrides(shelf = mark?.let { mapOf(key to it) }.orEmpty())).single()
    }

    private fun done(at: Long) = ComicProgress(99, 100, at, finishedAtEpochMs = at)
    private fun open(at: Long) = ComicProgress(10, 100, at)

    @Test
    fun `a work nobody opened is to read, one with a volume left is reading, and all done is finished`() {
        assertEquals(WorkShelf.TO_READ, WorkStatuses.of(work(), emptyMap()).shelf)
        // 1권만 들춰 봤어도 읽는 중 — 권마다 나누면 한 작품이 세 갈래에 흩어진다.
        assertEquals(WorkShelf.READING, WorkStatuses.of(work(), mapOf("1" to open(5))).shelf)
        // 1 · 2권을 다 읽었고 3권이 남았다 → 읽는 중.
        assertEquals(WorkShelf.READING, WorkStatuses.of(work(), mapOf("1" to done(5), "2" to done(6))).shelf)
        val all = WorkStatuses.of(work(), mapOf("1" to done(5), "2" to done(6), "3" to done(9)))
        assertEquals(WorkShelf.FINISHED, all.shelf)
        assertEquals(9L, all.finishedAtEpochMs, "다 읽은 날이 마지막 권을 끝낸 날이 아니다")
        assertEquals(9L, all.lastReadAtEpochMs)
    }

    @Test
    fun `a volume read from another copy counts as that volume`() {
        val units = three + archive("3b", "별_03.cbz")
        val s = WorkStatuses.of(work(units), mapOf("1" to done(5), "2" to done(6), "3b" to done(7)))
        assertEquals(WorkShelf.FINISHED, s.shelf, "사본으로 다 읽은 3권을 안 읽은 것으로 셌다")
    }

    @Test
    fun `a new volume after the work was finished brings it back to reading with a tag`() {
        val units = three + archive("4", "별 04권.cbz", added = 100)
        val s = WorkStatuses.of(work(units), mapOf("1" to done(5), "2" to done(6), "3" to done(9)))
        assertEquals(WorkShelf.READING, s.shelf)
        assertTrue(s.newVolumes, "새 권 표시가 없다 — 다 읽은 작품이 왜 돌아왔는지 모른다")
    }

    @Test
    fun `a volume that was already there and simply not opened yet is not new`() {
        // 4권은 처음부터 있었다(1). 3권까지 읽고 4권을 아직 안 펼친 것뿐이다.
        val units = three + archive("4", "별 04권.cbz", added = 1)
        val s = WorkStatuses.of(work(units), mapOf("1" to done(5), "2" to done(6), "3" to done(9)))
        assertEquals(WorkShelf.READING, s.shelf)
        assertFalse(s.newVolumes)
        // 아직 읽는 권이 있으면 새 권 표시를 달지 않는다 — 읽던 권이 먼저다.
        val midway = WorkStatuses.of(work(three + archive("4", "별 04권.cbz", added = 100)), mapOf("1" to done(5), "2" to open(6)))
        assertFalse(midway.newVolumes)
    }

    @Test
    fun `moving a work to finished holds until a new volume arrives`() {
        // 종이책으로 다 읽은 작품: 한 권도 안 펼쳤어도 다 읽은 칸에.
        val marked = WorkStatuses.of(work(mark = ShelfMark.Finished(50, 3)), emptyMap())
        assertEquals(WorkShelf.FINISHED, marked.shelf)
        assertEquals(50L, marked.finishedAtEpochMs)
        // 1권만 들춰 보고 다 읽었다고 옮긴 작품도 다 읽은 칸에 남는다.
        assertEquals(WorkShelf.FINISHED, WorkStatuses.of(work(mark = ShelfMark.Finished(50, 3)), mapOf("1" to open(5))).shelf)
        // 4권이 들어왔다 → 읽는 중 + 새 권.
        val grown = WorkStatuses.of(work(three + archive("4", "별 04권.cbz"), ShelfMark.Finished(50, 3)), emptyMap())
        assertEquals(WorkShelf.READING, grown.shelf)
        assertTrue(grown.newVolumes)
    }

    @Test
    fun `moving back to reading or to read is kept, but opening again after moving to read wins`() {
        val all = mapOf("1" to done(5), "2" to done(6), "3" to done(9))
        assertEquals(WorkShelf.READING, WorkStatuses.of(work(mark = ShelfMark.Reading(20)), all).shelf)
        assertEquals(WorkShelf.TO_READ, WorkStatuses.of(work(mark = ShelfMark.ToRead(20)), mapOf("1" to open(5))).shelf)
        // 읽을 칸으로 옮긴 뒤 다시 펼쳤다 → 읽는 중. 그대로 두면 펼친 작품을 이어 볼 길이 없다.
        assertEquals(WorkShelf.READING, WorkStatuses.of(work(mark = ShelfMark.ToRead(20)), mapOf("1" to open(30))).shelf)
    }

    @Test
    fun `marks survive a round trip and broken marks are ignored`() {
        for (m in listOf(ShelfMark.Finished(123, 4), ShelfMark.Reading(5), ShelfMark.ToRead(0))) {
            assertEquals(m, ShelfMark.parse(ShelfMark.format(m)))
        }
        for (bad in listOf("", "DONE", "DONE:x:3", "DONE:1", "DONE:1:-2", "DONE:1:2:3", "READING:", "READING:1:2", "LATER:1", "UNREAD:abc")) {
            assertNull(ShelfMark.parse(bad), "깨진 표시를 읽었다: $bad")
        }
    }

    @Test
    fun `an empty work is never finished`() {
        val empty = Work("k", "빈 작품", emptyList(), webtoon = false, complete = false, places = emptyList(), rightToLeft = null)
        assertEquals(WorkShelf.TO_READ, WorkStatuses.of(empty, emptyMap()).shelf)
    }
}
