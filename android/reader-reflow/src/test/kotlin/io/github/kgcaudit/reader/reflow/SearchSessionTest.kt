package io.github.kgcaudit.reader.reflow

import io.github.kgcaudit.reader.layout.book.SearchHit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 책 안 찾기의 상태(E2 · E3). 장 수 · 장 하나 찾기를 가짜로 주고, 결과가 바로 들어오게 Unconfined 에서 돌린다. */
class SearchSessionTest {

    private fun found(spine: Int) = Found(SearchHit(spine, 0, 2, "보아", 0), percent = spine * 10f)

    @Test
    fun `clearing the search empties the results, the summary and the result bar`() {
        val session = chapterSearch()
        session.query = "보아"
        session.start(CoroutineScope(Dispatchers.Unconfined), unitCount = { 3 }) { i, _ -> listOf(found(i)) }
        session.current = 1
        assertEquals(3, session.results.size)
        assertEquals(3, session.searched)
        assertEquals("3곳 · 3장에서", session.summary)

        // 검색 칸의 ×. 멈추기만 하던 때는 칸은 비었는데 옛 결과 목록과 "3곳 · 3장에서" 가 남아 지운 말의 결과로 보였다.
        session.query = ""
        session.stop()
        assertTrue(session.results.isEmpty(), "결과가 남았다")
        assertEquals(0, session.searched, "요약(몇 곳 · 몇 장)이 남았다")
        assertEquals(-1, session.current, "결과 막대가 남았다")
        assertEquals("", session.summary, "요약 줄이 남았다")
        assertFalse(session.running)
    }
}
