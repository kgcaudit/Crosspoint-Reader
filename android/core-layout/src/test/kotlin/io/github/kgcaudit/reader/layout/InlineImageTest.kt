package io.github.kgcaudit.reader.layout

import io.github.kgcaudit.reader.layout.book.findAll
import io.github.kgcaudit.reader.layout.book.speakable
import io.github.kgcaudit.reader.layout.book.splitSentences
import io.github.kgcaudit.reader.layout.html.Chapter
import io.github.kgcaudit.reader.layout.html.ChapterParser
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 문장 속 그림(로고 · 외자 · 그림 글자)이 글자처럼 줄 안에 서는가.
 *
 * 지면: 본문 200×400px, 기준 글자 10px, 줄 간격 1배. 가짜 측정기라 한글 한 글자 20px, 공백 5px, 줄 높이 12px,
 * 윗선(ascent) 8px — 모든 좌표를 손으로 셀 수 있다.
 */
class InlineImageTest {

    private val spec = LayoutSpec(
        viewportWidthPx = 220f,
        viewportHeightPx = 420f,
        margin = Insets.all(10f),
        baseSizePx = 10f,
        lineHeightMultiplier = 1f,
        align = TextAlign.Start,
    )
    private val measurer = FakeMeasurer(baseSizePx = 10f, lineHeightRatio = 1.2f)

    /** 파서로 읽고, ChapterLoader 처럼 그림 파일 크기를 채운다. */
    private fun chapter(xhtml: String, sizes: Map<String, Pair<Int, Int>> = emptyMap()): Chapter {
        val parsed = ChapterParser().parse(xhtml)
        fun sized(image: Block.Image) = sizes[image.href]?.let { (w, h) -> image.copy(intrinsicWidth = w, intrinsicHeight = h) } ?: image
        return parsed.copy(
            blocks = parsed.blocks.map { block ->
                when (block) {
                    is Block.Image -> sized(block)
                    is Block.Paragraph -> block.copy(images = block.images.map { it.copy(image = sized(it.image)) })
                    is Block.Rule -> block
                }
            },
        )
    }

    private fun pages(chapter: Chapter, spec: LayoutSpec = this.spec): List<Page> =
        Paginator(spec, measurer).paginate(chapter.text, chapter.blocks).toList()

    private fun starts(chapter: Chapter): Set<Int> =
        chapter.blocks.mapNotNull { (it as? Block.Paragraph)?.runs?.firstOrNull()?.start }.toSet()

    /** 그림 글자(U+FFFC)를 덮는 글자 조각이 없어야 한다 — 있으면 글꼴의 빈 네모가 그림 위에 찍힌다. */
    private fun assertNoGlyphForPictures(pages: List<Page>, text: String) {
        for (page in pages) for (run in page.runs) {
            assertTrue((run.start until run.endExclusive).none { text[it] == OBJECT_REPLACEMENT_CHAR }, "그림 글자가 글로 그려진다: $run")
        }
    }

    private fun near(expected: Float, actual: Float, what: String) =
        assertTrue(abs(expected - actual) < 0.01f, "$what: 기대 $expected, 실제 $actual")

    // ── 줄 안에 선다 ────────────────────────────────────────────────

    @Test
    fun `a small logo in a sentence sits on the same line between its words`() {
        // 6px: 글자 윗선(8)보다 낮은 그림이라 "윗끝을 줄 위에" 와 "아래 끝을 베이스라인에" 가 갈린다.
        val c = chapter("<p>외자 <img src=\"g.png\"/> 가 든 문장</p>", mapOf("g.png" to (6 to 6)))
        val page = pages(c).single()
        val image = page.images.single()
        val before = page.runs.single { it.start == 0 }
        val after = page.runs.single { it.start == c.text.indexOf('가') }

        // 같은 줄: 앞뒤 글이 한 베이스라인에 있고, 그림 아래 끝이 그 베이스라인에 선다(vertical-align: baseline).
        near(before.baselineYPx, after.baselineYPx, "그림 뒤 글이 다른 줄로 갔다")
        near(before.baselineYPx, image.yPx + image.heightPx, "그림 아래 끝이 베이스라인에 서지 않는다")
        near(10f + 8f - 6f, image.yPx, "그림 윗끝")
        // 차례: "외자"(40) + 공백(5) → 그림(6) → 공백(5) → "가". 원문 공백이 그림 양옆에 살아 있다.
        near(10f + 45f, image.xPx, "그림 자리")
        near(10f + 45f + 6f + 5f, after.xPx, "그림 뒤 글 자리")
        assertNoGlyphForPictures(listOf(page), c.text)
    }

