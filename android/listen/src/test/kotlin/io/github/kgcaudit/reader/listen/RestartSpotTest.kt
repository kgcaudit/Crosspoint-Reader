package io.github.kgcaudit.reader.listen

import io.github.kgcaudit.reader.layout.book.Sentence
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 목소리 엔진을 바꾸면 새 엔진으로 다시 연다 — 듣던 문장부터. 장(쪽)과 글자 번호를 서로 다른 곳에서 가져오면 듣던 장의 엉뚱한
 * 자리에서 다시 읽는다.
 */
class RestartSpotTest {

    /** 화면에 보이는 쪽: 1장의 500번째 글자부터. */
    private val shown = ListenSpot(1, 500, pageEnd = 900)

    @Test
    fun `the new engine reads on from the sentence being heard, in its own chapter`() {
        // 화면은 1장을 보지만(사람이 앞으로 넘겨 봄) 듣기는 3장의 120번째 글자 문장을 읽고 있었다.
        val heard = ListenState(active = true, spine = 3, sentence = Sentence(120, 160))
        assertEquals(ListenSpot(3, 120), restartSpot(heard) { shown })
    }

    @Test
    fun `a chapter known without a sentence starts at that chapter's beginning, not at the screen's offset`() {
        // 듣기가 장만 알고 문장은 아직 없다 — 화면 쪽의 글자 번호(500)를 3장에 쓰면 3장의 엉뚱한 곳(또는 장 밖)에서 읽는다.
        val heard = ListenState(active = true, spine = 3, sentence = null)
        assertEquals(ListenSpot(3, 0), restartSpot(heard) { shown })
    }

    @Test
    fun `before any sentence was heard it starts at the page on screen`() {
        // 엔진을 깨우는 중(아직 아무 문장도 없음)에 엔진을 바꿨다 — 보이는 쪽에서, 그 쪽 끝까지 함께 준다.
        val heard = ListenState(active = true, preparing = true)
        assertEquals(shown, restartSpot(heard) { shown })
        assertEquals(null, restartSpot(heard) { null }, "보이는 쪽도 없으면 다시 열 곳이 없다")
    }
}
