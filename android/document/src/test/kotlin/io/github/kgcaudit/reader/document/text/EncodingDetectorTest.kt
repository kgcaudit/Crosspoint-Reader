package io.github.kgcaudit.reader.document.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EncodingDetectorTest {

    private fun detect(bytes: ByteArray) = EncodingDetector.detect(bytes)

    private fun korean(text: String): ByteArray = text.toByteArray(TextEncoding.EUC_KR.charset)

    // ── BOM: 확정 판정 ──────────────────────────────────────────────

    @Test
    fun `utf8 bom is detected and reported for skipping`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "가나다".toByteArray()
        assertEquals(DetectedEncoding(TextEncoding.UTF_8, bomLength = 3, byBom = true), detect(bytes))
    }

    @Test
    fun `utf16 boms are distinguished by byte order`() {
        assertEquals(
            DetectedEncoding(TextEncoding.UTF_16LE, bomLength = 2, byBom = true),
            detect(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "가".toByteArray(Charsets.UTF_16LE)),
        )
        assertEquals(
            DetectedEncoding(TextEncoding.UTF_16BE, bomLength = 2, byBom = true),
            detect(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "가".toByteArray(Charsets.UTF_16BE)),
        )
    }

    // ── BOM 없는 경우 ───────────────────────────────────────────────

    @Test
    fun `plain ascii reads as utf8 with no bom to skip`() {
        val result = detect("Hello, plain text.\nSecond line.\n".toByteArray())
        assertEquals(TextEncoding.UTF_8, result.encoding)
        assertEquals(0, result.bomLength)
    }

    @Test
    fun `korean utf8 without a bom is detected as utf8`() {
        val text = "긴 문장을 여러 줄에 걸쳐 적는다.\n한글이 충분히 많아야 판정이 안정적이다.\n"
        assertEquals(TextEncoding.UTF_8, detect(text.toByteArray()).encoding)
    }

    @Test
    fun `korean cp949 without a bom is detected as korean not utf8`() {
        // 이 앱에서 가장 흔한 실패 사례다. 오판하면 본문 전체가 깨져 보인다.
        val text = "긴 문장을 여러 줄에 걸쳐 적는다.\n한글이 충분히 많아야 판정이 안정적이다.\n"
        assertEquals(TextEncoding.EUC_KR, detect(korean(text)).encoding)
    }

    @Test
    fun `a short cp949 string that happens to be valid utf8 still reads as korean`() {
        // CP949 바이트열이 우연히 유효한 UTF-8이 되는 경우가 있어서, 유효성만으로는
        // 판정할 수 없다. 점수 비교가 이 사례를 잡는지 확인한다.
        val text = "한글 문서 제목"
        val bytes = korean(text)
        val decodedAsUtf8 = String(bytes, Charsets.UTF_8)
        assertEquals(TextEncoding.EUC_KR, detect(bytes).encoding)
        // UTF-8로 읽으면 실제로 한글이 아니게 된다는 것도 같이 못 박는다.
        assertTrue(decodedAsUtf8 != text)
    }

    @Test
    fun `mixed ascii and korean cp949 is detected as korean`() {
        val text = "Chapter 1 — 서장\n주인공은 문을 열었다. The door creaked.\n"
        assertEquals(TextEncoding.EUC_KR, detect(korean(text)).encoding)
    }

    @Test
    fun `utf16 without a bom is detected from the nul byte pattern`() {
        val ascii = "The quick brown fox jumps over the lazy dog.\n"
        assertEquals(TextEncoding.UTF_16LE, detect(ascii.toByteArray(Charsets.UTF_16LE)).encoding)
        assertEquals(TextEncoding.UTF_16BE, detect(ascii.toByteArray(Charsets.UTF_16BE)).encoding)
    }

    // ── 경계 · 견고성 ───────────────────────────────────────────────

    @Test
    fun `empty and tiny inputs fall back to utf8 without throwing`() {
        assertEquals(TextEncoding.UTF_8, detect(ByteArray(0)).encoding)
        assertEquals(TextEncoding.UTF_8, detect(byteArrayOf(0x41)).encoding)
    }

    @Test
    fun `a sample cut in the middle of a utf8 sequence is still detected as utf8`() {
        // 표본이 64KB로 잘리면 마지막 글자가 끊긴다. 그것 때문에 인코딩 판정이
        // 뒤집히면 큰 파일만 깨지는 재현 어려운 버그가 된다.
        val full = "한글이 충분히 많은 문장을 적어 둔다. 판정이 안정적이어야 한다.".toByteArray()
        for (cut in 1..3) {
            val truncated = full.copyOf(full.size - cut)
            assertEquals(TextEncoding.UTF_8, detect(truncated).encoding, "cut=$cut")
        }
    }

    @Test
    fun `strict utf8 validation rejects overlong sequences and surrogates`() {
        // 이 엄격함이 판정 정확도의 근거다. 느슨하게 통과시키면 8비트 텍스트가
        // UTF-8로 오판된다. detect() 결과로는 확인할 수 없어 직접 검사한다 —
        // 과장 인코딩 바이트가 CP949에서 한글로도 읽히는 경우가 있어서, 최종
        // 판정이 EUC_KR로 나오는 것이 오히려 정상이다.
        val invalid = mapOf(
            "과장 인코딩된 '/'" to byteArrayOf(0xC0.toByte(), 0xAF.toByte()),
            "과장 인코딩된 NUL" to byteArrayOf(0xE0.toByte(), 0x80.toByte(), 0x80.toByte()),
            "서로게이트 U+D800" to byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()),
            "U+10FFFF 초과" to byteArrayOf(0xF5.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte()),
            "고아 연속 바이트" to byteArrayOf(0x80.toByte()),
            "연속 바이트 부족" to byteArrayOf(0xE0.toByte(), 0xA0.toByte()),
        )
        invalid.forEach { (label, bytes) ->
            assertFalse(EncodingDetector.isValidUtf8(bytes), label)
        }

        val valid = mapOf(
            "ASCII" to "abc".toByteArray(),
            "한글" to "한글".toByteArray(),
            "2바이트 경계 U+0080" to byteArrayOf(0xC2.toByte(), 0x80.toByte()),
            "3바이트 경계 U+0800" to byteArrayOf(0xE0.toByte(), 0xA0.toByte(), 0x80.toByte()),
            "4바이트 이모지" to "\uD83D\uDE00".toByteArray(),
        )
        valid.forEach { (label, bytes) ->
            assertTrue(EncodingDetector.isValidUtf8(bytes), label)
        }
    }

    @Test
    fun `eight bit text that is not korean stays utf8 so the breakage is visible`() {
        // 라틴-1 로 저장된 파일: UTF-8 로도 유효하지 않고 한국어로도 안 읽힌다.
        // 억지로 한국어로 읽어 엉뚱한 한글을 보여주는 대신 UTF-8 로 두어
        // 대체 문자로 정체가 드러나게 한다.
        val latin1 = "Le café était fermé, très dommage.".toByteArray(Charsets.ISO_8859_1)
        assertFalse(EncodingDetector.isValidUtf8(latin1))
        assertEquals(TextEncoding.UTF_8, detect(latin1).encoding)
    }

    @Test
    fun `korean score separates real hangul from a mis-decode`() {
        val text = "한글이 가득한 문장이다"
        assertTrue(EncodingDetector.koreanScore(text) > 0)
        // 같은 바이트를 UTF-8 로 잘못 읽으면 점수가 떨어져야 한다.
        val misdecoded = String(text.toByteArray(TextEncoding.EUC_KR.charset), Charsets.UTF_8)
        assertTrue(
            EncodingDetector.koreanScore(misdecoded) < EncodingDetector.koreanScore(text),
            "mis-decode scored ${EncodingDetector.koreanScore(misdecoded)}, " +
                "correct scored ${EncodingDetector.koreanScore(text)}",
        )
    }

    @Test
    fun `korean charset resolves to something that round trips hangul`() {
        // 플랫폼에 CP949 별칭이 없으면 EUC-KR로 내려간다. 어느 쪽이든 한글은 왕복해야 한다.
        val text = "한글 인코딩 확인"
        assertEquals(text, String(korean(text), TextEncoding.EUC_KR.charset))
    }
}
