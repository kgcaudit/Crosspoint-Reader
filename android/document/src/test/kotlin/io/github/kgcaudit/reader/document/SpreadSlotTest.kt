package io.github.kgcaudit.reader.document

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 두 쪽 보기의 표지 · 마지막 쪽 자리(0.46.0, 종이책의 규칙). */
class SpreadSlotTest {

    @Test
    fun `the cover sits on the later side and a last lonely page on the earlier side`() {
        assertEquals(SpreadSlot.Later, SpreadSlot.of(listOf(0), first = true, wide = false, coverAlone = true))
        assertEquals(SpreadSlot.Pair, SpreadSlot.of(listOf(1, 2), first = false, wide = false, coverAlone = true))
        assertEquals(SpreadSlot.Earlier, SpreadSlot.of(listOf(9), first = false, wide = false, coverAlone = true))
        // 펼침면 그림은 그 자체가 두 쪽 — 가운데.
        assertEquals(SpreadSlot.Center, SpreadSlot.of(listOf(3), first = false, wide = true, coverAlone = true))
        // 표지를 혼자 두지 않는 설정에서 혼자인 첫 쪽(한 쪽짜리 책)은 앞 쪽 자리.
        assertEquals(SpreadSlot.Earlier, SpreadSlot.of(listOf(0), first = true, wide = false, coverAlone = false))
        // 깨진 입력(빈 판)은 가운데 — 어느 쪽에 붙일 근거가 없다.
        assertEquals(SpreadSlot.Center, SpreadSlot.of(emptyList(), first = true, wide = false, coverAlone = true))
    }

    @Test
    fun `right to left mirrors the sides`() {
        // 왼→오: 표지 오른쪽, 마지막 쪽 왼쪽. 오→왼: 반대.
        assertFalse(SpreadSlot.Later.onLeft(rightToLeft = false))
        assertTrue(SpreadSlot.Earlier.onLeft(rightToLeft = false))
        assertTrue(SpreadSlot.Later.onLeft(rightToLeft = true))
        assertFalse(SpreadSlot.Earlier.onLeft(rightToLeft = true))
    }
}
