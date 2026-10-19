package io.github.kgcaudit.reader.document.io

/**
 * `java.io` 와 같은 모양의 흐름(iOS). 기본 구현은 JDK 의 것을 그대로 옮겼다 — 공통 코드의 흐름 클래스(PrefixedInput ·
 * InflaterInput …)는 JVM 의 기본 동작에 기대고 있어서, 여기서 다르게 굴면 안드로이드 시험은 통과하는데 iOS 에서만
 * 끝을 덜 읽거나(read 가 일찍 -1) 건너뛰기가 모자라는 식으로 갈라진다.
 */
actual abstract class InputStream actual constructor() : AutoCloseable {
    actual abstract fun read(): Int

    actual open fun read(b: ByteArray): Int = read(b, 0, b.size)

    actual open fun read(b: ByteArray, off: Int, len: Int): Int {
        if (off < 0 || len < 0 || len > b.size - off) throw IndexOutOfBoundsException("off=$off len=$len size=${b.size}")
        if (len == 0) return 0
        var c = read()
        if (c == -1) return -1
        b[off] = c.toByte()
        var i = 1
        // JDK 와 같이 첫 바이트 뒤의 IOException 은 삼키고 읽은 만큼 돌려준다.
        try {
            while (i < len) {
                c = read()
                if (c == -1) break
                b[off + i] = c.toByte()
                i++
            }
        } catch (_: IOException) {
        }
        return i
    }

    actual open fun skip(n: Long): Long {
        if (n <= 0) return 0
        var remaining = n
        val size = minOf(SKIP_BUFFER_SIZE.toLong(), remaining).toInt()
        val buffer = ByteArray(size)
        while (remaining > 0) {
            val read = read(buffer, 0, minOf(size.toLong(), remaining).toInt())
            if (read < 0) break
            remaining -= read
        }
        return n - remaining
    }

    actual open fun available(): Int = 0

    actual open fun markSupported(): Boolean = false

    actual open fun mark(readlimit: Int) {}

    actual open fun reset() {
        throw IOException("mark/reset not supported")
    }

    actual override fun close() {}

    private companion object {
        const val SKIP_BUFFER_SIZE = 2048
    }
}

actual abstract class Reader protected actual constructor() : AutoCloseable {
    actual abstract fun read(cbuf: CharArray, off: Int, len: Int): Int

    actual open fun read(): Int {
        val one = CharArray(1)
        return if (read(one, 0, 1) == -1) -1 else one[0].code
    }

    actual open fun read(cbuf: CharArray): Int = read(cbuf, 0, cbuf.size)

    actual open fun skip(n: Long): Long {
        require(n >= 0) { "skip value is negative" }
        val size = minOf(n, SKIP_BUFFER_SIZE.toLong()).toInt()
        val buffer = CharArray(size)
        var remaining = n
        while (remaining > 0) {
            val read = read(buffer, 0, minOf(remaining, size.toLong()).toInt())
            if (read == -1) break
            remaining -= read
        }
        return n - remaining
    }

    actual abstract override fun close()

    private companion object {
        const val SKIP_BUFFER_SIZE = 8192
    }
}

actual open class IOException : Exception {
    actual constructor() : super()
    actual constructor(message: String?) : super(message)
    actual constructor(message: String?, cause: Throwable?) : super(message, cause)
}

actual open class EOFException : IOException {
    actual constructor() : super()
    actual constructor(message: String?) : super(message)
}

actual interface Closeable : AutoCloseable {
    actual override fun close()
}
