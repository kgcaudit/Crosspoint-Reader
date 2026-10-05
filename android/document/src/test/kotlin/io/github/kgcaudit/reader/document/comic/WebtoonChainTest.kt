package io.github.kgcaudit.reader.document.comic

import io.github.kgcaudit.reader.document.image.ImageSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebtoonChainTest {

    // 폭 100 기둥: 1화는 높이 1000 그림 둘(2000), 2화는 500 그림 하나(500). 경계 띠 50.
    private val one = WebtoonColumn(listOf(ImageSize(100, 1000), ImageSize(100, 1000)), 100f)
    private val two = WebtoonColumn(listOf(ImageSize(100, 500)), 100f)
    private val chain = WebtoonChain(listOf(one, two), seam = 50f)

    @Test
    fun `episodes follow each other with a seam between them`() {
        assertEquals(listOf(0f, 2050f), chain.starts)
        assertEquals(2550f, chain.total)
        assertEquals(2000f, chain.endOf(0))
    }

    @Test
    fun `the seam already belongs to the next episode`() {
        // 경계 띠가 화면 위에 걸리면 다음 화를 보는 것 — 아래 줄 · 책갈피가 다음 화를 가리킨다.
        assertEquals(0, chain.episodeAt(1999f))
        assertEquals(1, chain.episodeAt(2000f))
        assertEquals(Triple(1, 0, 0f), chain.at(2010f), "경계 띠 안인데 다음 화의 처음이 아니다")
        assertEquals(Triple(0, 1, 0.5f), chain.at(1500f))
        assertEquals(1, chain.episodeAt(99999f), "끝을 넘은 자리가 마지막 화가 아니다")
    }

    @Test
    fun `a place written as episode, picture and fraction comes back to the same height`() {
        for (y in listOf(0f, 750f, 1999f, 2050f, 2300f)) {
            val (k, i, f) = chain.at(y)
            assertEquals(y, chain.offsetOf(k, i, f), 0.5f, "$y 에서 적은 자리가 미끄러졌다")
        }
    }

    @Test
    fun `percent and the end are counted inside the episode on screen`() {
        // 화면 높이 400: 1화는 0~1600 을 움직인다.
        assertEquals(0.5f, chain.fraction(800f, 400f), 0.001f)
        assertFalse(chain.reachedEnd(0, 1500f, 400f))
        assertTrue(chain.reachedEnd(0, 1600f, 400f))
        // 화면보다 짧은 2화도 0 으로 나누지 않는다.
        assertEquals(1f, chain.fraction(2300f, 600f))
    }

    @Test
    fun `an empty chain answers without crashing`() {
        val empty = WebtoonChain(emptyList(), 50f)
        assertEquals(0f, empty.total)
        assertEquals(Triple(0, 0, 0f), empty.at(100f))
        assertEquals(0f, empty.fraction(10f, 10f))
    }
}
