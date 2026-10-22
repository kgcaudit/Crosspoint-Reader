package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.document.comic.ComicView
import io.github.kgcaudit.reader.document.comic.Webtoon
import io.github.kgcaudit.reader.document.image.ImageSize
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 웹툰 보기(구상안 39): 기둥의 칸 · 그림 폭 · 자동 스크롤 · 자리 저장. */
class WebtoonViewTest {
    private val ep1 = listOf(ImageSize(800, 1600), ImageSize(800, 12000))
    private val ep2 = listOf(ImageSize(800, 2400), null)

    @Test
    fun `pictures follow each other without gaps and a seam stands before the next episode`() {
        val cells = IosWebtoon.cells(listOf(ep1, ep2), 400f, 1f)
        val pictures = cells.filter { !it.seam }
        // 칸은 위에서 아래로 틈 없이: 한 칸의 아래가 다음 칸의 위(경계 띠 자리만 비운다).
        for ((a, b) in cells.zipWithNext()) assertTrue(abs(a.bottom - b.top) < 0.01f, "${a.bottom} → ${b.top}")
        val seam = cells.single { it.seam }
        assertEquals(1, seam.episode)
        assertEquals(IosWebtoon.SEAM, seam.height)
        assertEquals(800f + 6000f, seam.top, "after episode 1 (400 wide: 800 + 6000)")
        assertTrue(pictures.count { it.episode == 0 && it.index == 1 } > 1, "a 12000px picture is cut into strips")
        assertTrue(pictures.all { !it.known || it.rowTo - it.rowFrom <= Webtoon.MAX_STRIP })
    }

    @Test
    fun `a picture with an unknown size keeps a square place so the broken notice shows`() {
        val cells = IosWebtoon.cells(listOf(ep2), 400f, 1f)
        val broken = cells.single { it.index == 1 }
        assertEquals(false, broken.known)
        assertEquals(400f, broken.height)
    }

    @Test
    fun `strips are finer on a sharp screen so no strip exceeds the limit in pixels`() {
        val one = IosWebtoon.cells(listOf(listOf(ImageSize(100, 4000))), 400f, 1f).size
        val three = IosWebtoon.cells(listOf(listOf(ImageSize(100, 4000))), 400f, 3f).size
        assertTrue(three > one, "$one → $three")
        // 화면에서도 띠 하나가 한도 안.
        assertTrue(IosWebtoon.cells(listOf(listOf(ImageSize(100, 4000))), 400f, 3f).all { it.height * 3f <= Webtoon.MAX_STRIP + 1 })
    }

    @Test
    fun `only the cells on screen are loaded`() {
        val cells = IosWebtoon.cells(listOf(ep1), 400f, 1f)
        val shown = IosWebtoon.visible(cells, 0f, 700f)
        assertEquals(listOf(0), shown, "the first picture is 800pt tall")
        assertTrue(IosWebtoon.visible(cells, 790f, 900f).size == 2)
        // 지나온 칸은 내려놓는다 — 위 칸이 남으면 긴 화를 읽는 동안 그림이 쌓인다.
        assertTrue(0 !in IosWebtoon.visible(cells, 2000f, 2100f))
    }

    @Test
    fun `the picture width is remembered per screen class and broken values fall back`() {
        assertEquals(100, IosWebtoon.percent(-1, wide = false))
        assertEquals(70, IosWebtoon.percent(-1, wide = true))
        assertEquals(70, IosWebtoon.percent(250, wide = true), "out of range")
        assertEquals(65, IosWebtoon.percent(63, wide = false), "snapped to a 5% step")
        assertEquals(40, IosWebtoon.percentAt(-1f))
        assertEquals(100, IosWebtoon.percentAt(1f))
        assertEquals(70, IosWebtoon.percentAt(0.5f))
    }

    @Test
    fun `auto scroll speeds match Android`() {
        assertEquals(listOf(25f, 15f, 10f, 7f, 5f), IosWebtoon.secondsPerScreen)
        assertEquals(2, IosWebtoon.speed(9), "unknown is normal")
        assertEquals("보통", IosWebtoon.speedLabels[IosWebtoon.speed(-3)])
    }

    @Test
    fun `the place inside a picture is saved and the chosen view is read back`() {
        val store = ReadingStore(NSTemporaryDirectory() + "olo-toon-" + NSUUID().UUIDString + ".tsv")
        store.saveComic("u", 3, 10, false, null, 0.4f)
        assertEquals(0.4f, store.comicProgress()["u"]!!.offset)
        store.saveComic("u", 3, 10, false, null, 7f)
        assertEquals(1f, store.comicProgress()["u"]!!.offset, "kept inside the picture")
        store.saveComic("u", 3, 10, false, null, null)
        assertNull(store.comicProgress()["u"]!!.offset, "page view: no place inside")
        store.setComicSetting("VIEW", "고양이우체국", "WEBTOON")
        store.setComicSetting("VIEW", "망가진것", "SIDEWAYS")
        assertEquals(ComicView.WEBTOON, store.comicOverrides().view["고양이우체국"])
        assertNull(store.comicOverrides().view["망가진것"], "an unknown view is automatic")
    }
}
