package io.github.kgcaudit.reader.document.comic

import io.github.kgcaudit.reader.document.comic.SpreadSplit.Half
import io.github.kgcaudit.reader.document.comic.SpreadSplit.Part
import io.github.kgcaudit.reader.document.image.ImageSize
import kotlin.test.Test
import kotlin.test.assertEquals

class SpreadSplitTest {

    private val tall = ImageSize(1000, 1450)
    private val spread = ImageSize(2000, 1450)

    @Test
    fun `a spread becomes two parts, the reading side first`() {
        val sizes = listOf(tall, spread, tall)
        assertEquals(listOf(Part(0, Half.WHOLE), Part(1, Half.LEFT), Part(1, Half.RIGHT), Part(2, Half.WHOLE)), SpreadSplit.parts(sizes, rightToLeft = false))
        // 오→왼 책은 오른쪽 반이 먼저 — 일본 만화 펼침면의 앞 쪽은 오른쪽이다.
        assertEquals(listOf(Part(0, Half.WHOLE), Part(1, Half.RIGHT), Part(1, Half.LEFT), Part(2, Half.WHOLE)), SpreadSplit.parts(sizes, rightToLeft = true))
    }

    @Test
    fun `a slightly wide page, a square picture and an unknown size are not cut`() {
        // 망가뜨린 경우: 조금 넓은 표지(1.1) · 정사각 · 크기를 모르는 깨진 쪽.
        val sizes = listOf(ImageSize(1100, 1000), ImageSize(1000, 1000), null)
        assertEquals(SpreadSplit.whole(3), SpreadSplit.parts(sizes, rightToLeft = false))
    }

    @Test
    fun `a book page maps to the first part showing it, so the place never slides`() {
        val parts = SpreadSplit.parts(listOf(tall, spread, tall), rightToLeft = false)
        assertEquals(listOf(0, 1, 3), (0..2).map { SpreadSplit.partOf(parts, it) })
        // 책 끝을 넘어선 쪽은 마지막 조각 · 빈 책은 0.
        assertEquals(3, SpreadSplit.partOf(parts, 9))
        assertEquals(0, SpreadSplit.partOf(emptyList(), 2))
        assertEquals(ImageSize(1000, 1450), SpreadSplit.sizeOf(parts[1], spread))
        assertEquals(tall, SpreadSplit.sizeOf(parts[0], tall))
    }

    @Test
    fun `uneven outer margins are trimmed evenly so the fold stays in the middle`() {
        // 왼쪽 10% · 오른쪽 2% 를 걷은 상자. 그대로 걸고 가운데를 가르면 가르는 자리가 접힌 자리에서 4% 밀린다.
        val found = MarginTrim.Frame(0.10f, 0.05f, 0.98f, 0.95f)
        val split = SpreadSplit.splitFrame(found)
        assertEquals(0.02f, split.left, 1e-5f)
        assertEquals(0.98f, split.right, 1e-5f)
        assertEquals(0.05f, split.top)
        assertEquals(0.95f, split.bottom)
        // 잘린 그림의 가운데 = 원래 가운데(접힌 자리).
        assertEquals(0.5f, (split.left + split.right) / 2, 1e-6f)
        // 반 조각의 상자: 바깥 변만 걷히고 안쪽(접힌 자리) 변은 그대로. 두 반의 폭이 같다.
        val left = SpreadSplit.halfFrame(found, Half.LEFT)
        val right = SpreadSplit.halfFrame(found, Half.RIGHT)
        assertEquals(1f, left.right)
        assertEquals(0f, right.left)
        assertEquals(left.width, right.width, 1e-6f)
        // 걷을 것이 없는 상자는 그대로, 한쪽 바깥 변이 0 이면 좌우는 걷지 않는다(위아래만).
        assertEquals(MarginTrim.Frame.WHOLE, SpreadSplit.splitFrame(MarginTrim.Frame.WHOLE))
        assertEquals(MarginTrim.Frame(0f, 0.05f, 1f, 0.95f), SpreadSplit.splitFrame(MarginTrim.Frame(0f, 0.05f, 0.9f, 0.95f)))
        // 망가뜨린 상자(반을 넘게 걷는다는 값)도 그림을 다 지우지 않는다.
        val broken = SpreadSplit.splitFrame(MarginTrim.Frame(0.7f, 0f, 0.3f, 1f))
        assertEquals(true, broken.width > 0f)
    }
}
