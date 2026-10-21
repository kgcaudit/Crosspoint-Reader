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

    @Test
    fun `hidden spaces become plain spaces and invisible marks disappear before speaking`() {
        // 어절 사이를 줄바꿈 금지 공백 · 전각 공백으로 띄운 책: 보통 공백이 되어야 엔진이 어절 경계로 읽는다.
        val nbsp = "보아\u00A0구렁이는\u3000먹이를\u202F삼킨다."
        assertEquals("보아 구렁이는 먹이를 삼킨다.", speakable(nbsp, splitSentences(nbsp).single()))
        // 폭 없는 공백 · 소프트 하이픈 · BOM 은 화면에 안 보이는 글자다 — 어절 한가운데서 끊기지 않게 지운다.
        val hidden = "\uFEFF어른\u00AD들\u200B은 모자\u2060라고 했다."
        assertEquals("어른들은 모자라고 했다.", speakable(hidden, splitSentences(hidden).single()))
        // 문장 끝 뒤가 폭 없는 공백이어도 문장은 끊긴다.
        assertEquals(2, splitSentences("첫 문장이다.\u200B둘째 문장이다.").size)
        // 숨은 문자만 있는 토막은 읽을 것이 없다.
        assertEquals(emptyList(), splitSentences("\u200B\u00A0\uFEFF"))
    }
}
