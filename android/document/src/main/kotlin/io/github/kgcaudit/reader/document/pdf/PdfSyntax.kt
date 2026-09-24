package io.github.kgcaudit.reader.document.pdf

/**
 * PDF 의 값. 목차를 읽는 데 필요한 만큼만 — 내용 스트림(글자·그림)은 해석하지 않는다.
 *
 * 문자열은 **바이트 그대로** 둔다. 어떤 인코딩인지는 쓰이는 자리가 정한다(제목은 텍스트 문자열,
 * 이름 트리의 열쇠는 바이트 비교).
 */
internal sealed interface PdfObject

internal object PdfNull : PdfObject

internal data class PdfBool(val value: Boolean) : PdfObject

internal data class PdfNumber(val value: Double) : PdfObject {
    val int: Int get() = value.toInt()
    val long: Long get() = value.toLong()
}

internal data class PdfName(val name: String) : PdfObject

internal class PdfString(val bytes: ByteArray) : PdfObject {
    override fun equals(other: Any?): Boolean = other is PdfString && other.bytes.contentEquals(bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
    override fun toString(): String = "PdfString(${String(bytes, Charsets.ISO_8859_1)})"
}

internal data class PdfArray(val items: List<PdfObject>) : PdfObject

internal data class PdfDict(val entries: Map<String, PdfObject>) : PdfObject {
    operator fun get(key: String): PdfObject? = entries[key]
}

internal data class PdfRef(val num: Int, val gen: Int) : PdfObject

/** 스트림. 데이터는 아직 읽지 않았다 — [dataOffset] 은 파일 안의 위치(또는 담긴 바이트 배열 안의 위치). */
internal data class PdfStream(val dict: PdfDict, val dataOffset: Long) : PdfObject

/** 읽던 창(window)이 끝나 더 읽어야 한다. 파일 끝이 아니라 창 끝이다. */
internal class NeedMoreBytes : RuntimeException(null, null, false, false)

internal class PdfSyntaxException(message: String) : RuntimeException(message)

/**
 * 바이트 창 위의 PDF 문법 읽개.
 *
 * 창은 파일의 일부다([base] 가 창 첫 바이트의 파일 위치). 값이 창 끝에서 잘리면 [NeedMoreBytes] 를
 * 던지고, 부르는 쪽이 창을 늘려 다시 읽는다 — 객체 하나 읽자고 파일 전체를 메모리에 올리지 않는다.
 * [atEof] 면 창 끝이 곧 파일 끝이라 더 기다리지 않는다.
 */
internal class PdfParser(
    private val bytes: ByteArray,
    private val length: Int = bytes.size,
    private val base: Long = 0,
    private val atEof: Boolean = true,
) {
    var pos: Int = 0

    val filePosition: Long get() = base + pos

    /** 값 하나. 간접 참조(`12 0 R`)는 [PdfRef] 로 돌려준다. */
    fun readObject(depth: Int = 0): PdfObject {
        // 악의적이거나 깨진 파일의 `[[[[[…` 가 스택을 넘치게 하지 않게.
        if (depth > MAX_DEPTH) throw PdfSyntaxException("nesting too deep")
        skipSpace()
        val c = peek()
        return when {
            c == '/'.code -> readName()
            c == '('.code -> readLiteralString()
            c == '<'.code && peekAt(1) == '<'.code -> readDictOrStream(depth)
            c == '<'.code -> readHexString()
            c == '['.code -> readArray(depth)
            c == '+'.code || c == '-'.code || c == '.'.code || c in DIGITS -> readNumberOrRef()
            else -> readKeyword()
        }
    }

    /** `N G obj` 머리말을 읽고 N·G 를 돌려준다. 머리말이 아니면 null. */
    fun readObjectHeader(): Pair<Int, Int>? {
        skipSpace()
        val num = readInteger() ?: return null
        skipSpace()
        val gen = readInteger() ?: return null
        skipSpace()
        return if (readWord() == "obj") num to gen else null
    }

    fun skipSpace() {
        while (true) {
            if (pos >= length) {
                if (atEof) return else throw NeedMoreBytes()
            }
            val c = bytes[pos].toInt() and 0xFF
            when {
                c in WHITESPACE -> pos++
                c == '%'.code -> while (pos < length && bytes[pos].toInt() != '\n'.code && bytes[pos].toInt() != '\r'.code) pos++
                else -> return
            }
        }
    }

    /** 다음 낱말(구분자까지). 파일 끝이면 빈 문자열. */
    fun readWord(): String {
        val start = pos
        while (pos < length && !isDelimiterOrSpace(bytes[pos].toInt() and 0xFF)) pos++
        if (pos >= length && !atEof) throw NeedMoreBytes()
        return String(bytes, start, pos - start, Charsets.ISO_8859_1)
    }

    /** 다음 바이트가 있는가. 창 끝이면 더 읽게 한다 — 이스케이프가 창 경계에서 잘려 다른 값이 되지 않게. */
    private fun hasByte(): Boolean {
        if (pos < length) return true
        if (atEof) return false else throw NeedMoreBytes()
    }

    private fun peek(): Int {
        if (pos >= length) {
            if (atEof) throw PdfSyntaxException("unexpected end of file") else throw NeedMoreBytes()
        }
        return bytes[pos].toInt() and 0xFF
    }

    private fun peekAt(offset: Int): Int {
        val at = pos + offset
        if (at >= length) {
            if (atEof) return -1 else throw NeedMoreBytes()
        }
        return bytes[at].toInt() and 0xFF
    }

    private fun readInteger(): Int? {
        val start = pos
        while (pos < length && (bytes[pos].toInt() and 0xFF) in DIGITS) pos++
        if (pos >= length && !atEof) throw NeedMoreBytes()
        if (pos == start) return null
        return String(bytes, start, pos - start, Charsets.ISO_8859_1).toIntOrNull()
    }

    private fun readName(): PdfName {
        pos++ // '/'
        val raw = java.io.ByteArrayOutputStream()
        while (true) {
            if (pos >= length) {
                if (atEof) break else throw NeedMoreBytes()
            }
            val c = bytes[pos].toInt() and 0xFF
            if (isDelimiterOrSpace(c)) break
            // #xx 는 바이트 하나(PDF 1.2). 이름에 공백·한글을 넣을 때 쓴다.
            if (c == '#'.code && pos + 2 < length && hexValue(bytes[pos + 1]) >= 0 && hexValue(bytes[pos + 2]) >= 0) {
                raw.write(hexValue(bytes[pos + 1]) * 16 + hexValue(bytes[pos + 2]))
                pos += 3
            } else {
                raw.write(c)
                pos++
            }
        }
        // 이름의 바이트는 대개 ASCII 다. 명명 목적지에 한글이 들어간 파일은 UTF-8 로 적는다.
        return PdfName(String(raw.toByteArray(), Charsets.UTF_8))
    }

    /**
     * `( … )` 문자열. 괄호는 짝이 맞으면 그대로 들어가고, 역슬래시 이스케이프를 푼다.
     *
     * `\b` 를 빠뜨리면 안 된다. UTF-16 제목에서 0x08 바이트는 흔하다 — '절'(U+C808)·'눈'(U+B208) 의
     * 둘째 바이트가 0x08 이라, 이걸 'b' 로 읽으면 "거절" 이 "거졢" 이 된다(실제 책에서 본 증상).
     */
    private fun readLiteralString(): PdfString {
        pos++ // '('
        val out = java.io.ByteArrayOutputStream()
        var nesting = 1
        while (true) {
            if (pos >= length) {
                if (atEof) break else throw NeedMoreBytes() // 닫히지 않은 문자열: 있는 만큼만
            }
            val c = bytes[pos].toInt() and 0xFF
            pos++
            when (c) {
                '('.code -> { nesting++; out.write(c) }
                ')'.code -> {
                    nesting--
                    if (nesting == 0) break
                    out.write(c)
                }
                '\\'.code -> {
                    if (pos >= length) {
                        if (atEof) break else throw NeedMoreBytes()
                    }
                    val e = bytes[pos].toInt() and 0xFF
                    pos++
                    when (e) {
                        'n'.code -> out.write('\n'.code)
                        'r'.code -> out.write('\r'.code)
                        't'.code -> out.write('\t'.code)
                        'b'.code -> out.write(0x08)
                        'f'.code -> out.write(0x0C)
                        '\r'.code -> if (hasByte() && bytes[pos].toInt() == '\n'.code) pos++ // 줄 이음
                        '\n'.code -> Unit
                        in OCTAL -> {
                            var value = e - '0'.code
                            var digits = 1
                            while (digits < 3 && hasByte() && (bytes[pos].toInt() and 0xFF) in OCTAL) {
                                value = value * 8 + (bytes[pos].toInt() - '0'.code)
                                pos++
                                digits++
                            }
                            out.write(value and 0xFF)
                        }
                        else -> out.write(e) // \( \) \\ 와 모르는 이스케이프(역슬래시는 버린다)
                    }
                }
                // 문자열 안의 줄바꿈은 무엇이든 LF 하나다.
                '\r'.code -> {
                    if (hasByte() && bytes[pos].toInt() == '\n'.code) pos++
                    out.write('\n'.code)
                }
                else -> out.write(c)
            }
        }
        return PdfString(out.toByteArray())
    }

    private fun readHexString(): PdfString {
        pos++ // '<'
        val out = java.io.ByteArrayOutputStream()
        var high = -1
        while (true) {
            if (pos >= length) {
                if (atEof) break else throw NeedMoreBytes()
            }
            val b = bytes[pos]
            pos++
            if (b.toInt() == '>'.code) break
            val v = hexValue(b)
            if (v < 0) continue // 공백과 잡음은 건너뛴다
            if (high < 0) high = v else { out.write(high * 16 + v); high = -1 }
        }
        // 홀수 자리면 마지막 자리 뒤에 0 을 붙인 것으로 본다(명세).
        if (high >= 0) out.write(high * 16)
        return PdfString(out.toByteArray())
    }

    private fun readArray(depth: Int): PdfArray {
        pos++ // '['
        val items = ArrayList<PdfObject>()
        while (true) {
            skipSpace()
            if (pos >= length) break // atEof: 닫히지 않은 배열은 있는 만큼
            if (peek() == ']'.code) { pos++; break }
            items.add(readObject(depth + 1))
        }
        return PdfArray(items)
    }

    private fun readDictOrStream(depth: Int): PdfObject {
        pos += 2 // '<<'
        val entries = LinkedHashMap<String, PdfObject>()
        while (true) {
            skipSpace()
            if (pos >= length) break
            val c = peek()
            if (c == '>'.code && peekAt(1) == '>'.code) { pos += 2; break }
            if (c != '/'.code) {
                // 열쇠 자리에 이름이 아닌 것: 값 하나를 버리고 계속 본다.
                readObject(depth + 1)
                continue
            }
            val key = readName().name
            skipSpace()
            if (pos < length && peek() == '>'.code && peekAt(1) == '>'.code) { entries[key] = PdfNull; pos += 2; break }
            entries[key] = readObject(depth + 1)
        }
        val dict = PdfDict(entries)
        // 사전 바로 뒤에 `stream` 이 오면 스트림이다. 데이터는 그 줄의 끝 다음부터.
        val save = pos
        runCatching { skipSpace() }.onFailure { if (it is NeedMoreBytes) throw it }
        if (pos + 6 <= length && String(bytes, pos, 6, Charsets.ISO_8859_1) == "stream") {
            pos += 6
            if (pos < length && bytes[pos].toInt() == '\r'.code) pos++
            if (pos < length && bytes[pos].toInt() == '\n'.code) pos++
            return PdfStream(dict, filePosition)
        }
        if (pos + 6 > length && !atEof) throw NeedMoreBytes()
        pos = save
        return dict
    }

    private fun readNumberOrRef(): PdfObject {
        val first = readNumberToken() ?: return PdfNull
        // `12 0 R` 인지 보려면 두 낱말을 앞질러 본다. 아니면 되돌린다.
        if (first.isWholeNonNegative()) {
            val save = pos
            try {
                skipSpace()
                val second = readInteger()
                if (second != null) {
                    skipSpace()
                    if (pos < length && bytes[pos].toInt() == 'R'.code &&
                        (pos + 1 >= length || isDelimiterOrSpace(bytes[pos + 1].toInt() and 0xFF))
                    ) {
                        pos++
                        return PdfRef(first.int, second)
                    }
                }
            } catch (e: PdfSyntaxException) {
                // 파일 끝: 숫자 하나로 끝난 것이다.
            }
            pos = save
        }
        return first
    }

    private fun readNumberToken(): PdfNumber? {
        val start = pos
        if (bytes[pos].toInt() == '+'.code || bytes[pos].toInt() == '-'.code) pos++
        while (pos < length && ((bytes[pos].toInt() and 0xFF) in DIGITS || bytes[pos].toInt() == '.'.code)) pos++
        if (pos >= length && !atEof) throw NeedMoreBytes()
        val text = String(bytes, start, pos - start, Charsets.ISO_8859_1)
        // `--5`, `1.2.3` 같은 잘못 쓴 숫자는 0 으로 본다(Acrobat 과 같다) — 목차 하나 때문에 멈추지 않는다.
        return PdfNumber(text.toDoubleOrNull() ?: 0.0)
    }

    private fun PdfNumber.isWholeNonNegative() = value >= 0 && value == Math.floor(value) && value < Int.MAX_VALUE

    private fun readKeyword(): PdfObject {
        val word = readWord()
        if (word.isEmpty()) {
            // 구분자 하나(`)`, `>`, `{` 등)가 엉뚱한 곳에 있다. 건너뛰고 없는 값으로 본다.
            pos++
            return PdfNull
        }
        return when (word) {
            "true" -> PdfBool(true)
            "false" -> PdfBool(false)
            else -> PdfNull // null · endobj · 모르는 낱말
        }
    }

    companion object {
        const val MAX_DEPTH = 64
        private val WHITESPACE = setOf(0, 9, 10, 12, 13, 32)
        private val DIGITS = '0'.code..'9'.code
        private val OCTAL = '0'.code..'7'.code
        private val DELIMITERS = "()<>[]{}/%".map { it.code }.toSet()

        fun isDelimiterOrSpace(c: Int) = c in WHITESPACE || c in DELIMITERS

        fun hexValue(b: Byte): Int = when (val c = b.toInt()) {
            in '0'.code..'9'.code -> c - '0'.code
            in 'a'.code..'f'.code -> c - 'a'.code + 10
            in 'A'.code..'F'.code -> c - 'A'.code + 10
            else -> -1
        }
    }
}
