package io.github.kgcaudit.reader.layout.html

import io.github.kgcaudit.reader.layout.Block
import io.github.kgcaudit.reader.layout.ImageSizing
import io.github.kgcaudit.reader.layout.TextAlign
import io.github.kgcaudit.reader.layout.VerticalAlign
import io.github.kgcaudit.reader.layout.css.CssLength
import io.github.kgcaudit.reader.layout.css.CssParser
import io.github.kgcaudit.reader.layout.css.CssUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChapterParserTest {

    private fun parse(xhtml: String, css: String = "", context: StyleContext = StyleContext()) =
        ChapterParser(CssParser.parse(css), context).parse(xhtml)

    private fun Chapter.paragraphs(): List<Block.Paragraph> = blocks.filterIsInstance<Block.Paragraph>()

    /** 블록이 실제로 덮는 글자. 조판이 보게 될 것과 같다. */
    private fun Chapter.rendered(index: Int): String {
        val block = blocks[index]
        return text.substring(block.charStart, block.charEndExclusive)
    }

    // ── 글자 정규화 ─────────────────────────────────────────────────

    @Test
    fun `paragraphs become blocks and whitespace collapses`() {
        val chapter = parse(
            """
            <html><body>
              <p>첫   문단
                 입니다.</p>
              <p>둘째 문단.</p>
            </body></html>
            """.trimIndent(),
        )
        assertEquals(2, chapter.blocks.size)
        assertEquals("첫 문단 입니다.", chapter.rendered(0))
        assertEquals("둘째 문단.", chapter.rendered(1))
        // 문단 사이의 공백은 글자가 되지 않는다.
        assertEquals("첫 문단 입니다.둘째 문단.", chapter.text)
    }

    @Test
    fun `space across inline tags is kept but paragraph edges are trimmed`() {
        val chapter = parse("<p> 앞 <em>강조</em> 뒤 </p>")
        assertEquals("앞 강조 뒤", chapter.text)
    }

    @Test
    fun `entities are resolved by the scanner`() {
        val chapter = parse("<p>a &amp; b &lt;c&gt; &#54620;</p>")
        assertEquals("a & b <c> 한", chapter.text)
    }

    @Test
    fun `a chapter without any markup still yields a paragraph`() {
        val chapter = parse("<body>맨몸 글</body>")
        assertEquals("맨몸 글", chapter.text)
        assertEquals(1, chapter.blocks.size)
    }

    @Test
    fun `unclosed tags do not lose the text after them`() {
        // 시중의 책에 흔하다. 여기서 멈추면 챕터 뒷부분이 통째로 사라진다.
        val chapter = parse("<body><p>하나<p>둘<div>셋</body>")
        assertEquals(listOf("하나", "둘", "셋"), chapter.blocks.indices.map { chapter.rendered(it) })
    }

    // ── 인라인 서식 ─────────────────────────────────────────────────

    @Test
    fun `inline tags give runs their style, and nesting combines`() {
        val chapter = parse("<p>보통<b>굵게<i>둘다</i></b></p>")
        val runs = chapter.paragraphs().single().runs
        assertEquals(3, runs.size)

        assertEquals("보통", chapter.text.substring(runs[0].start, runs[0].endExclusive))
        assertFalse(runs[0].style.bold)

        assertEquals("굵게", chapter.text.substring(runs[1].start, runs[1].endExclusive))
        assertTrue(runs[1].style.bold)
        assertFalse(runs[1].style.italic)

        assertEquals("둘다", chapter.text.substring(runs[2].start, runs[2].endExclusive))
        assertTrue(runs[2].style.bold)
        assertTrue(runs[2].style.italic)
    }

    @Test
    fun `adjacent text with the same style merges into one run`() {
        // 런이 잘게 쪼개지면 페이지 캐시도 커지고 drawText 횟수도 늘어난다.
        val chapter = parse("<p>가<span>나</span>다<span>라</span></p>")
        assertEquals(1, chapter.paragraphs().single().runs.size)
    }

    @Test
    fun `superscript and subscript come through as vertical alignment`() {
        val chapter = parse("<p>x<sup>2</sup>y<sub>1</sub></p>")
        val runs = chapter.paragraphs().single().runs
        assertEquals(VerticalAlign.Superscript, runs[1].style.vertical)
        assertEquals(VerticalAlign.Baseline, runs[2].style.vertical)
        assertEquals(VerticalAlign.Subscript, runs[3].style.vertical)
        assertTrue(runs[1].style.sizeScale < 1f)
    }

    @Test
    fun `font size multiplies down the tree`() {
        val chapter = parse("<p>a<span>b</span></p>", css = "p { font-size: 2em } span { font-size: 0.5em }")
        val runs = chapter.paragraphs().single().runs
        assertEquals(2f, runs[0].style.sizeScale)
        assertEquals(1f, runs[1].style.sizeScale)
    }

    @Test
    fun `an inline style attribute wins over the stylesheet`() {
        val chapter = parse("""<p style="font-weight: bold">굵게</p>""", css = "p { font-weight: normal }")
        assertTrue(chapter.paragraphs().single().runs.single().style.bold)
    }

    // ── 블록 서식 ───────────────────────────────────────────────────

    @Test
    fun `headings get their default size, weight and alignment`() {
        val chapter = parse("<h1>제목</h1>")
        val heading = chapter.paragraphs().single()
        assertEquals(2f, heading.runs.single().style.sizeScale)
        assertTrue(heading.runs.single().style.bold)
        // 제목을 양쪽정렬하면 두 줄짜리 제목의 첫 줄이 지면 폭까지 늘어난다.
        assertEquals(TextAlign.Start, heading.style.align)
        assertEquals(0f, heading.style.firstLineIndentEm)
        assertTrue(heading.style.marginTopEm > 0f)
    }

    @Test
    fun `a plain paragraph leaves the indent unset so the user setting applies`() {
        // null 과 0 의 차이가 여기서 눈에 보인다.
        assertNull(parse("<p>글</p>").paragraphs().single().style.firstLineIndentEm)
        assertEquals(0f, parse("<blockquote>글</blockquote>").paragraphs().single().style.firstLineIndentEm)
    }

    @Test
    fun `text-align and text-indent are inherited from an enclosing div`() {
        val chapter = parse(
            """<div class="t"><p>가운데</p></div>""",
            css = ".t { text-align: center; text-indent: 2em }",
        )
        val p = chapter.paragraphs().single()
        assertEquals(TextAlign.Center, p.style.align)
        assertEquals(2f, p.style.firstLineIndentEm)
    }

    @Test
    fun `nested quotes and lists stack their indents`() {
        val one = parse("<blockquote><p>한겹</p></blockquote>").paragraphs().single()
        val two = parse("<blockquote><blockquote><p>두겹</p></blockquote></blockquote>")
            .paragraphs().single()
        assertEquals(2f, one.style.indentStartEm)
        assertEquals(4f, two.style.indentStartEm)

        val nested = parse("<ul><li>바깥<ul><li>안쪽</li></ul></li></ul>").paragraphs()
        assertEquals(1.5f, nested[0].style.indentStartEm)
        assertEquals(3f, nested[1].style.indentStartEm)
    }

    @Test
    fun `the default alignment applies when nothing says otherwise`() {
        val chapter = parse("<p>글</p>", context = StyleContext(defaultAlign = TextAlign.Justify))
        assertEquals(TextAlign.Justify, chapter.paragraphs().single().style.align)
    }

    @Test
    fun `page-break-before lands on the first block inside`() {
        val chapter = parse(
            """<p>앞</p><div class="s"><p>절 제목</p><p>본문</p></div>""",
            css = ".s { page-break-before: always }",
        )
        val p = chapter.paragraphs()
        assertFalse(p[0].style.pageBreakBefore)
        assertTrue(p[1].style.pageBreakBefore)
        assertFalse(p[2].style.pageBreakBefore)
    }

    // ── 그림·구분선·줄바꿈 ──────────────────────────────────────────

    @Test
    fun `an image becomes a block and holds one character of the coordinate space`() {
        val chapter = parse("""<p>앞</p><img src="a/b.png" width="300" height="200"/><p>뒤</p>""")
        val image = chapter.blocks[1] as Block.Image
        assertEquals("a/b.png", image.href)
        // width/height 속성은 크기 "지정" 이다. 파일 크기는 ChapterLoader 가 파일에서 읽는다.
        assertEquals(CssLength(300f, CssUnit.Px), image.sizing.width)
        assertEquals(CssLength(200f, CssUnit.Px), image.sizing.height)
        assertFalse(image.hasIntrinsicSize)
        assertEquals("￼", chapter.rendered(1))
        assertEquals("앞￼뒤", chapter.text)
    }

    @Test
    fun `svg images and unknown sizes are handled`() {
        val chapter = parse("""<svg><image xlink:href="cover.jpg"/></svg>""")
        val image = chapter.blocks.single() as Block.Image
        assertEquals("cover.jpg", image.href)
        assertFalse(image.hasIntrinsicSize)
    }

    @Test
    fun `percent sizes in html attributes are kept`() {
        // 편집기로 만든 책의 모양: 속성에 퍼센트(한 권에서 114번). 예전에는 숫자로 못 읽어
        // 버렸고, 그래서 그림 116개가 전부 같은 상자를 받았다.
        val chapter = parse("""<img src="a.jpg" width="100%"/><img src="b.jpg" width="80%" height="85%"/>""")
        val (a, b) = chapter.blocks.filterIsInstance<Block.Image>()
        assertEquals(CssLength(100f, CssUnit.Percent), a.sizing.width)
        assertEquals(CssLength(80f, CssUnit.Percent), b.sizing.width)
        assertEquals(CssLength(85f, CssUnit.Percent), b.sizing.height)
    }

    @Test
    fun `a size set by a css class wins over the html attribute`() {
        // Calibre 변환본의 모양: 클래스가 의도다(장 제목 그림을 폭의 45% 로).
        val chapter = parse(
            """<img class="calibre4" src="c.png" width="600"/>""",
            css = ".calibre4 { height: auto; width: 45% }",
        )
        val image = chapter.blocks.single() as Block.Image
        assertEquals(CssLength(45f, CssUnit.Percent), image.sizing.width)
        assertEquals(null, image.sizing.height, "height:auto 는 지정하지 않은 것이다")
    }

    @Test
    fun `nonsense image sizes are ignored rather than obeyed`() {
        // 0·음수·auto 를 따르면 그림이 사라지거나 조판이 0 으로 나눈다.
        val chapter = parse(
            """<img src="a.png" width="0"/><img src="b.png" width="-5" height="auto"/><img class="z" src="c.png"/>""",
            css = ".z { width: 0; max-width: -10px }",
        )
        chapter.blocks.filterIsInstance<Block.Image>().forEach {
            assertEquals(ImageSizing.Auto, it.sizing, it.href)
        }
    }

    @Test
    fun `an hr becomes a rule`() {
        val chapter = parse("<p>앞</p><hr/><p>뒤</p>")
        assertTrue(chapter.blocks[1] is Block.Rule)
        assertEquals("￼", chapter.rendered(1))
    }

    @Test
    fun `br splits the paragraph without re-indenting`() {
        val chapter = parse("<p>첫 줄<br/>둘째 줄</p>")
        val p = chapter.paragraphs()
        assertEquals(2, p.size)
        assertEquals("첫 줄", chapter.rendered(0))
        assertEquals("둘째 줄", chapter.rendered(1))
        // 한 문단이 이어지는 것이므로 둘째 줄을 들여쓰지 않는다.
        assertEquals(0f, p[1].style.firstLineIndentEm)
    }

    // ── 버리는 것 ───────────────────────────────────────────────────

    @Test
    fun `script, head and display none are dropped whole`() {
        val chapter = parse(
            """
            <html><head><title>제목</title></head>
            <body><script>var x = "보이면 안 됨";</script>
            <p class="gone">숨김</p><p>보임</p></body></html>
            """.trimIndent(),
            css = ".gone { display: none }",
        )
        assertEquals("보임", chapter.text)
    }

    @Test
    fun `dropped elements do not take their parent's style with them`() {
        // <noscript>·<title> 의 내용은 버리지만, 닫히면서 **부모의** 프레임을 빼면 안 된다. 그러면
        // 인용문 속 문단이 들여쓰기와 `.poem p` 서식을 잃고, <head><title> 뒤에서 html 의 서식이 사라진다.
        val quote = parse(
            "<blockquote class=\"poem\"><noscript>x</noscript><p>시 한 줄</p></blockquote>",
            css = ".poem p { font-style: italic }",
        ).paragraphs().single()
        assertEquals(2f, quote.style.indentStartEm)
        assertTrue(quote.runs.all { it.style.italic }, "후손 셀렉터가 계속 맞아야 한다")

        val body = parse(
            "<html><head><title>t</title></head><body><p>가운데</p></body></html>",
            css = "html { text-align: center }",
        ).paragraphs().single()
        assertEquals(TextAlign.Center, body.style.align)
    }

    @Test
    fun `a style element is adopted as css rather than shown as text`() {
        val chapter = parse(
            """
            <html><head><style>p.hero { text-align: center }</style></head>
            <body><p class="hero">주인공</p></body></html>
            """.trimIndent(),
        )
        assertEquals("주인공", chapter.text)
        assertEquals(TextAlign.Center, chapter.paragraphs().single().style.align)
    }

    @Test
    fun `an embedded style beats the external stylesheet`() {
        val chapter = parse(
            "<head><style>p { text-align: center }</style></head><body><p>글</p></body>",
            css = "p { text-align: right }",
        )
        assertEquals(TextAlign.Center, chapter.paragraphs().single().style.align)
    }

    @Test
    fun `turning publisher styles off keeps tag defaults but drops the book css`() {
        val source = """<p class="c" style="text-align: right">글</p><h1>제목</h1>"""
        val css = ".c { text-align: center; margin-left: 4em }"

        val on = parse(source, css, StyleContext(defaultAlign = TextAlign.Justify))
        assertEquals(TextAlign.End, on.paragraphs()[0].style.align)
        assertEquals(4f, on.paragraphs()[0].style.indentStartEm)

        val off = parse(source, css, StyleContext(defaultAlign = TextAlign.Justify, usePublisherStyles = false))
        assertEquals(TextAlign.Justify, off.paragraphs()[0].style.align)
        assertEquals(0f, off.paragraphs()[0].style.indentStartEm)
        // 태그 기본값은 남는다 — 제목이 본문과 같은 크기가 되면 읽기 나쁘다.
        assertEquals(2f, off.paragraphs()[1].runs.single().style.sizeScale)
    }

    // ── 앵커 ────────────────────────────────────────────────────────

    @Test
    fun `ids are recorded as character offsets for the table of contents`() {
        val chapter = parse("""<p>앞</p><h2 id="ch2">둘째 장</h2><p id="p1">본문</p>""")
        assertEquals(chapter.text.indexOf("둘째 장"), chapter.anchors["ch2"])
        assertEquals(chapter.text.indexOf("본문"), chapter.anchors["p1"])
    }

    @Test
    fun `a legacy anchor name is recorded too`() {
        val chapter = parse("""<p>앞<a name="note1"/>뒤</p>""")
        assertEquals(chapter.text.indexOf("뒤"), chapter.anchors["note1"])
    }

    // ── pre ─────────────────────────────────────────────────────────

    @Test
    fun `pre keeps its spaces and breaks a paragraph per line`() {
        val chapter = parse("<pre>a   b\n  c</pre><p>보통   글</p>")
        val p = chapter.paragraphs()
        assertEquals("a   b", chapter.rendered(0))
        assertEquals("  c", chapter.rendered(1))
        // pre 를 빠져나오면 다시 공백을 접는다.
        assertEquals("보통 글", chapter.rendered(2))
        assertEquals(TextAlign.Start, p[0].style.align)
    }

    // ── 좌표계 불변식 ───────────────────────────────────────────────

    @Test
    fun `blocks cover the text in order and never overlap`() {
        val chapter = parse(
            """
            <h1 id="t">제목</h1>
            <p>문단 하나<b>굵게</b></p>
            <img src="x.png"/>
            <blockquote><p>인용</p></blockquote>
            <hr/>
            <p>끝</p>
            """.trimIndent(),
        )
        var previousEnd = 0
        for (block in chapter.blocks) {
            assertTrue(block.charStart >= previousEnd, "블록이 겹친다: $block")
            assertTrue(block.charEndExclusive > block.charStart, "빈 블록이 나왔다: $block")
            assertTrue(block.charEndExclusive <= chapter.text.length, "텍스트 밖을 가리킨다: $block")
            previousEnd = block.charEndExclusive
        }
        // 정규화 텍스트에는 제어 문자가 없다 — 있으면 측정과 그리기가 어긋난다.
        assertTrue(chapter.text.none { it < ' ' }, "제어 문자가 남았다")
    }

    @Test
    fun `runs stay inside their paragraph and follow one another`() {
        val chapter = parse("<p>가<b>나</b>다<i>라</i>마</p>")
        val paragraph = chapter.paragraphs().single()
        var cursor = paragraph.charStart
        for (run in paragraph.runs) {
            assertEquals(cursor, run.start, "런 사이에 구멍이 있다")
            cursor = run.endExclusive
        }
        assertEquals(paragraph.charEndExclusive, cursor)
    }

    @Test
    fun `an empty chapter is not a crash`() {
        assertEquals(Chapter.EMPTY.text, parse("").text)
        assertTrue(parse("<html><body></body></html>").blocks.isEmpty())
        assertTrue(parse("<p>   </p><div>\n\n</div>").blocks.isEmpty())
    }
}
