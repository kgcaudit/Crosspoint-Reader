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

    /**
     * 지금 자리를 이어읽기 위치로 저장한다.
     *
     * @param lastShown 화면에 보이는 마지막 쪽(두쪽보기면 오른쪽). 그것이 책의 마지막 쪽이면 진도를 100 으로 둔다 — 진도는
     *   쪽의 **첫 글자** 자리로 재서 마지막 쪽에서도 97% 쯤에 멈췄고, 그러면 책장이 "다 읽은 책" 을 알 수 없었다.
     */
    suspend fun saveProgress(position: ReadingPosition, lastShown: ReadingPosition = position): ReadingProgress {
        val locator = layout.locatorAt(position.spineIndex, position.pageIndex)
        val atEnd = lastShown.isLastPageOfChapter && lastShown.spineIndex >= layout.spine().size - 1
        val saved = ReadingProgress(
            bookId = bookId,
            locator = locator,
            percent = if (atEnd) 100f else layout.percent(locator),
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

    /**
     * 보이는 쪽([position]부터 [lastShown]까지 — 두쪽보기면 오른쪽 쪽까지)의 책갈피를 모두 뺀다. 하나라도 뺐으면 true.
     *
     * 자리가 정확히 같은 것만 빼던 때는, 글자 크기를 바꿔 쪽 첫 글자가 달라지면 리본은 보이는데(아래 [isBookmarked])
     * 눌러도 빠지지 않고 하나 더 꽂혔다.
     */
    suspend fun removeBookmarkAt(position: ReadingPosition, lastShown: ReadingPosition = position): Boolean {
        val shown = bookmarksOn(position, lastShown)
        shown.forEach { bookmarks.remove(it.id) }
        return shown.isNotEmpty()
    }

    /** 보이는 쪽에 책갈피가 있는가(리본). [removeBookmarkAt] 과 같은 범위를 본다. */
    suspend fun isBookmarked(position: ReadingPosition, lastShown: ReadingPosition = position): Boolean =
        bookmarksOn(position, lastShown).isNotEmpty()

    /**
     * 보이는 쪽에 드는 책갈피. 자리가 아니라 **글자 범위**로 본다 — 책갈피는 꽂을 때 쪽의 첫 글자를 기억하는데, 글자
     * 크기 · 여백을 바꾸면 쪽 경계가 움직여 그 글자가 쪽 한가운데로 간다. 그래도 그 쪽의 책갈피다.
     *
     * 범위는 첫 쪽의 첫 글자부터 마지막 쪽 다음 쪽의 첫 글자 앞까지다. 장의 첫 쪽 · 끝 쪽은 바깥으로 연다 — 책갈피로
     * 가는 [goTo] 가 범위 밖 글자를 첫 쪽 · 끝 쪽으로 보내므로, 판정도 같아야 간 쪽에서 리본이 보인다.
     */
    private suspend fun bookmarksOn(position: ReadingPosition, lastShown: ReadingPosition): List<Bookmark> {
        val spine = position.spineIndex
        val last = if (lastShown.spineIndex == spine && lastShown.pageIndex >= position.pageIndex) lastShown else position
        val from = if (position.pageIndex == 0) Int.MIN_VALUE else layout.locatorAt(spine, position.pageIndex).charOffset
        val to = if (last.isLastPageOfChapter) Int.MAX_VALUE else layout.locatorAt(spine, last.pageIndex + 1).charOffset
        return bookmarks.forBook(bookId).filter { b ->
            val at = b.locator as? Locator.Reflow ?: return@filter false
            at.spine == spine && at.charOffset >= from && at.charOffset < to
        }
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
        val page = layout.page(position.spineIndex, position.pageIndex) ?: return null
        // 문단 경계에 한 칸을 넣어 뜬다(excerpt) — 장 텍스트를 그대로 뜨면 두 문단이 한 낱말로 붙는다.
        return layout.excerpt(position.spineIndex, page.startChar, page.startChar + SNIPPET_CHARS).takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val SNIPPET_CHARS = 80
    }
}
