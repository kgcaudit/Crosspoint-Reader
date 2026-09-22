package io.github.kgcaudit.reader.document.text

/**
 * TXT 파일의 문자 인코딩을 앞부분 바이트만 보고 추정한다.
 *
 * 판정 순서와 그 이유:
 *  1. **BOM** — 있으면 확정. 추측할 게 없다.
 *  2. **UTF-16 NUL 패턴** — BOM 없는 UTF-16은 드물지만, 있으면 ASCII 바이트 사이에
 *     0x00이 규칙적으로 끼는 게 명백한 신호다.
 *  3. **순수 ASCII** — UTF-8과 CP949가 ASCII 영역에서 동일하므로 아무 쪽이나 맞다.
 *  4. **UTF-8 유효성 + 한국어 점수 비교** — 여기가 핵심이다. 아래 설명 참조.
 *
 * ### 왜 "UTF-8로 유효하면 UTF-8"이 아닌가
 *
 * 짧은 CP949 문장이 우연히 유효한 UTF-8일 수 있다. 선행 바이트 0xC7 다음에 0xA1이
 * 오면(CP949에서 흔한 조합) 0xA1은 `10xxxxxx` 형태라 UTF-8 연속 바이트로도 성립해서,
 * "한"이 U+01E1 같은 라틴 확장 글자로 조용히 디코딩된다. 유효성만 보면 이걸 잡을
 * 수 없다.
 *
 * 그래서 양쪽으로 디코딩해 보고 **어느 쪽이 더 그럴듯한 글자를 내놓는지** 비교한다.
 * 한글·한자·가나는 가점, 라틴-1 보충/확장과 대체 문자(U+FFFD)는 감점이다. 실제 한국어
 * 텍스트라면 CP949 해석이 한글을 쏟아내고 UTF-8 해석은 라틴 확장 잡동사니를 내놓으므로
 * 점수가 뚜렷하게 갈린다.
 */
object EncodingDetector {

    /** 감지에 쓰는 앞부분 크기. 이만큼이면 판정이 안정적이고, 큰 파일도 즉시 열린다. */
    const val SAMPLE_SIZE: Int = 64 * 1024

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    fun detect(sample: ByteArray): DetectedEncoding {
        detectBom(sample)?.let { return it }
        detectUtf16WithoutBom(sample)?.let { return it }

        if (sample.none { it < 0 }) return DetectedEncoding(TextEncoding.UTF_8)

        // 잘린 표본의 마지막 불완전한 멀티바이트 시퀀스 때문에 "UTF-8 아님"으로
        // 오판하지 않도록, 끝의 최대 3바이트는 판정에서 제외한다.
        val body = sample.copyOf(trimIncompleteTail(sample))

        val utf8Valid = isValidUtf8(body)
        val koreanScore = koreanScore(String(body, TextEncoding.EUC_KR.charset))

        if (!utf8Valid) {
            // UTF-8이 아닌 8비트 텍스트다. 한국어로 읽혀야 한국어로 읽고,
            // 아니면 UTF-8로 두어 대체 문자로 보이게 한다(정체를 숨기지 않는다).
            return DetectedEncoding(if (koreanScore > 0) TextEncoding.EUC_KR else TextEncoding.UTF_8)
        }

        val utf8Score = koreanScore(String(body, Charsets.UTF_8))
        return DetectedEncoding(if (koreanScore > utf8Score) TextEncoding.EUC_KR else TextEncoding.UTF_8)
    }

    private fun detectBom(b: ByteArray): DetectedEncoding? = when {
        b.startsWith(UTF8_BOM) -> DetectedEncoding(TextEncoding.UTF_8, UTF8_BOM.size, byBom = true)
        b.startsWith(UTF16LE_BOM) -> DetectedEncoding(TextEncoding.UTF_16LE, UTF16LE_BOM.size, byBom = true)
        b.startsWith(UTF16BE_BOM) -> DetectedEncoding(TextEncoding.UTF_16BE, UTF16BE_BOM.size, byBom = true)
        else -> null
    }

