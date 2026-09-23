package io.github.kgcaudit.reader.layout.book

import io.github.kgcaudit.reader.document.BookFormat
import io.github.kgcaudit.reader.document.ReflowDocument
import io.github.kgcaudit.reader.layout.LayoutSpec
import io.github.kgcaudit.reader.layout.css.CssParser
import io.github.kgcaudit.reader.layout.css.Stylesheet
import io.github.kgcaudit.reader.layout.html.Chapter
import io.github.kgcaudit.reader.layout.html.ChapterHead
import io.github.kgcaudit.reader.layout.html.ChapterParser
import io.github.kgcaudit.reader.layout.html.StyleContext
import io.github.kgcaudit.reader.layout.html.TextChapter
import java.io.InputStream

/**
 * 문서에서 챕터 하나를 꺼내 조판 입력([Chapter])으로 만든다.
 *
 * 포맷 차이가 여기서 끝난다. EPUB 이면 외부 CSS 를 모아 XHTML 을 해석하고, TXT 면
 * 줄 단위로 문단을 만든다. 위쪽(조판·캐시·리더)은 어느 쪽이든 같은 [Chapter] 만 본다 —
 * 새 포맷을 더할 때 손댈 곳이 이 클래스 하나라는 뜻이다.
 *
 * 스타일시트는 챕터마다 **캐시한다.** 한 책의 챕터 수십 개가 같은 CSS 한두 개를
 * 가리키는 게 보통이라, 캐시가 없으면 같은 파일을 수십 번 읽고 파싱한다.
 */
class ChapterLoader(
    private val document: ReflowDocument,
    private val context: StyleContext = StyleContext(),
) {

    constructor(document: ReflowDocument, spec: LayoutSpec) : this(document, StyleContext.of(spec))

    private val sheetCache = HashMap<String, Stylesheet>()

    suspend fun load(index: Int): Chapter = when (document.meta.format) {
        BookFormat.TXT -> document.openChapter(index).use { TextChapter.parse(it, context) }
        BookFormat.EPUB -> loadXhtml(index)
        // PDF 는 ReflowDocument 가 아니다. 여기 오면 호출부가 잘못 엮인 것이다.
        BookFormat.PDF -> error("PDF is not a reflow document")
    }

    /** 챕터의 `<head>` 만 훑어 이 챕터가 쓰는 스타일시트를 문서 순서대로 합친다. */
    suspend fun stylesheetFor(index: Int): Stylesheet {
        val head = document.openChapter(index).use { ChapterHead.scan(it) }
        var sheet = Stylesheet.EMPTY
        for (href in head.stylesheetHrefs) {
            sheet += sheetCache.getOrPut(href) { CssParser.parse(readCss(index, href)) }
        }
        return sheet
    }

    private suspend fun loadXhtml(index: Int): Chapter {
        val sheet = stylesheetFor(index)
        return document.openChapter(index).use { ChapterParser(sheet, context).parse(it) }
    }

    /**
     * CSS 파일을 글자로 읽는다. 없거나 읽다 실패하면 빈 문자열이다.
     *
     * CSS 를 못 읽었다고 책을 못 여는 일은 없어야 한다. 서식이 밋밋해질 뿐이고, 그게
     * "이 책은 열리지 않습니다" 보다 훨씬 낫다.
     */
    private suspend fun readCss(index: Int, href: String): String =
        runCatching {
            document.openChapterResource(index, href)?.use { it.readCssText() }
        }.getOrNull().orEmpty()

    private fun InputStream.readCssText(): String {
        // CSS 는 EPUB 규격상 UTF-8 이다. @charset 은 무시한다 — 다른 인코딩으로 적힌
        // 스타일시트는 실제로 보기 어렵고, 틀려도 서식 일부가 빠지는 정도다.
        val bytes = readBytes()
        val offset = if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            3
        } else {
            0
        }
        return String(bytes, offset, bytes.size - offset, Charsets.UTF_8)
    }
}
