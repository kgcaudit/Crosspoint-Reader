package io.github.kgcaudit.reader.document.io

/** 바이트 배열을 읽는 흐름(`java.io.ByteArrayInputStream` 의 공통판). */
class ByteArrayInput(private val bytes: ByteArray, private var pos: Int = 0, private val end: Int = bytes.size) : InputStream() {
    override fun read(): Int = if (pos < end) bytes[pos++].toInt() and 0xFF else -1

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (pos >= end) return -1
        val n = minOf(len, end - pos)
        bytes.copyInto(b, off, pos, pos + n)
        pos += n
        return n
    }

    override fun skip(n: Long): Long {
        val k = minOf(n.coerceAtLeast(0), (end - pos).toLong()).toInt()
        pos += k
        return k.toLong()
    }

    override fun available(): Int = end - pos

    // java.io.ByteArrayInputStream 처럼 되감을 수 있다 — 이미지 해독기가 머리를 본 뒤 되감는다.
    private var marked = pos
    override fun markSupported(): Boolean = true
    override fun mark(readlimit: Int) { marked = pos }
    override fun reset() { pos = marked }

    override fun close() = Unit
}

fun ByteArray.inputStream(): InputStream = ByteArrayInput(this)

/** 늘어나는 바이트 버퍼(`java.io.ByteArrayOutputStream` 의 공통판). */
class ByteSink(initial: Int = 64) {
    private var buf = ByteArray(maxOf(initial, 16))
    private var count: Int = 0

    /** 쓴 바이트 수(`ByteArrayOutputStream.size()` 와 같은 꼴). */
    fun size(): Int = count

    fun write(b: Int) {
        grow(1)
        buf[count++] = b.toByte()
    }

    fun write(b: ByteArray, off: Int = 0, len: Int = b.size - off) {
        grow(len)
        b.copyInto(buf, count, off, off + len)
        count += len
    }

    fun toByteArray(): ByteArray = buf.copyOf(count)

    fun reset() {
        count = 0
    }

    private fun grow(extra: Int) {
        if (count + extra <= buf.size) return
        buf = buf.copyOf(maxOf(buf.size * 2, count + extra))
    }
}

/** 문자열을 읽는 글자 흐름(`java.io.StringReader` 의 공통판). */
class StringInput(private val text: String) : Reader() {
    private var pos = 0

    override fun read(cbuf: CharArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (pos >= text.length) return -1
        val n = minOf(len, text.length - pos)
        for (i in 0 until n) cbuf[off + i] = text[pos + i]
        pos += n
        return n
    }

    override fun close() = Unit
}

/** 끝까지 읽은 바이트. */
fun InputStream.readAllBytesCommon(): ByteArray {
    val sink = ByteSink(8 * 1024)
    val chunk = ByteArray(8 * 1024)
    while (true) {
        val n = read(chunk, 0, chunk.size)
        if (n < 0) break
        if (n == 0) { val one = read(); if (one < 0) break; sink.write(one); continue }
        sink.write(chunk, 0, n)
    }
    return sink.toByteArray()
}

/** 끝까지 읽은 글자. */
fun Reader.readAllText(): String {
    val out = StringBuilder()
    val chunk = CharArray(8 * 1024)
    while (true) {
        val n = read(chunk, 0, chunk.size)
        if (n < 0) break
        out.appendRange(chunk, 0, n)
    }
    return out.toString()
}

/**
 * [Inflater] 로 풀며 읽는 흐름(`java.util.zip.InflaterInputStream` 의 공통판). [ownsInflater] 면 닫을 때 [Inflater.end] 를
 * 부른다 — 안 부르면 네이티브 메모리가 GC 까지 남아, 장을 넘길 때마다 쌓인다.
 */
