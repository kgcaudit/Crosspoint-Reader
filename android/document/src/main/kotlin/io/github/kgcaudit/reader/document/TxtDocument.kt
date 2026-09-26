package io.github.kgcaudit.reader.document

import io.github.kgcaudit.reader.document.text.DetectedEncoding
import io.github.kgcaudit.reader.document.text.EncodingDetector
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader

/**
 * 평문 TXT 문서.
 *
 * spine은 항상 한 항목이고 목차는 없다. 조판 쪽에서 보면 EPUB의 한 챕터와 똑같이
 * 생겼으므로, 리더·책갈피·진도가 EPUB과 같은 경로를 탄다. 그래서 TXT를 먼저 붙이면
 * EPUB의 ZIP·XML·CSS 없이 조판 파이프라인 전체를 검증할 수 있다.
 */
class TxtDocument private constructor(
    override val meta: BookMeta,
    val encoding: DetectedEncoding,
    private val source: ByteSource,
) : ReflowDocument {

    override suspend fun outline(): List<TocEntry> = emptyList()

    override suspend fun spine(): List<SpineItem> = listOf(SpineItem(index = 0, href = SINGLE_HREF))

    override suspend fun openChapter(index: Int): Reader {
        require(index == 0) { "TXT has a single chapter, requested $index" }
        val stream = source.openStream()
        return runCatching {
            stream.skipExactly(encoding.bomLength)
            InputStreamReader(BufferedInputStream(stream), encoding.encoding.charset)
        }.getOrElse { stream.close(); throw it }
    }

    /** TXT는 외부 리소스를 참조하지 않는다. */
    override suspend fun openResource(href: String): InputStream? = null

    override suspend fun openChapterResource(index: Int, href: String): InputStream? = null

    companion object {
        const val SINGLE_HREF: String = "content.txt"

        /**
         * 앞부분을 읽어 인코딩을 감지하고 문서를 만든다.
         *
         * @param displayName 파일명. 확장자를 떼어 제목으로 쓴다 — TXT에는 메타데이터가
         *   없으므로 사용자가 붙인 파일명이 유일한 단서다.
         */
        fun open(id: BookId, displayName: String, source: ByteSource): TxtDocument {
            val sample = source.openStream().use { it.readAtMost(EncodingDetector.SAMPLE_SIZE) }
            val meta = BookMeta(
                id = id,
                format = BookFormat.TXT,
                title = displayName.substringBeforeLast('.', displayName).ifBlank { displayName },
            )
            return TxtDocument(meta, EncodingDetector.detect(sample), source)
        }

        /**
         * 정확히 [count] 바이트를 건너뛴다.
         *
         * `InputStream.skip` 은 요청보다 적게 건너뛸 수 있다(계약상 허용된다). BOM을
         * 덜 건너뛰면 본문 첫 글자가 U+FEFF가 되어 보이지 않는 글자가 섞이므로
         * 루프로 보장한다.
         */
        private fun InputStream.skipExactly(count: Int) {
            var remaining = count.toLong()
            while (remaining > 0) {
                val skipped = skip(remaining)
                if (skipped > 0) {
                    remaining -= skipped
                } else {
                    if (read() < 0) return
                    remaining--
                }
            }
        }
    }
}
