package io.github.kgcaudit.reader.document.comic

import io.github.kgcaudit.reader.document.xml.XmlEvent
import io.github.kgcaudit.reader.document.xml.XmlScanner
import java.io.Reader

/**
 * 만화 압축 안의 `ComicInfo.xml`(Anansi 스키마)에서 서재에 쓰는 칸만.
 *
 * 모든 칸이 null 이면 "적혀 있지 않음" 이다. 묶을 때 null 인 칸은 다음 단계(파일 이름 → 폴더 이름)로 넘어간다
 * (규칙 5). 한국 만화의 권은 [number] 다 — [volume] 은 미국식 "시리즈 판본"(1, 2 또는 연도)이라 권 번호로 쓰면
 * 2016권이 생긴다.
 */
data class ComicInfo(
    val series: String? = null,
    val number: Double? = null,
    val volume: Int? = null,
    /** "Special" · "Omnibus" · "TPB" · "Web" … */
    val format: String? = null,
    /** `Manga=YesAndRightToLeft` — 오른쪽에서 왼쪽으로 넘긴다. "Yes" 만이면 null(방향을 정하지 않음). */
    val rightToLeft: Boolean? = null,
) {
    val isSpecial: Boolean get() = format?.lowercase()?.let { f -> SPECIAL_FORMATS.any { f.contains(it) } } == true
    val isOmnibus: Boolean get() = format?.lowercase()?.let { f -> f.contains("omnibus") || f == "tpb" || f == "tbp" } == true

    companion object {
        private val SPECIAL_FORMATS = listOf("special", "annual", "one-shot", "oneshot", "prologue", "epilogue", "anthology")

        /**
         * 읽을 수 있는 데까지 읽는다. 닫히지 않은 태그 · 깨진 글자 · 모르는 칸이 있어도 그때까지 읽은 칸은 남긴다 —
         * ComicInfo 가 깨졌다고 만화를 못 여는 것보다 파일 이름으로 묶는 편이 낫다(규칙 6). 아무 칸도 못 읽으면 null.
         */
        fun parse(reader: Reader): ComicInfo? {
            val fields = HashMap<String, String>()
            var current: String? = null
            val text = StringBuilder()
            var depth = 0
            runCatching {
                for (event in XmlScanner(reader).events()) {
                    when (event) {
                        is XmlEvent.StartElement -> {
                            depth++
                            // <ComicInfo> 바로 아래 칸만 본다. <Pages><Page …/></Pages> 같은 안쪽은 건너뛴다.
                            current = if (depth == 2) event.name.local.lowercase() else null
                            text.setLength(0)
                        }
                        is XmlEvent.Text -> if (current != null) text.append(event.value)
                        is XmlEvent.EndElement -> {
                            if (depth == 2) current?.let { k -> text.toString().trim().takeIf { it.isNotEmpty() }?.let { fields[k] = it } }
                            current = null
                            depth--
                        }
                    }
                }
            }
            if (fields.isEmpty()) return null
            val manga = fields["manga"]?.lowercase()
            return ComicInfo(
                series = fields["series"],
                number = fields["number"]?.replace(',', '.')?.toDoubleOrNull(),
                volume = fields["volume"]?.toIntOrNull(),
                format = fields["format"],
                rightToLeft = when (manga) {
                    "yesandrighttoleft" -> true
                    "no" -> false
                    else -> null
                },
            ).takeIf { it != ComicInfo() }
        }
    }
}