open class InflaterInput(
    private val source: InputStream,
    private val inflater: Inflater,
    bufferSize: Int = 8 * 1024,
    private val ownsInflater: Boolean = true,
) : InputStream() {
    private val buffer = ByteArray(bufferSize)
    private var closed = false
    private var eof = false
    private val one = ByteArray(1)

    override fun read(): Int = if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (closed) throw IOException("Stream closed")
        if (len == 0) return 0
        if (eof) return -1
        while (true) {
            val n = try {
                inflater.inflate(b, off, len)
            } catch (e: DataFormatException) {
                throw IOException(e.message ?: "Invalid compressed data", e)
            }
            if (n > 0) return n
            if (inflater.finished() || inflater.needsDictionary()) { eof = true; return -1 }
            if (inflater.needsInput()) {
                val read = source.read(buffer, 0, buffer.size)
                // 압축이 끝나기 전에 원천이 끝났다 — 잘린 파일. JVM 의 InflaterInputStream 과 같이 EOF 로 알린다.
                if (read < 0) throw EOFException("Unexpected end of ZLIB input stream")
                inflater.setInput(buffer, 0, read)
            }
        }
    }

    override fun skip(n: Long): Long {
        var left = n
        val tmp = ByteArray(minOf(n, 512L).toInt().coerceAtLeast(1))
        while (left > 0) {
            val r = read(tmp, 0, minOf(left, tmp.size.toLong()).toInt())
            if (r < 0) break
            left -= r
        }
        return n - left
    }

    override fun available(): Int = if (eof) 0 else 1

    override fun close() {
        if (closed) return
        closed = true
        if (ownsInflater) inflater.end()
        source.close()
    }
}

/** `System.arraycopy` 의 공통판(인자 순서도 같다 — 옮긴 코드를 그대로 읽게). */
fun arraycopy(src: ByteArray, srcPos: Int, dest: ByteArray, destPos: Int, length: Int) {
    src.copyInto(dest, destPos, srcPos, srcPos + length)
}

fun arraycopy(src: CharArray, srcPos: Int, dest: CharArray, destPos: Int, length: Int) {
    src.copyInto(dest, destPos, srcPos, srcPos + length)
}

/** `MutableMap.putIfAbsent` 의 공통판 — 이미 있으면 그대로 두고 있던 값을 돌려준다. */
fun <K, V> MutableMap<K, V>.putIfMissing(key: K, value: V): V? {
    val old = get(key)
    if (old == null && key !in this) put(key, value)
    return old
}

/** 코드 포인트 하나를 글자로(`String(Character.toChars(cp))`). 보조 평면은 대리 글자 둘. */
fun codePointToString(codePoint: Int): String =
    if (codePoint < 0x10000) {
        codePoint.toChar().toString()
    } else {
        val v = codePoint - 0x10000
        charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
    }

/** `Character.digit(c, radix)` 의 공통판: 숫자가 아니면 -1. */
fun digitValue(c: Char, radix: Int): Int = c.digitToIntOrNull(radix) ?: -1

/** [prefix] 의 한 구간을 먼저 내주고 이어서 [rest] 를 읽는 흐름. 머리를 본 뒤 되돌려 붙일 때 쓴다(되감기 없는 흐름에서). */
class PrefixedInput(private val prefix: ByteArray, private var pos: Int, len: Int, private val rest: InputStream) : InputStream() {
    private val end = pos + len

    override fun read(): Int = if (pos < end) prefix[pos++].toInt() and 0xFF else rest.read()

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (pos < end) {
            val n = minOf(len, end - pos)
            prefix.copyInto(b, off, pos, pos + n)
            pos += n
            return n
        }
        return rest.read(b, off, len)
    }

    override fun available(): Int = (end - pos) + rest.available()

    override fun close() = rest.close()
}

/** 공백류 글자인가(`Character.isSpaceChar`): 공백 · 줄 · 문단 구분자. 탭 · 줄바꿈 같은 제어 글자는 아니다. */
fun Char.isSpaceChar(): Boolean =
    category == CharCategory.SPACE_SEPARATOR || category == CharCategory.LINE_SEPARATOR || category == CharCategory.PARAGRAPH_SEPARATOR

/**
 * `%XX` 를 UTF-8 바이트로 풀어 글자로. `+` 는 그대로 둔다. `%` 뒤가 16진 두 자리가 아니면 null — `java.net.URLDecoder` 가
 * 예외를 던지던 자리다(부르는 쪽은 원래 글을 쓴다).
 */
fun percentDecodeStrict(text: String): String? {
    if ('%' !in text) return text
    val out = StringBuilder(text.length)
    val bytes = ByteSink()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '%') {
            if (i + 2 >= text.length) return null
            val high = digitValue(text[i + 1], 16)
            val low = digitValue(text[i + 2], 16)
            if (high < 0 || low < 0) return null
            bytes.write(high * 16 + low)
            i += 3
        } else {
            if (bytes.size() > 0) { out.append(Text.utf8(bytes.toByteArray())); bytes.reset() }
            out.append(c)
            i++
        }
    }
    if (bytes.size() > 0) out.append(Text.utf8(bytes.toByteArray()))
    return out.toString()
}
