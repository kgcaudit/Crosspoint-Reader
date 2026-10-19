@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)

package io.github.kgcaudit.reader.document.io

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toLong
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.iconv.iconv
import platform.iconv.iconv_close
import platform.iconv.iconv_open
import platform.posix.E2BIG
import platform.posix.EILSEQ
import platform.posix.EINVAL
import platform.posix.errno
import platform.posix.size_tVar
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.createCleaner

/**
 * 이름으로 고르는 인코딩(iOS).
 *
 * - UTF-8 · UTF-16LE/BE 는 공통 [Text] 로 푼다. 이쪽은 JVM 과 깨진 바이트를 똑같이 대체하도록 시험된 코드라(CommonIoTest),
 *   시스템 변환기에 맡기면 같은 깨진 TXT 가 두 앱에서 다른 글자 수 → 다른 쪽 나눔이 된다.
 * - 나머지(CP949 · EUC-KR · Shift_JIS …)는 시스템 iconv. CoreFoundation 의 변환은 깨진 바이트가 하나라도 있으면 통째로
 *   실패해서(대체 문자가 없다) "풀 수 없는 바이트는 대체 문자로" 를 지킬 수 없다. iconv 는 깨진 자리를 알려 주므로 거기만
 *   U+FFFD 로 바꾸고 계속 읽는다.
 */
actual object NamedCharsets {
    actual fun isSupported(name: String): Boolean = utfKind(name) != null || iconvName(name) != null

    actual fun decode(bytes: ByteArray, off: Int, len: Int, name: String): String? {
        when (utfKind(name)) {
            Utf.UTF8 -> return Text.utf8(bytes, off, len)
            Utf.UTF16LE -> return Text.utf16(bytes, off, len, bigEndian = false)
            Utf.UTF16BE -> return Text.utf16(bytes, off, len, bigEndian = true)
            null -> Unit
        }
        val decoder = IconvDecoder.open(name) ?: return null
        return try {
            val out = StringBuilder(len)
            decoder.decode(bytes, off, len, out, final = true, strict = false)
            out.toString()
        } finally {
            decoder.close()
        }
    }

    actual fun decodeStrict(bytes: ByteArray, name: String): String? {
        if (utfKind(name) == Utf.UTF8) {
            return try { bytes.decodeToString(throwOnInvalidSequence = true) } catch (_: CharacterCodingException) { null }
        }
        val decoder = IconvDecoder.open(name) ?: return null
        return try {
            val out = StringBuilder(bytes.size)
            if (decoder.decode(bytes, 0, bytes.size, out, final = true, strict = true) < 0) null else out.toString()
        } finally {
            decoder.close()
        }
    }

    actual fun reader(input: InputStream, name: String): Reader? {
        val chunks: ChunkDecoder = when (utfKind(name)) {
            Utf.UTF8 -> Utf8Chunks
            Utf.UTF16LE -> Utf16Chunks(bigEndian = false)
            Utf.UTF16BE -> Utf16Chunks(bigEndian = true)
            null -> IconvDecoder.open(name) ?: return null
        }
        return DecodingReader(input, chunks)
    }

    private enum class Utf { UTF8, UTF16LE, UTF16BE }

    private fun utfKind(name: String): Utf? = when (name.trim().lowercase().replace('_', '-')) {
        "utf-8", "utf8" -> Utf.UTF8
        "utf-16le", "utf16le", "x-utf-16le" -> Utf.UTF16LE
        "utf-16be", "utf16be", "x-utf-16be" -> Utf.UTF16BE
        else -> null
    }

    /** JVM 에만 있는 별칭 → iconv 이름. 공통 코드가 JVM 이름(`x-windows-949`)을 먼저 물어보므로 둘 다 받아야 한다. */
    private val aliases = mapOf(
        "x-windows-949" to "CP949",
        "windows-949" to "CP949",
        "ms949" to "CP949",
        "ks_c_5601-1987" to "EUC-KR",
        "windows-31j" to "CP932",
        "ms932" to "CP932",
        "x-sjis" to "SHIFT_JIS",
        "sjis" to "SHIFT_JIS",
        "x-euc-jp" to "EUC-JP",
    )

    /**
     * iconv 가 여는 이름. 모르면 null. 기억해 두지 않는다 — 책은 여러 스레드에서 함께 열리는데 Kotlin/Native 의 HashMap 은
     * 동시에 고치면 깨지고, 열어 보기는 장 하나 여는 값에 비해 아주 싸다.
     */
    internal fun iconvName(name: String): String? {
        val key = name.trim().lowercase()
        if (key.isEmpty()) return null
        val candidate = aliases[key] ?: name.trim()
        val cd = iconv_open(IconvDecoder.TARGET, candidate)
        if (cd.isFailure()) return null
        iconv_close(cd)
        return candidate
    }
}

