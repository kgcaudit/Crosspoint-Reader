package io.github.kgcaudit.reader.ui.design

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 넘김 소리의 방향(0.48.0): 넘어가는 종이 쪽에서 소리가 시작해 반대쪽으로 쓸려 간다. */
class TurnSoundSideTest {

    @Test
    fun `the sound starts on the side of the paper that turns`() {
        // 왼→오 책: 다음 쪽은 오른쪽 종이, 이전 쪽은 왼쪽 종이. 오→왼 만화는 반대.
        assertEquals(TurnSide.Right, turnSide(forward = true, mirrored = false))
        assertEquals(TurnSide.Left, turnSide(forward = false, mirrored = false))
        assertEquals(TurnSide.Left, turnSide(forward = true, mirrored = true))
        assertEquals(TurnSide.Right, turnSide(forward = false, mirrored = true))
    }

    @Test
    fun `direction is used only where left and right really are left and right`() {
        // 세로로 든 휴대폰 스피커는 위 · 아래에 있다 — 좌우로 나눈 소리가 위아래로 갈라진다.
        assertEquals(false, spatialTurnSound(headphones = false, landscape = false, widthDp = 393))
        assertTrue(spatialTurnSound(headphones = true, landscape = false, widthDp = 393))
        assertTrue(spatialTurnSound(headphones = false, landscape = true, widthDp = 851))
        assertTrue(spatialTurnSound(headphones = false, landscape = false, widthDp = 673), "펼친 폴더블")
        assertNull(turnSweep(TurnSide.Right, spatial = false))
        assertNull(turnSweep(null, spatial = true))
    }

    @Test
    fun `a right page sweeps from right to a little past the middle`() {
        val (from, to) = assertNotNull(turnSweep(TurnSide.Right, spatial = true))
        assertTrue(from > 0.5f && to < 0f && to > -0.5f, "$from → $to")
        val (lFrom, lTo) = assertNotNull(turnSweep(TurnSide.Left, spatial = true))
        assertEquals(-from, lFrom)
        assertEquals(-to, lTo)
    }

    @Test
    fun `the swept sound begins loud on its side, ends on the other, and never clips`() {
        val mono = ShortArray(1000) { Short.MAX_VALUE }
        val (from, to) = turnSweep(TurnSide.Right, spatial = true)!!
        val st = sweepStereo(mono, from, to)
        val firstL = st[0].toInt(); val firstR = st[1].toInt()
        val lastL = st[st.size - 2].toInt(); val lastR = st[st.size - 1].toInt()
        assertTrue(firstR > firstL * 2, "처음에 오른쪽이 크지 않다: L=$firstL R=$firstR")
        assertTrue(lastL > lastR, "끝에 왼쪽으로 넘어가지 않았다: L=$lastL R=$lastR")
        // 가장 큰 소리도 잘리지 않는다(넘치면 부호가 뒤집혀 "딱" 소리가 난다).
        assertTrue(st.all { it >= 0 }, "넘친 표본이 있다")
        // 가운데는 원래 크기 그대로 — 방향을 넣었다고 소리가 작아지거나 커지지 않는다.
        val center = sweepStereo(shortArrayOf(10000), 0f, 0f)
        assertTrue(center.all { kotlin.math.abs(it - 10000) <= 1 }, center.toList().toString())
    }

    @Test
    fun `the shipped sounds can be read and written back as stereo`() {
        for (name in listOf("turn_rustle", "turn_swish", "turn_tap")) {
            val (channels, rate, mono) = assertNotNull(readPcm16(File("src/main/res/raw/$name.wav").readBytes()), name)
            assertEquals(1, channels, name)
            val wav = writeStereoPcm16(sweepStereo(mono, 0.7f, -0.3f), rate)
            val (c2, r2, s2) = assertNotNull(readPcm16(wav))
            assertEquals(2, c2); assertEquals(rate, r2); assertEquals(mono.size * 2, s2.size)
        }
    }

    @Test
    fun `a broken sound file is refused instead of read as noise`() {
        val good = writeStereoPcm16(ShortArray(8), 44100)
        assertNull(readPcm16(ByteArray(0)))
        assertNull(readPcm16("RIFF0000WAVEjunk".toByteArray()))
        // data 조각이 파일보다 길다고 적힌 것(잘린 파일).
        assertNull(readPcm16(good.copyOf(good.size - 4)))
        // 8비트 소리는 모른다.
        assertNull(readPcm16(good.copyOf().also { it[34] = 8 }))
    }
}
