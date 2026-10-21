package io.github.kgcaudit.reader.layout.html

import io.github.kgcaudit.reader.layout.FakeMeasurer
import io.github.kgcaudit.reader.layout.Block
import io.github.kgcaudit.reader.layout.Insets
import io.github.kgcaudit.reader.layout.LayoutSpec
import io.github.kgcaudit.reader.layout.Page
import io.github.kgcaudit.reader.layout.Paginator
import io.github.kgcaudit.reader.layout.PlacedRun
import io.github.kgcaudit.reader.layout.TextAlign
import io.github.kgcaudit.reader.layout.css.CssParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * XHTML 한 장이 페이지가 되기까지를 한 줄로 확인한다.
 *
 * 단위 테스트가 각 층을 따로 지키는 것과 별개로, **층과 층 사이**에서 깨지는 일이
 * 실제로 많다 — 파서가 내놓은 글자 오프셋과 조판기가 읽는 오프셋이 어긋나면 어느
 * 단위 테스트도 울지 않지만 책갈피는 엉뚱한 자리에 선다. 여기서 그걸 막는다.
 */
class ChapterPipelineTest {

    private val css = """
        body { text-align: justify }
        h1 { text-align: center }
        .quote { margin-left: 4em; margin-right: 4em; font-style: italic }
    """.trimIndent()

    private val xhtml = """
        <?xml version="1.0" encoding="utf-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml">
        <head>
          <title>표지</title>
          <style>p.lead { font-weight: bold }</style>
        </head>
        <body>
          <h1 id="top">어린 왕자</h1>
          <p class="lead">여섯 살 적에 나는 본 적이 있다.</p>
          <p>보아뱀이 코끼리를 삼키는 그림이었다. 어른들은 그것을 모자라고 했다.</p>
          <blockquote class="quote"><p id="q">가장 중요한 것은 눈에 보이지 않아.</p></blockquote>
          <img src="images/rose.png" width="200" height="300"/>
          <hr/>
          <p>그래서 나는 그림 그리기를 <em>그만두었다</em>.</p>
        </body>
        </html>
    """.trimIndent()

    private val spec = LayoutSpec(
        viewportWidthPx = 360f,
        viewportHeightPx = 200f,
        margin = Insets.all(20f),
        baseSizePx = 10f,
        lineHeightMultiplier = 1.2f,
        align = TextAlign.Justify,
        paragraphIndentEm = 1f,
        paragraphSpacingEm = 0.5f,
    )

    private fun chapter() = ChapterParser(CssParser.parse(css), StyleContext.of(spec)).parse(xhtml)

    private fun pages(chapter: Chapter, layout: LayoutSpec = spec) =
        Paginator(layout, FakeMeasurer(baseSizePx = layout.baseSizePx))
            .paginate(chapter.text, chapter.blocks)
            .toList()

    /** 이 페이지가 덮는 챕터 글자. 줄바꿈으로 런이 쪼개져도 그대로 읽힌다. */
    private fun Chapter.on(page: Page): String = text.substring(page.startChar, page.endCharExclusive)

    /** [needle] 이 놓인 자리를 덮는 런. 런은 줄 단위로 쪼개지므로 오프셋으로 찾는다. */
    private fun List<Page>.runAt(offset: Int): PlacedRun =
        flatMap { it.runs }.first { offset in it.start until it.endExclusive }

    @Test
    fun `the whole chapter reaches the page, in order and without gaps`() {
        val chapter = chapter()
        val pages = pages(chapter)

        assertTrue(pages.isNotEmpty(), "페이지가 하나도 안 나왔다")
        assertEquals(0, pages.first().startChar)
        assertEquals(chapter.text.length, pages.last().endCharExclusive)

        // 페이지가 이어져야 진도 막대가 뒤로 가지 않는다.
        pages.zipWithNext { a, b ->
            assertEquals(a.endCharExclusive, b.startChar, "페이지 ${a.index}→${b.index} 사이가 끊겼다")
        }
        assertEquals(pages.indices.toList(), pages.map { it.index })
    }

