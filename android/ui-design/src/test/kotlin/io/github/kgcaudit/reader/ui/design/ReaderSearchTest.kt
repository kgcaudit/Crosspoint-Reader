package io.github.kgcaudit.reader.ui.design

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 두 리더(EPUB 장 · PDF 쪽)가 함께 쓰는 찾기 상태. 결과가 바로 들어오게 Unconfined 에서 돌린다. */
class ReaderSearchTest {

    /** 결과 하나 = 그것이 나온 단위 번호. */
    private fun pages() = ReaderSearch<Int>("쪽") { it }

    @Test
    fun `clearing the search empties the results and the summary in the reader's own unit`() {
        val search = pages()
        search.query = "보아"
        // 0 · 2 쪽에서 두 번씩, 1 쪽에서는 못 찾는다.
        search.start(CoroutineScope(Dispatchers.Unconfined), unitCount = { 3 }) { i, _ -> if (i == 1) emptyList() else listOf(i, i) }
        search.current = 2
        assertEquals("4곳 · 2쪽에서", search.summary)
        assertEquals("보아", search.searchedQuery)
        // 다 찾았으면 "찾는 중" 이 꺼진다 — 곧바로 도는 디스패처에서도.
        assertFalse(search.running, "다 찾았는데 찾는 중으로 남았다")
        assertNull(search.progress)

        // 검색 칸의 ×. 칸은 비었는데 옛 목록과 요약이 남으면 지운 말의 결과로 보인다.
        search.query = ""
        search.stop()
        assertTrue(search.results.isEmpty(), "결과가 남았다")
        assertEquals("", search.summary, "요약이 남았다")
        assertEquals(-1, search.current, "결과 막대가 남았다")
    }

    @Test
    fun `a new search replaces the one still running`() {
        val search = pages()
        val gate = CompletableDeferred<Unit>()
        search.query = "옛말"
        // 첫 찾기는 1 쪽에서 멈춰 기다린다.
        search.start(CoroutineScope(Dispatchers.Unconfined), unitCount = { 3 }) { i, _ -> if (i == 1) gate.await(); listOf(i) }
        assertTrue(search.running)
        assertEquals("찾는 중… 1 / 3쪽 · 지금까지 1곳", search.summary)
        assertEquals(1 / 3f, search.progress)

        search.query = "새말"
        search.start(CoroutineScope(Dispatchers.Unconfined), unitCount = { 2 }) { i, _ -> listOf(10 + i) }
        gate.complete(Unit)
        // 옛 찾기의 결과가 새 목록에 섞이지 않는다.
        assertEquals(listOf(10, 11), search.results)
        assertEquals("2곳 · 2쪽에서", search.summary)
    }
}
