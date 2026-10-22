@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.document.comic.MarginTrim
import io.github.kgcaudit.reader.document.comic.SpreadSplit
import io.github.kgcaudit.reader.document.comic.SpreadSplit.Half
import io.github.kgcaudit.reader.document.comic.SpreadSplit.Part
import io.github.kgcaudit.reader.document.image.ImageSize
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.create
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 만화 쪽 다듬기(구상안 38): 펼침면 나눔 · 여백 상자 · 색 행렬 · 반 자리 저장. */
class ComicTrimTest {
    private val tall = ImageSize(700, 1000)
    private val wide = ImageSize(1400, 1000)
    private val sizes = listOf(tall, tall, wide, tall)

    @Test
    fun `a spread is read in halves left first and right first in a right-to-left work`() {
        val ltr = IosComicLayout.units(sizes, twoPages = false, coverAlone = true, split = true, rightToLeft = false)
        assertEquals(listOf(Part(2, Half.LEFT)), ltr[2])
        assertEquals(listOf(Part(2, Half.RIGHT)), ltr[3])
        assertEquals(5, ltr.size, "four pages, one of them in two halves")
        val rtl = IosComicLayout.units(sizes, twoPages = false, coverAlone = true, split = true, rightToLeft = true)
        assertEquals(Half.RIGHT, rtl[2].single().half)
    }

    @Test
    fun `turning splitting off or seeing two pages keeps a spread whole`() {
        val off = IosComicLayout.units(sizes, twoPages = false, coverAlone = true, split = false, rightToLeft = false)
        assertEquals(4, off.size)
        assertTrue(off.all { u -> u.single().half == Half.WHOLE })
        // 두 쪽 보기에서 펼침면은 원래 한 판 — 나누기가 켜져 있어도 나누지 않는다.
        val two = IosComicLayout.units(sizes, twoPages = true, coverAlone = true, split = true, rightToLeft = false)
        assertTrue(two.flatten().all { it.half == Half.WHOLE })
        assertEquals(listOf(listOf(0), listOf(1), listOf(2), listOf(3)), two.map { u -> u.map { it.page } }, "the cover and the spread stand alone")
    }

    @Test
    fun `the saved half reopens on that half and falls back to the page when there are no halves`() {
        val split = IosComicLayout.units(sizes, false, true, true, false)
        assertEquals(3, IosComicLayout.indexOf(split, 2, Half.RIGHT))
        assertEquals(2, IosComicLayout.indexOf(split, 2, null), "no half: the first half")
        val whole = IosComicLayout.units(sizes, false, true, false, false)
        assertEquals(2, IosComicLayout.indexOf(whole, 2, Half.RIGHT), "a half saved elsewhere opens the whole page")
        // 쪽 수가 줄었으면(파일을 바꿨다) 가장 가까운 앞 판.
        assertEquals(3, IosComicLayout.indexOf(whole, 40, null))
        assertEquals(0, IosComicLayout.indexOf(emptyList(), 3, null))
    }

    @Test
    fun `two pages share one margin box and a half keeps the fold`() {
        val a = MarginTrim.Frame(0.10f, 0.05f, 0.90f, 0.95f)
        val b = MarginTrim.Frame(0.02f, 0.08f, 0.97f, 0.90f)
        val shared = IosComicLayout.partFrames(listOf(Part(0, Half.WHOLE), Part(1, Half.WHOLE)), listOf(a, b))
        assertEquals(shared[0], shared[1], "both pages scale the same — the seam lines up")
        assertEquals(0.02f, shared[0].left)
        val half = IosComicLayout.partFrames(listOf(Part(2, Half.LEFT)), listOf(MarginTrim.Frame(0.10f, 0f, 0.96f, 1f)))
        assertEquals(1f, half.single().right, "the fold side of a left half is never cut")
        assertEquals(IosComicLayout.partFrames(listOf(Part(0, Half.WHOLE)), listOf(a)).single(), a)
        // 짝이 안 맞으면(깨진 입력) 자르지 않는다.
        assertEquals(listOf(MarginTrim.Frame.WHOLE), IosComicLayout.partFrames(listOf(Part(0, Half.WHOLE)), emptyList()))
    }