    /**
     * BOM 없는 UTF-16을 NUL 바이트 분포로 추정한다.
     *
     * ASCII가 섞인 UTF-16 텍스트는 두 바이트 중 하나가 0x00이 된다. 짝수/홀수 위치
     * 어느 쪽에 몰리는지로 LE/BE를 가른다. 한글만 있는 UTF-16(U+AC00 이상)은 NUL이
     * 안 나오므로 이 검사에 걸리지 않지만, 그런 파일은 BOM 없이 만들어지는 경우가
     * 거의 없다.
     */
    private fun detectUtf16WithoutBom(b: ByteArray): DetectedEncoding? {
        if (b.size < 16) return null
        val pairs = b.size / 2
        var evenNul = 0
        var oddNul = 0
        for (i in 0 until pairs * 2 step 2) {
            if (b[i] == 0.toByte()) evenNul++
            if (b[i + 1] == 0.toByte()) oddNul++
        }
        val threshold = pairs / 2
        return when {
            oddNul > threshold && evenNul == 0 -> DetectedEncoding(TextEncoding.UTF_16LE)
            evenNul > threshold && oddNul == 0 -> DetectedEncoding(TextEncoding.UTF_16BE)
            else -> null
        }
    }

    /** 표본 끝의 불완전한 UTF-8 시퀀스를 잘라낸 길이. */
    private fun trimIncompleteTail(b: ByteArray): Int {
        var end = b.size
        var trimmed = 0
        while (end > 0 && trimmed < 3) {
            val v = b[end - 1].toInt() and 0xFF
            if (v and 0xC0 != 0x80) {
                // 선행 바이트다. 이 시퀀스가 표본 안에서 끝나는지 본다.
                val need = when {
                    v and 0x80 == 0x00 -> 1
                    v and 0xE0 == 0xC0 -> 2
                    v and 0xF0 == 0xE0 -> 3
                    v and 0xF8 == 0xF0 -> 4
                    else -> 1 // 잘못된 선행 바이트는 그대로 두어 유효성 검사가 잡게 한다
                }
                return if (end - 1 + need > b.size) end - 1 else b.size
            }
            end--
            trimmed++
        }
        return b.size
    }

    /**
     * 엄격한 UTF-8 유효성 검사.
     *
     * 과장 인코딩(overlong), 서로게이트(U+D800–U+DFFF), U+10FFFF 초과를 모두 거부한다.
     * 느슨하게 통과시키면 CP949 텍스트가 UTF-8로 오판될 여지가 늘어난다.
     *
     * private 이 아니라 internal 인 이유: 이 엄격함이 판정 정확도의 근거인데,
     * [detect] 결과만으로는 검증할 수 없다. 과장 인코딩 바이트가 CP949 에서
     * 한글로도 읽히는 경우가 있어 최종 판정이 EUC_KR 로 나오는 게 정상이기 때문이다.
     * 그래서 이 속성만 따로 테스트한다.
     */
    internal fun isValidUtf8(b: ByteArray): Boolean {
        var i = 0
        while (i < b.size) {
            val v = b[i].toInt() and 0xFF
            val need: Int
            val minCp: Int
            when {
                v and 0x80 == 0x00 -> { i++; continue }
                v and 0xE0 == 0xC0 -> { need = 1; minCp = 0x80 }
                v and 0xF0 == 0xE0 -> { need = 2; minCp = 0x800 }
                v and 0xF8 == 0xF0 -> { need = 3; minCp = 0x10000 }
                else -> return false
            }
            if (i + need >= b.size) return false
            // 선행 바이트에서 페이로드 비트만 남기는 마스크: 2/3/4바이트에 대해 0x1F/0x0F/0x07.
            var cp = v and (0x3F shr need)
            for (k in 1..need) {
                val c = b[i + k].toInt() and 0xFF
                if (c and 0xC0 != 0x80) return false
                cp = (cp shl 6) or (c and 0x3F)
            }
            if (cp < minCp) return false
            if (cp in 0xD800..0xDFFF) return false
            if (cp > 0x10FFFF) return false
            i += need + 1
        }
        return true
    }

    /**
     * 디코딩 결과가 한국어 텍스트로 얼마나 그럴듯한지 점수화한다.
     *
     * 양수면 한글·한자 위주, 음수면 라틴 잡동사니거나 디코딩이 깨진 것이다.
     */
    internal fun koreanScore(text: String): Int {
        var score = 0
        for (ch in text) {
            score += when (ch.code) {
                in 0xAC00..0xD7A3 -> 2 // 한글 음절
                in 0x1100..0x11FF, in 0x3130..0x318F -> 2 // 한글 자모
                in 0x4E00..0x9FFF -> 1 // 한자
                in 0x3040..0x30FF -> 1 // 가나
                in 0x3000..0x303F, in 0xFF00..0xFFEF -> 1 // CJK 구두점 · 전각
                0xFFFD -> -4 // 대체 문자: 디코딩 실패의 직접 증거
                in 0x0080..0x024F -> -2 // 라틴-1 보충 / 확장: 한국어 오판의 전형적 산물
                in 0x0250..0x02FF -> -2 // IPA 확장: 같은 이유
                else -> 0
            }
        }
        return score
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
