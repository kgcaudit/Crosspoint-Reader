package io.github.kgcaudit.reader.ios

import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 넘김 소리의 자리(구상안 23A, 디자인 인계 §6-7). 안드로이드 `PageTurnFxTest` 와 같은 기대값. */
class TurnFxTest {
    @Test
    fun `sound sweeps only where left and right are real`() {
        // 세로로 든 휴대폰 스피커는 위 · 아래 — 가운데.
        assertTrue(!TurnFx.spatial(headphones = false, landscape = false, widthPt = 402f))
        assertTrue(TurnFx.spatial(headphones = true, landscape = false, widthPt = 402f))
        assertTrue(TurnFx.spatial(headphones = false, landscape = true, widthPt = 874f))
        assertTrue(TurnFx.spatial(headphones = false, landscape = false, widthPt = 834f))
        assertNull(TurnFx.sweep(forward = true, spatial = false))
    }

    @Test
    fun `the next page sweeps from the right past the middle and back pages from the left`() {
        assertContentEquals(floatArrayOf(0.7f, -0.3f), TurnFx.sweep(forward = true, spatial = true))
        assertContentEquals(floatArrayOf(-0.7f, 0.3f), TurnFx.sweep(forward = false, spatial = true))
    }

    @Test
    fun `the sweep keeps the middle at the original loudness and never clips`() {
        val mono = ShortArray(101) { 20000 }
        val stereo = TurnFx.sweepStereo(mono, 0.7f, -0.3f)
        // 처음은 오른쪽이 크고, 끝은 왼쪽이 크다(가운데를 조금 지난다).
        assertTrue(stereo[1] > stereo[0])
        assertTrue(stereo[200] > stereo[201])
        // 큰 쪽은 원래 크기에서 멈춘다 — 넘으면 소리가 깨진다.
        assertTrue(stereo.all { abs(it.toInt()) <= 20000 })
        // 가운데(pan 0)는 양쪽 모두 원래 크기.
        val mid = TurnFx.sweepStereo(ShortArray(3) { 10000 }, 0f, 0f)
        assertEquals(10000, mid[0].toInt(), "left")
        assertEquals(10000, mid[1].toInt(), "right")
    }

    @Test
    fun `a broken or stereo wav is not swept and a mono one round trips`() = runTest {
        assertNull(TurnFx.readPcm16("not a wav at all".encodeToByteArray()))
        val stereo = TurnFx.writeStereoPcm16(ShortArray(8) { it.toShort() }, 44100)
        val (channels, rate, samples) = assertNotNull(TurnFx.readPcm16(stereo))
        assertEquals(2, channels)
        assertEquals(44100, rate)
        assertContentEquals(ShortArray(8) { it.toShort() }, samples)
        // 잘린 파일(data 크기가 파일보다 큼)은 버린다.
        assertNull(TurnFx.readPcm16(stereo.copyOf(30)))
    }
}
