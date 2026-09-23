package io.github.kgcaudit.reader.layout.book

import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.Bookmark
import io.github.kgcaudit.reader.document.BookmarkRepository
import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.document.ProgressRepository
import io.github.kgcaudit.reader.document.ReadingProgress

/**
 * 책 한 권을 읽는 동안의 상태.
 *
 * [BookLayout] 이 "몇 번째 페이지인가" 를 맡고, 여기서는 "그 자리를 기억하고 되찾는"
 * 일을 맡는다. 리더 화면은 이 둘만 쓴다.
 *
 * 위치의 원천은 언제나 [Locator] 다. 퍼센트는 표시용 파생값이라 조판이 바뀌면 다시
 * 계산된다 — 반대로 두면(퍼센트를 저장하고 거기서 위치를 복원하면) 글자 크기를 한 번
 * 바꿀 때마다 읽던 자리가 미끄러진다.
 */
class ReadingSession(
    private val layout: BookLayout,
    private val bookmarks: BookmarkRepository,
    private val progress: ProgressRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * 지난번에 읽던 자리. 없으면 책의 처음.
     *
     * 저장된 위치가 지금 조판에서 어느 페이지인지는 여기서 다시 계산한다. 그래서 글꼴을
     * 바꾼 뒤에 열어도 읽던 글자로 돌아온다.
     */
    suspend fun restore(): ReadingPosition {
        val saved = progress.get(bookId)?.locator as? Locator.Reflow
        return layout.resolve(saved ?: Locator.Reflow(spine = 0, charOffset = 0))
    }

    /** 지금 자리를 이어읽기 위치로 저장한다. */
    suspend fun saveProgress(position: ReadingPosition): ReadingProgress {
        val locator = layout.locatorAt(position.spineIndex, position.pageIndex)
        val saved = ReadingProgress(
            bookId = bookId,
            locator = locator,
            percent = layout.percent(locator),
            updatedAtEpochMs = clock(),
        )
        progress.save(saved)
        return saved
    }

    suspend fun bookmarks(): List<Bookmark> = bookmarks.forBook(bookId)

    /** 책갈피와 진도가 늘 같은 책을 가리키도록 [BookLayout] 에서 받아 쓴다. */
    private val bookId: BookId get() = layout.bookId

    /**
     * 지금 페이지에 책갈피를 꽂는다. 이미 같은 자리에 있으면 그것을 돌려준다.
     *
     * 같은 자리에 두 번 꽂히지 않게 하는 이유: 책갈피 단추를 두 번 누르는 일이 흔하고,
     * 목록에 똑같은 항목이 둘 있으면 어느 것을 지워야 하는지 알 수 없다.
     */
    suspend fun addBookmark(position: ReadingPosition): Bookmark {
        val locator = layout.locatorAt(position.spineIndex, position.pageIndex)
        bookmarks.forBook(bookId).firstOrNull { it.locator == locator }?.let { return it }

        return bookmarks.add(
            Bookmark(
                id = Bookmark.NO_ID,
                bookId = bookId,
                locator = locator,
                snippet = snippetAt(position),
                createdAtEpochMs = clock(),
            ),
        )
    }

    /** 이 페이지의 책갈피를 뺀다. 없으면 아무 일도 없다. 있으면 true. */
    suspend fun removeBookmarkAt(position: ReadingPosition): Boolean {
        val locator = layout.locatorAt(position.spineIndex, position.pageIndex)
        val existing = bookmarks.forBook(bookId).firstOrNull { it.locator == locator } ?: return false
        bookmarks.remove(existing.id)
        return true
    }

    suspend fun isBookmarked(position: ReadingPosition): Boolean {
        val locator = layout.locatorAt(position.spineIndex, position.pageIndex)
        return bookmarks.forBook(bookId).any { it.locator == locator }
    }

    /** 책갈피를 눌러 그 자리로 간다. */
    suspend fun goTo(bookmark: Bookmark): ReadingPosition? =
        (bookmark.locator as? Locator.Reflow)?.let { layout.resolve(it) }

    /**
     * 목록에 보여 줄 본문 한 토막.
     *
     * 페이지 **첫 글자부터** 뜬다. 목록에서 알아보는 단서는 "이 페이지가 무슨 얘기로
     * 시작하는가" 이고, 그게 페이지를 열지 않고도 짐작할 수 있는 유일한 정보다.
     */
    private suspend fun snippetAt(position: ReadingPosition): String? {
        val text = layout.chapterText(position.spineIndex) ?: return null
        val page = layout.page(position.spineIndex, position.pageIndex) ?: return null

        val from = page.startChar.coerceIn(0, text.length)
        val to = (from + SNIPPET_CHARS).coerceAtMost(text.length)
        if (from >= to) return null

        // 줄바꿈과 그림 자리 글자(U+FFFC)는 한 칸 공백으로 바꾼다. 한 줄로 보여 줄
        // 문장이라 제어 문자가 섞이면 목록이 깨진다.
        val snippet = text.substring(from, to)
            .map { if (it < ' ' || it == '￼') ' ' else it }
            .joinToString("")
            .replace(WHITESPACE, " ")
            .trim()
        return snippet.takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val SNIPPET_CHARS = 80
        val WHITESPACE = Regex("\\s+")
    }
}
