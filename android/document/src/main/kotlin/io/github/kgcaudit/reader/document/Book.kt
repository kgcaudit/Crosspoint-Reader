package io.github.kgcaudit.reader.document

/** 라이브러리에 등록된 책 한 권의 안정적 식별자. 파일 경로가 바뀌어도 유지된다. */
@JvmInline
value class BookId(val value: String) {
    init { require(value.isNotBlank()) { "BookId must not be blank" } }
    override fun toString(): String = value
}

enum class BookFormat {
    EPUB,
    TXT,
    PDF,
    ;

    /** 리플로우(조판) 대상인지. false면 고정 페이지(PDF) 경로로 간다. */
    val isReflowable: Boolean get() = this != PDF

    companion object {
        /**
         * 파일명 확장자로 포맷을 추정한다. 지원하지 않는 확장자는 null.
         *
         * 확장자 앞에 이름이 있어야 한다: `.epub` 처럼 점으로 시작하기만 한 이름은
         * 확장자 없는 숨김 파일이지 책이 아니다. 라이브러리 스캔이 이 함수로
         * 후보를 거르므로 여기서 걸러야 한다.
         */
        fun fromFileName(name: String): BookFormat? {
            val dot = name.lastIndexOf('.')
            if (dot <= 0) return null
            return when (name.substring(dot + 1).lowercase()) {
                "epub" -> EPUB
                "txt" -> TXT
                "pdf" -> PDF
                else -> null
            }
        }
    }
}

data class BookMeta(
    val id: BookId,
    val format: BookFormat,
    val title: String,
    val author: String? = null,
    val language: String? = null,
)

/** 리플로우 문서의 한 챕터(EPUB spine 항목, TXT는 항상 1개). */
data class SpineItem(
    val index: Int,
    val href: String,
    val sizeBytes: Long = 0,
)

/** 목차 한 줄. [locator]로 바로 이동할 수 있어야 한다. */
data class TocEntry(
    val label: String,
    val locator: Locator,
    val depth: Int = 0,
)
