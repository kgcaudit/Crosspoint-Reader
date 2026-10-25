package io.github.kgcaudit.reader.layout.pdf

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * PDF 칠을 칠한 글로 다시 잡기(0.61.1, iOS 요청). 두 플랫폼의 PDF 엔진이 같은 쪽 글을 다르게 나누면 백업 · 동기화로 옮긴 칠의
 * 글자 번호가 밀린다.
 */
class AnchorHighlightTest {

    private fun layer(text: String) = PageText(text, FloatArray(text.length * 4))

    @Test
    fun `a highlight lands on the same words in a layer with one more space`() {
        // 칠한 휴대폰의 층: "하얀늑대들이 언덕을 넘었다." — 다른 엔진은 앞에 공백 하나를 더 넣어 한 칸씩 밀렸다.
        val other = layer("눈이  내렸다. 하얀 늑대들이 언덕을 넘었다.")
        val snippet = "하얀늑대들이 언덕을"
        val shifted = "눈이 내렸다. 하얀늑대들이 언덕을".indexOf("하얀")
        val found = anchorHighlight(other, shifted, shifted + snippet.length, snippet)
        assertEquals("하얀 늑대들이 언덕을", other.text.substring(found.first, found.last + 1))
    }

    @Test
    fun `a highlight that already sits on its words stays where it is`() {
        val page = layer("첫 줄의 글.\n둘째 줄의 글.")
        // 줄바꿈 자리는 인용에서 공백 하나였다 — 그래도 같은 글이다.
        assertEquals(2 until 10, anchorHighlight(page, 2, 10, "줄의 글. 둘째"))
    }

    @Test
    fun `when the words appear twice the one nearest the old place is taken`() {
        val page = layer("늑대가 울었다. 한참 뒤 늑대가 울었다. 다시 늑대가 울었다.")
        val second = page.text.indexOf("늑대", 5)
        // 두 번째 자리 근처(한 칸 밀림)에서 왔다 — 첫 번째나 세 번째로 가면 엉뚱한 문장에 칠이 앉는다.
        val found = anchorHighlight(page, second + 1, second + 7, "늑대가 울었다")
        assertEquals(second, found.first)
    }

    @Test
    fun `an empty snippet or words not on the page keep the numbers as they were`() {
        val page = layer("눈이 내렸다.")
        assertEquals(2 until 5, anchorHighlight(page, 2, 5, ""))
        assertEquals(2 until 5, anchorHighlight(page, 2, 5, "   "))
        assertEquals(2 until 5, anchorHighlight(page, 2, 5, "없는 말"))
    }

    @Test
    fun `numbers past the end of the page are pulled back onto the words`() {
        // 다른 엔진의 층이 더 짧다(합자를 하나로 냈다) — 번호가 쪽 끝을 넘어도 죽지 않고 글을 찾는다.
        val page = layer("ﬁn 끝.")
        assertEquals(0..3, anchorHighlight(page, 40, 50, "ﬁn 끝"))
        assertEquals(40 until 50, anchorHighlight(page, 40, 50, "다른 글"))
    }
}