    @Test
    fun `the picture keeps exactly the one character it had, so saved places do not move`() {
        // 그림마다 문단을 끊던 판과 정규화 텍스트가 같아야 이미 꽂은 책갈피 · 형광펜이 그대로다.
        val c = chapter("<p>외자 <img src=\"g.png\"/> 가 든 문장</p>")
        assertEquals("외자￼가 든 문장", c.text)
        val paragraph = c.blocks.single() as Block.Paragraph
        val picture = paragraph.images.single()
        assertEquals(2, picture.image.charStart)
        assertEquals(3, picture.image.charEndExclusive)
        assertTrue(picture.spaceBefore && picture.spaceAfter, "원문의 앞뒤 공백을 잃었다")
    }

    @Test
    fun `text after an inline picture is the same paragraph for listening and search`() {
        val c = chapter("<p>외자 <img src=\"g.png\"/> 가 든 문장. 다음 문장.</p>", mapOf("g.png" to (8 to 8)))
        val starts = starts(c)
        // 낭독: 그림에서 쉬지 않고 한 문장으로, 그림은 공백으로 읽는다.
        val sentences = splitSentences(c.text, starts)
        assertEquals(listOf("외자 가 든 문장.", "다음 문장."), sentences.map { speakable(c.text, it) })
        // 찾기: 화면에 보이는 대로 "외자 가" 를 치면 찾힌다. 목록 문맥에 그림 글자가 찍히지 않는다.
        val hit = findAll(c.text, "외자 가", 0, starts).single()
        assertEquals(0, hit.start)
        assertFalse(OBJECT_REPLACEMENT_CHAR in hit.context, "찾기 문맥에 그림 글자가 찍혔다: ${hit.context}")
        // 문단이 하나라 그림 뒤 글이 들여쓰기 · 문단 간격을 받지 않는다.
        assertEquals(1, c.blocks.size)
    }

    @Test
    fun `a logo without a set size is shrunk to letter height so the line spacing stays even`() {
        // 16×16 로고: 글자 윗선(8)보다 크다. 원래 크기로 두면 그 줄만 8px 벌어진다.
        val c = chapter("<p>앞 <img src=\"g.png\"/> 뒤</p>", mapOf("g.png" to (16 to 16)))
        val page = pages(c).single()
        val image = page.images.single()
        near(8f, image.heightPx, "높이")
        near(8f, image.widthPx, "폭(비율 유지)")
        near(10f + 8f, page.runs.first().baselineYPx, "줄이 벌어졌다")
    }

    @Test
    fun `a line grows to hold an inline picture taller than its letters`() {
        // 책이 높이 20px 을 적었다 — 출판사가 고른 크기라 줄이지 않고, 그 줄이 위로 늘어난다.
        val withPicture = chapter("<p>앞 <img src=\"g.png\" height=\"20\"/> 뒤</p>", mapOf("g.png" to (20 to 20)))
        val page = pages(withPicture).single()
        val image = page.images.single()
        near(20f, image.heightPx, "높이")
        // 그림 윗끝이 지면 위 여백에 닿고, 베이스라인은 글만 있을 때(10+8)보다 12px 아래.
        near(10f, image.yPx, "그림 윗끝")
        near(10f + 20f, page.runs.first().baselineYPx, "베이스라인")
    }

    // ── 예전처럼 블록으로 ───────────────────────────────────────────

