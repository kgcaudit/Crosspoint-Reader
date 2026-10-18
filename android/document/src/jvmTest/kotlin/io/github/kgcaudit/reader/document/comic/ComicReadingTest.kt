package io.github.kgcaudit.reader.document.comic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComicReadingTest {

    private fun archive(id: String, name: String, info: ComicInfo? = null) =
        ComicUnit(id, name, listOf("C"), ComicUnitKind.ARCHIVE, info = info)

    private val units = listOf(
        archive("1", "별 01권.cbz"), archive("2", "별 02권.cbz"), archive("3", "별 03권.cbz"),
        archive("3b", "별_03.cbz"), archive("x", "별 외전.cbz"),
    )
    private val work = ComicShelf.group(units).single()

    @Test
    fun `right to left books turn forward from the left side`() {
        assertTrue(ComicReading.forward(screenNext = true, rightToLeft = false))
        assertFalse(ComicReading.forward(screenNext = false, rightToLeft = false))
        // 오→왼: 왼쪽을 누르면(화면의 "앞") 다음 쪽.
        assertTrue(ComicReading.forward(screenNext = false, rightToLeft = true))
        assertFalse(ComicReading.forward(screenNext = true, rightToLeft = true))
    }

    @Test
    fun `the direction chosen for a work beats comic info, and nothing chosen reads left to right`() {
        val manga = listOf(archive("m", "작품 01권.cbz", ComicInfo(rightToLeft = true)), archive("n", "작품 02권.cbz"))
        assertTrue(ComicReading.rightToLeft(ComicShelf.group(manga).single()))
        val key = ComicShelf.group(manga).single().key
        val chosen = ComicShelf.group(manga, ComicOverrides(rightToLeft = mapOf(key to false))).single()
        assertFalse(ComicReading.rightToLeft(chosen), "사람이 고른 왼→오가 ComicInfo 에 졌다")
        assertFalse(ComicReading.rightToLeft(work))
        assertNull(work.rightToLeft, "아무도 정하지 않았는데 값이 있다")
    }

    @Test
    fun `the next volume follows in order, also from the other copy, and the last has none`() {
        assertEquals("2권", ComicReading.nextAfter(work, "1")?.label)
        val third = work.entries.single { it.label == "3권" }
        val other = (listOf(third.unit) + third.copies).first { it.id != third.unit.id }.id
        assertEquals("외전", ComicReading.nextAfter(work, other)?.label, "사본으로 읽던 권의 다음을 못 찾았다")
        assertNull(ComicReading.nextAfter(work, "x"))
        assertNull(ComicReading.nextAfter(work, "없는 파일"))
    }

    @Test
    fun `resume picks the latest opened volume, or the next one once it is finished`() {
        assertNull(ComicReading.resume(work, emptyMap()))
        val reading = mapOf("1" to ComicProgress(179, 180, 10, finishedAtEpochMs = 10), "2" to ComicProgress(44, 182, 20))
        assertEquals("2권" to 44, ComicReading.resume(work, reading)!!.let { it.first.label to it.second!!.page })
        // 2권을 다 읽었다 → 3권 처음부터(진도 없음).
        val done = reading + ("2" to ComicProgress(181, 182, 30, finishedAtEpochMs = 30))
        val (next, p) = ComicReading.resume(work, done)!!
        assertEquals("3권", next.label)
        assertNull(p)
        // 마지막 권을 다 읽었으면 그 권 그대로.
        assertEquals("외전", ComicReading.resume(work, mapOf("x" to ComicProgress(0, 1, 5, 5)))!!.first.label)
    }

    @Test
    fun `progress fraction never leaves zero to one, even for broken counts`() {
        assertEquals(0.5f, ComicProgress(50, 101, 0).fraction)
        assertEquals(1f, ComicProgress(500, 10, 0).fraction)
        assertEquals(0f, ComicProgress(0, 0, 0).fraction)
        assertEquals(1f, ComicProgress(0, 1, 0, finishedAtEpochMs = 1).fraction)
        assertEquals(0f, ComicProgress(-3, 10, 0).fraction)
    }
}
