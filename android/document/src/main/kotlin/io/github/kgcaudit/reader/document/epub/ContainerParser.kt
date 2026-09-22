package io.github.kgcaudit.reader.document.epub

import io.github.kgcaudit.reader.document.xml.XmlEvent
import io.github.kgcaudit.reader.document.xml.XmlScanner
import java.io.Reader

/**
 * `META-INF/container.xml` 파서 — `content.opf` 의 위치를 찾는다.
 *
 * EPUB 을 여는 첫 단계다. 이 파일에 rootfile 이 여러 개일 수 있지만(다중 표현
 * 출판물) 첫 번째를 쓴다.
 */
object ContainerParser {

    const val PATH: String = "META-INF/container.xml"

    /** OPF 경로를 돌려준다. 못 찾으면 null. */
    fun parse(reader: Reader): String? {
        for (event in XmlScanner(reader).events()) {
            if (event is XmlEvent.StartElement && event.isLocal("rootfile")) {
                val path = event.attribute("full-path")?.takeIf { it.isNotBlank() } ?: continue
                return Hrefs.resolve(baseDir = "", href = path)
            }
        }
        return null
    }

    /**
     * `container.xml` 이 없거나 깨졌을 때 찾아볼 관례적인 OPF 위치.
     *
     * 규격상 필수 파일이지만 없는 책이 있다. 후보를 순서대로 열어 보는 것이
     * "이 책은 못 읽습니다"보다 낫다.
     */
    val FALLBACK_OPF_PATHS: List<String> = listOf(
        "OEBPS/content.opf",
        "OPS/content.opf",
        "content.opf",
        "OEBPS/package.opf",
        "EPUB/package.opf",
    )
}
