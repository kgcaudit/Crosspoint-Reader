package io.github.kgcaudit.reader.document.comic

import java.text.Normalizer

/**
 * 만화 파일 · 폴더 이름 하나를 읽은 결과.
 *
 * 숫자 칸은 모두 null 이 "이름에 없음" 이다 — 0 과 다르다(CLAUDE.md 규칙 5). "0권" 은 [volume] = 0.0 이고, 그래서
 * 특별편으로 친다. null 을 0 으로 뭉개면 번호 없는 외전과 0권이 같은 자리에 겹쳐 같은 권 둘로 보인다.
 *
 * @param series 번호 표시 앞의 작품 이름(꼬리표를 지운 것). 이름에 작품 이름이 없으면(예: "001화") 빈 글.
 * @param volumeEnd 범위("4-6권")의 끝. 있으면 합본이다.
 * @param part "2부" · "시즌2" — 권 · 화보다 위의 순서.
 * @param special 외전 · 특별편 · 번외 · SP · 0권.
 * @param complete 이름에 완결 표시("(완)" "[완결]" "(完)")가 있었다.
 */
data class ComicName(
    val series: String,
    val volume: Double? = null,
    val volumeEnd: Double? = null,
    val chapter: Double? = null,
    val chapterEnd: Double? = null,
    val part: Int? = null,
    val special: Boolean = false,
    val complete: Boolean = false,
    /** 꼬리표를 지운 이름 전체. 번호를 못 읽었을 때 보이는 이름으로 쓴다. */
    val cleaned: String = series,
    /**
     * 번호가 "권" 같은 표시 없이 끝의 숫자에서 나왔다("3월의 라이온 5", "001"). 웹툰의 화 폴더는 흔히 숫자뿐이라
     * 묶을 때 권이 아니라 화로 고쳐 읽는다.
     */
    val bareNumber: Boolean = false,
) {
    val isRange: Boolean get() = volumeEnd != null || chapterEnd != null
    val hasNumber: Boolean get() = volume != null || chapter != null

    companion object {

        private val COMPLETE = Regex("""[(\[【]\s*(?:완결?|完)\s*[)\]】]""")
        private val LEADING_TAG = Regex("""^\s*[\[(【{][^\])】}]*[\])】}]\s*""")
        private val ANY_TAG = Regex("""\([^)]*\)|\[[^\]]*]|\{[^}]*}|【[^】]*】""")
        // 숫자 사이의 점(12.5)은 남기고, 낱말을 잇는 점 · 밑줄만 띄어쓰기로.
        private val SEPARATOR = Regex("""_|(?<!\d)\.|\.(?!\d)""")
        private val SPACES = Regex("""\s+""")

        private const val NUM = """(\d+(?:\.\d+)?)"""

        /**
         * 범위의 이음표. 붙임표(-) · 물결(~ ∼)에 더해 줄표(– —)도 받는다 — 맥 · 워드에서 붙인 이름은 "4–6권" 처럼 줄표가
         * 들어가는데, 이것을 놓치면 "작품 4–" 와 "6권" 으로 갈려 합본이 엉뚱한 작품이 됐다.
         */
        private const val DASH = """[-~∼–—]"""

        // 먼저 맞는 것을 쓴다. 범위가 낱권보다 앞이다 — "4-6권" 을 6권으로 읽으면 합본이 6권 자리를 빼앗는다.
        private val VOLUME_RANGE = listOf(
            Regex("""$NUM\s*$DASH\s*$NUM\s*권"""),
            Regex("""(?i)(?<![a-z])(?:v|vol\.?|volume)\s*$NUM\s*$DASH\s*$NUM(?!\d)"""),
        )
        private val CHAPTER_RANGE = Regex("""$NUM\s*$DASH\s*$NUM\s*(?:화|회|話)""")
        private val VOLUME = listOf(
            Regex("""제?\s*$NUM\s*권"""),
            Regex("""(?i)(?<![a-z])(?:v|vol\.?|volume)\s*$NUM(?!\d)"""),
            Regex("""$NUM\s*巻"""),
        )
        private val CHAPTER = listOf(
            Regex("""제?\s*$NUM\s*(?:화|회|話)"""),
            Regex("""(?i)(?<![a-z])(?:ch|chapter|ep|episode)\.?\s*$NUM(?!\d)"""),
        )
        private val PART = listOf(
            Regex("""제?\s*(\d+)\s*부(?!록)"""),
            Regex("""시즌\s*(\d+)"""),
            Regex("""(\d+)\s*시즌"""),
            Regex("""(?<![A-Za-z])[Ss](\d{1,2})(?![\dA-Za-z])"""),
        )
        private val SPECIAL = listOf(
            Regex("""외전|특별편|번외편?|단편|부록"""),
            Regex("""(?i)(?<![a-z])(?:sp|special|extra|omake)(?![a-z])\s*\d*"""),
        )
        private val TRAILING = Regex("""(?<![\d.])(\d{1,4}(?:\.\d+)?)\s*$""")
        private val EDGE = Regex("""^[\s\-–—_#:·~,.]+|[\s\-–—_#:·~,.]+$""")

        private val EXTENSION = Regex("""\.[A-Za-z0-9]{1,5}$""")

        /**
         * 확장자를 뗀 이름. 확장자로 보는 것은 점 뒤의 영문 · 숫자 다섯 자 이하이고 영문이 하나는 있는 것뿐이다 —
         * 그래야 "7.5권" 의 ".5권" 이나 "12.5" 의 ".5" 를 확장자로 잘못 떼어 7권 · 12권이 되지 않는다.
         * 점으로만 시작하는 이름(숨김 파일)은 그대로.
         */
        fun stem(name: String): String {
            val m = EXTENSION.find(name) ?: return name
            if (m.range.first == 0 || m.value.none { it.isLetter() }) return name
            return name.substring(0, m.range.first)
        }

        /**
         * [name](확장자 포함 가능)을 읽는다. 어떤 글이 와도 예외를 던지지 않는다 — 이름 하나 때문에 훑기가 멈추면
         * 그 폴더의 책이 통째로 사라진다(규칙 6).
         */
        fun parse(name: String, hasExtension: Boolean = true): ComicName {
            val original = Normalizer.normalize(if (hasExtension) stem(name) else name, Normalizer.Form.NFC)
            var s = original
            val complete = COMPLETE.containsMatchIn(s)
            s = COMPLETE.replace(s, " ")
            // 앞쪽 [작가] · [그룹] 은 작품 이름이 아니다. 단, 꼬리표만 있는 이름이면 그것이 이름이다.
            var stripped = s
            while (true) {
                val m = LEADING_TAG.find(stripped) ?: break
                val rest = stripped.substring(m.range.last + 1)
                if (rest.isBlank()) break
                stripped = rest
            }
            val untagged = ANY_TAG.replace(stripped, " ").takeIf { it.isNotBlank() } ?: stripped
            val text = SPACES.replace(SEPARATOR.replace(untagged, " "), " ").trim()

            var cut = text.length
            fun mark(r: IntRange) { if (r.first < cut) cut = r.first }

            var volume: Double? = null
            var volumeEnd: Double? = null
            var chapter: Double? = null
            var chapterEnd: Double? = null
            var part: Int? = null
            var special = false
            var bare = false

            VOLUME_RANGE.firstNotNullOfOrNull { it.find(text) }?.let {
                volume = it.groupValues[1].toDoubleOrNull()
                volumeEnd = it.groupValues[2].toDoubleOrNull()
                mark(it.range)
            }
            if (volume == null) VOLUME.firstNotNullOfOrNull { it.find(text) }?.let {
                volume = it.groupValues[1].toDoubleOrNull()
                mark(it.range)
            }
            CHAPTER_RANGE.find(text)?.let {
                chapter = it.groupValues[1].toDoubleOrNull()
                chapterEnd = it.groupValues[2].toDoubleOrNull()
                mark(it.range)
            }
            if (chapter == null) CHAPTER.firstNotNullOfOrNull { it.find(text) }?.let {
                chapter = it.groupValues[1].toDoubleOrNull()
                mark(it.range)
            }
            PART.firstNotNullOfOrNull { it.find(text) }?.let {
                part = it.groupValues[1].toIntOrNull()
                mark(it.range)
            }
            SPECIAL.firstNotNullOfOrNull { it.find(text) }?.let {
                special = true
                mark(it.range)
            }
            if (volume == null && chapter == null && !special) {
                TRAILING.find(text)?.let {
                    val raw = it.groupValues[1]
                    val n = raw.toDoubleOrNull()
                    // 네 자리 1900~2099 는 연도다("1984", "Title 2019") — 번호로 읽으면 제목이 잘린다.
                    val year = raw.length == 4 && !raw.contains('.') && n != null && n in 1900.0..2099.0
                    // 이름 전체가 숫자뿐이면("001") 그 숫자가 번호이고 작품 이름은 없다.
                    if (!year && n != null) {
                        volume = n
                        bare = true
                        mark(it.range)
                    }
                }
            }
            if (volume == 0.0 && volumeEnd == null) special = true

            val series = EDGE.replace(text.substring(0, cut), "").let { SPACES.replace(it, " ") }
            return ComicName(
                series = series,
                volume = volume,
                volumeEnd = volumeEnd,
                chapter = chapter,
                chapterEnd = chapterEnd,
                part = part,
                special = special,
                complete = complete,
                // 다 지우고 아무것도 안 남으면(이름이 "(완)" 뿐) 원래 이름을 보인다 — 빈 줄은 무엇인지 모른다.
                cleaned = text.ifBlank { s.trim() }.ifBlank { original.trim() },
                bareNumber = bare,
            )
        }

        /**
         * 이름 열쇠: 띄어쓰기 · 기호 · 대소문자 · 전각을 지운 이름. 이 열쇠가 같으면 어느 폴더에 있든 한 작품이다
         * ("원피스 01" 과 "[오다] 원피스 02권 (완)" 은 둘 다 `원피스`). 글자 · 숫자가 하나도 없으면 빈 글.
         */
        fun key(series: String): String =
            Normalizer.normalize(series, Normalizer.Form.NFKC)
                .lowercase()
                .filter { it.isLetterOrDigit() }
    }
}
