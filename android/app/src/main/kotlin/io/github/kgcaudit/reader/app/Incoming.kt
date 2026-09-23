package io.github.kgcaudit.reader.app

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import io.github.kgcaudit.reader.document.BookFormat

/**
 * 다른 앱이 "연결 프로그램" 으로 넘긴 파일.
 *
 * 이 파일은 라이브러리 폴더 밖에 있을 수 있고, 읽기 권한은 **이 화면이 떠 있는 동안만** 있다
 * (FLAG_GRANT_READ_URI_PERMISSION). 그래서 라이브러리에 넣지 않는다 — 최근 목록에 올렸다가
 * 나중에 누르면 권한이 없어 열리지 않는다. 진도·책갈피는 URI 로 저장한다. OLO Explorer 는
 * 같은 파일에 늘 같은 URI 를 주므로 다시 열면 읽던 곳으로 돌아온다.
 */
data class IncomingFile(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long?,
    val format: BookFormat,
)

sealed interface Incoming {
    data class Book(val file: IncomingFile) : Incoming

    /** 열 수 없는 형식. [message] 를 그대로 보여 준다. */
    data class Refused(val message: String) : Incoming

    companion object {
        /**
         * 인텐트가 "이 파일을 열어 줘" 이면 그 파일을, 아니면(런처에서 켬) null.
         *
         * 형식은 **파일 이름을 먼저** 본다. MIME 은 보내는 앱마다 제각각이라(아무 형식 와일드카드,
         * `application/octet-stream`) 이름보다 믿을 수 없다. 이름에 확장자가 없을 때만 MIME 을 본다.
         */
        fun from(intent: Intent?, resolver: ContentResolver): Incoming? {
            if (intent?.action != Intent.ACTION_VIEW) return null
            val uri = intent.data ?: return null
            val (name, size) = describe(uri, resolver)
            val format = BookFormat.fromFileName(name) ?: formatOf(intent.type ?: runCatching { resolver.getType(uri) }.getOrNull())
            return when (format) {
                null -> Refused("OLO eBook 은 EPUB · TXT · PDF 파일을 엽니다. ‘$name’ 은 열 수 없는 형식입니다.")
                else -> Book(IncomingFile(uri, name, size, format))
            }
        }

        /**
         * 표시 이름과 크기. 이름을 모르면 URI 의 마지막 조각.
         *
         * 크기를 알려 주지 않는 제공자가 있어(메일 첨부, 일부 클라우드) 그때는 파일 디스크립터에서
         * 잰다. 크기를 모르면 라이브러리의 같은 책과 짝지을 수 없다([Library.findByFile]).
         */
        fun describe(uri: Uri, resolver: ContentResolver): Pair<String, Long?> {
            var name: String? = null
            var size: Long? = null
            runCatching {
                // Bundle 판을 쓴다. DocumentsProvider 는 옛 5인자 판을 직접 받으면 거절한다.
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null as android.os.Bundle?, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val s = c.getColumnIndex(OpenableColumns.SIZE)
                        if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                        if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                    }
                }
            }
            if (size == null) {
                size = runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull()
            }
            val fallback = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "이름 없는 파일"
            return (name?.takeIf { it.isNotBlank() } ?: fallback) to size?.takeIf { it >= 0 }
        }

        private fun formatOf(mime: String?): BookFormat? = when (mime?.lowercase()) {
            "application/epub+zip" -> BookFormat.EPUB
            "text/plain" -> BookFormat.TXT
            "application/pdf" -> BookFormat.PDF
            else -> null
        }
    }
}
