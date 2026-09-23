package io.github.kgcaudit.reader.layout.html

import io.github.kgcaudit.reader.document.xml.XmlEvent
import io.github.kgcaudit.reader.document.xml.XmlScanner
import io.github.kgcaudit.reader.layout.Block
import io.github.kgcaudit.reader.layout.BlockStyle
import io.github.kgcaudit.reader.layout.ImageSizing
import io.github.kgcaudit.reader.layout.InlineRun
import io.github.kgcaudit.reader.layout.TextStyle
import io.github.kgcaudit.reader.layout.book.BookFontTable
import io.github.kgcaudit.reader.layout.css.CssDeclarations
import io.github.kgcaudit.reader.layout.css.CssLength
import io.github.kgcaudit.reader.layout.css.CssParser
import io.github.kgcaudit.reader.layout.css.CssUnit
import io.github.kgcaudit.reader.layout.css.ElementInfo
import io.github.kgcaudit.reader.layout.css.Stylesheet
import java.io.Reader
import java.io.StringReader

/**
 * 조판 준비가 끝난 챕터 하나.
 *
 * [text] 가 이 챕터의 **좌표계**다. 책갈피·진도·검색·낭독이 모두 여기의 글자
 * 오프셋을 가리키므로, 같은 파일을 같은 설정으로 다시 읽으면 같은 오프셋이 나와야
 * 한다 — 그래서 정규화 규칙이 파서 안에 고정돼 있다.
 */
data class Chapter(
    val text: String,
    val blocks: List<Block>,
    /** `id` 속성 → 글자 오프셋. 목차의 `#fragment` 가 가리키는 자리다. */
    val anchors: Map<String, Int> = emptyMap(),
) {
    companion object {
        val EMPTY: Chapter = Chapter("", emptyList())
    }
}

/**
 * XHTML 챕터를 [Block] 목록으로 바꾼다.
 *
 * 브라우저가 아니다. 목표는 **읽을 수 있는 본문**이지 원본 재현이 아니라서, 표는
 * 칸마다 한 문단으로 펴고 배경·테두리·띄움(float)은 버린다. 이 선택 덕분에 조판기는
 * 문단·그림·구분선 세 가지만 알면 되고, 페이지 캐시도 그만큼 단순해진다.
 *
 * 한 번만 훑는다(streaming). DOM 을 세우지 않으므로 큰 챕터에서도 메모리가 본문 길이에
 * 비례할 뿐이고, 그게 저사양 기기에서 첫 페이지가 빨리 뜨는 이유다.
 *
 * 알려진 한계:
 * - `<style>` 은 만나는 순간부터 적용된다. `<head>` 안에 있으면(거의 전부) 문제없지만
 *   본문 뒤에 있으면 앞쪽에는 적용되지 않는다.
 * - `<br>` 은 여백 없는 문단 나눔으로 처리한다. 줄만 바꾸는 것과 결과가 같다.
 * - 표는 칸 단위로 펴진다. 칸 사이 정렬은 맞지 않는다.
 * - 글을 직접 담지 않는 바깥 블록(`div`·`blockquote`·`ul`)의 위아래 여백은 버린다.
 *   조판에 나가는 것은 글을 담은 가장 안쪽 블록뿐이기 때문이다. 좌우 여백은 누적해
 *   넘기므로 인용문 들여쓰기는 살아 있고, 문단 사이 간격은 사용자 설정이 맡는다.
 */
