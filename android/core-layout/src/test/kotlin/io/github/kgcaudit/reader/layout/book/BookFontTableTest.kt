package io.github.kgcaudit.reader.layout.book

import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.SeekableSource
import io.github.kgcaudit.reader.document.epub.EpubDocument
import io.github.kgcaudit.reader.layout.Block
import io.github.kgcaudit.reader.layout.Insets
import io.github.kgcaudit.reader.layout.LayoutSpec
import io.github.kgcaudit.reader.layout.css.CssFontFace
import io.github.kgcaudit.reader.layout.css.CssParser
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 출판사 글꼴(`@font-face`)이 조판까지 가는 길. CSS 는 올려 받은 세 권의 것을 그대로 옮겼다
 * (도시·퀴즈·삼체 — 책 파일은 저장소에 넣지 않는다).
 */
class BookFontTableTest {

    // ── @font-face 읽기 ─────────────────────────────────────────────

    @Test
    fun `font faces are read as the three real books write them`() {
        // 도시: 따옴표 이름에 공백·한글, 굵기·모양 명시, url 에 따옴표 없음.
        val city = CssParser.parse(
            """
            @font-face {
                font-family: "KoPubWorld바탕체 Light";
                font-style: normal;
                font-weight: normal;
                src: url(EPUB/fonts/KoPubWorldBatangLight.ttf)
            }
            .ps { font-family: "KoPubWorld바탕체 Light"; text-indent: 1em }
            """.trimIndent(),
        )
        assertEquals(listOf(CssFontFace("kopubworld바탕체 light", "EPUB/fonts/KoPubWorldBatangLight.ttf", 400, false)), city.fontFaces)
        // @font-face 를 읽는다고 뒤의 일반 규칙을 잃으면 안 된다.
        assertEquals(listOf("kopubworld바탕체 light"), city.rules.single().declarations.fontFamilies)

        // 퀴즈: 한 줄에 하나씩, 굵기 없이 가족 이름으로 굵기를 나눈다("바탕B").
        val quiz = CssParser.parse(
            """
            @font-face { font-family: "바탕"; src: url(../Fonts/KoPubBatangMedium.ttf); }
            @font-face { font-family: "바탕B"; src: url(../Fonts/KoPubBatangBold.ttf); }
            h1 { font-family: "바탕B"; }
            """.trimIndent(),
        )
        assertEquals(listOf("바탕" to "../Fonts/KoPubBatangMedium.ttf", "바탕b" to "../Fonts/KoPubBatangBold.ttf"), quiz.fontFaces.map { it.family to it.src })
        assertEquals(null, quiz.fontFaces[0].weight, "적지 않은 굵기는 파일에서 읽는다")

        // 삼체: 세미콜론을 빠뜨린 규칙. 버리면 kofb2 를 쓰는 문단만 기본 글꼴로 나온다.
        val samche = CssParser.parse(
            """
            @font-face {
              font-family: "kofb2":src:url(../Fonts/KoPubBatangMedium.ttf);
            }
            body { font-family: "kofd1", "kofd2", "kofb2", serif; }
            """.trimIndent(),
        )
        assertEquals(listOf("kofb2" to "../Fonts/KoPubBatangMedium.ttf"), samche.fontFaces.map { it.family to it.src })
        assertEquals(listOf("kofd1", "kofd2", "kofb2", "serif"), samche.rules.single().declarations.fontFamilies)
    }

    @Test
    fun `a face offering several formats picks one Android can read`() {
        val sheet = CssParser.parse(
            """@font-face { font-family: X; font-weight: 700; font-style: italic;
               src: url("x.woff2") format("woff2"), url('x.woff') format("woff"), url(x.ttf) format("truetype"); }""",
        )
        assertEquals(CssFontFace("x", "x.ttf", 700, true), sheet.fontFaces.single())
    }

    @Test
    fun `broken font faces are dropped without taking the stylesheet with them`() {
        val sheet = CssParser.parse(
            """
            @font-face { src: url(a.ttf) }
            @font-face { font-family: "b" }
            @font-face { font-family: "c"; src: url(c.ttf)
            """.trimIndent() + "\np { text-indent: 2em }",
        )
        // 이름 없는 것·파일 없는 것은 버린다. 닫히지 않은 블록은 문서 끝까지 먹는다(다른 규칙과 같다).
        assertEquals(listOf("c"), sheet.fontFaces.map { it.family })
    }

    @Test
    fun `quoted commas and inherit are read correctly`() {
        val sheet = CssParser.parse("""p { font-family: "A, B", 'C' , d } span { font-family: inherit; font-weight: bold }""")
        assertEquals(listOf("a, b", "c", "d"), sheet.rules[0].declarations.fontFamilies)
        assertEquals(null, sheet.rules[1].declarations.fontFamilies)
    }

    // ── 책 글꼴표 ───────────────────────────────────────────────────

