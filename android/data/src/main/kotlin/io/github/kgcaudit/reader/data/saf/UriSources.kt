package io.github.kgcaudit.reader.data.saf

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.github.kgcaudit.reader.document.ByteSource
import io.github.kgcaudit.reader.document.SeekableSource
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * SAF `Uri` 를 :document 의 바이트 원천으로 연다.
 *
 * @param spoolDir 랜덤 액세스를 못 하는 원천을 옮겨 둘 곳. 앱 캐시 아래를 준다.
 */
class UriSources(
    private val resolver: ContentResolver,
    private val spoolDir: File,
) {

    /** TXT 처럼 앞에서부터 읽으면 되는 책. 여러 번 다시 열 수 있다. */
    fun byteSource(uri: Uri): ByteSource = ByteSource {
        resolver.openInputStream(uri) ?: throw FileNotFoundException("cannot open $uri")
    }

    /**
     * EPUB 처럼 임의 위치를 읽어야 하는 책.
     *
     * 대부분의 제공자(기기 저장소, SD 카드)는 진짜 파일 디스크립터를 준다. 그러면 복사 없이
     * 그 자리에서 읽는다. 일부 클라우드 제공자는 파이프를 주는데, 파이프는 되감을 수 없어
     * zip 중앙 디렉터리(파일 끝)를 읽을 수 없다. 그때는 캐시로 한 번 옮겨서 연다 —
     * "이 책은 열리지 않습니다" 보다 첫 열기가 느린 편이 낫다.
     */
    fun seekableSource(uri: Uri): SeekableSource {
        val pfd = resolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException("cannot open $uri")
        val direct = runCatching { FileDescriptorSource.open(pfd) }.getOrNull()
        if (direct != null) return direct

        pfd.close()
        return spooledSource(uri)
    }

    /**
     * 캐시로 옮긴 사본에서 연다.
     *
     * 파이프 판정(`statSize < 0`)과 떼어 둔 이유: Robolectric 은 파이프를 임시 파일로
     * 흉내 내서 `statSize` 가 -1 이 되지 않는다. 판정은 기기에서만 재현되므로, 사본
     * 경로는 따로 시험한다.
     */
    internal fun spooledSource(uri: Uri): SeekableSource {
        spoolDir.mkdirs()
        val file = File.createTempFile("spool", ".bin", spoolDir)
        try {
            val input = resolver.openInputStream(uri) ?: throw FileNotFoundException("cannot open $uri")
            input.use { src -> file.outputStream().use { src.copyTo(it) } }
            return SpooledFileSource(file)
        } catch (e: IOException) {
            file.delete()
            throw e
        }
    }
}

/**
 * 파일 디스크립터 위의 원천.
 *
 * 위치를 지정해 읽는 `FileChannel.read(buffer, position)` 을 쓴다. 채널의 현재 위치를
 * 옮기지 않으므로, 챕터를 읽는 도중에 그림을 읽으러 다른 스레드가 들어와도 서로의 읽기
 * 위치를 망가뜨리지 않는다.
 */
private class FileDescriptorSource(
    private val pfd: ParcelFileDescriptor,
    private val stream: FileInputStream,
    private val channel: FileChannel,
    override val size: Long,
) : SeekableSource {

    override fun readAt(offset: Long, dest: ByteArray, destOffset: Int, length: Int): Int =
        channel.readAt(offset, dest, destOffset, length, size)

    override fun close() {
        stream.close()
        pfd.close()
    }

    companion object {
        /**
         * 되감을 수 없는 디스크립터(파이프·소켓)면 예외를 던진다.
         *
         * 판단은 `statSize` 하나로 한다. 파이프에서는 -1 이다. `channel.size()` 로
         * 대신하면 안 된다 — 파이프에서 예외 없이 0 을 돌려주어, 크기 0 짜리 원천이
         * 만들어지고 EPUB 이 "zip 이 아니다" 로 실패한다.
         */
        fun open(pfd: ParcelFileDescriptor): FileDescriptorSource {
            val size = pfd.statSize
            if (size < 0) throw IOException("not a seekable file descriptor")
            val stream = FileInputStream(pfd.fileDescriptor)
            return FileDescriptorSource(pfd, stream, stream.channel, size)
        }
    }
}

/** 캐시로 옮겨 둔 사본. 닫을 때 지운다 — 남겨 두면 책을 열 때마다 캐시가 쌓인다. */
private class SpooledFileSource(private val file: File) : SeekableSource {
    private val raf = RandomAccessFile(file, "r")
    override val size: Long = raf.length()

    override fun readAt(offset: Long, dest: ByteArray, destOffset: Int, length: Int): Int =
        raf.channel.readAt(offset, dest, destOffset, length, size)

    override fun close() {
        raf.close()
        file.delete()
    }
}

private fun FileChannel.readAt(offset: Long, dest: ByteArray, destOffset: Int, length: Int, size: Long): Int {
    if (offset >= size) return -1
    if (length == 0) return 0
    return read(ByteBuffer.wrap(dest, destOffset, length), offset)
}
