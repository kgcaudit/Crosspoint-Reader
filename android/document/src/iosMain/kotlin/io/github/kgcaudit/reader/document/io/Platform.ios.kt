@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)

package io.github.kgcaudit.reader.document.io

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.free
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.CC_MD5
import platform.CoreCrypto.CC_MD5_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA1
import platform.CoreCrypto.CC_SHA1_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA384
import platform.CoreCrypto.CC_SHA384_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA512
import platform.CoreCrypto.CC_SHA512_DIGEST_LENGTH
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCDecrypt
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCSuccess
import platform.Foundation.NSDate
import platform.Foundation.NSString
import platform.Foundation.precomposedStringWithCanonicalMapping
import platform.Foundation.precomposedStringWithCompatibilityMapping
import platform.Foundation.timeIntervalSince1970
import platform.posix.memset
import platform.posix.size_tVar
import platform.zlib.MAX_WBITS
import platform.zlib.ZLIB_VERSION
import platform.zlib.Z_BUF_ERROR
import platform.zlib.Z_DATA_ERROR
import platform.zlib.Z_NEED_DICT
import platform.zlib.Z_OK
import platform.zlib.Z_PARTIAL_FLUSH
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.createCleaner

/**
 * 시스템 zlib 로 채운 [Inflater]. `java.util.zip.Inflater` 와 같이 Z_PARTIAL_FLUSH 로 풀고, 같은 때에 0 을 돌려준다
 * (입력이 모자람 · 사전이 필요함 · 끝남). 다르게 굴면 공통 InflaterInput 이 끝난 줄 모르고 계속 읽거나 일찍 멈춘다.
 *
 * 입력은 네이티브 메모리에 **복사**해 둔다 — z_stream 이 다음 inflate 까지 입력을 가리키고 있어야 하는데, Kotlin 배열은
 * 호출 사이에 고정해 둘 수 없다. 복사 비용은 zip 창 크기(수십 KB)만큼이라 무시할 만하다.
 */
actual open class Inflater actual constructor(nowrap: Boolean) {
    actual constructor() : this(false)

    private val native = NativeZStream(windowBits = if (nowrap) -MAX_WBITS else MAX_WBITS)

    // end() 를 부르지 않고 버려도 zlib 상태 · 입력 복사본이 새지 않게. JVM 의 Inflater 도 Cleaner 로 같은 일을 한다.
    @Suppress("unused")
    private val cleaner = createCleaner(native) { it.release() }

    private var finished = false
    private var needsDictionary = false

    actual fun setInput(input: ByteArray, off: Int, len: Int) {
        if (off < 0 || len < 0 || off > input.size - len) throw IndexOutOfBoundsException("off=$off len=$len size=${input.size}")
        native.setInput(input, off, len)
    }

    actual fun setInput(input: ByteArray) = setInput(input, 0, input.size)

    actual fun inflate(output: ByteArray, off: Int, len: Int): Int {
        if (off < 0 || len < 0 || off > output.size - len) throw IndexOutOfBoundsException("off=$off len=$len size=${output.size}")
        if (len == 0 || finished || needsDictionary || native.remaining == 0) return 0
        val (code, written) = native.inflate(output, off, len)
        when (code) {
            Z_OK, Z_BUF_ERROR -> Unit
            Z_STREAM_END -> finished = true
            Z_NEED_DICT -> needsDictionary = true
            Z_DATA_ERROR -> throw DataFormatException(native.message() ?: "invalid deflate data")
            else -> throw IllegalStateException("zlib inflate failed: $code")
        }
        return written
    }

    actual fun inflate(output: ByteArray): Int = inflate(output, 0, output.size)

    actual fun finished(): Boolean = finished

    actual fun needsInput(): Boolean = native.remaining <= 0

    actual fun needsDictionary(): Boolean = needsDictionary

    actual fun getRemaining(): Int = native.remaining

    actual fun end() = native.release()
}

/** z_stream 과 입력 복사본. [Inflater] 와 떼어 둔 것은 Cleaner 가 Inflater 자신을 붙잡으면 영영 수거되지 않아서다. */
private class NativeZStream(windowBits: Int) {
    private val stream = nativeHeap.alloc<z_stream>()
    private var input: kotlinx.cinterop.CPointer<UByteVar>? = null
    private var inputCapacity = 0
    private var released = false

    init {
        memset(stream.ptr, 0, sizeOf<z_stream>().toULong())
        val code = inflateInit2_(stream.ptr, windowBits, ZLIB_VERSION, sizeOf<z_stream>().toInt())
        check(code == Z_OK) { "zlib inflateInit2 failed: $code" }
    }

    val remaining: Int get() = if (released) 0 else stream.avail_in.toInt()

