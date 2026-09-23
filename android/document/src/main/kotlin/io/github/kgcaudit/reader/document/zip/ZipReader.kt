package io.github.kgcaudit.reader.document.zip

import io.github.kgcaudit.reader.document.SeekableSource
import io.github.kgcaudit.reader.document.readFully
import io.github.kgcaudit.reader.document.readUpTo
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/** 중앙 디렉터리에서 읽어낸 엔트리 하나. */
data class ZipEntry(
    val name: String,
    val method: Int,
    val compressedSize: Long,
    val size: Long,
    val localHeaderOffset: Long,
    val encrypted: Boolean,
) {
    val isDirectory: Boolean get() = name.endsWith("/")

    companion object {
        const val METHOD_STORED: Int = 0
        const val METHOD_DEFLATE: Int = 8
    }
}

/**
 * 중앙 디렉터리를 직접 읽는 zip 리더.
 *
 * ### 왜 `java.util.zip.ZipFile` 을 쓰지 않는가
 *
 * `ZipFile` 은 `File` 을 요구한다. 안드로이드의 SAF 는 파일 경로가 아니라 `Uri` 를
 * 주므로 쓸 수 없다. 남는 선택은 `ZipInputStream`(순차 접근) 또는 앱 저장소로 복사인데,
 * 순차 접근은 챕터를 열 때마다 앞에서부터 훑어 "책 열기 300ms" 예산을 깨고, 복사는
 * 사용자 저장 공간을 두 배로 쓰고 큰 책에서 지연을 만든다.
 *
 * 중앙 디렉터리를 한 번 읽어 두면 이후 엔트리 접근이 O(1) 이고, 순수 Kotlin 이라 기기
 * 없이 검증된다. 압축 해제는 JDK/ART 에 내장된 [Inflater] 를 쓴다.
 *
 * ### 범위
 *
 * 읽기 전용. ZIP64 를 포함한다(엔트리가 65535 개를 넘거나 4GB 를 넘는 경우 — 드물지만
 * 지원하지 않으면 원인을 알기 어려운 실패가 된다). 암호화 엔트리는 읽지 않고 명확히
 * 보고한다.
 */
