package io.github.kgcaudit.reader.document.epub

import io.github.kgcaudit.reader.document.xml.XmlEvent
import io.github.kgcaudit.reader.document.xml.XmlScanner
import java.io.Reader

/**
 * EPUB 3 목차(`nav.xhtml`) 파서.
 *
 * 구조:
 * ```
 * <nav epub:type="toc">
 *   <ol><li><a href="ch1.xhtml">제1장</a>
 *          <ol><li><a href="ch1.xhtml#s2">1.2 절</a></li></ol>
 *   </li></ol>
 * </nav>
 * ```
 *
 * 한 파일에 `nav` 이 여럿 있다(`toc`, `landmarks`, `page-list`). 목차는
 * `epub:type="toc"` 이고, 그 표시가 없으면 `role="doc-toc"` 를, 그것도 없으면
 * 첫 번째 `nav` 을 쓴다 — 타입을 안 적은 책이 있다.
 *
 * 깊이는 `ol` 중첩으로 센다.
 */
object NavParser {

    fun parse(reader: Reader, baseDir: String): List<RawTocEntry> {
        val entries = ArrayList<RawTocEntry>()

        // 목차 nav 을 찾기 전에 지나친 첫 nav 의 항목을 담아 두는 예비 목록.
        // epub:type 을 안 적은 책을 위해 마지막에 쓴다.
        var fallback: List<RawTocEntry>? = null

        var navDepth = 0 // 0 = nav 밖
        var inTocNav = false
        var listDepth = 0
        var anchorLabel: StringBuilder? = null
        var anchorHref: String? = null
        // <a> 가 열려 있는가. 그 안의 <span> 을 항목으로 보지 않게 한다.
        var inAnchor = false

        fun flushAnchor() {
            val text = anchorLabel?.toString()?.normalizeLabel()
            val target = anchorHref
            anchorLabel = null
            anchorHref = null
            if (text.isNullOrEmpty() || target == null) return
            entries.add(
                RawTocEntry(
                    label = text,
                    href = Hrefs.resolve(baseDir, target),
                    fragment = Hrefs.fragment(target),
                    depth = (listDepth - 1).coerceAtLeast(0),
                ),
            )
        }

        for (event in XmlScanner(reader).events()) {
            when (event) {
                is XmlEvent.StartElement -> when {
                    event.isLocal("nav") -> {
                        navDepth++
                        val type = event.attribute("epub", "type") ?: event.attribute("type")
                        val role = event.attribute("role")
                        inTocNav = type.equals("toc", true) || role.equals("doc-toc", true)
                        listDepth = 0
                    }

                    navDepth == 0 -> Unit

                    event.isLocal("ol") || event.isLocal("ul") -> listDepth++

                    // <a> 안의 <span> 은 글자 꾸밈일 뿐이다(`<a href="c1"><span>1장</span></a>`). 새 항목으로
                    // 보면 아직 빈 <a> 가 먼저 버려져 그 목차 줄이 통째로 사라진다.
                    event.isLocal("span") && inAnchor -> Unit

                    event.isLocal("a") || event.isLocal("span") -> {
                        // <span> 은 링크 없는 상위 항목에 쓰인다(눌러도 이동할 곳이 없는 제목).
                        flushAnchor()
                        anchorHref = event.attribute("href")
                        anchorLabel = StringBuilder()
                        inAnchor = event.isLocal("a")
                    }
                }

                // <a> 안에 <em> 같은 꾸밈 태그가 있으면 텍스트가 여러 조각으로 온다.
                is XmlEvent.Text -> if (navDepth > 0) anchorLabel?.append(event.value)

                is XmlEvent.EndElement -> when {
                    event.isLocal("span") && inAnchor -> Unit

                    event.isLocal("a") || event.isLocal("span") -> {
                        flushAnchor()
                        inAnchor = false
                    }

                    event.isLocal("ol") || event.isLocal("ul") ->
                        if (navDepth > 0 && listDepth > 0) listDepth--

                    event.isLocal("nav") -> {
                        flushAnchor()
                        navDepth = (navDepth - 1).coerceAtLeast(0)
                        if (navDepth == 0) {
                            if (inTocNav) return entries
                            // 목차 nav 이 아니었다. 첫 nav 의 내용만 예비로 남기고 비운다.
                            if (fallback == null && entries.isNotEmpty()) fallback = entries.toList()
                            entries.clear()
                        }
                    }
                }
            }
        }

        flushAnchor()
        return entries.ifEmpty { fallback ?: emptyList() }
    }
}
