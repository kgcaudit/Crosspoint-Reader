package io.github.kgcaudit.reader.pdf

import kotlin.test.Test
import kotlin.test.assertEquals

/** 엔진이 준 글 조각을 글자 층으로 엮기(안드로이드 PdfRenderer 전용 — iOS 는 PDFKit 이 번호를 맞춰 준다). PageTextTest 에서 떼었다. */
class LayerAssemblyTest {
    /**
     * 안드로이드 PDF 엔진(pdfClient)을 그대로 흉내 낸다: 번호 [first]..[last] 만 글이 있고(앞뒤 공백 · 하이픈은 잘림),
     * 번호 2 는 "-\r\n" 으로 늘어나며, 고르기는 [시작, 끝) 이다.
     */
    private class FakeClient(val codes: IntArray) {
        val first = codes.indexOfFirst { it !in listOf(0x20, 0x2D, 2, 13, 10) }
        val last = codes.indexOfLast { it !in listOf(0x20, 0x2D, 2, 13, 10) }
        fun textOf(i: Int) = if (codes[i] == 2) "-\r\n" else codes[i].toChar().toString()
        /** 쪽 전체 글(textContents): 잘리고 늘어난 것. */
        val whole = (first..last).joinToString("") { textOf(it) }
        fun select(from: Int, to: Int): Pair<String, FloatArray?> {
            val a = from.coerceIn(first, last + 1)
            val b = to.coerceIn(first, last + 1)
            val s = (a until b).joinToString("") { textOf(it) }
            // 글자 i 의 네모: x = i × 0.01 — 어느 번호의 네모인지 알아볼 수 있게.
            return s to (a until b).firstOrNull { codes[it] != 0x20 }?.let { floatArrayOf(it * 0.01f, 0.1f, it * 0.01f + 0.01f, 0.12f) }
        }
    }

    @Test
    fun `every letter keeps its own box when the engine trims and expands the page text`() {
        // 앞머리 공백 둘(잘림) · 가운데 번호 2 둘(세 글자로 늘어남). 쪽 전체 글의 번호로 고르면 뒤 글자의 네모가 밀렸다.
        val codes = intArrayOf(0x20, 0x20, '가'.code, '나'.code, 2, '다'.code, 2, '라'.code, '마'.code)
        val engine = FakeClient(codes)
        val layer = assembleLayer(engine.whole.length + 64) { a, b -> engine.select(a, b) }
        assertEquals("가나\u0002다\u0002라마", layer.text)
        // "마" 는 번호 8 의 네모(x = 0.08)를 가진다 — 밀렸다면 다른 글자의 네모다.
        val ma = layer.text.indexOf('마')
        assertEquals(0.08f, layer.box(ma)!!.left, 1e-4f)
        assertEquals(0.02f, layer.box(0)!!.left, 1e-4f)
    }

}
