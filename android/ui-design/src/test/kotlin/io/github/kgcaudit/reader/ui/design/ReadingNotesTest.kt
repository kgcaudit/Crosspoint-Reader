package io.github.kgcaudit.reader.ui.design

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 독서노트의 거르개 · 위치 표시 · 내보내기 글. */
class ReadingNotesTest {

    private val items = listOf(
        NoteItem("b1", "1장", "여섯 살 적에", null, null, "3% · 2026.09.24."),
        NoteItem("a1", "1장", "보아 구렁이 그림", Pen.Yellow, null, "3% · 2026.09.24."),
        NoteItem("a2", "1장", "여섯 달 동안", Pen.Green, "제일 오래\n기억에 남은 대목", "4% · 2026.09.24."),
        NoteItem("a3", "2장", "숫자를 좋아한다", Pen.Blue, "목소리를 물어보라", "18% · 2026.09.25."),
    )

    @Test
    fun `the filter chips split the notes without overlap`() {
        // 책갈피 · 형광펜 · 메모의 수를 더하면 전체다 — 한 항목이 두 칩에 세어지면 수가 맞지 않아 보인다.
        val counts = NoteFilter.entries.associateWith { f -> items.count { f.shows(it) } }
        assertEquals(4, counts[NoteFilter.All])
        assertEquals(1, counts[NoteFilter.Bookmarks])
        assertEquals(1, counts[NoteFilter.Highlights])
        assertEquals(2, counts[NoteFilter.Memos])
        assertEquals(counts[NoteFilter.All], counts[NoteFilter.Bookmarks]!! + counts[NoteFilter.Highlights]!! + counts[NoteFilter.Memos]!!)
    }

    @Test
    fun `a note says where it is as a percent and a date, pdf only a date`() {
        val at = java.util.Calendar.getInstance().apply { set(2026, 8, 24, 10, 0) }.timeInMillis
        assertEquals("3% · 2026.09.24.", noteWhere(3.4f, at))
        assertEquals("2026.09.24.", noteWhere(null, at))
    }

    @Test
    fun `the export reads like the screen, chapter by chapter with memos under their highlight`() {
        val text = exportNotes("어린 왕자", "생텍쥐페리", items)
        assertTrue(text.startsWith("어린 왕자 — 생텍쥐페리\n독서노트 4개\n"))
        // 장 이름은 장마다 한 번.
        assertEquals(1, Regex("\\[1장]").findAll(text).count())
        assertTrue("■ 책갈피 · 3% · 2026.09.24.\n여섯 살 적에" in text)
        assertTrue("● 초록 · 4% · 2026.09.24.\n여섯 달 동안\n  └ 메모: 제일 오래\n    기억에 남은 대목" in text)
        assertTrue(text.indexOf("[1장]") < text.indexOf("[2장]"))
    }

    @Test
    fun `highlights are lighter on dark paper so the text stays readable`() {
        Pen.entries.forEach { assertTrue(it.fill(dark = true).alpha < it.fill(dark = false).alpha) }
    }
}