class ChapterParser(
    /** 책의 외부 CSS 를 합친 것. `<style>` 블록은 파싱 중에 여기에 덧붙는다. */
    private val publisherStyles: Stylesheet = Stylesheet.EMPTY,
    private val context: StyleContext = StyleContext(),
    /** 책 글꼴표. [StyleContext.useBookFonts] 가 꺼져 있으면 쓰이지 않는다. */
    private val fonts: BookFontTable = BookFontTable.EMPTY,
) {

    fun parse(xhtml: String): Chapter = parse(StringReader(xhtml))

    fun parse(reader: Reader): Chapter = Session().run(reader)

    private inner class Session {

        private var resolver = StyleResolver(publisherStyles, context, fonts)
        private var embedded = Stylesheet.EMPTY

        private val text = StringBuilder()
        private val blocks = ArrayList<Block>()
        private val anchors = LinkedHashMap<String, Int>()

        /** 여는 요소 하나마다 한 칸. CSS 후손 셀렉터가 이 목록을 본다. */
        private val elements = ArrayList<ElementInfo>()
        private val frames = ArrayList<Frame>()

        /** 지금 쌓고 있는 문단. */
        private val runs = ArrayList<InlineRun>()
        private var paragraphStart = 0
        private var blockStyle: BlockStyle = BlockStyle.Default

        /** 아직 글자로 바뀌지 않은 공백. 다음 글자가 나올 때만 space 한 칸이 된다. */
        private var pendingSpace = false

        /** `display:none` 이나 `<script>` 안. 0 보다 크면 내용을 통째로 버린다. */
        private var skipDepth = 0

        /** `<style>` 안. 본문이 아니라 CSS 로 모은다. */
        private var styleDepth = 0
        private val css = StringBuilder()

        /** `<pre>` 중첩 수. 0 보다 크면 공백을 그대로 둔다. */
        private var preDepth = 0

        /** 다음에 나오는 블록 앞에서 페이지를 넘긴다. */
        private var pendingPageBreak = false

        fun run(reader: Reader): Chapter {
            for (event in XmlScanner(reader).events()) {
                when (event) {
                    is XmlEvent.StartElement -> startElement(event)
                    is XmlEvent.EndElement -> endElement()
                    is XmlEvent.Text -> text(event.value)
                }
            }
            flushParagraph()
            return Chapter(text.toString(), blocks.toList(), anchors)
        }

        // ── 요소 ────────────────────────────────────────────────────

        private fun startElement(event: XmlEvent.StartElement) {
            if (skipDepth > 0) {
                skipDepth++
                return
            }
            if (styleDepth > 0) {
                styleDepth++
                return
            }

            val tag = event.name.local.lowercase()
            if (tag in TagDefaults.SKIPPED_TAGS) {
                skipDepth = 1
                return
            }
            if (tag == "style") {
                styleDepth = 1
                css.setLength(0)
                return
            }

            val element = ElementInfo.of(tag, event.attribute("class"), event.attribute("id"))
            elements.add(element)

            val declarations = resolver.declarationsFor(elements, event.attribute("style"))
            if (declarations.hidden == true) {
                // 여는 태그는 이미 스택에 넣었다. 닫힐 때 짝을 맞춰 빼야 하므로
                // 프레임도 넣어 두고, 내용만 버린다.
                frames.add(Frame(tag, current(), isBlock = false, restoreStyle = blockStyle))
                skipDepth = 1
                return
            }

            val inherited = resolver.inherit(current(), declarations)
            val isBlock = tag in TagDefaults.BLOCK_TAGS
            frames.add(Frame(tag, inherited, isBlock, blockStyle))

            if (isBlock) {
                flushParagraph()
                blockStyle = resolver.blockStyle(inherited, declarations)
            }
            if (declarations.pageBreakBefore == true) pendingPageBreak = true

            // 앵커는 문단을 끊은 **뒤에** 잡아야 한다. 먼저 잡으면 방금 문단에서
            // 떼어 낸 꼬리 공백만큼 어긋나 목차가 한 글자씩 밀린다.
            recordAnchor(event)

            when (tag) {
                "br" -> lineBreak()
                "img", "image" -> image(event, declarations)
                "hr" -> rule()
                in TagDefaults.PREFORMATTED_TAGS -> preDepth++
            }
        }

        private fun endElement() {
            if (skipDepth > 0) {
                skipDepth--
                // 스킵이 끝나면 그 요소의 프레임과 스택도 함께 닫는다.
                if (skipDepth == 0 && frames.isNotEmpty() && elements.isNotEmpty()) {
                    val frame = frames.removeAt(frames.size - 1)
                    elements.removeAt(elements.size - 1)
                    if (frame.isBlock) {
                        flushParagraph()
                        blockStyle = frame.restoreStyle
                    }
                }
                return
            }
            if (styleDepth > 0) {
                styleDepth--
                if (styleDepth == 0) adoptEmbeddedCss()
                return
            }
            if (frames.isEmpty()) return

            val frame = frames.removeAt(frames.size - 1)
            if (elements.isNotEmpty()) elements.removeAt(elements.size - 1)
            if (frame.tag in TagDefaults.PREFORMATTED_TAGS && preDepth > 0) preDepth--
            if (frame.isBlock) {
                flushParagraph()
                blockStyle = frame.restoreStyle
            }
        }

        private fun recordAnchor(event: XmlEvent.StartElement) {
            val id = event.attribute("id") ?: event.attribute("name") ?: return
            if (id.isNotBlank()) anchors.putIfAbsent(id, text.length)
        }

        private fun adoptEmbeddedCss() {
            if (css.isBlank()) return
            embedded += CssParser.parse(css.toString())
            resolver = StyleResolver(publisherStyles + embedded, context, fonts)
            css.setLength(0)
        }

        private fun current(): InheritedStyle =
            frames.lastOrNull()?.inherited ?: InheritedStyle.Root

        // ── 글자 ────────────────────────────────────────────────────

        private fun text(value: String) {
            if (skipDepth > 0) return
            if (styleDepth > 0) {
                css.append(value)
                return
            }
            if (preDepth > 0) appendPreformatted(value) else appendNormalized(value)
        }

        /**
         * 공백을 CSS `white-space: normal` 규칙대로 접는다: 연속 공백은 한 칸,
         * 문단 처음과 끝의 공백은 없앤다.
         *
         * 이 정규화가 고정돼 있어야 같은 책이 늘 같은 글자 오프셋을 갖고, 어제 꽂은
         * 책갈피가 오늘도 같은 자리에 선다.
         */
        private fun appendNormalized(value: String) {
            val chunk = StringBuilder(value.length)
            for (ch in value) {
                if (isCollapsible(ch)) {
                    pendingSpace = true
                    continue
                }
                if (pendingSpace) {
                    // 문단 첫 글자 앞의 공백은 버린다.
                    if (chunk.isNotEmpty() || text.length > paragraphStart) chunk.append(' ')
                    pendingSpace = false
                }
                chunk.append(ch)
            }
            commit(chunk)
        }

        /** `<pre>` 안. 줄바꿈마다 문단을 끊고 나머지 공백은 그대로 둔다. */
        private fun appendPreformatted(value: String) {
            val chunk = StringBuilder()
            for (ch in value) {
                when (ch) {
                    '\n' -> {
                        commit(chunk)
                        chunk.setLength(0)
                        flushParagraph()
                    }
                    '\r' -> Unit
                    else -> chunk.append(ch)
                }
            }
            commit(chunk)
        }

        private fun commit(chunk: CharSequence) {
            if (chunk.isEmpty()) return
            val style = current().text
            val start = text.length
            text.append(chunk)

            val last = runs.lastOrNull()
            if (last != null && last.style == style && last.endExclusive == start) {
                runs[runs.size - 1] = last.copy(endExclusive = text.length)
            } else {
                runs.add(InlineRun(start, text.length, style))
            }
        }

        // ── 블록 만들기 ─────────────────────────────────────────────

        /** `<br>`. 여백 없이 줄만 바꾼다. */
        private fun lineBreak() {
            val style = blockStyle
            flushParagraph()
            // 강제 줄바꿈 뒤에는 들여쓰기를 하지 않는다. 한 문단이 이어지는 것이지
            // 새 문단이 시작하는 게 아니다.
            blockStyle = style.copy(firstLineIndentEm = 0f, marginTopEm = 0f)
        }

        private fun image(event: XmlEvent.StartElement, declarations: CssDeclarations) {
            val href = event.attribute("src")
                ?: event.attribute("xlink", "href")
                ?: event.attribute("href")
                ?: return
            if (href.isBlank()) return

            flushParagraph()
            val start = text.length
            // 그림 자리에 글자 한 칸(U+FFFC)을 둔다. 그래야 오프셋이 끊기지 않고,
            // 그림으로 시작하는 페이지에도 책갈피를 꽂을 수 있다.
            text.append(OBJECT_REPLACEMENT)
            blocks.add(
                Block.Image(
                    href = href,
                    charStart = start,
                    charEndExclusive = text.length,
                    style = blockStyle.copy(pageBreakBefore = takePageBreak()),
                    // HTML 의 width/height 는 CSS 보다 약한 "표현 힌트" 다. CSS 가 정했으면
                    // CSS 를 따른다 — Calibre 는 속성과 클래스를 함께 쓰는데 클래스 쪽이 의도다.
                    sizing = ImageSizing(
                        width = declarations.width ?: event.attribute("width")?.let(::htmlLength),
                        height = declarations.height ?: event.attribute("height")?.let(::htmlLength),
                        maxWidth = declarations.maxWidth,
                        maxHeight = declarations.maxHeight,
                    ),
                ),
            )
            paragraphStart = text.length
        }

        private fun rule() {
            flushParagraph()
            val start = text.length
            text.append(OBJECT_REPLACEMENT)
            blocks.add(
                Block.Rule(
                    charStart = start,
                    charEndExclusive = text.length,
                    style = blockStyle.copy(pageBreakBefore = takePageBreak()),
                ),
            )
            paragraphStart = text.length
        }

        private fun flushParagraph() {
            pendingSpace = false
            trimTrailingSpace()

            val kept = runs.filter { !it.isEmpty }
            runs.clear()
            paragraphStart = text.length

            if (kept.isEmpty()) return
            blocks.add(
                Block.Paragraph(kept, blockStyle.copy(pageBreakBefore = takePageBreak())),
            )
        }

        /**
         * 문단 끝의 공백 한 칸을 되돌린다.
         *
         * `<p>글 <em>강조</em> </p>` 처럼 꼬리 공백이 남으면 양쪽정렬이 그 공백까지
         * 늘려 마지막 줄이 어긋난다.
         */
        private fun trimTrailingSpace() {
            val last = runs.lastOrNull() ?: return
            if (last.endExclusive != text.length || text.isEmpty()) return
            if (text[text.length - 1] != ' ') return

            text.setLength(text.length - 1)
            val shrunk = last.copy(endExclusive = last.endExclusive - 1)
            if (shrunk.isEmpty) runs.removeAt(runs.size - 1) else runs[runs.size - 1] = shrunk
        }

        private fun takePageBreak(): Boolean {
            val value = pendingPageBreak
            pendingPageBreak = false
            return value
        }
    }

    private class Frame(
        val tag: String,
        val inherited: InheritedStyle,
        val isBlock: Boolean,
        /** 이 블록이 닫힐 때 되돌릴 바깥 블록의 서식. */
        val restoreStyle: BlockStyle,
    )

    private companion object {
        const val OBJECT_REPLACEMENT = '￼'

        /** 줄바꿈으로 접히는 공백. NBSP 는 **접지 않는다** — 붙여 두려고 쓴 글자다. */
        fun isCollapsible(ch: Char): Boolean =
            ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' || ch == '\u000C'

        /** `width="300"` 과 `width="300px"` 를 모두 받는다. 퍼센트는 모름(0)으로 본다. */
        /**
         * HTML 속성의 길이. 단위 없는 숫자는 CSS px 이고(`width="600"`), 퍼센트도 된다
         * (`width="100%"` — 한 권에서 106번 나왔다). 0 이하나 알아볼 수 없는 값은 지정 없음.
         */
        fun htmlLength(raw: String): CssLength? {
            val text = raw.trim().lowercase()
            val length = when {
                text.endsWith("%") -> text.dropLast(1).trim().toFloatOrNull()?.let { CssLength(it, CssUnit.Percent) }
                text.endsWith("px") -> text.dropLast(2).trim().toFloatOrNull()?.let { CssLength(it, CssUnit.Px) }
                else -> text.toFloatOrNull()?.let { CssLength(it, CssUnit.Px) } ?: CssLength.parse(text)
            }
            return length?.takeIf { it.value > 0f }
        }
    }
}
