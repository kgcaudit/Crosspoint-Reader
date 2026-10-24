@file:OptIn(ExperimentalForeignApi::class)

package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.document.SeekableSource
import io.github.kgcaudit.reader.document.io.IOException
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.fstat
import platform.posix.open
import platform.posix.pread
import platform.posix.stat

/**
 * 파일을 아무 자리에서나 읽는 [SeekableSource](EPUB 은 zip 이라 끝의 목차부터 읽는다). `pread` 는 파일 위치를 바꾸지 않아,
 * 장을 읽는 스레드와 표지를 읽는 스레드가 같은 파일을 함께 읽어도 서로의 자리를 흩뜨리지 않는다. 안드로이드의
 * `FileDescriptorSource`(FileChannel 위치 읽기)와 같다.
 */
class FileSeekableSource(path: String) : SeekableSource {
    private var fd: Int = open(path, O_RDONLY).also { if (it < 0) throw IOException("cannot open $path") }

    override val size: Long = memScoped {
        val st = alloc<stat>()
        if (fstat(fd, st.ptr) != 0) {
            close(fd)
            throw IOException("cannot stat $path")
        }
        st.st_size
    }

    override fun readAt(offset: Long, dest: ByteArray, destOffset: Int, length: Int): Int {
        if (length == 0) return 0
        // 배열 밖에 쓰지 않게 — pread 는 범위를 보지 않는다.
        require(destOffset >= 0 && length >= 0 && destOffset + length <= dest.size) { "read range out of bounds" }
        if (offset >= size) return -1
        val handle = fd
        if (handle < 0) throw IOException("Stream closed")
        val n = dest.usePinned { pread(handle, it.addressOf(destOffset), length.toULong(), offset).toInt() }
        if (n < 0) throw IOException("read failed at $offset")
        return if (n == 0) -1 else n
    }

    override fun close() {
        if (fd >= 0) close(fd)
        fd = -1
    }
}
