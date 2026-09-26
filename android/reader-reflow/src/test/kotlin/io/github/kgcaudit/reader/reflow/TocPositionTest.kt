package io.github.kgcaudit.reader.reflow

import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.document.TocEntry
import org.junit.Test
import kotlin.test.assertEquals

/** 목차에서 "지금 여기" 로 표시할 항목. */
class TocPositionTest {

    private fun entry(label: String, spine: Int, anchor: String? = null) =
        TocEntry(label, Locator.Reflow(spine, 0), anchor = anchor)

    @Test
    fun `a chapter between two entries belongs to the one before it`() {
        // 역사게임: 목차 7개가 챕터 113개를 덮는다. 50번째 챕터에서 "같은 챕터" 만 찾으면
        // 아무것도 표시되지 않는다.
        val toc = listOf(entry("감수사", 1), entry("1~17주", 5), entry("18~34주", 40), entry("35~52주", 75))
        assertEquals(2, currentTocIndex(toc, 50))
        assertEquals(1, currentTocIndex(toc, 5))
        assertEquals(3, currentTocIndex(toc, 112))
    }

    @Test
    fun `sections inside one chapter point at the chapter's first entry`() {
        // 한 파일에 절이 여럿이면 앵커 위치는 조판해야 안다. 첫 항목을 고른다 — 뒤의 절을
        // 고르면 아직 읽지 않은 곳이 "지금" 으로 표시된다.
        val toc = listOf(entry("1장", 3), entry("1.1", 3, "s1"), entry("1.2", 3, "s2"), entry("2장", 4))
        assertEquals(0, currentTocIndex(toc, 3))
    }

    @Test
    fun `before the first entry nothing is marked`() {
        // 표지·판권처럼 목차 앞의 페이지.
        assertEquals(-1, currentTocIndex(listOf(entry("1장", 3)), 0))
        assertEquals(-1, currentTocIndex(emptyList(), 5))
    }

    @Test
    fun `entries that do not point at a reflow position are skipped`() {
        // 깨진 목차 링크가 PDF 식 위치로 들어온 경우 — 무시하고 나머지로 고른다.
        val toc = listOf(entry("1장", 0), TocEntry("깨진", Locator.FixedPage(9)), entry("2장", 2))
        assertEquals(2, currentTocIndex(toc, 5))
    }
}