class ZipReader private constructor(
    private val source: SeekableSource,
    /** 중앙 디렉터리 순서를 유지한다. EPUB 은 `mimetype` 이 첫 엔트리여야 한다. */
    val entries: Map<String, ZipEntry>,
) : Closeable {

    /** 지연 계산되는 데이터 시작 오프셋. 지역 헤더를 한 번 읽어야 알 수 있다. */
    private val dataOffsets = HashMap<String, Long>()

    operator fun contains(name: String): Boolean = name in entries

    /**
     * 엔트리 내용을 압축 해제된 스트림으로 연다. 없으면 null. 호출부가 닫는다.
     *
     * @throws IOException 엔트리가 암호화됐거나 지원하지 않는 압축 방식일 때
     */
    @Throws(IOException::class)
    fun openStream(name: String): InputStream? {
        val entry = entries[name] ?: return null
        if (entry.encrypted) throw IOException("encrypted zip entry is not supported: $name")

        val raw = RangeInputStream(source, dataOffsetOf(entry), entry.compressedSize)
        return when (entry.method) {
            ZipEntry.METHOD_STORED -> raw
            ZipEntry.METHOD_DEFLATE -> {
                // Inflater 를 넘겨 만든 InflaterInputStream 은 close() 에서 end() 를 부르지 않는다. 부르지
                // 않으면 네이티브 zlib 메모리가 GC 까지 남고, 안드로이드는 챕터·그림을 열 때마다 누수 경고를 낸다.
                val inflater = Inflater(true)
                object : InflaterInputStream(raw, inflater, INFLATE_BUFFER) {
                    override fun close() {
                        try {
                            super.close()
                        } finally {
                            inflater.end()
                        }
                    }
                }
            }
            else -> throw IOException("unsupported compression method ${entry.method} for $name")
        }
    }

    /** 엔트리 전체를 바이트로 읽는다. 없으면 null. 작은 파일(OPF·NCX·CSS)에 쓴다. */
    @Throws(IOException::class)
    fun readBytes(name: String): ByteArray? {
        val entry = entries[name] ?: return null
        return openStream(name)?.use { stream ->
            if (entry.size in 1..MAX_EAGER_READ) {
                // 크기를 아는 경우 한 번에 담는다(재할당 없음).
                val out = ByteArray(entry.size.toInt())
                val read = stream.readUpTo(out)
                if (read == out.size) out else out.copyOf(read)
            } else {
                stream.readBytes()
            }
        }
    }

    /**
     * 엔트리 데이터가 시작하는 절대 오프셋.
     *
     * 중앙 디렉터리로는 알 수 없다 — 지역 헤더의 이름·extra 필드 길이가 중앙 디렉터리의
     * 것과 다를 수 있어서(실제로 다른 도구가 많다), 지역 헤더를 읽어 더해야 한다.
     * 이걸 중앙 디렉터리 값으로 계산하면 일부 책에서만 데이터가 밀려 깨진다.
     */
    @Throws(IOException::class)
    private fun dataOffsetOf(entry: ZipEntry): Long = dataOffsets.getOrPut(entry.name) {
        val header = source.readFully(entry.localHeaderOffset, LOCAL_HEADER_SIZE)
        if (header.u32(0) != LOCAL_HEADER_SIGNATURE) {
            throw IOException("bad local header for ${entry.name} at ${entry.localHeaderOffset}")
        }
        val nameLength = header.u16(26)
        val extraLength = header.u16(28)
        entry.localHeaderOffset + LOCAL_HEADER_SIZE + nameLength + extraLength
    }

    override fun close() = source.close()

    companion object {
        private const val INFLATE_BUFFER = 16 * 1024
        private const val MAX_EAGER_READ = 32L * 1024 * 1024

        private const val LOCAL_HEADER_SIGNATURE = 0x04034b50L
        private const val LOCAL_HEADER_SIZE = 30
        private const val CENTRAL_HEADER_SIGNATURE = 0x02014b50L
        private const val CENTRAL_HEADER_SIZE = 46
        private const val EOCD_SIGNATURE = 0x06054b50L
        private const val EOCD_SIZE = 22
        private const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50L
        private const val ZIP64_LOCATOR_SIZE = 20
        private const val ZIP64_EOCD_SIGNATURE = 0x06064b50L

        /** zip 주석 최대 길이(65535) + EOCD 크기. EOCD 를 뒤에서 찾을 범위. */
        private const val MAX_EOCD_SEARCH = 65535 + EOCD_SIZE

        /** UTF-8 이름 플래그(general purpose bit 11). */
        private const val FLAG_UTF8_NAMES = 0x800

        /** 암호화 플래그(general purpose bit 0). */
        private const val FLAG_ENCRYPTED = 0x1

        /**
         * 중앙 디렉터리를 읽어 리더를 만든다.
         *
         * 실패하면 [source] 를 닫고 [IOException] 을 던진다 — 호출부가 반쯤 열린
         * 리더를 들고 있지 않게 한다.
         */
        @Throws(IOException::class)
        fun open(source: SeekableSource): ZipReader {
            return runCatching { ZipReader(source, readCentralDirectory(source)) }
                .getOrElse { error ->
                    runCatching { source.close() }
                    throw if (error is IOException) error else IOException("not a zip archive", error)
                }
        }

        private fun readCentralDirectory(source: SeekableSource): Map<String, ZipEntry> {
            val (count, cdOffset) = locateCentralDirectory(source)
            val entries = LinkedHashMap<String, ZipEntry>(count.coerceAtMost(4096).toInt().coerceAtLeast(16))

            var offset = cdOffset
            var read = 0L
            while (read < count) {
                val header = source.readFully(offset, CENTRAL_HEADER_SIZE)
                if (header.u32(0) != CENTRAL_HEADER_SIGNATURE) break // 개수가 부풀려진 아카이브

                val flags = header.u16(8)
                val nameLength = header.u16(28)
                val extraLength = header.u16(30)
                val commentLength = header.u16(32)

                val variable = source.readFully(
                    offset + CENTRAL_HEADER_SIZE,
                    nameLength + extraLength + commentLength,
                )
                val name = decodeName(variable, 0, nameLength, flags)

                var uncompressed = header.u32(24)
                var compressed = header.u32(20)
                var localOffset = header.u32(42)

                // ZIP64: 0xFFFFFFFF 는 "실제 값은 extra 필드에 있다"는 표시다.
                if (uncompressed == 0xFFFFFFFFL || compressed == 0xFFFFFFFFL || localOffset == 0xFFFFFFFFL) {
                    val zip64 = findZip64Extra(variable, nameLength, extraLength)
                    if (zip64 != null) {
                        var cursor = zip64
                        if (uncompressed == 0xFFFFFFFFL) { uncompressed = variable.u64(cursor); cursor += 8 }
                        if (compressed == 0xFFFFFFFFL) { compressed = variable.u64(cursor); cursor += 8 }
                        if (localOffset == 0xFFFFFFFFL) { localOffset = variable.u64(cursor) }
                    }
                }

                if (name.isNotEmpty()) {
                    entries[name] = ZipEntry(
                        name = name,
                        method = header.u16(10),
                        compressedSize = compressed,
                        size = uncompressed,
                        localHeaderOffset = localOffset,
                        encrypted = flags and FLAG_ENCRYPTED != 0,
                    )
                }

                offset += CENTRAL_HEADER_SIZE + nameLength + extraLength + commentLength
                read++
            }
            return entries
        }

        /** @return 엔트리 개수와 중앙 디렉터리 오프셋 */
        private fun locateCentralDirectory(source: SeekableSource): Pair<Long, Long> {
            val eocdOffset = findEocd(source)
            val eocd = source.readFully(eocdOffset, EOCD_SIZE)
            var count = eocd.u16(10).toLong()
            var cdOffset = eocd.u32(16)

            // ZIP64 표시: 개수나 오프셋이 32비트에 안 들어간다.
            if (count == 0xFFFFL || cdOffset == 0xFFFFFFFFL) {
                readZip64(source, eocdOffset)?.let { (z64Count, z64Offset) ->
                    count = z64Count
                    cdOffset = z64Offset
                }
            }
            if (cdOffset < 0 || cdOffset >= source.size) {
                throw IOException("central directory offset $cdOffset out of range (size=${source.size})")
            }
            return count to cdOffset
        }

        /**
         * EOCD 를 파일 끝에서부터 찾는다.
         *
         * zip 은 디렉터리가 앞이 아니라 **끝**에 있고, EOCD 뒤에 최대 64KB 주석이
         * 붙을 수 있다. 그래서 고정 위치를 읽을 수 없고 뒤에서 서명을 훑어야 한다.
         *
         * 서명만 보고 "가장 뒤의 후보"를 집으면 틀린다 — 주석은 EOCD **뒤**에 오므로,
         * 주석 안에 서명과 같은 바이트가 있으면 그게 더 뒤에 있어서 먼저 걸린다.
         * 그래서 후보마다 **선언된 주석 길이가 남은 바이트 수와 맞는지** 확인한다.
         * 이 검사를 통과하는 위치가 진짜 EOCD 다.
         *
         * 어느 후보도 정확히 맞지 않으면(파일 끝에 쓰레기가 붙은 아카이브) 중앙
         * 디렉터리 오프셋이 파일 안을 가리키는 후보 중 가장 뒤의 것을 쓴다.
         */
        private fun findEocd(source: SeekableSource): Long {
            val searchLength = source.size.coerceAtMost(MAX_EOCD_SEARCH.toLong()).toInt()
            if (searchLength < EOCD_SIZE) throw IOException("too small to be a zip: ${source.size} bytes")

            val start = source.size - searchLength
            val tail = source.readFully(start, searchLength)
            var relaxedCandidate = -1L

            for (i in searchLength - EOCD_SIZE downTo 0) {
                if (tail.u32(i) != EOCD_SIGNATURE) continue
                val offset = start + i

                val declaredCommentLength = tail.u16(i + 20)
                val actualTrailing = source.size - (offset + EOCD_SIZE)
                if (declaredCommentLength.toLong() == actualTrailing) return offset

                if (relaxedCandidate < 0 && tail.u32(i + 16) < source.size) relaxedCandidate = offset
            }

            if (relaxedCandidate >= 0) return relaxedCandidate
            throw IOException("end of central directory not found; not a zip archive")
        }

        /** @return ZIP64 의 엔트리 개수와 중앙 디렉터리 오프셋, 없으면 null */
        private fun readZip64(source: SeekableSource, eocdOffset: Long): Pair<Long, Long>? {
            val locatorOffset = eocdOffset - ZIP64_LOCATOR_SIZE
            if (locatorOffset < 0) return null

            val locator = source.readFully(locatorOffset, ZIP64_LOCATOR_SIZE)
            if (locator.u32(0) != ZIP64_LOCATOR_SIGNATURE) return null

            val z64Offset = locator.u64(8)
            if (z64Offset < 0 || z64Offset + 56 > source.size) return null

            val record = source.readFully(z64Offset, 56)
            if (record.u32(0) != ZIP64_EOCD_SIGNATURE) return null

            return record.u64(32) to record.u64(48)
        }

        /** extra 필드에서 ZIP64 블록(id 0x0001)의 데이터 시작 위치를 찾는다. */
        private fun findZip64Extra(buffer: ByteArray, extraStart: Int, extraLength: Int): Int? {
            var cursor = extraStart
            val end = extraStart + extraLength
            while (cursor + 4 <= end) {
                val id = buffer.u16(cursor)
                val size = buffer.u16(cursor + 2)
                if (id == 0x0001) return cursor + 4
                cursor += 4 + size
            }
            return null
        }

        /**
         * 엔트리 이름을 디코딩한다.
         *
         * 플래그 bit 11 이 서면 UTF-8 로 확정이다. 서지 않은 경우 규격은 CP437 이지만
         * 실제로는 UTF-8 로 쓰면서 플래그를 빼먹은 도구가 많고, 한국 도구는 CP949 로
         * 쓴다. 그래서 UTF-8 로 유효하면 UTF-8, 아니면 CP949 로 읽는다 — 이름을 잘못
         * 읽으면 엔트리를 아예 못 찾는다.
         */
        private fun decodeName(buffer: ByteArray, offset: Int, length: Int, flags: Int): String {
            if (length == 0) return ""
            val raw = buffer.copyOfRange(offset, offset + length)
            if (flags and FLAG_UTF8_NAMES != 0) return String(raw, Charsets.UTF_8)
            val asUtf8 = String(raw, Charsets.UTF_8)
            return if ('�' in asUtf8) {
                String(raw, io.github.kgcaudit.reader.document.text.TextEncoding.EUC_KR.charset)
            } else {
                asUtf8
            }
        }

        // ── 리틀엔디안 읽기 ─────────────────────────────────────────

        private fun ByteArray.u16(at: Int): Int =
            (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)

        private fun ByteArray.u32(at: Int): Long =
            (this[at].toLong() and 0xFF) or
                ((this[at + 1].toLong() and 0xFF) shl 8) or
                ((this[at + 2].toLong() and 0xFF) shl 16) or
                ((this[at + 3].toLong() and 0xFF) shl 24)

        private fun ByteArray.u64(at: Int): Long {
            var value = 0L
            for (i in 7 downTo 0) value = (value shl 8) or (this[at + i].toLong() and 0xFF)
            return value
        }
    }
}

/**
 * [SeekableSource] 의 한 구간을 순차 스트림으로 보여 준다.
 *
 * [Inflater] 에 먹일 압축 바이트를 구간 단위로 공급하는 용도다. 구간 밖으로는 절대
 * 넘어가지 않으므로, 한 엔트리를 읽다가 다음 엔트리 데이터까지 흘러 들어가지 않는다.
 */
private class RangeInputStream(
    private val source: SeekableSource,
    private val start: Long,
    private val length: Long,
) : InputStream() {

    private var position = 0L
    private var mark = 0L

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == 1) one[0].toInt() and 0xFF else -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (position >= length) return -1
        val want = len.toLong().coerceAtMost(length - position).toInt()
        if (want <= 0) return 0
        val read = source.readAt(start + position, b, off, want)
        if (read <= 0) return -1
        position += read
        return read
    }

    override fun available(): Int = (length - position).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    override fun skip(n: Long): Long {
        val skipped = n.coerceAtLeast(0).coerceAtMost(length - position)
        position += skipped
        return skipped
    }

    override fun markSupported(): Boolean = true
    override fun mark(readlimit: Int) { mark = position }
    override fun reset() { position = mark }
}