    @Test
    fun `every drawn run points at real text, in reading order`() {
        val chapter = chapter()
        var cursor = 0
        var count = 0
        for (page in pages(chapter)) {
            for (run in page.runs) {
                assertTrue(run.endExclusive <= chapter.text.length, "런이 텍스트 밖을 가리킨다")
                assertTrue(run.start >= cursor, "런이 앞으로 되돌아갔다: $run")
                cursor = run.endExclusive
                count++
            }
        }
        assertTrue(count > 0, "그려질 글자가 하나도 없다")
        // 마지막 문단의 끝까지 그려져야 한다.
        assertEquals(chapter.text.length, cursor)
    }

    @Test
    fun `styles survive the whole way to the placed runs`() {
        val chapter = chapter()
        val pages = pages(chapter)

        fun styleAt(needle: String) = pages.runAt(chapter.text.indexOf(needle)).style

        // 태그 기본값(h1 → 2배, 굵게)
        assertEquals(2f, styleAt("어린 왕자").sizeScale)
        assertTrue(styleAt("어린 왕자").bold)
        // <head> 안의 <style> 블록(p.lead → 굵게)
        assertTrue(styleAt("여섯 살").bold)
        assertEquals(1f, styleAt("여섯 살").sizeScale)
        // 외부 CSS(.quote → 기울임)가 안쪽 <p> 까지 상속된다
        assertTrue(styleAt("가장 중요한").italic)
        // 인라인 태그
        assertTrue(styleAt("그만두").italic)
        assertFalse(styleAt("보아뱀").italic)
    }

    @Test
    fun `images and rules keep their place in the coordinate space`() {
        val chapter = chapter()
        val pages = pages(chapter)

        val image = pages.flatMap { it.images }.single()
        assertEquals("images/rose.png", image.href)
        assertEquals(1, pages.flatMap { it.rules }.size)

        // 그림이 차지한 한 글자가 페이지 범위 안에 들어 있어야 그 페이지에 책갈피를 꽂을 수 있다.
        val imageOffset = chapter.text.indexOf('￼')
        val owner = pages.first { imageOffset in it.startChar until it.endCharExclusive }
        assertTrue(owner.images.isNotEmpty(), "그림 오프셋이 그림 없는 페이지에 잡혔다")
    }

    @Test
    fun `an anchor resolves to a page`() {
        val chapter = chapter()
        val pages = pages(chapter)
        val offset = chapter.anchors.getValue("q")

        val page = pages.first { offset in it.startChar until it.endCharExclusive }
        assertTrue(chapter.on(page).contains("가장 중요한"), "앵커가 가리킨 페이지에 그 글이 없다")
    }

    @Test
    fun `the quote is narrower than the body`() {
        val chapter = chapter()
        val quote = chapter.blocks.first { it.charStart == chapter.anchors.getValue("q") }
        assertEquals(4f, quote.style.indentStartEm)
        assertEquals(4f, quote.style.indentEndEm)

        val pages = pages(chapter)
        val quoteX = pages.runAt(chapter.text.indexOf("가장")).xPx
        val bodyX = pages.runAt(chapter.text.indexOf("보아뱀")).xPx
        assertTrue(quoteX > bodyX, "인용문이 본문보다 들어가 있지 않다")
    }

    @Test
    fun `turning publisher styles off changes the layout but not the text`() {
        val plain = ChapterParser(
            CssParser.parse(css),
            StyleContext.of(spec.copy(usePublisherStyles = false)),
        ).parse(xhtml)

        // 글자 좌표계는 설정과 무관해야 한다 — 그래야 설정을 바꿔도 책갈피가 살아남는다.
        assertEquals(chapter().text, plain.text)
        assertEquals(chapter().anchors, plain.anchors)

        // 책이 준 4em 은 사라지고 blockquote 의 태그 기본값 2em 만 남는다.
        val quote = plain.blocks.first { it.charStart == plain.anchors.getValue("q") }
        assertEquals(2f, quote.style.indentStartEm)
        // 인용문의 기울임도 책이 준 것이므로 사라진다. <em> 의 기울임은 태그 기본값이라 남는다.
        assertFalse((quote as Block.Paragraph).runs.any { it.style.italic })
        assertTrue(plain.blocks.any { it is Block.Paragraph && it.runs.any { run -> run.style.italic } })

        // 설정이 다르면 캐시도 달라야 한다. 아니면 옛 페이지가 그대로 보인다.
        assertTrue(spec.copy(usePublisherStyles = false).cacheKey != spec.cacheKey)
    }
}