    fun setInput(bytes: ByteArray, off: Int, len: Int) {
        check(!released) { "Inflater has been closed" }
        if (len > inputCapacity) {
            input?.let { nativeHeap.free(it) }
            input = nativeHeap.allocArray(len)
            inputCapacity = len
        }
        val target = input
        if (len > 0 && target != null) {
            bytes.usePinned { pinned -> platform.posix.memcpy(target, pinned.addressOf(off), len.toULong()) }
        }
        stream.next_in = target
        stream.avail_in = len.toUInt()
    }

    /** (zlib 반환값, 쓴 바이트 수). */
    fun inflate(output: ByteArray, off: Int, len: Int): Pair<Int, Int> {
        check(!released) { "Inflater has been closed" }
        return output.usePinned { pinned ->
            stream.next_out = pinned.addressOf(off).reinterpret()
            stream.avail_out = len.toUInt()
            val code = inflate(stream.ptr, Z_PARTIAL_FLUSH)
            val written = len - stream.avail_out.toInt()
            // 고정이 풀린 배열을 가리킨 채로 두지 않는다.
            stream.next_out = null
            stream.avail_out = 0u
            code to written
        }
    }

    fun message(): String? = stream.msg?.toKString()

    fun release() {
        if (released) return
        released = true
        inflateEnd(stream.ptr)
        input?.let { nativeHeap.free(it) }
        input = null
        nativeHeap.free(stream)
    }
}

actual open class DataFormatException : Exception {
    actual constructor() : super()
    actual constructor(message: String?) : super(message)
}

/** CommonCrypto. JVM 과 같이 모르는 알고리즘 · 16 의 배수가 아닌 AES 입력은 예외로 알린다(PdfSecurity 가 받아 넘긴다). */
actual object Crypto {
    @Suppress("DEPRECATION")
    actual fun digest(algorithm: String, data: ByteArray): ByteArray {
        val length = when (algorithm.uppercase()) {
            "MD5" -> CC_MD5_DIGEST_LENGTH
            "SHA-1", "SHA1" -> CC_SHA1_DIGEST_LENGTH
            "SHA-256" -> CC_SHA256_DIGEST_LENGTH
            "SHA-384" -> CC_SHA384_DIGEST_LENGTH
            "SHA-512" -> CC_SHA512_DIGEST_LENGTH
            else -> throw IllegalArgumentException("$algorithm MessageDigest not available")
        }
        val out = UByteArray(length)
        // 빈 배열은 addressOf(0) 를 쓸 수 없어 1바이트짜리를 대신 고정한다(길이 0 이라 읽지 않는다).
        val source = if (data.isEmpty()) ByteArray(1) else data
        source.usePinned { src ->
            out.usePinned { dst ->
                val p = src.addressOf(0)
                val n = data.size.toUInt()
                val d = dst.addressOf(0)
                when (algorithm.uppercase()) {
                    "MD5" -> CC_MD5(p, n, d)
                    "SHA-1", "SHA1" -> CC_SHA1(p, n, d)
                    "SHA-256" -> CC_SHA256(p, n, d)
                    "SHA-384" -> CC_SHA384(p, n, d)
                    else -> CC_SHA512(p, n, d)
                }
            }
        }
        return out.asByteArray()
    }

    actual fun aesCbcNoPadding(encrypt: Boolean, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        require(key.size == 16 || key.size == 24 || key.size == 32) { "Invalid AES key length: ${key.size}" }
        require(iv.size == 16) { "Wrong IV length: must be 16 bytes long" }
        require(data.size % 16 == 0) { "Input length not multiple of 16 bytes" }
        if (data.isEmpty()) return ByteArray(0)
        val out = ByteArray(data.size)
        val status = memScoped {
            val moved = alloc<size_tVar>()
            val status = key.usePinned { k ->
                iv.usePinned { v ->
                    data.usePinned { d ->
                        out.usePinned { o ->
                            CCCrypt(
                                if (encrypt) kCCEncrypt else kCCDecrypt,
                                kCCAlgorithmAES,
                                0u, // 덧대기 없음 · ECB 아님 = CBC
                                k.addressOf(0), key.size.toULong(),
                                v.addressOf(0),
                                d.addressOf(0), data.size.toULong(),
                                o.addressOf(0), out.size.toULong(),
                                moved.ptr,
                            )
                        }
                    }
                }
            }
            check(status != kCCSuccess || moved.value.toInt() == out.size) { "CCCrypt wrote ${moved.value} of ${out.size}" }
            status
        }
        check(status == kCCSuccess) { "CCCrypt failed: $status" }
        return out
    }
}

@Suppress("CAST_NEVER_SUCCEEDS")
actual object Unicode {
    actual fun nfc(text: String): String = (text as NSString).precomposedStringWithCanonicalMapping

    actual fun nfkc(text: String): String = (text as NSString).precomposedStringWithCompatibilityMapping
}

/** Kotlin/Native 에는 스택 기록을 끄는 생성자가 없다. 흐름 제어용이라 메시지 없이 가볍게만 둔다. */
actual abstract class ControlFlowException actual constructor() : RuntimeException()

actual fun currentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
