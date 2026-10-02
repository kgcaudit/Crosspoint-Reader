package io.github.kgcaudit.reader.document.comic

import kotlin.test.Test
import kotlin.test.assertEquals

class NaturalOrderTest {

    @Test
    fun `2 comes before 10 in names and in pages`() {
        assertEquals(listOf("1.jpg", "2.jpg", "10.jpg"), listOf("10.jpg", "2.jpg", "1.jpg").sortedWith(NaturalOrder))
        assertEquals(listOf("작품 2권", "작품 10권"), listOf("작품 10권", "작품 2권").sortedWith(NaturalOrder))
    }

    @Test
    fun `case is ignored and long numbers do not overflow`() {
        assertEquals(listOf("a2", "B3"), listOf("B3", "a2").sortedWith(NaturalOrder))
        val big = listOf("x99999999999999999999999", "x100000000000000000000000")
        assertEquals(big, big.reversed().sortedWith(NaturalOrder))
    }

    @Test
    fun `the order is total so a list sorts the same every time`() {
        // 값이 같고 0 만 다른 이름 · 대소문자만 다른 이름도 늘 같은 자리에 — 아니면 훑을 때마다 순서가 흔들린다.
        val names = listOf("p01", "p1", "P1", "p001", "p1")
        val once = names.sortedWith(NaturalOrder)
        assertEquals(once, names.reversed().sortedWith(NaturalOrder))
        assertEquals(0, NaturalOrder.compare("p1", "p1"))
    }
}
