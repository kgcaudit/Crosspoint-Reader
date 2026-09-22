package io.github.kgcaudit.reader.document.epub

import io.github.kgcaudit.reader.document.xml.XmlEvent
import io.github.kgcaudit.reader.document.xml.XmlScanner
import java.io.Reader

/**
 * EPUB 2 목차(`toc.ncx`) 파서.
 *
 * 구조:
 * ```
 * <navMap>
 *   <navPoint><navLabel><text>제1장</text></navLabel><content src="ch1.xhtml"/>
 *     <navPoint>…</navPoint>   ← 중첩으로 소제목을 표현한다
 *   </navPoint>
 * </navMap>
 * ```
 *
 * 깊이는 `navPoint` 중첩으로 센다. `playOrder` 는 쓰지 않는다 — 빠져 있거나 중복된
 * 책이 흔하고, 문서 순서가 더 믿을 만하다.
 *
 * `pageList` 나 `navList` 안의 항목은 목차가 아니므로 `navMap` 안에서만 읽는다.
 */
object NcxParser {

    fun parse(reader: Reader, baseDir: String): List<RawTocEntry> {
        val entries = ArrayList<RawTocEntry>()

        var inNavMap = false
        var depth = -1 // navPoint 중첩 깊이. 최상위 navPoint 에서 0 이 된다.

        // 현재 navPoint 에서 모으는 중인 값들
        // inText: <text> 안에서만 라벨을 모은다. navMap 안의 아무 텍스트나 받으면
        // 요소 사이의 공백이나 잡다한 텍스트 노드가 라벨에 섞인다(trim 으로 가려지긴
        // 하지만, <content/> 앞뒤에 부스러기가 있는 NCX 에서는 실제로 라벨이 오염된다).
        var inText = false
        var label: StringBuilder? = null
        var href: String? = null
        var pendingDepth = 0
        var open = false

        fun flush() {
            if (!open) return
            open = false
            inText = false
            val text = label?.toString()?.normalizeLabel().orEmpty()
            val target = href
            label = null
            href = null
            // 라벨이 비었거나 목적지가 없으면 버린다. 목록에 빈 줄이 생기거나
            // 눌러도 아무 일이 없는 항목이 되는 것보다 낫다.
            if (text.isEmpty() || target == null) return
            entries.add(
                RawTocEntry(
                    label = text,
                    href = Hrefs.resolve(baseDir, target),
                    fragment = Hrefs.fragment(target),
                    depth = pendingDepth,
                ),
            )
        }

        for (event in XmlScanner(reader).events()) {
            when (event) {
                is XmlEvent.StartElement -> when {
                    event.isLocal("navMap") -> inNavMap = true
                    !inNavMap -> Unit

                    event.isLocal("navPoint") -> {
                        // 자식 navPoint 가 열리면 부모를 먼저 내보낸다. 부모의 라벨과
                        // content 는 자식보다 앞에 오므로 이 시점에 이미 다 모였다.
                        flush()
                        depth++
                        pendingDepth = depth
                        open = true
                        label = StringBuilder()
                    }

                    event.isLocal("text") -> if (open) {
                        inText = true
                        if (label == null) label = StringBuilder()
                    }

                    event.isLocal("content") ->
                        if (open && href == null) href = event.attribute("src")
                }

                is XmlEvent.Text -> if (inText) label?.append(event.value)

                is XmlEvent.EndElement -> when {
                    event.isLocal("text") -> inText = false
                    event.isLocal("navMap") -> {
                        flush()
                        inNavMap = false
                    }
                    event.isLocal("navPoint") && inNavMap -> {
                        flush()
                        if (depth >= 0) depth--
                    }
                }
            }
        }
        flush()
        return entries
    }
}

/** 목차 라벨의 줄바꿈·연속 공백을 한 칸으로 줄인다. 여러 줄로 적힌 라벨이 흔하다. */
internal fun String.normalizeLabel(): String =
    trim().replace(WHITESPACE_RUN, " ")

private val WHITESPACE_RUN = Regex("""\s+""")
