package io.github.kgcaudit.reader.document.io

/**
 * 플랫폼에 맡기지 않는 바이트 ↔ 글자 변환. PDF 이름 · zip 항목 이름 · 캐시 글자처럼 두 앱이 **똑같이** 읽어야 하는 곳에 쓴다 —
 * 플랫폼 변환은 깨진 바이트를 대체하는 방식이 조금씩 달라, 같은 책의 같은 쪽이 두 앱에서 다른 글자가 될 수 있다.
 */
object Text {
    /** ISO-8859-1: 바이트 하나가 글자 하나(U+0000..U+00FF). PDF 구문은 이것으로 읽는다. */
    fun latin1(bytes: ByteArray, off: Int = 0, len: Int = bytes.size - off): String {
        val chars = CharArray(len)
        for (i in 0 until len) chars[i] = (bytes[off + i].toInt() and 0xFF).toChar()
        return chars.concatToString()
    }

    /** US-ASCII: 0x80 이상은 대체 문자 — JVM 의 US_ASCII 와 같다. */
    fun ascii(bytes: ByteArray, off: Int = 0, len: Int = bytes.size - off): String {
        val chars = CharArray(len)
        for (i in 0 until len) {
            val b = bytes[off + i].toInt() and 0xFF
            chars[i] = if (b < 0x80) b.toChar() else REPLACEMENT
        }
        return chars.concatToString()
    }

    fun utf8(bytes: ByteArray, off: Int = 0, len: Int = bytes.size - off): String = bytes.decodeToString(off, off + len)

    /**
     * UTF-16. 깨진 곳은 JVM 의 UTF-16 해독기와 똑같이 대체 문자로 바꾼다 — 짝 없는 높은 대리 글자는 **뒤 단위와 묶어** 하나,
     * 짝 없는 낮은 대리 글자는 하나, 끝에 남은 조각(홀수 바이트 · 끝의 높은 대리 글자)은 묶어 하나. 그대로 두면 짝 없는 대리
     * 글자가 조판 · 캐시로 흘러가 글자 수를 셀 때마다 어긋나고, 다르게 바꾸면 같은 PDF 제목이 두 앱에서 달라진다.
     */
    fun utf16(bytes: ByteArray, off: Int = 0, len: Int = bytes.size - off, bigEndian: Boolean): String {
        val units = len / 2
        val out = StringBuilder(units + 1)
        fun unit(k: Int): Char {
            val a = bytes[off + 2 * k].toInt() and 0xFF
            val b = bytes[off + 2 * k + 1].toInt() and 0xFF
            return (if (bigEndian) (a shl 8) or b else (b shl 8) or a).toChar()
        }
        var i = 0
        while (i < units) {
            val c = unit(i)
            when {
                c.isHighSurrogate() -> {
                    if (i + 1 >= units) {
                        // 끝의 높은 대리 글자: 남은 홀수 바이트와 함께 하나.
                        out.append(REPLACEMENT)
                        return out.toString()
                    }
                    val next = unit(i + 1)
                    if (next.isLowSurrogate()) out.append(c).append(next) else out.append(REPLACEMENT)
                    i += 2
                }
                c.isLowSurrogate() -> { out.append(REPLACEMENT); i++ }
                else -> { out.append(c); i++ }
            }
        }
        if (len % 2 != 0) out.append(REPLACEMENT)
        return out.toString()
    }

    /** 글자를 ISO-8859-1 바이트로. U+00FF 넘는 글자는 '?' — JVM 과 같다. */
    fun latin1Bytes(text: String): ByteArray = ByteArray(text.length) { i ->
        val c = text[i].code
        (if (c <= 0xFF) c else '?'.code).toByte()
    }

    const val REPLACEMENT: Char = '�'
}
