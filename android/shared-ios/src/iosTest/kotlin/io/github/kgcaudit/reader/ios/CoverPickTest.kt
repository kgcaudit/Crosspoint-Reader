package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.document.comic.SpreadSplit.Half
import io.github.kgcaudit.reader.document.image.ImageSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 표지 고르기(구상안 44): 저장 크기 · 판의 글 · 보던 장면에서 고를 곳. */
class CoverPickTest {
    @Test
    fun `a picked picture is stored at cover height and never enlarged`() {
        assertEquals(ImageSize(320, 480), IosCovers.fit(2000, 3000))
        // 작은 그림을 키우면 흐릿한 표지가 파일만 커진다.
        assertEquals(ImageSize(200, 300), IosCovers.fit(200, 300))
    }

    @Test
    fun `a panorama is limited by width so the file stays small`() {
        val size = IosCovers.fit(8000, 1000)!!
        assertEquals(IosCovers.MAX_WIDTH, size.width)
        assertTrue(size.height < IosCovers.HEIGHT)
    }

    @Test
    fun `a picture without a size is refused`() {
        assertNull(IosCovers.fit(0, 480))
        assertNull(IosCovers.fit(640, -1))
    }

    @Test
    fun `a work cover never shares a slot with a book of the same name`() {
        assertNotEquals("만화/바다 마을", IosCovers.workId("만화/바다 마을"))
        assertNotEquals(IosCovers.workId("a"), IosCovers.workId("b"))
    }

    @Test
    fun `the book panel says what cover is shown now`() {
        assertEquals("직접 고른 표지를 쓰고 있습니다.", IosCovers.bookMessage(custom = true, hasOwn = true, pdf = true))
        assertEquals("지금 표지는 파일 첫 쪽입니다.", IosCovers.bookMessage(custom = false, hasOwn = true, pdf = true))
        assertEquals("지금 표지는 책에 든 표지 그림입니다.", IosCovers.bookMessage(custom = false, hasOwn = true, pdf = false))
        assertTrue(IosCovers.bookMessage(custom = false, hasOwn = false, pdf = false).contains("대신 표지"))
        // 책에 표지가 없으면 "원래 표지" 는 없다 — 되돌아갈 곳은 대신 표지다.
        assertEquals("대신 표지로 되돌리기", IosCovers.revertLabel(hasOwn = false))
        assertEquals("원래 표지로 되돌리기", IosCovers.revertLabel(hasOwn = true))
    }

    @Test
    fun `the half buttons map to the half they name`() {
        IosCovers.PICK_HALVES.indices.forEach { i -> assertEquals(i, IosCovers.indexOf(IosCovers.halfAt(i))) }
        assertEquals(Half.LEFT, IosCovers.halfAt(IosCovers.PICK_HALVES.indexOf("왼쪽")))
        assertEquals(Half.RIGHT, IosCovers.halfAt(IosCovers.PICK_HALVES.indexOf("오른쪽")))
        assertEquals(Half.WHOLE, IosCovers.halfAt(IosCovers.PICK_HALVES.indexOf("통째로")))
    }

    @Test
    fun `the front half is chosen first like the automatic cover`() {
        assertEquals(Half.RIGHT, IosCovers.frontHalf(rightToLeft = false))
        assertEquals(Half.LEFT, IosCovers.frontHalf(rightToLeft = true))
    }

    @Test
    fun `what is saved is what the reader sees`() {
        // 반을 나눠 보는 중이면 그 반 — 버튼에 남은 선택은 상관없다.
        assertEquals(Half.LEFT, IosCovers.savedHalf(Half.LEFT, wide = false, chosen = Half.RIGHT))
        // 통째로 보이는 넓은 쪽만 고른 곳을 따른다.
        assertEquals(Half.RIGHT, IosCovers.savedHalf(Half.WHOLE, wide = true, chosen = Half.RIGHT))
        assertEquals(Half.WHOLE, IosCovers.savedHalf(Half.WHOLE, wide = true, chosen = Half.WHOLE))
        // 세로 쪽에 앞 쪽의 선택이 새면 반쪽 표지가 된다.
        assertEquals(Half.WHOLE, IosCovers.savedHalf(Half.WHOLE, wide = false, chosen = Half.LEFT))
    }

    @Test
    fun `the header says which page and half will be saved`() {
        assertEquals("5쪽 · 왼쪽 반", IosCovers.pickSubtitle(4, Half.LEFT, wide = true, failed = false))
        assertEquals("5쪽 · 오른쪽 반", IosCovers.pickSubtitle(4, Half.RIGHT, wide = false, failed = false))
        assertEquals("3쪽 — 표지로 쓸 곳을 고릅니다", IosCovers.pickSubtitle(2, Half.WHOLE, wide = true, failed = false))
        assertEquals("넘겨서 쪽을 고릅니다", IosCovers.pickSubtitle(2, Half.WHOLE, wide = false, failed = false))
        assertEquals("이 쪽을 표지로 쓸 수 없습니다", IosCovers.pickSubtitle(2, Half.LEFT, wide = true, failed = true))
    }

    @Test
    fun `toasts do not attach a particle to the name`() {
        assertEquals("‘바다 마을’ 표지를 바꿨습니다", IosCovers.changed("바다 마을"))
        assertEquals("표지를 바꿨습니다", IosCovers.changed(null))
        assertEquals("‘바다 마을’ 표지를 되돌렸습니다", IosCovers.reverted("바다 마을"))
    }
}
