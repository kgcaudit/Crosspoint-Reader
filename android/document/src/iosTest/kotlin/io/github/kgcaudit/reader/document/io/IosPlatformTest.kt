package io.github.kgcaudit.reader.document.io

import io.github.kgcaudit.reader.document.text.TextEncoding
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * iOS 의 actual(zlib · CommonCrypto · iconv · Foundation)이 JVM 이 하던 것과 같은 답을 내는지. 기대값은 JVM · 파이썬이 낸 것을
 * 박아 두었다 — 여기가 틀리면 안드로이드에서 열리던 EPUB · PDF · CP949 TXT 가 iOS 에서만 깨진다.
 */
class IosPlatformTest {
    private val sample = "모든 사람은 자유롭다. All human beings are born free. ".repeat(20)

    // zlib.compress(sample, 9) 와 raw deflate(windowBits -15).
    private val zlibBytes = hex(
        "78da7bbd6ac5ebc90b14de34ad793dafe7cddc068537f326bc99b3e0f5c2b5afbb97e82938e6e4286494e626e62924a566e6a5172b2416a52a24" +
            "e517e529a415a5a6ea29bc1ed53eaa7d54fba8f6a1a01d007f2e6913",
    )
    private val rawBytes = zlibBytes.copyOfRange(2, zlibBytes.size - 4)

    @Test
    fun `zip entries and pdf streams inflate to the original bytes`() {
        assertEquals(sample, inflateAll(Inflater(), zlibBytes, feed = zlibBytes.size).decodeToString())
        assertEquals(sample, inflateAll(Inflater(true), rawBytes, feed = rawBytes.size).decodeToString())
    }

    @Test
    fun `input fed a few bytes at a time inflates the same as all at once`() {
        // 공통 InflaterInput 은 zip 창 크기만큼씩 먹인다. 경계에서 needsInput 을 잘못 알리면 본문 끝이 잘린다.
        for (feed in listOf(1, 3, 7, 64)) {
            assertEquals(sample, inflateAll(Inflater(true), rawBytes, feed).decodeToString(), "feed=$feed")
        }
    }

    @Test
    fun `bytes after the end of the deflate stream are left as remaining`() {
        // zip 은 압축 자료 뒤에 데이터 기술자가 붙는다 — 남은 바이트 수로 다음 머리를 찾는다.
        val inflater = Inflater(true)
        inflater.setInput(rawBytes + byteArrayOf(1, 2, 3, 4, 5))
        val out = ByteArray(4096)
        var total = 0
        while (!inflater.finished()) total += inflater.inflate(out, total, out.size - total)
        assertEquals(sample.encodeToByteArray().size, total)
        assertEquals(5, inflater.getRemaining())
        assertEquals(0, inflater.inflate(out))
        inflater.end()
    }

    @Test
    fun `a corrupted deflate stream is reported instead of silently truncated`() {
        val broken = zlibBytes.copyOf().also { it[10] = (it[10].toInt() xor 0x5A).toByte(); it[11] = 0xFF.toByte() }
        val inflater = Inflater()
        inflater.setInput(broken)
        assertFailsWith<DataFormatException> {
            val out = ByteArray(4096)
            while (!inflater.finished() && !inflater.needsInput()) inflater.inflate(out)
            // 끝까지 먹고도 끝나지 않았다면 그것도 깨진 것이다 — 여기까지 오면 시험이 잡지 못한 것.
            if (!inflater.finished()) throw DataFormatException("unfinished")
        }
        inflater.end()
    }

    @Test
    fun `a wrong zlib header is a format error`() {
        val inflater = Inflater()
        inflater.setInput(rawBytes) // zlib 머리 없이 raw 를 zlib 로 읽게 한다.
        assertFailsWith<DataFormatException> { inflater.inflate(ByteArray(4096)) }
        inflater.end()
    }