    /** 흰 여백 [m] 픽셀 안에 검은 그림이 찬 RGBA 그림. */
    private fun page(w: Int, h: Int, m: Int, paper: Int = 0xFF): NSData {
        val bytes = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val inside = x in m until w - m && y in m until h - m
            val v = if (inside) ((x * 7 + y * 13) % 200) else paper
            val i = (y * w + x) * 4
            bytes[i] = v.toByte(); bytes[i + 1] = v.toByte(); bytes[i + 2] = v.toByte(); bytes[i + 3] = 0xFF.toByte()
        }
        return bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
    }

    @Test
    fun `a scanned margin is found from the picture bytes`() {
        val f = IosComicLayout.frameOfRgba(100, 140, 400, page(100, 140, 10))
        assertTrue(f.left > 0.05f && f.left < 0.1f, "left ${f.left}")
        assertTrue(f.bottom < 0.95f, "bottom ${f.bottom}")
        // 여백이 없는 그림은 자르지 않는다.
        assertEquals(MarginTrim.Frame.WHOLE, IosComicLayout.frameOfRgba(100, 140, 400, page(100, 140, 0)))
    }

    @Test
    fun `broken picture bytes are not cut`() {
        assertEquals(MarginTrim.Frame.WHOLE, IosComicLayout.frameOfRgba(100, 140, 400, page(10, 10, 2)), "too few bytes")
        assertEquals(MarginTrim.Frame.WHOLE, IosComicLayout.frameOfRgba(100, 140, 100, page(100, 140, 10)), "rows shorter than the width")
        assertEquals(MarginTrim.Frame.WHOLE, IosComicLayout.frameOfRgba(0, 0, 0, page(4, 4, 1)))
    }

    @Test
    fun `the colour matrices match Android`() {
        assertNull(IosComicLayout.colorMatrix(0), "off costs nothing")
        assertNull(IosComicLayout.colorMatrix(9), "an unknown mode is off")
        val clear = IosComicLayout.colorMatrix(1)!!
        // 흐린 먹(0.25)은 검정으로, 바랜 종이(0.86)는 흰색으로.
        assertTrue(abs(0.25f * clear[0] + clear[4]) < 1e-4f)
        assertTrue(abs(0.86f * clear[0] + clear[4] - 1f) < 1e-4f)
        assertEquals(0f, clear[1], "clear keeps colours apart")
        val gray = IosComicLayout.colorMatrix(2)!!
        assertEquals(gray[0], gray[5], "every channel reads the same brightness")
        assertTrue(abs((gray[0] + gray[1] + gray[2]) - clear[0]) < 1e-4f, "gray is clear without colour")
        assertEquals(1f, gray[18], "alpha untouched")
    }

    @Test
    fun `the half being read is saved and cleared when a whole page is read`() {
        val store = ReadingStore(NSTemporaryDirectory() + "olo-trim-" + NSUUID().UUIDString + ".tsv")
        store.saveComic("u", 2, 4, false, "RIGHT")
        assertEquals(SpreadSplit.Half.RIGHT, store.comicProgress()["u"]!!.half)
        store.saveComic("u", 3, 4, false, null)
        assertNull(store.comicProgress()["u"]!!.half)
        store.saveComic("u", 3, 4, false, "WHOLE")
        assertNull(store.comicProgress()["u"]!!.half, "WHOLE is no half")
        assertEquals(" · 왼쪽 반", IosComicLayout.halfLabel(Half.LEFT))
        assertEquals("", IosComicLayout.halfLabel(Half.WHOLE))
    }

    @Test
    fun `a work can split or keep spreads whole on its own`() {
        val store = ReadingStore(NSTemporaryDirectory() + "olo-trim-" + NSUUID().UUIDString + ".tsv")
        store.setComicSetting("SPLIT", "원피스", "0")
        assertEquals(false, store.comicOverrides().splitSpreads["원피스"])
        store.setComicSetting("SPLIT", "원피스", null)
        assertNull(store.comicOverrides().splitSpreads["원피스"], "follow the setting for all comics")
    }

    @Test
    fun `a wide picture is two pages unless the work says one`() {
        assertEquals(true, IosComicLayout.wideIsTwo(null), "automatic: a wide picture holds two pages")
        assertEquals(false, IosComicLayout.wideIsTwo(false))
        assertEquals(true, IosComicLayout.wideIsTwo(true))
        // 작품이 "한 장" 이면 한 쪽 보기에서도 나누지 않는다.
        val one = IosComicLayout.units(sizes, twoPages = false, coverAlone = true, split = IosComicLayout.wideIsTwo(false), rightToLeft = false)
        assertTrue(one.all { u -> u.single().half == Half.WHOLE })
        assertTrue(IosComicLayout.WIDE_AUTO_NOTE.startsWith("자동으로 판단했습니다"))
    }
}
