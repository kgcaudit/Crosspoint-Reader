package io.github.kgcaudit.reader.ios

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 독서노트 내보내기(구상안 22). 안드로이드 `NotesExportTest` 와 같은 기대값 — 두 앱이 같은 파일을 내야 한다. */
class NotesExportTest {
    private val items = listOf(
        ExportItem("첫째 장", "아침부터 비가 내렸다.", null, "2% · 2026.10.21.", null),
        ExportItem("첫째 장", "습기로 유리창이 뿌옇게", "파랑", "3% · 2026.10.21.", null),
        ExportItem("둘째 장", "# 1. 제목 같은 *글*", "노랑", "41% · 2026.10.21.", "첫 줄\n둘째 줄"),
    )

    @Test
    fun `markdown keeps chapters quotes and multi line memos and escapes book text`() {
        val md = NotesExport.markdown("비 오는 아침", "OLO", items, "2026년 10월 21일")
        assertTrue(md.startsWith("# 비 오는 아침\nOLO · 독서노트 3개 · 2026년 10월 21일\n"))
        assertTrue("\n## 첫째 장\n" in md)
        assertTrue("- 책갈피 · 2% · 2026.10.21. — 아침부터 비가 내렸다.\n" in md)
        assertTrue("> 습기로 유리창이 뿌옇게\n\n파랑 · 3% · 2026.10.21.\n" in md)
        // 책 글의 # · * 가 제목 · 기울임으로 바뀌면 받은 노트가 엉뚱한 모양이 된다.
        assertTrue("> \\# 1. 제목 같은 \\*글\\*\n" in md, md)
        // 여러 줄 메모는 줄 끝 두 칸으로 줄바꿈을 살린다.
        assertTrue("**메모** 첫 줄  \n둘째 줄\n" in md)
    }

    @Test
    fun `plain text marks bookmarks and pens and indents memo lines`() {
        val text = NotesExport.text("비 오는 아침", null, items)
        assertTrue(text.startsWith("비 오는 아침\n독서노트 3개\n"))
        assertTrue("\n[첫째 장]\n■ 책갈피 · 2% · 2026.10.21.\n아침부터 비가 내렸다.\n" in text)
        assertTrue("● 노랑 · 41% · 2026.10.21.\n" in text)
        assertTrue("  └ 메모: 첫 줄\n    둘째 줄\n" in text)
    }

    @Test
    fun `scopes take everything or every highlight with or without memo or memos only`() {
        assertEquals(3, items.count { NotesExport.takes(0, it) })
        assertEquals(2, items.count { NotesExport.takes(1, it) })
        assertEquals(1, items.count { NotesExport.takes(2, it) })
        assertFalse(NotesExport.takes(1, items[0]), "a bookmark is not a highlight")
    }

    @Test
    fun `a title with path or control characters still makes a file name`() {
        assertEquals("상 하 독서노트.md", NotesExport.fileName("상/하", true))
        assertEquals("a b 독서노트.txt", NotesExport.fileName("a:\tb", false))
        assertEquals("책 독서노트.md", NotesExport.fileName("///", true))
        assertEquals("\\- 줄머리", NotesExport.escape("- 줄머리"))
        assertEquals("3\\. 번호", NotesExport.escape("3. 번호"))
    }
}
