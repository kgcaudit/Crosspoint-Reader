package io.github.kgcaudit.reader.document.epub

import io.github.kgcaudit.reader.document.xml.XmlEvent
import io.github.kgcaudit.reader.document.xml.XmlScanner
import java.io.Reader

/**
 * `content.opf` 파서 — 제목·저자·manifest·spine.
 *
 * 이름공간 접두사를 무시하고 지역명으로만 비교한다. EPUB 은 같은 요소를 `<item>`,
 * `<opf:item>`, `<dc:title>` 등으로 제각기 쓴다.
 *
 * 텍스트 요소(`dc:title` 등)는 여러 [XmlEvent.Text] 로 쪼개져 올 수 있다(엔티티가
 * 중간에 있으면 그렇게 된다). 그래서 이어 붙여 모은다 — 첫 조각만 받으면 제목이
 * 잘린다.
 */
object OpfParser {

    /**
     * @param opfPath zip 안의 `content.opf` 경로. manifest href 를 이 파일 기준으로 푼다.
     */
    fun parse(reader: Reader, opfPath: String): OpfPackage {
        val baseDir = Hrefs.dirOf(opfPath)

        var title: String? = null
        var creator: String? = null
        var language: String? = null
        var identifier: String? = null
        var ncxId: String? = null
        var coverMetaId: String? = null
        var version: String? = null

        val manifest = LinkedHashMap<String, ManifestItem>()
        val spine = ArrayList<String>()

        // 지금 텍스트를 모으고 있는 메타데이터 요소의 지역명. null 이면 모으지 않는다.
        var collecting: String? = null
        val collected = StringBuilder()

        fun finishCollecting() {
            val value = collected.toString().trim()
            collected.setLength(0)
            val name = collecting ?: return
            collecting = null
            if (value.isEmpty()) return
            when (name) {
                // 같은 요소가 여러 번 나오면 첫 값을 쓴다. 부제·원제가 뒤에 붙는 경우가 있다.
                "title" -> title = title ?: value
                "creator" -> creator = creator ?: value
                "language" -> language = language ?: value
                "identifier" -> identifier = identifier ?: value
            }
        }

        for (event in XmlScanner(reader).events()) {
            when (event) {
                is XmlEvent.StartElement -> when {
                    event.isLocal("package") -> version = event.attribute("version")

                    event.isLocal("title") || event.isLocal("creator") ||
                        event.isLocal("language") || event.isLocal("identifier") -> {
                        finishCollecting() // 중첩·미닫힘 대비
                        collecting = event.name.local.lowercase()
                    }

                    event.isLocal("item") -> {
                        val id = event.attribute("id")
                        val href = event.attribute("href")
                        if (id != null && href != null) {
                            manifest[id] = ManifestItem(
                                id = id,
                                href = Hrefs.resolve(baseDir, href),
                                mediaType = event.attribute("media-type"),
                                properties = event.attribute("properties")
                                    ?.split(' ', '\t', '\n')
                                    ?.filter { it.isNotBlank() }
                                    ?.toSet()
                                    ?: emptySet(),
                            )
                        }
                    }

                    event.isLocal("spine") -> ncxId = event.attribute("toc")

                    event.isLocal("itemref") -> {
                        val idref = event.attribute("idref")
                        // linear="no" 는 본문 흐름에서 빠지는 보조 자료(표지·판권)다.
                        // 읽기 순서에서 제외해야 진도 퍼센트가 어긋나지 않는다.
                        if (idref != null && !event.attribute("linear").equals("no", true)) {
                            spine.add(idref)
                        }
                    }

                    // EPUB 2 표지 지시: <meta name="cover" content="cover-img"/>
                    event.isLocal("meta") -> {
                        if (event.attribute("name").equals("cover", true)) {
                            coverMetaId = event.attribute("content")
                        }
                    }
                }

                is XmlEvent.Text -> if (collecting != null) collected.append(event.value)

                is XmlEvent.EndElement ->
                    if (collecting != null && event.isLocal(collecting!!)) finishCollecting()
            }
        }
        finishCollecting()

        return OpfPackage(
            title = title,
            creator = creator,
            language = language,
            identifier = identifier,
            baseDir = baseDir,
            manifestById = manifest,
            spineIdRefs = spine,
            ncxId = ncxId,
            coverMetaId = coverMetaId,
            version = version,
        )
    }
}
