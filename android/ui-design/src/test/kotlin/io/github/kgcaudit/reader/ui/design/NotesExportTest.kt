package io.github.kgcaudit.reader.ui.design

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 독서노트 내보내기(0.28.0): 담을 것 · 마크다운 모양 · 파일 이름. */
class NotesExportTest {

    private val items = listOf(
        NoteItem("b1", "1장", "여섯 살 적에", null, null, "3% · 2026.09.24."),
        NoteItem("a1", "1장", "보아 구렁이 그림", Pen.Yellow, null, "3% · 2026.09.24."),
        NoteItem("a2", "1장", "여섯 달 동안", Pen.Green, "제일 오래\n기억에 남은 대목", "4% · 2026.09.24."),
        NoteItem("a3", "2장", "숫자를 좋아한다", Pen.Blue, "목소리를 물어보라", "18% · 2026.09.25."),
    )
    private val sept30 = java.util.Calendar.getInstance().apply { set(2026, 8, 30, 10, 0) }.timeInMillis

    @Test
    fun `what goes in follows the chip the reader was looking at`() {
        assertEquals(NoteExportScope.Memos, NoteExportScope.from(NoteFilter.Memos))
        assertEquals(NoteExportScope.Marked, NoteExportScope.from(NoteFilter.Highlights))
        assertEquals(NoteExportScope.All, NoteExportScope.from(NoteFilter.Bookmarks))
        // "형광펜 · 메모" 는 칠 전부 — 메모 달린 칠이 빠지면 메모를 단 대목이 내보낸 글에서 사라진다.
        assertEquals(listOf("a1", "a2", "a3"), items.filter(NoteExportScope.Marked::takes).map { it.key })
        assertEquals(listOf("a2", "a3"), items.filter(NoteExportScope.Memos::takes).map { it.key })
    }

    @Test
    fun `markdown has the book as title, chapters as headings and highlights as quotes`() {
        val md = exportMarkdown("어린 왕자", "생텍쥐페리", items, sept30)
        assertTrue(md.startsWith("# 어린 왕자\n생텍쥐페리 · 독서노트 4개 · 2026년 9월 30일\n"), md)
        assertTrue("\n## 1장\n" in md && "\n## 2장\n" in md, md)
        assertEquals(1, Regex("## 1장").findAll(md).count(), "같은 장이 두 번 제목이 되었다")
        assertTrue("\n> 보아 구렁이 그림\n\n노랑 · 3% · 2026.09.24.\n" in md, md)
        assertTrue("- 책갈피 · 3% · 2026.09.24. — 여섯 살 적에\n" in md, md)
        // 여러 줄 메모는 줄바꿈이 살아 있다(줄 끝 두 칸).
        assertTrue("**메모** 제일 오래  \n기억에 남은 대목\n" in md, md)
        // 저자를 모르면(PDF) 저자 자리를 비우지 않고 뺀다.
        assertTrue(exportMarkdown("문서", null, items, sept30).startsWith("# 문서\n독서노트 4개 · "))
    }

    @Test
    fun `book text that looks like markdown stays plain text`() {
        // 본문에 서식 글자가 들어 있으면 받는 앱이 제목 · 목록 · 기울임으로 바꿔 인용이 원문과 달라진다.
        assertEquals("\\*강조\\* 와 \\_밑줄\\_", markdownEscape("*강조* 와 _밑줄_"))
        assertEquals("\\# 제목 같은 줄", markdownEscape("# 제목 같은 줄"))
        assertEquals("\\- 목록 같은 줄", markdownEscape("- 목록 같은 줄"))
        assertEquals("\\===", markdownEscape("==="))
        assertEquals("1\\. 번호 같은 줄", markdownEscape("1. 번호 같은 줄"))
        assertEquals("\\<b\\>굵게\\</b\\>", markdownEscape("<b>굵게</b>"))
        // 줄 가운데의 # · - · 숫자는 서식이 아니다 — 건드리지 않는다.
        assertEquals("C# 과 1-2 쪽 3. 장", markdownEscape("C# 과 1-2 쪽 3. 장"))
        val md = exportMarkdown("*별* 책", null, listOf(NoteItem("a", "# 장", "> 인용 같은 글\n- 둘째 줄", Pen.Pink, null, "1% · 2026.09.24.")), sept30)
        assertTrue(md.startsWith("# \\*별\\* 책\n"), md)
        assertTrue("## \\# 장\n" in md, md)
        assertTrue("> \\> 인용 같은 글\n> \\- 둘째 줄\n" in md, md)
    }

    @Test
    fun `the file name has no characters a folder cannot hold`() {
        assertEquals("반지의 제왕 1 2 독서노트.md", exportFileName("반지의 제왕 1/2", NoteFormat.Markdown))
        assertEquals("a b c 독서노트.txt", exportFileName("a:b*c?", NoteFormat.Text))
        assertEquals("책 독서노트.md", exportFileName(" / ", NoteFormat.Markdown))
        assertFalse(exportFileName("x".repeat(500), NoteFormat.Text).length > 100)
    }

    @Test
    fun `a book with thousands of notes is too long to share at once and is sent to a file instead`() {
        // 칠 수천 개의 책에서 "공유" 를 누르면 아무 일도 없었다. 한도를 넘는 글은 공유하지 않고 파일로 저장하라고 알린다.
        val many = List(3_000) { i -> NoteItem("a$i", "${i / 100}장", "칠한 대목 $i ".repeat(4), Pen.Yellow, "메모 $i", "$i% · 2026.09.24.") }
        assertFalse(shareable(exportNotes("많은 책", null, many)))
        assertTrue(shareable(exportNotes("어린 왕자", null, items)))
    }
}
