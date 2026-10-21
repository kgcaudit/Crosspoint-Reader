package io.github.kgcaudit.reader.ios

/**
 * 독서노트 내보내기(구상안 22, 안드로이드 `CpNotesExport` · `exportNotes` · `exportMarkdown` 와 같은 글). 두 앱이 같은 파일을 내야
 * 한쪽에서 낸 노트를 다른 쪽 사람이 그대로 읽는다 — 그래서 문구 · 기호를 안드로이드에서 옮겨 오고 시험으로 지킨다.
 *
 * [pen] 은 칠의 색 이름("노랑" 등), 책갈피면 null. [place] 는 "3% · 2026.10.21." 처럼 이미 꾸민 자리.
 */
class ExportItem(val section: String, val text: String, val pen: String?, val place: String, val memo: String?) {
    val isBookmark: Boolean get() = pen == null
}

object NotesExport {
    /** 담을 것: 0 전체 · 1 형광펜 · 메모(칠 전부 — 메모 달린 칠도 칠이다) · 2 메모만. */
    fun takes(scope: Int, item: ExportItem): Boolean = when (scope) {
        1 -> !item.isBookmark
        2 -> item.memo != null
        else -> true
    }

    /** 글: 메모장 · 메신저 · 메일에 그대로. */
    fun text(title: String, author: String?, items: List<ExportItem>): String = buildString {
        append(title)
        if (!author.isNullOrBlank()) append(" — ").append(author)
        append("\n독서노트 ").append(items.size).append("개\n")
        var section: String? = null
        for (item in items) {
            if (item.section != section) {
                section = item.section
                append("\n[").append(item.section).append("]\n")
            }
            append(if (item.isBookmark) "■ 책갈피" else "● ${item.pen}").append(" · ").append(item.place).append('\n')
            append(item.text).append('\n')
            item.memo?.let { append("  └ 메모: ").append(it.replace("\n", "\n    ")).append('\n') }
        }
    }

    /** 마크다운: # 책 · ## 장 · 칠은 인용, 그 아래 색 · 자리, **메모**, 책갈피는 목록 한 줄. [date] 는 "2026년 10월 21일". */
    fun markdown(title: String, author: String?, items: List<ExportItem>, date: String): String = buildString {
        append("# ").append(escape(title)).append("\n")
        append(listOfNotNull(author?.takeIf { it.isNotBlank() }?.let(::escape), "독서노트 ${items.size}개", date).joinToString(" · ")).append("\n")
        var section: String? = null
        for (item in items) {
            if (item.section != section) {
                section = item.section
                append("\n## ").append(escape(item.section)).append("\n")
            }
            append('\n')
            if (item.isBookmark) {
                append("- 책갈피 · ").append(item.place).append(" — ").append(escape(item.text.lines().joinToString(" "))).append('\n')
                continue
            }
            item.text.lines().forEach { append("> ").append(escape(it)).append('\n') }
            append('\n').append(item.pen).append(" · ").append(item.place).append('\n')
            item.memo?.let { memo ->
                // 여러 줄 메모는 줄 끝에 두 칸을 붙인다. 없으면 마크다운이 한 줄로 이어 붙여 사용자가 나눈 줄이 사라진다.
                append("**메모** ").append(memo.lines().joinToString("  \n") { escape(it) }).append('\n')
            }
        }
    }

    /** 마크다운이 서식으로 읽는 글자를 글자 그대로 읽게 한다 — 받는 앱이 책 글을 서식으로 바꾸지 않게. */
    fun escape(line: String): String {
        val inline = buildString {
            for (ch in line) {
                if (ch in "\\`*_[]<>|") append('\\')
                append(ch)
            }
        }
        // 줄머리에서만 서식이 되는 것: 제목(#) · 목록(- + 1.) · 인용(>, 위에서 이미 감쌈).
        return when {
            Regex("^\\s*[#+=-]").containsMatchIn(inline) -> inline.replaceFirst(Regex("^(\\s*)([#+=-])"), "$1\\\\$2")
            Regex("^\\s*\\d+[.)]").containsMatchIn(inline) -> inline.replaceFirst(Regex("^(\\s*\\d+)([.)])"), "$1\\\\$2")
            else -> inline
        }
    }

    /** 파일 이름에 쓸 수 없는 글자를 뺀다. 책 제목의 "/" 가 폴더로 읽혀 저장이 실패했다(안드로이드). */
    fun fileName(title: String, markdown: Boolean): String {
        val safe = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(80).ifEmpty { "책" }
        return "$safe 독서노트.${if (markdown) "md" else "txt"}"
    }
}
