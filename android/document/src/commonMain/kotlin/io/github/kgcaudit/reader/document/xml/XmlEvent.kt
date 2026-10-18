package io.github.kgcaudit.reader.document.xml

/**
 * 태그·속성 이름. 접두사와 지역명을 따로 들고 있다.
 *
 * 완전한 이름공간 해석(xmlns 선언 추적)을 하지 않는 이유: EPUB에서 실제로 필요한 것은
 * "이 태그가 `item`인가", "이 속성이 `href`인가" 뿐이고, 접두사는 파일마다
 * `opf:` / `dc:` / `ncx:` / `epub:` 또는 아예 없음으로 갈린다. 지역명으로 비교하면
 * 그 변주를 전부 흡수한다. 접두사가 필요한 경우(EPUB 3 `epub:type`)는 [prefix]로 본다.
 */
data class XmlName(val prefix: String?, val local: String) {
    val qualified: String get() = if (prefix == null) local else "$prefix:$local"

    /** 지역명 비교(대소문자 무시). XHTML 태그는 대소문자가 섞여 들어온다. */
    fun isLocal(name: String): Boolean = local.equals(name, ignoreCase = true)

    override fun toString(): String = qualified

    companion object {
        /** `prefix:local` 형태의 이름을 쪼갠다. 콜론이 없으면 접두사는 null. */
        fun parse(qualified: String): XmlName {
            val colon = qualified.indexOf(':')
            return if (colon < 0) {
                XmlName(null, qualified)
            } else {
                XmlName(qualified.substring(0, colon), qualified.substring(colon + 1))
            }
        }
    }
}

data class XmlAttribute(val name: XmlName, val value: String)

/**
 * XML 파싱 이벤트.
 *
 * 빈 요소(`<br/>`)는 [StartElement] 와 [EndElement] 를 연달아 낸다. 소비자가
 * 자기닫힘 태그를 따로 처리할 필요가 없다.
 */
sealed interface XmlEvent {

    data class StartElement(
        val name: XmlName,
        val attributes: List<XmlAttribute> = emptyList(),
    ) : XmlEvent {
        /** 지역명으로 속성을 찾는다(대소문자 무시). 없으면 null. */
        fun attribute(localName: String): String? =
            attributes.firstOrNull { it.name.isLocal(localName) }?.value

        /** 접두사까지 맞춰 속성을 찾는다. `epub:type` 처럼 접두사가 의미를 갖는 경우에 쓴다. */
        fun attribute(prefix: String?, localName: String): String? =
            attributes.firstOrNull { it.name.prefix == prefix && it.name.isLocal(localName) }?.value

        fun isLocal(localName: String): Boolean = name.isLocal(localName)
    }

    data class EndElement(val name: XmlName) : XmlEvent {
        fun isLocal(localName: String): Boolean = name.isLocal(localName)
    }

    /** 텍스트 노드. 엔티티는 이미 풀려 있고 CDATA도 여기로 온다. 공백 정규화는 하지 않는다. */
    data class Text(val value: String) : XmlEvent
}
