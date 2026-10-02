package io.github.kgcaudit.reader.document.tar

import io.github.kgcaudit.reader.document.SeekableSource
import io.github.kgcaudit.reader.document.zip.RangeInputStream
import java.io.IOException
import java.io.InputStream

/**
 * tar(cbt) 읽기(0.37.0, docs/COMIC_PLAN.md C4). tar 는 압축하지 않고 512바이트 머리 + 내용을 잇기만 해서, 머리만 건너뛰며
 * 읽으면 항목마다 시작 자리를 알고 쪽 하나를 꺼낼 때 그 자리만 읽는다(zip 리더와 같은 방식 — 통째로 복사하지 않는다).
 *
 * 받는 것: ustar(앞머리 155자 + 이름 100자), GNU 긴 이름(`L`), PAX 머리(`x` 의 `path=`), 크기의 base-256 표기(8GB 넘는 항목).
 * 깨진 머리(체크섬이 안 맞음)를 만나면 거기서 멈추고 **그때까지 읽은 항목은 둔다** — 끝이 잘린 파일도 앞쪽은 읽힌다(규칙 6).
 */
class TarReader private constructor(
    private val source: SeekableSource,
    /** 이름 → (내용 시작 자리, 크기). 파일만, 읽은 차례. */
    val entries: Map<String, Pair<Long, Long>>,
) : AutoCloseable {

    fun openStream(name: String): InputStream? {
        val (start, size) = entries[name] ?: return null
        return RangeInputStream(source, start, size)
    }

    override fun close() = source.close()

    companion object {
        private const val BLOCK = 512

        /** 앞 512바이트가 tar 머리인가(ustar 표시 또는 맞는 체크섬). */
        fun looksLikeTar(head: ByteArray): Boolean =
            head.size >= BLOCK && (String(head, 257, 5, Charsets.US_ASCII) == "ustar" || checksumOk(head))

        fun open(source: SeekableSource): TarReader {
            val entries = LinkedHashMap<String, Pair<Long, Long>>()
            val header = ByteArray(BLOCK)
            var at = 0L
            var longName: String? = null
            while (at + BLOCK <= source.size) {
                if (!readFully(source, at, header)) break
                if (header.all { it == 0.toByte() }) break
                if (!checksumOk(header)) {
                    if (entries.isEmpty()) throw IOException("not a tar archive")
                    break
                }
                val size = sizeOf(header)
                if (size < 0) break
                val dataStart = at + BLOCK
                val type = header[156].toInt().toChar()
                when (type) {
                    // GNU 긴 이름: 내용이 다음 항목의 이름이다.
                    'L' -> longName = readString(source, dataStart, size)
                    // PAX 머리: "길이 path=이름\n" 줄들. 이름만 쓴다.
                    'x' -> longName = readString(source, dataStart, size).lineSequence()
                        .mapNotNull { line -> line.substringAfter(' ', "").takeIf { it.startsWith("path=") }?.removePrefix("path=") }
                        .firstOrNull() ?: longName
                    '0', '\u0000', '7' -> {
                        val name = longName ?: nameOf(header)
                        longName = null
                        if (name.isNotEmpty() && !name.endsWith("/")) entries[name] = dataStart to size
                    }
                    else -> longName = null // 폴더 · 링크 · 장치: 쪽이 아니다.
                }
                at = dataStart + (size + BLOCK - 1) / BLOCK * BLOCK
            }
            if (entries.isEmpty() && at == 0L) throw IOException("not a tar archive")
            return TarReader(source, entries)
        }

        private fun nameOf(h: ByteArray): String {
            val name = cString(h, 0, 100)
            val prefix = if (String(h, 257, 5, Charsets.US_ASCII) == "ustar") cString(h, 345, 155) else ""
            return if (prefix.isEmpty()) name else "$prefix/$name"
        }

        private fun sizeOf(h: ByteArray): Long {
            // base-256: 첫 바이트의 높은 비트가 켜져 있으면 나머지가 큰 수다(GNU · star).
            if (h[124].toInt() and 0x80 != 0) {
                var v = 0L
                for (i in 125 until 136) v = (v shl 8) or (h[i].toLong() and 0xFF)
                return v
            }
            return cString(h, 124, 12).trim().ifEmpty { "0" }.toLongOrNull(8) ?: -1
        }

        private fun checksumOk(h: ByteArray): Boolean {
            val stored = cString(h, 148, 8).trim().toLongOrNull(8) ?: return false
            var sum = 0L
            for (i in 0 until BLOCK) sum += if (i in 148 until 156) ' '.code else h[i].toInt() and 0xFF
            return sum == stored
        }

        private fun cString(b: ByteArray, from: Int, len: Int): String {
            var end = from
            while (end < from + len && b[end] != 0.toByte()) end++
            return String(b, from, end - from, Charsets.UTF_8)
        }

        private fun readString(source: SeekableSource, at: Long, size: Long): String {
            val bytes = ByteArray(size.coerceIn(0, 64 * 1024).toInt())
            readFully(source, at, bytes)
            return String(bytes, Charsets.UTF_8).trimEnd('\u0000', '\n')
        }

        private fun readFully(source: SeekableSource, at: Long, into: ByteArray): Boolean {
            var done = 0
            while (done < into.size) {
                val n = source.readAt(at + done, into, done, into.size - done)
                if (n <= 0) return false
                done += n
            }
            return true
        }
    }
}
