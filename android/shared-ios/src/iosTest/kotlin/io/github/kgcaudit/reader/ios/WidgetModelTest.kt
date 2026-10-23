package io.github.kgcaudit.reader.ios

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 이어 읽기 위젯이 무엇을 보일지(구상안 47, 안드로이드 `ContinueWidgetTest` 와 같은 규칙). */
class WidgetModelTest {
    private fun book(id: String, readAt: Long, percent: Float = 40f, finishedAt: Long? = null) =
        WidgetCandidate(false, id, id, "저자", percent, readAt, finishedAt)

    private fun comic(id: String, readAt: Long, percent: Float = 40f) = WidgetCandidate(true, id, id, "만화", percent, readAt, null)

    @Test
    fun `the most recent unfinished book is on top and the next ones are listed`() {
        val m = IosWidget.model(listOf(book("a", 10), book("b", 30), book("c", 20), book("d", 5), book("e", 1)), IosWidget.ALL)
        assertEquals("b", m.top?.id)
        // 맨 위 것은 목록에 다시 오르지 않고, 많아야 셋.
        assertEquals(listOf("c", "a", "d"), m.recent.map { it.id })
    }

    @Test
    fun `a book just finished gives way to the one read before it`() {
        // 방금 다 읽은 책을 맨 위에 두면 "이어 읽기" 가 할 일이 없다.
        val m = IosWidget.model(listOf(book("done", 100, percent = 100f), book("next", 50)), IosWidget.ALL)
        assertEquals("next", m.top?.id)
        assertTrue(m.recent.isEmpty())
        // 다 읽은 때와 1분 안에 마지막으로 읽었으면 다 읽고 덮은 것, 한참 뒤에 다시 펼쳤으면 다시 읽는 중.
        assertEquals("next", IosWidget.model(listOf(book("closed", 100_000, finishedAt = 70_000), book("next", 50)), IosWidget.ALL).top?.id)
        assertEquals("again", IosWidget.model(listOf(book("again", 500_000, finishedAt = 70_000), book("next", 50)), IosWidget.ALL).top?.id)
    }

    @Test
    fun `nothing read shows the open app panel`() {
        assertNull(IosWidget.model(emptyList(), IosWidget.ALL).top)
        assertNull(IosWidget.model(listOf(book("done", 1, percent = 99.96f)), IosWidget.ALL).top)
    }

    @Test
    fun `the recent filter never changes the top book`() {
        val all = listOf(comic("c1", 50), book("b1", 40), comic("c2", 30), book("b2", 20))
        val books = IosWidget.model(all, IosWidget.BOOKS)
        assertEquals("c1", books.top?.id)
        assertEquals(listOf("b1", "b2"), books.recent.map { it.id })
        assertEquals(listOf("c2"), IosWidget.model(all, IosWidget.COMICS).recent.map { it.id })
    }

    @Test
    fun `percent is rounded down so an unfinished book never reads 100`() {
        val m = IosWidget.model(listOf(book("a", 1, percent = 99.9f)), IosWidget.ALL)
        assertEquals("99%", m.top?.percentText)
        assertEquals(999, m.top?.progress)
        // 깨진 값(음수 · 100 넘음)은 막대 밖으로 나가지 않는다.
        assertEquals("0%", IosWidget.model(listOf(book("neg", 1, percent = -5f)), IosWidget.ALL).top?.percentText)
    }

    @Test
    fun `a blank byline is dropped`() {
        val m = IosWidget.model(listOf(WidgetCandidate(false, "a", "어린 왕자", "  ", 10f, 1, null)), IosWidget.ALL)
        assertNull(m.top?.byline)
    }
}
