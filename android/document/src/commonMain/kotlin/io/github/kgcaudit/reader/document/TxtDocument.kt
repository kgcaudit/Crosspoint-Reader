package io.github.kgcaudit.reader.document

import io.github.kgcaudit.reader.document.text.DetectedEncoding
import io.github.kgcaudit.reader.document.text.EncodingDetector
import io.github.kgcaudit.reader.document.io.InputStream
import io.github.kgcaudit.reader.document.io.Reader
import io.github.kgcaudit.reader.document.io.StringInput
import io.github.kgcaudit.reader.document.io.readAllText

/**
 * 평문 TXT 문서.
 *
 * 목차는 없다. 조판 쪽에서 보면 EPUB의 챕터와 똑같이 생겼으므로, 리더·책갈피·진도가 EPUB과 같은 경로를 탄다.
 *
 * 큰 파일은 빈 줄(장면이 바뀌는 곳)에서 [PIECE_CHARS] 안팎의 조각으로 나눠 spine 에 낸다([PiecedDocument]) — 한 항목이면
 * 첫 쪽을 보이기 전에 파일 전체를 조판해야 했다. 조각을 이으면 한 장의 글과 글자 하나 다르지 않아, 저장된 자리(`r:0:N`)가
 * 그대로 같은 글자를 가리킨다. 작은 파일은 예전처럼 한 항목이다.
 */
class TxtDocument private constructor(
    override val meta: BookMeta,
    val encoding: DetectedEncoding,
    private val source: ByteSource,
) : ReflowDocument, PiecedDocument {

    /**
     * 줄바꿈을 맞춘 본문과 조각 자리. 처음 물을 때 한 번 파일 전체를 읽는다(672KB 를 글자로 푸는 데는 조판보다 훨씬 짧다).
     * 조각이 하나면 본문은 들고 있지 않는다 — 작은 파일은 예전과 같은 길로 읽는다.
     */
    private class Pieces(val text: String?, val starts: IntArray, val length: Int)

    private val pieces: Pieces by lazy {
        // 줄바꿈은 TextChapter 와 같은 규칙으로 맞춘다 — 저장된 자리는 맞춘 글의 번호라, 맞추기 전 글에서 자르면 \r\n 이 있는
        // 파일에서 조각마다 자리가 밀렸다.
        val text = rawReader().use { it.readAllText() }.replace("\r\n", "\n").replace('\r', '\n')
        val starts = pieceStartsOf(text)
        Pieces(if (starts.size > 1) text else null, starts, text.length)
    }

    override suspend fun outline(): List<TocEntry> = emptyList()

    override suspend fun spine(): List<SpineItem> {
        val p = pieces
        if (p.starts.size == 1) return listOf(SpineItem(index = 0, href = SINGLE_HREF))
        return p.starts.indices.map { i ->
            SpineItem(index = i, href = if (i == 0) SINGLE_HREF else "$SINGLE_HREF#$i", sizeBytes = (pieceEnd(p, i) - p.starts[i]).toLong())
        }
    }

    override suspend fun pieceStarts(): IntArray = pieces.starts.copyOf()

    override suspend fun textLength(): Int = pieces.length

    override suspend fun openChapter(index: Int): Reader {
        val p = pieces
        require(index in p.starts.indices) { "TXT has ${p.starts.size} pieces, requested $index" }
        val text = p.text ?: return rawReader()
        return StringInput(text.substring(p.starts[index], pieceEnd(p, index)))
    }

    private fun pieceEnd(p: Pieces, index: Int): Int = if (index + 1 < p.starts.size) p.starts[index + 1] else p.length

    private fun rawReader(): Reader {
        val stream = source.openStream()
        return runCatching {
            stream.skipExactly(encoding.bomLength)
            encoding.encoding.reader(stream)
        }.getOrElse { stream.close(); throw it }
    }

    /** TXT는 외부 리소스를 참조하지 않는다. */
    override suspend fun openResource(href: String): InputStream? = null

    override suspend fun openChapterResource(index: Int, href: String): InputStream? = null

    companion object {
        const val SINGLE_HREF: String = "content.txt"

        /**
         * 조각 하나의 목표 글자 수(UTF-8 한글로 약 48KB, 사용자 결정 2026-10-24). 작으면 조각 경계에서 일찍 끝나는 쪽이 잦고,
         * 크면 첫 쪽 · 글자 크기 바꾸기가 그만큼 늦다(iPhone 13 mini 에서 약 2초).
         */
        const val PIECE_CHARS: Int = 16_000

        /**
         * [text] 를 [target] 안팎의 조각으로 나눈 시작 자리들(첫 값 0).
         *
         * 자르는 곳은 **빈 줄 뒤 첫 글줄의 처음**(장면이 바뀌는 곳) 중 목표에 가장 가까운 것 — 조각 경계에서 쪽이 일찍 끝나므로
         * 문단 한가운데보다 장면 사이가 덜 어색하다. 목표의 ±절반 안에 빈 줄이 없으면 가까운 줄의 처음, 줄바꿈조차 없는
         * 파일이면 목표 자리(이모지를 반으로 자르지 않게). 남은 글이 한 조각 반보다 적으면 더 자르지 않는다 — 꼬리에 몇 쪽짜리
         * 조각이 생기지 않게.
         */
        internal fun pieceStartsOf(text: String, target: Int = PIECE_CHARS): IntArray {
            val starts = ArrayList<Int>()
            starts += 0
            var from = 0
            while (text.length - from > target + target / 2) {
                val goal = from + target
                val cut = blankLineBreak(text, goal, from + target / 2, goal + target / 2)
                    ?: lineStartNear(text, goal, from)
                    ?: if (text[goal - 1].isHighSurrogate()) goal - 1 else goal
                starts += cut
                from = cut
            }
            return starts.toIntArray()
        }

        /** [lo]..[hi] 에서 빈 줄 바로 뒤 글줄의 처음 중 [goal] 에 가장 가까운 것. 없으면 null. */
        private fun blankLineBreak(text: String, goal: Int, lo: Int, hi: Int): Int? {
            var best: Int? = null
            var lineStart = text.lastIndexOf('\n', lo - 1) + 1
            var previousBlank = false
            while (lineStart <= hi && lineStart < text.length) {
                val end = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
                var blank = true
                for (i in lineStart until end) if (!text[i].isWhitespace()) { blank = false; break }
                if (previousBlank && !blank && lineStart >= lo) {
                    if (best == null || kotlin.math.abs(lineStart - goal) < kotlin.math.abs(best - goal)) best = lineStart
                }
                previousBlank = blank
                lineStart = end + 1
            }
            return best
        }

        /** [goal] 에 가장 가까운 줄의 처음([from] 뒤, 끝 앞). 줄바꿈이 없으면 null. */
        private fun lineStartNear(text: String, goal: Int, from: Int): Int? {
            val after = text.indexOf('\n', goal - 1).let { if (it < 0 || it + 1 >= text.length) null else it + 1 }
            val before = text.lastIndexOf('\n', goal - 2).let { if (it < 0 || it + 1 <= from) null else it + 1 }
            return listOfNotNull(after, before).minByOrNull { kotlin.math.abs(it - goal) }
        }

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