    @Test
    fun `digests match the published test vectors`() {
        val abc = "abc".encodeToByteArray()
        assertEquals("900150983cd24fb0d6963f7d28e17f72", Crypto.digest("MD5", abc).toHex())
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", Crypto.digest("SHA-1", abc).toHex())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Crypto.digest("SHA-256", abc).toHex())
        assertEquals(
            "cb00753f45a35e8bb5a03d699ac65007272c32ab0eded1631a8b605a43ff5bed8086072ba1e7cc2358baeca134c825a7",
            Crypto.digest("SHA-384", abc).toHex(),
        )
        assertEquals(
            "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
            Crypto.digest("SHA-512", abc).toHex(),
        )
        // 빈 암호(PDF 의 사용자 암호 없음)도 해시된다.
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Crypto.digest("SHA-256", ByteArray(0)).toHex())
        assertFailsWith<IllegalArgumentException> { Crypto.digest("SHA-3", abc) }
    }

    @Test
    fun `aes cbc matches the nist vector both ways`() {
        // NIST SP 800-38A F.2.1 · F.2.2 (CBC-AES128) 의 앞 두 블록.
        val key = hex("2b7e151628aed2a6abf7158809cf4f3c")
        val iv = hex("000102030405060708090a0b0c0d0e0f")
        val plain = hex("6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e51")
        val cipher = hex("7649abac8119b246cee98e9b12e9197d5086cb9b507219ee95db113a917678b2")
        assertContentEquals(cipher, Crypto.aesCbcNoPadding(encrypt = true, key = key, iv = iv, data = plain))
        assertContentEquals(plain, Crypto.aesCbcNoPadding(encrypt = false, key = key, iv = iv, data = cipher))
    }

    @Test
    fun `aes input that is not whole blocks is refused`() {
        val key = ByteArray(16)
        assertFailsWith<IllegalArgumentException> { Crypto.aesCbcNoPadding(false, key, ByteArray(16), ByteArray(17)) }
    }

    @Test
    fun `korean txt resolves to cp949 and reads extension syllables`() {
        // CP949 확장 영역(선행 0x81–0xA0)의 '똠' '뷁' 은 EUC-KR 로만 읽으면 깨진다.
        assertEquals("CP949", TextEncoding.koreanCharset?.let { NamedCharsets.iconvName(it) })
        val bytes = hex("b0a18c6394eec164") + "\r\nabc".encodeToByteArray()
        assertEquals("가똠뷁햏\r\nabc", TextEncoding.EUC_KR.decode(bytes))
        assertEquals("가똠뷁햏\r\nabc", NamedCharsets.decodeStrict(bytes, "x-windows-949"))
    }

    @Test
    fun `broken cp949 bytes become one replacement each and reading goes on`() {
        // 0xB0 다음에 줄바꿈: 짝 잃은 선행 바이트. 그 자리만 버리고 뒤 글자는 살아야 한다.
        val bytes = hex("b0a1b00a") + hex("b0a1")
        assertEquals("가�\n가", NamedCharsets.decode(bytes, 0, bytes.size, "CP949"))
        assertNull(NamedCharsets.decodeStrict(bytes, "CP949"))
        // 끝에서 잘린 글자.
        assertEquals("가�", NamedCharsets.decode(hex("b0a1b0"), 0, 3, "CP949"))
    }

    @Test
    fun `unknown encodings are reported as unsupported`() {
        assertFalse(NamedCharsets.isSupported("no-such-charset"))
        assertNull(NamedCharsets.decode(ByteArray(1), 0, 1, "no-such-charset"))
        assertNull(NamedCharsets.reader(ByteInput(ByteArray(1)), "no-such-charset"))
        assertTrue(NamedCharsets.isSupported("EUC-KR"))
        assertTrue(NamedCharsets.isSupported("Shift_JIS"))
    }

    @Test
    fun `a reader across many small chunks gives the same text as decoding at once`() {
        // 글자가 조각 경계에서 잘려도 대체 문자가 끼면 안 된다. 일부러 7바이트씩만 내주는 흐름으로 경계를 많이 만든다.
        val korean = ("한글 텍스트 파일, 똠방각하. " + "🙂").repeat(3000)
        val cases = listOf(
            "UTF-8" to korean.encodeToByteArray(),
            "UTF-16LE" to utf16(korean, bigEndian = false),
            "UTF-16BE" to utf16(korean, bigEndian = true),
        )
        for ((name, bytes) in cases) {
            val reader = assertNotNull(NamedCharsets.reader(TrickleInput(bytes, 7), name))
            assertEquals(korean, reader.readAll(), name)
        }
        val koreanOnly = "한글 텍스트 파일, 똠방각하.\n".repeat(3000)
        val encoded = cp949Bytes(koreanOnly)
        val reader = assertNotNull(NamedCharsets.reader(TrickleInput(encoded, 7), "CP949"))
        assertEquals(koreanOnly, reader.readAll())
    }

    @Test
    fun `mac file names in nfd match their nfc form`() {
        assertEquals("가", Unicode.nfc("가"))
        assertEquals("12권", Unicode.nfkc("１２권"))
    }

    @Test
    fun `the clock reads the current time`() {
        assertTrue(currentTimeMillis() > 1_767_225_600_000L) // 2026-01-01
    }

    @Test
    fun `the default stream methods behave like java io`() {
        val input = TrickleInput(ByteArray(5000) { it.toByte() }, 1000)
        assertEquals(3000L, input.skip(3000))
        val out = ByteArray(10)
        assertEquals(10, input.read(out, 0, 10))
        assertEquals(3000.toByte(), out[0])
        assertFailsWith<IOException> { input.reset() }
        assertEquals(0, input.read(out, 0, 0))
    }

    private fun inflateAll(inflater: Inflater, input: ByteArray, feed: Int): ByteArray {
        val out = ByteArray(8192)
        var total = 0
        var at = 0
        while (!inflater.finished()) {
            if (inflater.needsInput()) {
                check(at < input.size) { "ran out of input" }
                val n = minOf(feed, input.size - at)
                inflater.setInput(input, at, n)
                at += n
            }
            total += inflater.inflate(out, total, out.size - total)
        }
        inflater.end()
        return out.copyOf(total)
    }

    /** CP949 바이트를 손으로 짓는다 — 시험하는 쪽(iconv)으로 기대값을 만들면 틀려도 같이 틀린다. 표는 파이썬 cp949 로 뽑았다. */
    private fun cp949Bytes(text: String): ByteArray {
        val map = mapOf('한' to "c7d1", '글' to "b1db", ' ' to "20", '텍' to "c5d8", '스' to "bdba", '트' to "c6ae",
            '파' to "c6c4", '일' to "c0cf", ',' to "2c", '똠' to "8c63", '방' to "b9e6", '각' to "b0a2", '하' to "c7cf",
            '.' to "2e", '\n' to "0a")
        return text.flatMap { hex(map.getValue(it)).toList() }.toByteArray()
    }

    private fun utf16(text: String, bigEndian: Boolean): ByteArray = ByteArray(text.length * 2).also { b ->
        text.forEachIndexed { i, c ->
            val hi = (c.code shr 8).toByte()
            val lo = c.code.toByte()
            b[2 * i] = if (bigEndian) hi else lo
            b[2 * i + 1] = if (bigEndian) lo else hi
        }
    }

    private fun Reader.readAll(): String {
        val sb = StringBuilder()
        val buf = CharArray(333)
        while (true) {
            val n = read(buf)
            if (n < 0) break
            sb.appendRange(buf, 0, n)
        }
        close()
        return sb.toString()
    }

    private class ByteInput(private val bytes: ByteArray) : InputStream() {
        private var at = 0
        override fun read(): Int = if (at < bytes.size) bytes[at++].toInt() and 0xFF else -1
    }

    /** 한 번에 [step] 바이트까지만 내주는 흐름. */
    private class TrickleInput(private val bytes: ByteArray, private val step: Int) : InputStream() {
        private var at = 0
        override fun read(): Int = if (at < bytes.size) bytes[at++].toInt() and 0xFF else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (at >= bytes.size) return -1
            val n = minOf(len, step, bytes.size - at)
            bytes.copyInto(b, off, at, at + n)
            at += n
            return n
        }
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun ByteArray.toHex() = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
