package io.github.kgcaudit.reader.reflow

import io.github.kgcaudit.reader.layout.Insets
import io.github.kgcaudit.reader.layout.book.ReadingPosition
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 읽는 속도에 넣을 "한 쪽 넘김" 인가. 건너뛴 쪽 · 다시 짠 쪽을 넘김으로 읽으면 남은 시간이 엉뚱해진다. */
class ShownPagesTest {

    /** [size] 는 글자 크기(조판 설정). 같으면 같은 조판이다. */
    private fun at(spine: Int, page: Int, count: Int = 10, spread: Boolean = false, size: Int = 18) =
        ShownPages(ReadingPosition(spine, page, count), spread, ReaderPrefs(fontSizeSp = size).toSpec(400f, 800f, Insets.all(0f), 1f, 1f, "serif"))

    @Test
    fun `the next page in the chapter and the first page of the next chapter are one turn`() {
        assertTrue(at(0, 3).isFollowedBy(at(0, 4)))
        // 장 끝에서 다음 장 첫 쪽.
        assertTrue(at(0, 9).isFollowedBy(at(1, 0, count = 5)))
    }

    @Test
    fun `jumps are not one turn`() {
        assertFalse(at(0, 3).isFollowedBy(at(0, 7)), "같은 장 안에서 건너뜀(진행 막대)")
        assertFalse(at(0, 3).isFollowedBy(at(1, 0)), "장 한가운데서 다음 장으로(목차)")
        assertFalse(at(0, 9).isFollowedBy(at(2, 0)), "장을 건너뜀")
        assertFalse(at(0, 4).isFollowedBy(at(0, 3)), "뒤로")
    }

    @Test
    fun `in two page view a turn moves by a whole spread`() {
        assertTrue(at(0, 2, spread = true).isFollowedBy(at(0, 4, spread = true)))
        assertFalse(at(0, 2, spread = true).isFollowedBy(at(0, 3, spread = true)))
        // 홀수 쪽으로 끝나는 장의 마지막 펼침(쪽 하나)에서 다음 장으로.
        assertTrue(at(0, 8, count = 9, spread = true).isFollowedBy(at(1, 0, spread = true)))
    }

    @Test
    fun `a page laid out again with other settings is not a turn`() {
        // 글자를 줄여 같은 글이 3쪽에서 4쪽이 되었다 — 넘긴 것이 아니다.
        assertFalse(at(0, 3, size = 20).isFollowedBy(at(0, 4, size = 16)))
        // 두쪽보기를 켜고 끈 것도.
        assertFalse(at(0, 3).isFollowedBy(at(0, 4, spread = true)))
    }
}
