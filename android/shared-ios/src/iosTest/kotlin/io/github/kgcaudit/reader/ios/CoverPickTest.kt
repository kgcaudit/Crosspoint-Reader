package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.document.comic.SpreadSplit.Half
import io.github.kgcaudit.reader.document.image.ImageSize
import io.github.kgcaudit.reader.document.comic.WebtoonPiece
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

    // ── 웹툰 틀(구상안 44 ⑤) ──

    private val viewW = 393.0
    private val viewH = 852.0
    private val column = 393.0

    @Test
    fun `the first frame is cover shaped and sits a little above the middle`() {
        val f = IosCovers.startFrame(viewW, viewH, column)
        assertEquals(IosCovers.ASPECT, f.height / f.width, 1e-9)
        assertTrue(f.width <= column * 0.8 + 1e-9)
        assertEquals(viewW / 2, f.x + f.width / 2, 1e-6)
        // 가운데가 화면 높이의 45% — 아래 단추에 가리지 않는다.
        assertEquals(viewH * 0.45, f.y + f.height / 2, 1e-6)
    }

    @Test
    fun `a frame dragged off screen comes back inside`() {
        val f = IosCovers.clampFrame(IosCovers.FrameBox(-80.0, 2000.0, 200.0), viewW, viewH, column)
        assertEquals(0.0, f.x)
        assertEquals(viewH - f.height, f.y, 1e-9)
    }

    @Test
    fun `pinching keeps the frame between a quarter of the column and the screen height`() {
        val f = IosCovers.startFrame(viewW, viewH, column)
        val small = IosCovers.resizeFrame(f, 0.01, viewW, viewH, column)
        assertEquals(column * 0.25, small.width, 1e-9)
        val big = IosCovers.resizeFrame(f, 100.0, viewW, viewH, column)
        assertTrue(big.height <= viewH + 1e-9)
        assertTrue(big.width <= column + 1e-9)
        // 크기만 바뀌고 자리는 지킨다(화면 끝에 닿지 않는 동안).
        val mid = IosCovers.resizeFrame(f, 0.8, viewW, viewH, column)
        assertEquals(f.x + f.width / 2, mid.x + mid.width / 2, 1e-6)
        assertEquals(f.y + f.height / 2, mid.y + mid.height / 2, 1e-6)
    }

    @Test
    fun `a frame over two pictures stitches both without a gap`() {
        // 폭 400 기둥에 높이 500 인 그림 둘. 틀이 400 ~ 600 이면 앞 그림 끝 100 과 뒤 그림 앞 100.
        val chain = IosWebtoon.chain(listOf(listOf(ImageSize(800, 1000), ImageSize(800, 1000))), 400f)
        val pieces = chain.pieces(0, 400f, 600f)
        assertEquals(listOf(0, 1), pieces.map { it.index })
        val out = IosCovers.TOON_OUT_WIDTH
        val draws = pieces.map { IosCovers.pieceDraw(it, 0.0, 1.0, 200.0, 800, 1000, out, IosCovers.HEIGHT)!! }
        assertEquals(0.0, draws[0].dstY, 1e-6)
        assertEquals(draws[0].dstY + draws[0].dstHeight, draws[1].dstY, 1e-6)
        assertEquals(IosCovers.HEIGHT.toDouble(), draws[1].dstY + draws[1].dstHeight, 1e-6)
        // 앞 그림은 아래 1/5(800 ~ 1000 행), 뒤 그림은 위 1/5.
        assertEquals(800, draws[0].srcY)
        assertEquals(0, draws[1].srcY)
        assertEquals(200, draws[1].srcHeight)
    }

    @Test
    fun `the part of a frame outside the column stays backdrop instead of stretching the picture`() {
        val piece = WebtoonPiece(0, 0f, 1f, 0f, 100f)
        // 틀이 기둥 왼쪽 밖 -0.5 ~ 0.5: 그림 왼쪽 반이 표지 오른쪽 반에.
        val d = IosCovers.pieceDraw(piece, -0.5, 0.5, 100.0, 800, 1000, 300, 480)!!
        assertEquals(150.0, d.dstX, 1e-9)
        assertEquals(150.0, d.dstWidth, 1e-9)
        assertEquals(0, d.srcX)
        assertEquals(400, d.srcWidth)
        // 기둥 밖에만 걸친 틀은 놓을 것이 없다.
        assertNull(IosCovers.pieceDraw(piece, 1.2, 1.6, 100.0, 800, 1000, 300, 480))
    }
}