/** 바이트 조각을 글자로. 끝이 덜 온 글자는 다음 조각을 기다린다. */
private interface ChunkDecoder {
    /**
     * [bytes] 의 [off]부터 [len]바이트를 풀어 [out] 에 붙이고 **쓴 바이트 수**를 돌려준다. [final] 이 아니면 끝의 덜 온 글자는
     * 남겨 둔다(부르는 쪽이 다음 조각 앞에 붙인다). [strict] 에서 깨진 바이트를 만나면 -1.
     */
    fun decode(bytes: ByteArray, off: Int, len: Int, out: StringBuilder, final: Boolean, strict: Boolean = false): Int

    fun close() {}
}

private object Utf8Chunks : ChunkDecoder {
    override fun decode(bytes: ByteArray, off: Int, len: Int, out: StringBuilder, final: Boolean, strict: Boolean): Int {
        val cut = if (final) len else len - incompleteTail(bytes, off, len)
        out.append(Text.utf8(bytes, off, cut))
        return cut
    }

    /** 끝에서 덜 온 UTF-8 글자의 바이트 수. 조각 경계에서 한 글자가 둘로 잘려 대체 문자 두 개가 되는 것을 막는다. */
    private fun incompleteTail(b: ByteArray, off: Int, len: Int): Int {
        for (k in 1..minOf(3, len)) {
            val v = b[off + len - k].toInt() and 0xFF
            if (v and 0xC0 == 0x80) continue
            val need = when {
                v >= 0xF0 -> 4
                v >= 0xE0 -> 3
                v >= 0xC0 -> 2
                else -> 1
            }
            return if (need > k) k else 0
        }
        return 0
    }
}

private class Utf16Chunks(private val bigEndian: Boolean) : ChunkDecoder {
    override fun decode(bytes: ByteArray, off: Int, len: Int, out: StringBuilder, final: Boolean, strict: Boolean): Int {
        var cut = if (final) len else len and 1.inv()
        // 끝의 높은 대리 글자는 짝이 다음 조각에 있을 수 있다 — 지금 풀면 대체 문자가 된다.
        if (!final && cut >= 2) {
            val a = bytes[off + cut - 2].toInt() and 0xFF
            val b = bytes[off + cut - 1].toInt() and 0xFF
            val unit = if (bigEndian) (a shl 8) or b else (b shl 8) or a
            if (unit.toChar().isHighSurrogate()) cut -= 2
        }
        out.append(Text.utf16(bytes, off, cut, bigEndian))
        return cut
    }
}

/** 시스템 iconv 로 [name] → UTF-16LE. 깨진 바이트는 하나씩 U+FFFD 로 바꾸고 건너뛴다. */
private class IconvDecoder private constructor(private val handle: IconvHandle) : ChunkDecoder {
    @Suppress("unused")
    private val cleaner = createCleaner(handle) { it.close() }

    private val buffer = ByteArray(OUT_BUFFER)