    @Test
    fun `a tall picture in a sentence lays out exactly as it did before`() {
        // 40px: 둘레 줄 높이(12)의 두 배를 넘는다 — 삽화에 설명이 붙은 모양. 예전 판(그림마다 문단을 끊음)과 같은 쪽이 나와야 한다.
        val c = chapter("<p>앞 글 <img src=\"big.png\"/> 뒤 글</p>", mapOf("big.png" to (40 to 40)))
        val paragraph = c.blocks.single() as Block.Paragraph
        val picture = paragraph.images.single().image
        val style = paragraph.style
        val old = listOf(
            Block.Paragraph(listOf(InlineRun(0, picture.charStart, paragraph.runs.first().style)), style),
            picture.copy(style = style),
            Block.Paragraph(
                listOf(InlineRun(picture.charEndExclusive, c.text.length, paragraph.runs.last().style)),
                style.copy(firstLineIndentEm = 0f, marginTopEm = 0f),
                continuesLine = true,
            ),
        )
        val now = pages(c)
        assertEquals(Paginator(spec, measurer).paginate(c.text, old).toList(), now)
        // 그림은 가운데에 혼자 선다.
        near(10f + (200f - 40f) / 2f, now.single().images.single().xPx, "가운데")
        assertNoGlyphForPictures(now, c.text)
    }

    @Test
    fun `the two line limit counts the line spacing the reader sees`() {
        // 30px 로고: 줄 간격 1배(줄 12)면 두 줄(24)을 넘어 블록, 1.5배(줄 18)면 두 줄(36) 안이라 문장에 남는다. 앱의 측정기는
        // 줄 높이를 1em 으로 주므로, 간격을 빼고 재면 흔한 크기의 로고가 다 블록으로 떨어졌다.
        val c = chapter("<p>앞 <img src=\"g.png\"/> 뒤</p>", mapOf("g.png" to (30 to 30)))
        val tight = pages(c).single()
        val loose = pages(c, spec.copy(lineHeightMultiplier = 1.5f)).single()
        assertTrue(tight.images.single().yPx >= tight.runs.first().baselineYPx, "줄 간격 1배에서 블록이 아니다")
        near(loose.runs.first().baselineYPx, loose.runs.last().baselineYPx, "줄 간격 1.5배에서 한 줄이 아니다")
        near(8f, loose.images.single().heightPx, "크기 없는 로고는 글자 높이로")
    }

    @Test
    fun `a picture alone in its paragraph is still a centred block`() {
        val c = chapter("<p>앞</p><p> <img src=\"cover.png\"/> </p><p>뒤</p>", mapOf("cover.png" to (8 to 8)))
        // 작은 그림이어도 글이 없는 문단이면 줄 안에 넣을 문장이 없다 — 예전처럼 블록.
        assertEquals(listOf(Block.Paragraph::class, Block.Image::class, Block.Paragraph::class), c.blocks.map { it::class })
        assertEquals("앞￼뒤", c.text)
        assertTrue(c.blocks.filterIsInstance<Block.Paragraph>().all { it.images.isEmpty() })
        near(10f + (200f - 8f) / 2f, pages(c).single().images.single().xPx, "가운데")
    }

    @Test
    fun `several pictures with no words between them stay blocks with no extra characters`() {
        // 그림 사이 공백은 예전처럼 글자가 되지 않는다 — 되면 그 뒤 모든 글자 위치가 한 칸씩 밀린다.
        val c = chapter("<p><img src=\"a.png\"/> <img src=\"b.png\"/></p><p>끝</p>")
        assertEquals("￼￼끝", c.text)
        assertEquals(listOf("a.png", "b.png"), c.blocks.filterIsInstance<Block.Image>().map { it.href })
    }

