package io.github.kgcaudit.reader.layout.book

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 듣기(4단계)가 한 번에 읽는 토막. */
class SentencesTest {

    private fun words(text: String, starts: Set<Int> = emptySet(), max: Int = 300) =
        splitSentences(text, starts, max).map { text.substring(it.start, it.endExclusive) }

    @Test
    fun `sentences end at the full stop and keep their closing quote`() {
        assertEquals(
            listOf("그 책에는 이렇게 씌어 있었다.", "\"보아 구렁이는 통째로 삼킨다.\"", "정말일까?", "그렇다!"),
            words("그 책에는 이렇게 씌어 있었다. \"보아 구렁이는 통째로 삼킨다.\" 정말일까? 그렇다!"),
        )
    }

    @Test
    fun `a paragraph break always ends a sentence even without a space`() {
        // 장 텍스트는 문단을 구분자 없이 잇는다 — 끊지 않으면 두 문단이 한 문장으로 읽힌다.
        val text = "먹이를 삼킨다.어른들은 모자라고 했다그리고 끝"
        assertEquals(listOf("먹이를 삼킨다.", "어른들은 모자라고 했다", "그리고 끝"), words(text, setOf(8, 20)))
    }

    @Test
    fun `numbers and abbreviations are not cut at their dots`() {
        assertEquals(listOf("원주율은 3.14 이다.", "U.S.A. 에서 왔다."), words("원주율은 3.14 이다. U.S.A. 에서 왔다."))
    }

    @Test
    fun `pieces with nothing to say are dropped`() {
        // 그림 자리(U+FFFC)와 장식 줄은 엔진이 기호를 읽거나 멈춘다.
        val text = "앞 문장. \uFFFC * * * 뒤 문장."
        assertEquals(listOf("앞 문장.", "뒤 문장."), words(text, setOf(6, 8, text.indexOf('뒤'))))
        assertEquals(emptyList(), words(""))
        assertEquals(emptyList(), words("   \n  "))
    }

    @Test
    fun `a very long sentence is cut at a comma or a space`() {
        val long = (1..40).joinToString(", ") { "보아 구렁이 $it" } + "."
        val parts = words(long, max = 60)
        assertTrue(parts.size > 5)
        assertTrue(parts.all { it.length <= 60 }, parts.toString())
        // 잘라도 글자를 잃지 않는다(공백만 빠진다).
        assertEquals(long.replace(" ", ""), parts.joinToString("").replace(" ", ""))
        // 쉼표 · 공백이 전혀 없어도 끝없이 돌지 않는다.
        assertEquals(3, words("가".repeat(150), max = 60).size)
    }

    @Test
    fun `the sentence at an offset and its speakable text`() {
        val text = "하나.  둘\n셋.￼넷."
        val list = splitSentences(text)
        assertEquals(0, list.indexAt(0))
        // 문장 사이 공백은 뒤 문장.
        assertEquals(1, list.indexAt(4))
        assertEquals(-1, list.indexAt(text.length))
        assertEquals("둘 셋. 넷.", speakable(text, list[1]))
    }
}