    override fun decode(bytes: ByteArray, off: Int, len: Int, out: StringBuilder, final: Boolean, strict: Boolean): Int {
        if (len == 0) return 0
        var consumed = 0
        bytes.usePinned { src ->
            buffer.usePinned { dst ->
                memScoped {
                    val inPtr = alloc<CPointerVar<ByteVar>>()
                    val inLeft = alloc<size_tVar>()
                    val outPtr = alloc<CPointerVar<ByteVar>>()
                    val outLeft = alloc<size_tVar>()
                    inPtr.value = src.addressOf(off)
                    inLeft.value = len.toULong()
                    while (inLeft.value > 0u) {
                        outPtr.value = dst.addressOf(0)
                        outLeft.value = OUT_BUFFER.toULong()
                        val result = iconv(handle.cd, inPtr.ptr, inLeft.ptr, outPtr.ptr, outLeft.ptr)
                        val error = if (result == ICONV_ERROR) errno else 0
                        appendUtf16Le(out, OUT_BUFFER - outLeft.value.toInt())
                        consumed = len - inLeft.value.toInt()
                        when (error) {
                            0, E2BIG -> Unit
                            EILSEQ -> {
                                if (strict) return -1
                                out.append(Text.REPLACEMENT)
                                inPtr.value = inPtr.value!! + 1
                                inLeft.value = inLeft.value - 1u
                                consumed++
                                handle.reset()
                            }
                            EINVAL -> {
                                // 덜 온 글자. 끝이 아니면 다음 조각에서 다시, 끝이면 JVM 처럼 대체 문자 하나.
                                if (!final) return consumed
                                if (strict) return -1
                                out.append(Text.REPLACEMENT)
                                return len
                            }
                            else -> {
                                if (strict) return -1
                                out.append(Text.REPLACEMENT)
                                return len
                            }
                        }
                    }
                    if (final) {
                        // 상태를 가진 인코딩(ISO-2022)이 남긴 것을 털어낸다.
                        outPtr.value = dst.addressOf(0)
                        outLeft.value = OUT_BUFFER.toULong()
                        iconv(handle.cd, null, null, outPtr.ptr, outLeft.ptr)
                        appendUtf16Le(out, OUT_BUFFER - outLeft.value.toInt())
                    }
                }
            }
        }
        return consumed
    }

    private fun appendUtf16Le(out: StringBuilder, produced: Int) {
        var i = 0
        while (i + 1 < produced) {
            out.append(((buffer[i].toInt() and 0xFF) or ((buffer[i + 1].toInt() and 0xFF) shl 8)).toChar())
            i += 2
        }
    }

    override fun close() = handle.close()

    companion object {
        const val TARGET = "UTF-16LE"
        private const val OUT_BUFFER = 16 * 1024

        fun open(name: String): IconvDecoder? {
            val iconvName = NamedCharsets.iconvName(name) ?: return null
            val cd = iconv_open(TARGET, iconvName)
            if (cd.isFailure()) return null
            return IconvDecoder(IconvHandle(cd!!))
        }
    }
}

/** iconv 손잡이. [IconvDecoder] 와 떼어 둔 것은 Cleaner 가 디코더 자신을 붙잡지 않게 하려는 것이다. */
private class IconvHandle(val cd: COpaquePointer) {
    private var closed = false

    fun reset() {
        iconv(cd, null, null, null, null)
    }

    fun close() {
        if (closed) return
        closed = true
        iconv_close(cd)
    }
}

/**
 * [ChunkDecoder] 로 푸는 글자 흐름. 입력을 조각으로 읽어 풀므로 수십 MB 짜리 CP949 TXT 도 통째로 메모리에 올리지 않는다.
 */
private class DecodingReader(private val input: InputStream, private val decoder: ChunkDecoder) : Reader() {
    private val bytes = ByteArray(BYTE_CHUNK)
    private var pendingBytes = 0
    private val chars = StringBuilder()
    private var charPos = 0
    private var eof = false

    override fun read(cbuf: CharArray, off: Int, len: Int): Int {
        if (off < 0 || len < 0 || len > cbuf.size - off) throw IndexOutOfBoundsException("off=$off len=$len size=${cbuf.size}")
        if (len == 0) return 0
        while (charPos >= chars.length) {
            if (eof) return -1
            fill()
        }
        val n = minOf(len, chars.length - charPos)
        for (i in 0 until n) cbuf[off + i] = chars[charPos + i]
        charPos += n
        return n
    }

    private fun fill() {
        chars.clear()
        charPos = 0
        val read = input.read(bytes, pendingBytes, bytes.size - pendingBytes)
        if (read < 0) eof = true else pendingBytes += read
        val used = decoder.decode(bytes, 0, pendingBytes, chars, final = eof)
        val left = if (eof) 0 else pendingBytes - used
        bytes.copyInto(bytes, 0, used, used + left)
        pendingBytes = left
    }

    override fun close() {
        decoder.close()
        input.close()
    }

    private companion object {
        const val BYTE_CHUNK = 16 * 1024
    }
}

private val ICONV_ERROR: ULong = ULong.MAX_VALUE

private fun CPointer<*>?.isFailure(): Boolean = this == null || this.toLong() == -1L
