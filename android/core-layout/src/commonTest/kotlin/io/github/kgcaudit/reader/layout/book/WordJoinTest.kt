package io.github.kgcaudit.reader.layout.book

import kotlin.test.Test
import kotlin.test.assertEquals

class WordJoinTest {

    // 구상안(stage7) 설명판의 예문 그대로 — 확정한 모양이 바뀌면 여기서 걸린다.
    private val cases = listOf(
        Pair(
            "그 사람이 무엇을 말하려는 것인지 나는 알 수 없었다.",
            "그사람이 무엇을 말하려는것인지 나는 알수 없었다.",
        ),
        Pair(
            "첫 공연을 앞둔 며칠 동안 빌리는 평소보다 더 미친 듯 돈을 썼다.",
            "첫공연을 앞둔 며칠 동안 빌리는 평소보다 더미친듯 돈을 썼다.",
        ),
        Pair(
            "나는 그 걸작품을 어른들에게 보여 주고 내 그림이 무섭지 않느냐고 물었다.",
            "나는 그걸작품을 어른들에게 보여주고 내 그림이 무섭지않느냐고 물었다.",
        ),
        Pair(
            "달빛이 유난히 밝은 밤, 나와 린윈, 딩이는 기지 내 한적한 산책로를 걸었다.",
            "달빛이 유난히 밝은 밤, 나와 린윈, 딩이는 기지 내 한적한 산책로를 걸었다.",
        ),
    )

    @Test
    fun `fast reading joins only where the meaning clearly continues, as in the confirmed mockup`() {
        for ((off, light) in cases) assertEquals(light, joinWords(off, WordJoin.Light))
    }

    @Test
    fun `only normal and fast reading are offered`() {
        // "강하게" 는 들어 보고 뺐다(2026-09-26). 다시 생기면 듣기 판 · 비교 판에 셋째 줄이 뜬다.
        assertEquals(listOf("일반", "속독"), WordJoin.entries.map { it.label })
    }

    @Test
    fun `normal sends the sentence untouched and fast reading leaves fewer pauses`() {
        val s = cases[0].first
        assertEquals(s, joinWords(s, WordJoin.Off))
        // 엔진이 쉬는 자리(띄어쓰기)가 8 → 5 로 준다.
        assertEquals(listOf(8, 5), listOf(joinWords(s, WordJoin.Off), joinWords(s, WordJoin.Light)).map { t -> t.count { it == ' ' } })
    }

    @Test
    fun `it never joins across punctuation, quotes or foreign words`() {
        // 쉼표 · 마침표 뒤, 여는 따옴표 앞은 쉬어야 뜻이 산다. 영어 낱말을 붙이면 한 낱말로 읽는다.
        // "말했다." 뒤는 붙이지 않고, 그 뒤의 "그 사람이" 는 붙인다.
        assertEquals("그는 말했다. 그사람이 왔다.", joinWords("그는 말했다. 그 사람이 왔다.", WordJoin.Light))
        assertEquals("그 “사람”이", joinWords("그 “사람”이", WordJoin.Light))
        assertEquals("the book 수 있다", joinWords("the book 수 있다", WordJoin.Light))
        assertEquals("밤, 그리고", joinWords("밤, 그리고", WordJoin.Light))
    }

    @Test
    fun `a word that merely starts like a dependent noun is not glued`() {
        // "수학을" 은 의존명사 "수" 가 아니다 — 붙이면 "배운수학을" 처럼 뜻이 뭉개진다.
        assertEquals("나는 수학을 배웠다.", joinWords("나는 수학을 배웠다.", WordJoin.Light))
        assertEquals("나는 때때로 웃었다.", joinWords("나는 때때로 웃었다.", WordJoin.Light))
    }

    @Test
    fun `broken input comes back without failing`() {
        for (bad in listOf("", "   ", "…", "!!", "가", " ")) joinWords(bad, WordJoin.Light)
        assertEquals("", joinWords("", WordJoin.Light))
        assertEquals("가", joinWords("가", WordJoin.Light))
    }
}