    @Test
    fun `an inline picture wider than the narrowest line falls back to a block`() {
        // 폭 200 그대로인 띠 그림인데 첫 줄은 1em 들여 190 이다. 줄에 넣으면 지면 밖으로 10px 넘친다.
        val indented = spec.copy(paragraphIndentEm = 1f)
        val c = chapter("<p>앞 <img src=\"band.png\"/> 뒤</p>", mapOf("band.png" to (400 to 10)))
        val page = pages(c, indented).single()
        val image = page.images.single()
        near(200f, image.widthPx, "폭")
        // 블록이 되어 앞 글 아래 · 뒤 글 위에 따로 선다.
        val before = page.runs.first()
        val after = page.runs.last()
        assertTrue(image.yPx >= before.baselineYPx, "그림이 앞 글과 같은 줄에 남았다")
        assertTrue(after.baselineYPx - 8f >= image.yPx + image.heightPx - 0.01f, "뒤 글이 그림과 겹친다")
        for (run in page.runs) {
            assertTrue(run.xPx + measurer.advance(c.text, run.start, run.endExclusive, run.style) <= 210.01f, "글이 넘쳤다: $run")
        }
    }

    @Test
    fun `lines with many inline pictures never run past the right edge`() {
        val body = (1..30).joinToString(" ") { "가나다 <img src=\"g.png\"/>라" }
        val c = chapter("<p>$body</p>", mapOf("g.png" to (8 to 8)))
        val all = pages(c)
        for (page in all) {
            for (run in page.runs) {
                assertTrue(run.xPx + measurer.advance(c.text, run.start, run.endExclusive, run.style) <= 210.01f, "글이 넘쳤다: $run")
            }
            for (image in page.images) assertTrue(image.xPx + image.widthPx <= 210.01f, "그림이 넘쳤다: $image")
        }
        assertEquals(30, all.sumOf { it.images.size })
        assertNoGlyphForPictures(all, c.text)
    }

    // ── 깨진 입력 ───────────────────────────────────────────────────

    @Test
    fun `a picture with no src simply disappears from the sentence`() {
        val c = chapter("<p>앞 <img alt=\"로고\"/> 뒤</p>")
        assertEquals("앞 뒤", c.text)
        assertTrue((c.blocks.single() as Block.Paragraph).images.isEmpty())
        assertTrue(pages(c).single().images.isEmpty())
    }

    @Test
    fun `an inline picture whose size cannot be read becomes a letter sized square`() {
        // 머리가 깨졌거나(크기 모름) 0×0 이라고 적힌 그림. 블록 그림처럼 본문 폭 3:4 로 잡으면 추측 하나로 문장이 쪼개진다.
        for (size in listOf<Pair<Int, Int>?>(null, 0 to 0)) {
            val c = chapter("<p>앞 <img src=\"x.png\"/> 뒤</p>", size?.let { mapOf("x.png" to it) } ?: emptyMap())
            val page = pages(c).single()
            val image = page.images.single()
            near(8f, image.widthPx, "폭 ($size)")
            near(8f, image.heightPx, "높이 ($size)")
            near(page.runs.first().baselineYPx, page.runs.last().baselineYPx, "한 줄 ($size)")
        }
    }

    @Test
    fun `hundreds of picture glyphs on one page survive the cache`() {
        // 그림 글자로 넣은 이모지 600개. 쪽당 그림 수 칸이 u8 이던 때는 256번째부터 수가 돌아 그림이 사라졌다.
        val c = chapter("<p>" + "<img src=\"e.png\"/>".repeat(600) + "끝</p>", mapOf("e.png" to (8 to 8)))
        val all = pages(c)
        assertTrue(all.any { it.images.size > 255 }, "한 쪽에 256개 넘게 놓여야 의미 있는 검사다: ${all.map { it.images.size }}")
        assertEquals(600, all.sumOf { it.images.size })
        val encoded = PageCodec.encode(all, c.text.length)
        assertEquals(all, all.indices.map { PageCodec.decodePage(encoded, it) })
        assertNoGlyphForPictures(all, c.text)
    }

    @Test
    fun `turning pictures off leaves no picture and no stray glyph`() {
        val c = chapter("<p>앞 <img src=\"g.png\"/> 뒤</p>", mapOf("g.png" to (8 to 8)))
        val page = pages(c, spec.copy(imagesEnabled = false)).single()
        assertTrue(page.images.isEmpty())
        assertNoGlyphForPictures(listOf(page), c.text)
        // 그림이 빠진 자리는 공백 한 칸: "앞"(20) + 5 → "뒤".
        near(10f + 25f, page.runs.last().xPx, "빈자리 폭")
    }
}
