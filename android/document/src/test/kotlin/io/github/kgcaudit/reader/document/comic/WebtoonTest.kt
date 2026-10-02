package io.github.kgcaudit.reader.document.comic

import io.github.kgcaudit.reader.document.image.ImageSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebtoonTest {

    private val tall = ImageSize(800, 12000)
    private val page = ImageSize(1200, 1800)

    @Test
    fun `mostly tall pictures are a webtoon even with an ordinary cover`() {
        assertEquals(true, Webtoon.detect(listOf(page, tall, tall, tall)))
        // 펼침면 한두 장이 길쭉해도 만화다.
        assertEquals(false, Webtoon.detect(listOf(page, page, page, ImageSize(500, 1100))))
        // 반반이면 만화(넘치지 않으면 웹툰이 아니다).
        assertEquals(false, Webtoon.detect(listOf(page, tall)))
        assertNull(Webtoon.detect(listOf(null, null)), "크기를 모르는데 판별했다")
        assertEquals(true, Webtoon.detect(listOf(null, tall)), "깨진 그림 하나가 판별을 막았다")
    }

    @Test
    fun `a choice beats comic info which beats the picture shape`() {
        val tallPages = listOf(tall, tall)
        assertEquals(ComicView.WEBTOON, Webtoon.view(null, null, tallPages))
        assertEquals(ComicView.PAGE, Webtoon.view(ComicView.PAGE, ComicInfo(format = "Webtoon"), tallPages), "고른 쪽 넘김이 졌다")
        assertEquals(ComicView.WEBTOON, Webtoon.view(null, ComicInfo(format = "webtoon"), listOf(page)))
        assertEquals(ComicView.PAGE, Webtoon.view(null, ComicInfo(format = "Digital"), listOf(page)))
        assertEquals(ComicView.PAGE, Webtoon.view(null, null, emptyList()))
    }

    @Test
    fun `a very long picture is cut into even strips under the limit`() {
        val strips = Webtoon.strips(ImageSize(800, 30000))
        assertEquals(15, strips.size)
        assertTrue(strips.all { it.last - it.first + 1 <= Webtoon.MAX_STRIP })
        assertEquals(0, strips.first().first)
        assertEquals(29999, strips.last().last)
        // 빈틈도 겹침도 없다.
        strips.zipWithNext().forEach { (a, b) -> assertEquals(a.last + 1, b.first) }
        assertEquals(listOf(0 until 10), Webtoon.strips(ImageSize(10, 10)))
        // 고르게: 2049 줄은 1024 + 1025 이지 2048 + 1 이 아니다.
        assertEquals(listOf(1024, 1025), Webtoon.strips(ImageSize(10, 2049)).map { it.last - it.first + 1 })
        // 늘려 그리면 화면에서의 높이도 한도 안: 60px 폭을 786px 로(13.1배) 그리면 400줄도 셋으로.
        val scale = 786f / 60
        val stretched = Webtoon.strips(ImageSize(60, 400), scale = scale)
        assertEquals(3, stretched.size)
        assertTrue(stretched.all { (it.last - it.first + 1) * scale <= Webtoon.MAX_STRIP })
        assertEquals(399, stretched.last().last)
    }

    @Test
    fun `a position survives a width change because it is kept per picture`() {
        val sizes = listOf(ImageSize(800, 8000), null, ImageSize(400, 800))
        val narrow = WebtoonColumn(sizes, 400f)
        assertEquals(listOf(4000f, 400f, 800f), narrow.heights)
        assertEquals(5200f, narrow.total)
        assertEquals(0 to 0.5f, narrow.at(2000f))
        assertEquals(1 to 0.5f, narrow.at(4200f))
        val (i, f) = narrow.at(4800f)
        assertEquals(2, i)
        assertEquals(0.5f, f, 0.001f)
        // 넓은 기둥에서 같은 자리.
        val wide = WebtoonColumn(sizes, 1000f)
        assertEquals(2 to 0.5f, wide.at(wide.offsetOf(i, f)).let { it.first to (Math.round(it.second * 1000) / 1000f) })
        assertEquals(2 to 1f, narrow.at(99999f))
        assertEquals(0 to 0f, narrow.at(-5f))
        assertEquals(0 to 0f, WebtoonColumn(emptyList(), 400f).at(10f))
    }

    @Test
    fun `webtoon progress counts the part of the picture already read`() {
        assertEquals(0.5f, ComicProgress(1, 4, 0, offset = 1f).fraction)
        assertEquals(0.625f, ComicProgress(2, 4, 0, offset = 0.5f).fraction)
        assertEquals(1f, ComicProgress(3, 4, 0, offset = 7f).fraction)
    }
}
