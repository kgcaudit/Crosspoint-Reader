package io.github.kgcaudit.reader.layout.html

import io.github.kgcaudit.reader.document.xml.XmlEvent
import io.github.kgcaudit.reader.document.xml.XmlScanner
import java.io.Reader

/**
 * 챕터 `<head>` 에서 조판 전에 알아야 할 것만 뽑는다.
 *
 * 본문을 읽기 **전에** 외부 CSS 를 손에 넣어야 하기 때문에 따로 있다. [ChapterParser]
 * 가 `<link>` 를 만난 자리에서 직접 파일을 읽게 하면 파서가 IO 를 하게 되고, 그러면
 * 순수 함수(글자 → 블록)가 아니게 되어 캐시하기도 테스트하기도 어려워진다.
 *
 * `<body>` 를 만나면 멈춘다. 그래서 두 번 읽어도 두 번째는 수백 바이트짜리다.
 */
data class ChapterHead(
    /** `<link rel="stylesheet">` 의 href. 문서 순서대로다 — 뒤의 것이 이긴다. */
    val stylesheetHrefs: List<String> = emptyList(),
    val title: String? = null,
) {
    companion object {

        fun scan(reader: Reader): ChapterHead {
            val hrefs = ArrayList<String>()
            var title: String? = null
            var inTitle = false
            val titleText = StringBuilder()

            for (event in XmlScanner(reader).events()) {
                when (event) {
                    is XmlEvent.StartElement -> {
                        if (event.isLocal("body")) break
                        when {
                            event.isLocal("link") -> event.stylesheetHref()?.let(hrefs::add)
                            event.isLocal("title") -> inTitle = true
                        }
                    }
                    is XmlEvent.EndElement -> if (event.isLocal("title")) {
                        inTitle = false
                        title = titleText.toString().trim().takeIf { it.isNotEmpty() }
                    }
                    is XmlEvent.Text -> if (inTitle) titleText.append(event.value)
                }
            }
            return ChapterHead(hrefs, title)
        }

        /**
         * 스타일시트 링크의 href. 아니면 null.
         *
         * `rel` 은 `stylesheet` 말고도 여러 값이 공백으로 붙어 올 수 있고(`alternate
         * stylesheet`), `rel` 을 빼고 `type="text/css"` 만 적은 책도 있다. 둘 중 하나만
         * 맞으면 받는다 — 서식을 통째로 잃는 쪽이 훨씬 나쁘다.
         */
        private fun XmlEvent.StartElement.stylesheetHref(): String? {
            val href = attribute("href")?.takeIf { it.isNotBlank() } ?: return null
            val rel = attribute("rel")?.lowercase().orEmpty()
            val type = attribute("type")?.lowercase().orEmpty()
            val looksLikeCss = "stylesheet" in rel || type == "text/css" ||
                (rel.isEmpty() && type.isEmpty() && href.endsWith(".css", ignoreCase = true))
            return href.takeIf { looksLikeCss }
        }
    }
}