    private fun epub(vararg entries: Pair<String, String>): EpubDocument {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(body.toByteArray()); zip.closeEntry()
            }
            put("mimetype", "application/epub+zip")
            put("META-INF/container.xml", """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""")
            entries.forEach { (name, body) -> put(name, body) }
        }
        return EpubDocument.open(BookId("t"), "b.epub", SeekableSource.of(out.toByteArray()))
    }

    private val opf = """
        <package version="3.0"><metadata><dc:title>삼체</dc:title></metadata>
          <manifest>
            <item id="c1" href="Text/c1.xhtml" media-type="application/xhtml+xml"/>
            <item id="c2" href="Text/c2.xhtml" media-type="application/xhtml+xml"/>
            <item id="b" href="Styles/b.css" media-type="text/css"/>
            <item id="a" href="Styles/a.css" media-type="text/css"/>
          </manifest>
          <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
        </package>
    """.trimIndent()

    private val css = """
        @font-face { font-family: "kofd1"; src: url(../Fonts/KoPubDotumLight.ttf); }
        @font-face { font-family: "kofb1"; src: url(../Fonts/KoPubBatangLight.ttf); }
        @font-face { font-family: "unused"; src: url(../Fonts/Unused.ttf); }
        @font-face { font-family: "kofb3"; src: url(../Fonts/KoPubBatangBold.ttf); }
        body { font-family: "kofd1", "kofd2", "unused"; }
        p { font-family: "kofb1"; text-indent: 1em; }
        .title { font-family: "kofb3"; }
        .plain { font-family: serif; }
    """.trimIndent()

    private fun book() = epub(
        "OEBPS/content.opf" to opf,
        "OEBPS/Styles/a.css" to css,
        "OEBPS/Styles/b.css" to "h2 { font-family: 'nowhere' }",
        "OEBPS/Text/c1.xhtml" to """
            <html><head><link rel="stylesheet" href="../Styles/a.css"/></head><body>
              <h1 class="title">제1부</h1>
              <p>물리학은 존재하지 않는다. <span>여전히</span> <span class="plain">본문</span></p>
              <div>몸글</div>
            </body></html>
        """.trimIndent(),
        "OEBPS/Text/c2.xhtml" to """<html><head><link rel="stylesheet" href="../Styles/a.css"/></head><body><p>둘째 장</p></body></html>""",
    )

    @Test
    fun `only fonts the book actually uses are listed, with paths resolved from the stylesheet`() = runTest {
        // "unused" 는 선언되고 목록에도 있지만 늘 kofd1 뒤라 쓰이지 않는다. 꺼내면 캐시만 차지한다.
        val table = BookFontTable.load(book())
        assertEquals(listOf("kofd1", "kofb1", "kofb3"), table.families.map { it.name })
        assertEquals("OEBPS/Fonts/KoPubBatangLight.ttf", table.families[1].files.single().path)
        assertEquals(2, table.faceFor(listOf("nowhere", "KOFB1")), "대소문자 무시, 없는 이름은 건너뛴다")
        assertEquals(0, table.faceFor(listOf("serif")))
    }

    @Test
    fun `font numbers do not depend on which chapter was opened first`() = runTest {
        // 번호는 페이지 캐시에 들어간다. 먼저 연 챕터에 따라 달라지면 다음에 열 때 다른 글꼴로 그린다.
        val first = BookFontTable.load(book()).families
        val second = BookFontTable.load(book()).families
        assertEquals(first, second)
        assertTrue(BookFontTable.load(epub("OEBPS/content.opf" to opf)).isEmpty, "CSS 가 없는 책")
    }

    private val spec = LayoutSpec(viewportWidthPx = 600f, viewportHeightPx = 800f, margin = Insets.all(20f), baseSizePx = 20f)

    /** 낱말 → 그 낱말이 든 런의 글꼴 번호. 같은 서식의 이웃 런은 하나로 합쳐지므로 "든" 으로 찾는다. */
    private suspend fun faces(useBookFonts: Boolean): (String) -> Set<Int> {
        val document = book()
        val table = BookFontTable.load(document)
        val chapter = ChapterLoader(document, spec.copy(useBookFonts = useBookFonts), table).load(0)
        val runs = chapter.blocks.filterIsInstance<Block.Paragraph>().flatMap { block ->
            block.runs.map { chapter.text.substring(it.start, it.endExclusive) to it.style.face }
        }
        return { word -> runs.filter { word in it.first }.mapTo(HashSet()) { it.second } }
    }

    @Test
    fun `each paragraph gets the publisher font its rules name`() = runTest {
        val faces = faces(useBookFonts = true)
        assertEquals(setOf(3), faces("제1부"), "제목 = kofb3")
        assertEquals(setOf(2), faces("물리학은"), "본문 = kofb1")
        assertEquals(setOf(2), faces("여전히"), "span 은 문단의 글꼴을 물려받는다")
        assertEquals(setOf(0), faces("본문"), "font-family: serif 는 출판사 글꼴을 끄고 본문 글꼴로")
        assertEquals(setOf(1), faces("몸글"), "body 의 목록에서 처음으로 책에 있는 kofd1")
    }

    @Test
    fun `with publisher fonts off every run uses the reader's font`() = runTest {
        val off = faces(useBookFonts = false)
        for (word in listOf("제1부", "물리학은", "여전히", "본문", "몸글")) assertEquals(setOf(0), off(word), word)
        // 끄고 켠 조판은 다른 캐시여야 한다.
        assertNotEquals(spec.cacheKey, spec.copy(useBookFonts = true).cacheKey)
    }
}
