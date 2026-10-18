package io.github.kgcaudit.reader.document.io

import org.junit.jupiter.api.Test
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * 공통(iOS 와 나눠 쓰는) 바이트 · 글자 도우미가 JVM 이 하던 것과 **똑같이** 읽는지(2026-10-18, iOS 공유). 다르면 같은 책이
 * 안드로이드에서 이번 판부터 다른 글자 · 다른 쪽으로 보인다. JVM 구현을 정답으로 놓고 대조한다.
 */
class CommonIoTest {
    private val random = Random(20261018)

    @Test
    fun `latin1, ascii and utf16 decode every byte the way the jvm did`() {
        val all = ByteArray(256) { it.toByte() }
        assertEquals(String(all, Charsets.ISO_8859_1), Text.latin1(all))
        assertEquals(String(all, Charsets.US_ASCII), Text.ascii(all))
        repeat(5_000) {
            // 짝 없는 대리 글자 · 홀수 끝 바이트까지 섞인 막 바이트.
            val bytes = random.nextBytes(random.nextInt(0, 40))
            assertEquals(String(bytes, Charsets.UTF_16BE), Text.utf16(bytes, bigEndian = true), bytes.contentToString())
            assertEquals(String(bytes, Charsets.UTF_16LE), Text.utf16(bytes, bigEndian = false), bytes.contentToString())
        }
        val text = "가나 🙂 abc"
        assertEquals(text, Text.utf16(text.toByteArray(Charsets.UTF_16BE), bigEndian = true))
        assertContentEquals("café?".toByteArray(Charsets.ISO_8859_1), Text.latin1Bytes("café가"))
    }

    @Test
    fun `percent decoding fails where url decoder failed and keeps plus signs`() {
        fun jvm(s: String) = runCatching { java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrNull()
        for (s in listOf("a%20b", "%EA%B0%80.xhtml", "c+d", "100%", "%4", "%zz", "x%2", "%", "plain", "%E0%A4")) {
            assertEquals(jvm(s), percentDecodeStrict(s), s)
        }
    }

    @Test
    fun `the common inflater stream reads what the jvm one read and reports a cut stream`() {
        val data = random.nextBytes(50_000) + "반복 ".repeat(5_000).encodeToByteArray()
        val raw = java.io.ByteArrayOutputStream().also { out ->
            DeflaterOutputStream(out, Deflater(Deflater.DEFAULT_COMPRESSION, true)).use { it.write(data) }
        }.toByteArray()
        val read = InflaterInput(ByteArrayInput(raw), Inflater(true), 1024).use { it.readAllBytesCommon() }
        assertContentEquals(data, read)
        // 잘린 압축(내려받다 끊긴 책): JVM 처럼 EOF 로 알린다 — 조용히 짧게 끝나면 장 뒷부분이 사라진다.
        assertFailsWith<EOFException> {
            InflaterInput(ByteArrayInput(raw.copyOf(raw.size / 2)), Inflater(true), 1024).use { it.readAllBytesCommon() }
        }
    }

    @Test
    fun `space characters and code points match the jvm`() {
        for (c in 0..0xFFFF) {
            assertEquals(Character.isSpaceChar(c), c.toChar().isSpaceChar(), "U+%04X".format(c))
        }
        for (cp in listOf(0x41, 0xAC00, 0xFFFD, 0x1F642, 0x10FFFF)) assertEquals(String(Character.toChars(cp)), codePointToString(cp))
    }

    @Test
    fun `buffers and prefixed streams hand back every byte in order`() {
        val sink = ByteSink(2)
        val bytes = random.nextBytes(1000)
        bytes.forEach { sink.write(it.toInt()) }
        sink.write(bytes, 10, 20)
        assertContentEquals(bytes + bytes.copyOfRange(10, 30), sink.toByteArray())
        val head = "머리".encodeToByteArray()
        val joined = PrefixedInput(head, 0, head.size, ByteArrayInput("몸".encodeToByteArray())).readAllBytesCommon()
        assertEquals("머리몸", joined.decodeToString())
        // 되감기: 이미지 해독기는 머리를 본 뒤 되감는다.
        val stream = ByteArrayInput(bytes)
        stream.mark(16); stream.read(); stream.read(); stream.reset()
        assertEquals(bytes[0].toInt() and 0xFF, stream.read())
    }

    @Test
    fun `named charsets refuse what they cannot decode strictly`() {
        assertNull(NamedCharsets.decodeStrict(byteArrayOf(0xC3.toByte()), "UTF-8"), "잘린 UTF-8 을 온전하다고 했다")
        assertNull(NamedCharsets.decode(byteArrayOf(1), 0, 1, "no-such-charset"))
        val korean = "한글".toByteArray(charset("x-windows-949"))
        assertEquals("한글", NamedCharsets.decode(korean, 0, korean.size, "x-windows-949"))
    }
}
