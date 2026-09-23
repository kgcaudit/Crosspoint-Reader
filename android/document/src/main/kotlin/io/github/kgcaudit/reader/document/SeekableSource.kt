package io.github.kgcaudit.reader.document

import java.io.Closeable
import java.io.EOFException
import java.io.IOException

/**
 * 임의 위치를 읽을 수 있는 바이트 원천.
 *
 * zip 중앙 디렉터리는 **파일 끝**에 있고 엔트리 데이터는 흩어져 있다. 순차 스트림만
 * 있으면 챕터를 열 때마다 앞에서부터 훑어야 하고, 그러면 "책 열기 300ms" 예산이
 * 깨진다. 그래서 EPUB 를 여는 경로는 순차 [ByteSource] 가 아니라 이 인터페이스를 쓴다.
 *
 * 안드로이드에서는 SAF 의 `ParcelFileDescriptor` → `FileChannel` 이 구현체가 되고,
 * 테스트에서는 바이트 배열이 된다.
 */
interface SeekableSource : Closeable {

    /** 전체 길이(바이트). */
    val size: Long

    /**
     * [offset] 부터 최대 [length] 바이트를 [dest] 의 [destOffset] 위치에 읽는다.
     *
     * @return 실제로 읽은 바이트 수. 끝에 도달하면 요청보다 적을 수 있고, 끝이면 -1.
     */
    fun readAt(offset: Long, dest: ByteArray, destOffset: Int, length: Int): Int

    companion object {
        /** 테스트·소용량 데이터용 인메모리 원천. */
        fun of(bytes: ByteArray): SeekableSource = ByteArraySource(bytes)
    }
}

/**
 * 정확히 [length] 바이트를 읽는다. 모자라면 [EOFException].
 *
 * zip 구조를 읽을 때는 짧은 읽기를 조용히 넘기면 안 된다 — 필드가 어긋난 채로 파싱이
 * 계속되어 엉뚱한 곳에서 실패한다.
 */
@Throws(IOException::class)
fun SeekableSource.readFully(offset: Long, length: Int): ByteArray {
    val out = ByteArray(length)
    var read = 0
    while (read < length) {
        val n = readAt(offset + read, out, read, length - read)
        if (n <= 0) throw EOFException("wanted $length bytes at $offset, got $read (size=$size)")
        read += n
    }
    return out
}

private class ByteArraySource(private val bytes: ByteArray) : SeekableSource {
    override val size: Long get() = bytes.size.toLong()

    override fun readAt(offset: Long, dest: ByteArray, destOffset: Int, length: Int): Int {
        if (offset >= bytes.size) return -1
        val available = (bytes.size - offset).coerceAtMost(length.toLong()).toInt()
        System.arraycopy(bytes, offset.toInt(), dest, destOffset, available)
        return available
    }

    override fun close() = Unit
}
