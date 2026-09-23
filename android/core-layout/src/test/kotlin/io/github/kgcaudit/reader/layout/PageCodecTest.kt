package io.github.kgcaudit.reader.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PageCodecTest {

    private fun run(from: Int, to: Int, x: Float, y: Float, style: TextStyle = TextStyle.Default) =
        PlacedRun(from, to, style, x, y)

    private fun roundTrip(pages: List<Page>, textLength: Int = 1000): List<Page?> {
        val encoded = PageCodec.encode(pages, textLength)
        return pages.indices.map { PageCodec.decodePage(encoded, it) }
    }

    // ── 왕복 ────────────────────────────────────────────────────────

    @Test
    fun `a simple page round trips exactly`() {
        val page = Page(
            index = 0,
            startChar = 12,
            endCharExclusive = 345,
            runs = listOf(run(12, 40, 20f, 33.5f), run(40, 70, 20f, 45.5f)),
        )
        assertEquals(page, roundTrip(listOf(page)).single())
    }

    @Test
    fun `every text style survives the round trip`() {
        // 서식이 비트로 접히므로, 하나라도 빠뜨리면 그 서식이 조용히 사라진다.
        val styles = listOf(
            TextStyle.Default,
            TextStyle(bold = true),
            TextStyle(italic = true),
            TextStyle(underline = true),
            TextStyle(strikethrough = true),
            TextStyle(vertical = VerticalAlign.Superscript),
            TextStyle(vertical = VerticalAlign.Subscript),
            TextStyle(sizeScale = 1.5f),
            TextStyle(bold = true, italic = true, underline = true, strikethrough = true, sizeScale = 0.85f),
        )
        val page = Page(
            index = 0,
            startChar = 0,
            endCharExclusive = styles.size,
            runs = styles.mapIndexed { i, style -> run(i, i + 1, i * 10f, 20f, style) },
        )
        assertEquals(styles, roundTrip(listOf(page)).single()!!.runs.map { it.style })
    }

    @Test
    fun `float positions survive exactly, including fractional pixels`() {
        // 양쪽정렬이 소수점 위치를 만든다. 반올림해 저장하면 글자가 조금씩 밀린다.
        val positions = listOf(0f, 0.25f, 33.333333f, -1.5f, 1234.5678f)
        val page = Page(
            index = 0,
            startChar = 0,
            endCharExclusive = positions.size,
            runs = positions.mapIndexed { i, x -> run(i, i + 1, x, x * 2f) },
        )
        val decoded = roundTrip(listOf(page)).single()!!
        assertEquals(positions, decoded.runs.map { it.xPx })
        assertEquals(positions.map { it * 2f }, decoded.runs.map { it.baselineYPx })
    }

    @Test
    fun `images and rules round trip with their pages`() {
        val pages = listOf(
            Page(
                0, 0, 10,
                runs = listOf(run(0, 5, 1f, 2f)),
                images = listOf(PlacedImage("OEBPS/images/그림 1.png", 10f, 20f, 300f, 200f)),
                rules = listOf(PlacedRule(0f, 250f, 320f, 1f)),
            ),
            Page(
                1, 10, 20,
                images = listOf(
                    PlacedImage("a.jpg", 0f, 0f, 100f, 100f),
                    PlacedImage("b.jpg", 0f, 110f, 100f, 100f),
                ),
            ),
        )
        assertEquals(pages, roundTrip(pages))
    }

    @Test
    fun `a page with no runs at all round trips`() {
        val page = Page(0, 5, 6, images = listOf(PlacedImage("only.png", 0f, 0f, 50f, 50f)))
        assertEquals(page, roundTrip(listOf(page)).single())
    }

    @Test
    fun `many pages each decode independently and in the right order`() {
        // 페이지마다 색인에서 바로 잘라 오는 성질을 확인한다. 어긋나면 엉뚱한 페이지가 보인다.
        val pages = (0 until 50).map { i ->
            Page(
                index = i,
                startChar = i * 100,
                endCharExclusive = (i + 1) * 100,
                runs = (0 until 3).map { r -> run(i * 100 + r, i * 100 + r + 1, r * 10f, 20f + r) },
            )
        }
        val encoded = PageCodec.encode(pages, textLength = 5000)
        // 순서를 뒤섞어 읽어도 같아야 한다.
        listOf(49, 0, 25, 7, 49, 1).forEach { i ->
            assertEquals(pages[i], PageCodec.decodePage(encoded, i), "page $i")
        }
    }

    // ── 머리말 ──────────────────────────────────────────────────────

    @Test
    fun `the index reports the page count and text length`() {
        val pages = (0 until 7).map { Page(it, it, it + 1, runs = listOf(run(it, it + 1, 0f, 0f))) }
        val index = PageCodec.decodeIndex(PageCodec.encode(pages, textLength = 4321).index)
        assertNotNull(index)
        assertEquals(7, index.pageCount)
        assertEquals(4321, index.textLength)
        assertTrue(index.complete)
        assertEquals(PageCodec.VERSION, index.version)
    }

    @Test
    fun `a partial cache is marked incomplete so the rest gets rebuilt`() {
        // 조판을 중간에 멈춘 결과다. 그 지점까지는 바로 보여 주고 뒤만 이어서 만든다.
        val pages = listOf(Page(0, 0, 100, runs = listOf(run(0, 100, 0f, 10f))))
        val encoded = PageCodec.encode(pages, textLength = 9999, complete = false)
        val index = PageCodec.decodeIndex(encoded.index)!!
        assertEquals(false, index.complete)
        assertEquals(1, index.pageCount)
        // 있는 페이지는 정상적으로 읽힌다.
        assertEquals(pages[0], PageCodec.decodePage(encoded, 0))
    }

    @Test
    fun `an empty chapter encodes and decodes to nothing`() {
        val encoded = PageCodec.encode(emptyList(), textLength = 0)
        assertEquals(0, PageCodec.decodeIndex(encoded.index)!!.pageCount)
        assertNull(PageCodec.decodePage(encoded, 0))
    }

    // ── 손상 · 버전 ─────────────────────────────────────────────────

    @Test
    fun `a corrupt or foreign file is rejected rather than misread`() {
        // 엉뚱한 화면을 그리는 것보다 캐시를 버리고 다시 조판하는 게 낫다.
        assertNull(PageCodec.decodeIndex(ByteArray(0)))
        assertNull(PageCodec.decodeIndex(ByteArray(8)))
        assertNull(PageCodec.decodeIndex("완전히 다른 파일 내용이다".toByteArray()))
    }

    @Test
    fun `a different format version is rejected`() {
        // 포맷을 바꾸면 옛 캐시는 못 읽어야 한다. 읽으면 필드가 밀려 엉뚱한 좌표가 된다.
        val encoded = PageCodec.encode(
            listOf(Page(0, 0, 1, runs = listOf(run(0, 1, 0f, 0f)))),
            textLength = 1,
        )
        val tampered = encoded.index.copyOf()
        tampered[4] = 99 // version 하위 바이트
        assertNull(PageCodec.decodeIndex(tampered))
    }

    @Test
    fun `a truncated index is rejected`() {
        val encoded = PageCodec.encode(
            (0 until 5).map { Page(it, it, it + 1, runs = listOf(run(it, it + 1, 0f, 0f))) },
            textLength = 5,
        )
        // 머리말은 5페이지라고 하는데 엔트리가 모자란다.
        assertNull(PageCodec.decodeIndex(encoded.index.copyOf(encoded.index.size - 10)))
    }

    @Test
    fun `a truncated run block is rejected instead of decoding garbage`() {
        val page = Page(0, 0, 10, runs = listOf(run(0, 5, 1f, 2f), run(5, 10, 3f, 4f)))
        val encoded = PageCodec.encode(listOf(page), textLength = 10)
        val broken = encoded.copy(runs = encoded.runs.copyOf(encoded.runs.size - 5))
        assertNull(PageCodec.decodePage(broken, 0))
    }

    @Test
    fun `a corrupt object block is rejected`() {
        val page = Page(0, 0, 1, images = listOf(PlacedImage("a.png", 0f, 0f, 1f, 1f)))
        val encoded = PageCodec.encode(listOf(page), textLength = 1)
        val broken = encoded.copy(objects = ByteArray(encoded.objects.size) { 0x7F })
        assertNull(PageCodec.decodePage(broken, 0))
    }

    @Test
    fun `out of range page indices return null`() {
        val encoded = PageCodec.encode(
            listOf(Page(0, 0, 1, runs = listOf(run(0, 1, 0f, 0f)))),
            textLength = 1,
        )
        assertNull(PageCodec.decodePage(encoded, -1))
        assertNull(PageCodec.decodePage(encoded, 1))
        assertNull(PageCodec.decodePage(encoded, 9999))
    }

    // ── 형식 성질 ───────────────────────────────────────────────────

    @Test
    fun `encoding is deterministic so an unchanged chapter is not rewritten`() {
        val pages = listOf(
            Page(0, 0, 10, runs = listOf(run(0, 10, 1.5f, 2.5f, TextStyle(bold = true)))),
            Page(1, 10, 20, images = listOf(PlacedImage("x.png", 1f, 2f, 3f, 4f))),
        )
        assertEquals(PageCodec.encode(pages, 20), PageCodec.encode(pages, 20))
    }

    @Test
    fun `record sizes stay fixed so a page can be sliced from the index`() {
        // 고정 길이가 깨지면 "페이지 N 읽기" 가 훑기로 바뀌어 페이지 넘김 예산이 무너진다.
        val onePage = PageCodec.encode(
            listOf(Page(0, 0, 1, runs = listOf(run(0, 1, 0f, 0f)))),
            textLength = 1,
        )
        val twoPages = PageCodec.encode(
            listOf(
                Page(0, 0, 1, runs = listOf(run(0, 1, 0f, 0f))),
                Page(1, 1, 2, runs = listOf(run(1, 2, 0f, 0f))),
            ),
            textLength = 2,
        )
        assertEquals(24, twoPages.index.size - onePage.index.size) // 페이지 엔트리
        assertEquals(24, twoPages.runs.size - onePage.runs.size) // 런 레코드
    }

    @Test
    fun `the whole pipeline round trips from blocks to bytes and back`() {
        // 조판 → 인코딩 → 디코딩이 한 바퀴 도는지 본다. 중간에 어긋나면 화면과 캐시가
        // 다른 것을 가리키게 된다.
        val text = "한국어 본문이 여러 줄에 걸쳐 조판되고 캐시에 저장된다. ".repeat(6)
        val spec = LayoutSpec(
            viewportWidthPx = 300f,
            viewportHeightPx = 120f,
            margin = Insets.all(10f),
            baseSizePx = 10f,
            align = TextAlign.Justify,
        )
        val pages = Paginator(spec, FakeMeasurer(baseSizePx = 10f, lineHeightRatio = 1f))
            .paginate(text, listOf(Block.Paragraph(listOf(InlineRun(0, text.length)))))
            .toList()

        assertTrue(pages.size > 1, "여러 페이지가 나와야 의미 있는 검사가 된다")
        val encoded = PageCodec.encode(pages, text.length)
        assertEquals(pages, pages.indices.map { PageCodec.decodePage(encoded, it) })
    }
}
