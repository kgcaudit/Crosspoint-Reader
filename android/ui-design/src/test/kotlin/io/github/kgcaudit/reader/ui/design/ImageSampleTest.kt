package io.github.kgcaudit.reader.ui.design

import org.junit.Test
import kotlin.test.assertEquals

class ImageSampleTest {

    @Test
    fun `a picture is never decoded smaller than the box it is drawn in`() {
        // 4000×3000 사진을 1000×700 상자에: 2배(2000×1500)까지는 상자보다 크고, 4배(1000×750)도 크다. 8배면 500 — 흐리다.
        assertEquals(4, sampleKeeping(4000, 3000, 1000, 700))
        // 상자보다 작은 그림은 솎지 않는다.
        assertEquals(1, sampleKeeping(300, 200, 1000, 700))
        // 한 변만: 폭 1080 에 맞추는 긴 띠는 높이와 상관없이 폭으로만.
        assertEquals(2, sampleKeeping(2400, 90_000, 1080, 0))
        // 앞 셈에 이어 더 솎는다(표지: 높이로 솎은 뒤 폭으로).
        assertEquals(8, sampleKeeping(20_000, 500, 2000, 0, from = 2))
    }

    @Test
    fun `nothing to keep does not loop forever`() {
        // 두 한도가 모두 0 이면 이전 셈은 끝나지 않았다.
        assertEquals(1, sampleKeeping(4000, 3000, 0, 0))
    }

    @Test
    fun `a long strip is thinned until it fits the memory limit`() {
        // 높이 30000 을 4096 안으로: 8배(3750).
        assertEquals(8, sampleWithin(30_000, 4096))
        assertEquals(1, sampleWithin(4096, 4096))
        // 360×25000 띠를 화소 1,000,000 안으로: 2배는 180×12500 = 2,250,000, 4배는 90×6250 = 562,500.
        assertEquals(4, sampleWithinPixels(360, 25_000, 1_000_000))
    }
}
