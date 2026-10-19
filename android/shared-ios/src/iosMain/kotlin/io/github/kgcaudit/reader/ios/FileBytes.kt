@file:OptIn(ExperimentalForeignApi::class)

package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.document.ByteSource
import io.github.kgcaudit.reader.document.io.IOException
import io.github.kgcaudit.reader.document.io.InputStream
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.fgetc
import platform.posix.fopen
import platform.posix.fread
import platform.posix.memcpy

/** Swift 의 `Data` · 파일 경로를 공통 코어의 [ByteSource] 로. */
object FileBytes {
    /**
     * 파일을 열 때마다 처음부터 읽는 [ByteSource]. 통째로 메모리에 올리지 않는다 — 수백 MB 짜리 만화 · PDF 도 같은 길로 연다.
     * 경로는 열 수 있는 동안만 유효하다(파일 앱의 보안 범위 URL 은 부르는 쪽이 접근을 열어 둔다).
     */
    fun file(path: String): ByteSource = ByteSource { PosixFileInput(path) }

    /** 이미 메모리에 있는 바이트(번들의 작은 시험 책 · 붙여 넣은 자료). 한 번 복사한다. */
    fun data(data: NSData): ByteSource {
        val bytes = data.toByteArray()
        return ByteSource.of(bytes)
    }
}

fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val out = ByteArray(size)
    if (size > 0) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

/** stdio 로 읽는 흐름. fread 가 버퍼를 두므로 read() 한 바이트씩도 느리지 않다. */
private class PosixFileInput(path: String) : InputStream() {
    private var file: CPointer<FILE>? = fopen(path, "rb") ?: throw IOException("cannot open $path")

    private fun handle(): CPointer<FILE> = file ?: throw IOException("Stream closed")

    override fun read(): Int {
        val c = fgetc(handle())
        return if (c < 0) -1 else c
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (off < 0 || len < 0 || len > b.size - off) throw IndexOutOfBoundsException("off=$off len=$len size=${b.size}")
        if (len == 0) return 0
        val f = handle()
        val n = b.usePinned { fread(it.addressOf(off), 1u, len.toULong(), f).toInt() }
        return if (n == 0) -1 else n
    }

    // skip 은 기본(읽어서 넘기기) 그대로 둔다. fseeko 는 끝을 넘어도 성공해서, 실제로 건너뛴 만큼을 알 수 없다.

    override fun close() {
        file?.let { fclose(it) }
        file = null
    }
}
