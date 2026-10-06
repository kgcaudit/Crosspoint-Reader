package io.github.kgcaudit.reader.data

import io.github.kgcaudit.reader.archive.NativeArchives
import io.github.kgcaudit.reader.archive.NativeKind
import io.github.kgcaudit.reader.archive.Unpacked
import io.github.kgcaudit.reader.data.saf.UriSources
import io.github.kgcaudit.reader.document.SeekableSource
import io.github.kgcaudit.reader.document.tar.TarReader
import io.github.kgcaudit.reader.document.zip.ZipReader
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * 만화 압축 하나 — zip(cbz) · tar(cbt) · RAR(cbr) · 7z(cb7)를 같은 모양으로(0.37.0). 종류는 **파일 머리**로 가린다:
 * 확장자를 믿으면 cbr 이라는 이름의 zip(흔하다)이 열리지 않는다.
 *
 * zip · tar 는 목록만 읽고 항목을 볼 때 그 자리만 읽는다. RAR · 7z 는 해제기가 파일로 풀어야 해서 [entry] 가 항목 하나를
 * 임시 폴더에 풀어 읽는다(살피기 · 표지용). 만화를 **열** 때는 [unpackAll] 로 한 번에 푼다 — 통짜 RAR 은 한 쪽을 풀려면
 * 앞 쪽들을 다 풀어야 해서, 쪽마다 따로 풀면 n² 이다.
 */
internal sealed class ComicArchive : Closeable {
    abstract val names: List<String>
    abstract fun entry(name: String): InputStream?

    /** 항목을 풀었을 때 크기. 모르면 null. 압축 속 권(0.48.0)이 바뀌었는지 가리는 데 쓴다. */
    abstract fun sizeOf(name: String): Long?

    /**
     * 항목 하나를 [dest] 파일로 꺼낸다(0.48.0, 압축 속 권). 기본은 흘려 옮긴다 — 수십 MB 권을 메모리에 통째로 담지 않는다.
     * 다 옮기지 못했으면 false 이고 [dest] 는 남기지 않는다.
     */
    open fun copyEntry(name: String, dest: File): Boolean {
        val input = entry(name) ?: return false
        try {
            input.use { src -> dest.outputStream().use { src.copyTo(it) } }
            return true
        } catch (e: Throwable) {
            dest.delete()
            throw e
        }
    }

    /** @param owner 함께 닫을 것 — 압축 속 권을 바깥 압축의 구간에서 읽으면 바깥 압축이다. */
    class Zip(private val zip: ZipReader, private val owner: Closeable? = null) : ComicArchive() {
        override val names get() = zip.entries.keys.toList()
        override fun entry(name: String) = zip.openStream(name)
        override fun sizeOf(name: String) = zip.entries[name]?.size?.takeIf { it >= 0 }

        /**
         * 압축 없이 담은 zip 속 zip 을 그 자리에서 연다(0.48.0) — 복사가 없어 바로 열린다. 압축해 담았거나 안쪽이 zip 이 아니면
         * null(부르는 쪽이 꺼내서 연다). 돌려준 것을 닫으면 이 바깥 압축도 닫힌다.
         */
        fun openStored(name: String): Zip? {
            val slice = zip.storedSource(name) ?: return null
            val inner = try {
                ZipReader.open(slice)
            } catch (e: IOException) {
                return null
            }
            return Zip(inner, owner = this)
        }

        override fun close() {
            try { zip.close() } finally { owner?.close() }
        }
    }

    class Tar(private val tar: TarReader, private val owner: Closeable? = null) : ComicArchive() {
        override val names get() = tar.entries.keys.toList()
        override fun entry(name: String) = tar.openStream(name)
        override fun sizeOf(name: String) = tar.entries[name]?.second
        override fun close() {
            try { tar.close() } finally { owner?.close() }
        }
    }

    class Native(
        private val kind: NativeKind,
        private val local: UriSources.LocalPath,
        private val scratch: File,
    ) : ComicArchive() {
        private val listed = NativeArchives.list(kind, local.path).filter { !it.isDirectory && !it.encrypted && safeEntryName(it.path) }
        override val names: List<String> = listed.map { it.path }
        override fun sizeOf(name: String) = listed.firstOrNull { it.path == name }?.size?.takeIf { it >= 0 }

        /** 해제기가 파일로 풀게 하고 옮긴다 — [entry] 처럼 바이트로 읽으면 90MB 권이 메모리에 통째로 올라온다. */
        override fun copyEntry(name: String, dest: File): Boolean =
            extractOne(name) { file -> file.renameTo(dest) || run { file.copyTo(dest, overwrite = true); true } } ?: false

        override fun entry(name: String): InputStream? = extractOne(name) { it.readBytes().inputStream() }

        /**
         * 항목 하나를 임시 폴더에 풀어 [use] 에 넘기고 폴더를 지운다. 못 풀었으면 null. 해제기는 깨진 항목을 파일로 남기지 않으니
         * 파일이 있으면 온전하다 — 다른 항목을 건너뛴 것([Unpacked.SKIPPED_SOME])은 이 항목과 상관없다.
         */
        private inline fun <T> extractOne(name: String, use: (File) -> T): T? {
            val dir = File(scratch, "one-" + System.nanoTime())
            try {
                if (NativeArchives.extract(kind, local.path, dir, setOf(name)) == Unpacked.STOPPED) return null
                val file = File(dir, name).takeIf { it.isFile } ?: return null
                return use(file)
            } finally {
                dir.deleteRecursively()
            }
        }

        /**
         * 전부 [into] 아래에 푼다. 끝까지 돌았으면 true — 풀 수 없는 쪽(암호 · 깨짐)을 건너뛰었어도 다시 풀면 같으니 끝난 것이다.
         * 도중에 멈췄으면(공간 부족 · 못 읽음) false: 풀린 쪽은 그대로 보이고, 부르는 쪽은 "다 풀었음" 을 적지 않아 다음에 다시
         * 푼다.
         */
        fun unpackAll(into: File): Boolean = NativeArchives.extract(kind, local.path, into, null) != Unpacked.STOPPED

        override fun close() = local.close()
    }

    companion object {
        /**
         * 풀어도 되는 항목 이름인가: 절대 경로나 ".." 마디가 있으면 풀 곳 밖에 쓰인다 — 일부러 만든 압축이 앱의 DB · 설정을
         * 덮어쓸 수 있다("zip slip"). 7z 풀기(C++)도 같은 이름을 거르고, 여기서 한 번 더 걸러 쪽 목록에도 넣지 않는다.
         */
        fun safeEntryName(name: String): Boolean =
            name.isNotEmpty() && !name.startsWith("/") && !name.startsWith("\\") && name.replace('\\', '/').split('/').none { it == ".." }

        /**
         * 꺼내 둔 파일 하나를 압축으로 연다(0.48.0, 압축 속 권). 닫으면 [onClose] 를 부른다 — 살피기용 임시 사본을 지운다.
         * 종류는 [open] 과 같이 머리로 가린다.
         */
        fun openFile(file: File, scratch: File, onClose: () -> Unit = {}): ComicArchive {
            val source = try {
                FileSource(file)
            } catch (e: Throwable) {
                onClose()
                throw e
            }
            return fromSource(source, scratch, Closeable { onClose() }) {
                source.close()
                UriSources.LocalPath(file.path, onClose)
            }
        }

        /** [uri] 의 압축을 연다. 아는 형식이 아니면 [IOException]. */
        fun open(sources: UriSources, uri: android.net.Uri, scratch: File): ComicArchive =
            open(sources, uri, sources.seekableSource(uri), scratch)

        /**
         * 이미 연 [source] 로 [uri] 의 압축을 연다. RAR · 7z 면 해제기에 줄 경로를 [UriSources.localPath] 가 그 원천에서 넘겨받는다
         * — 파이프(클라우드) 원천은 이미 캐시로 한 번 받은 사본이라, 닫고 경로를 새로 청하면 같은 파일을 한 번 더 통째로 받았다.
         */
        internal fun open(sources: UriSources, uri: android.net.Uri, source: SeekableSource, scratch: File): ComicArchive =
            fromSource(source, scratch, owner = null) { sources.localPath(uri, source) }

        /**
         * 종류를 **파일 머리**로 가려 연다 — [open] · [openFile] 이 같은 판별을 쓴다. 실패하면 [source] 와 [owner] 를 닫는다:
         * 깨진 압축에서 닫지 않으면 파일 손잡이와 받아 둔 임시 사본(spool)이 남는다.
         *
         * @param owner 함께 닫을 것(꺼내 둔 임시 사본 지우기).
         * @param local RAR · 7z 일 때 해제기에 줄 경로. [source] 를 넘겨받아 닫는 책임도 진다.
         */
        private fun fromSource(source: SeekableSource, scratch: File, owner: Closeable?, local: () -> UriSources.LocalPath): ComicArchive {
            try {
                val head = ByteArray(512)
                val n = runCatching { source.readAt(0, head, 0, head.size) }.getOrDefault(-1)
                val kind = if (n >= 6) NativeArchives.kindOf(head) else null
                if (kind != null) {
                    val path = local()
                    return try {
                        Native(kind, path, scratch)
                    } catch (e: Throwable) {
                        path.close()
                        throw e
                    }
                }
                return if (n >= 512 && head[0] != 'P'.code.toByte() && TarReader.looksLikeTar(head)) Tar(TarReader.open(source), owner)
                else Zip(ZipReader.open(source), owner)
            } catch (e: Throwable) {
                runCatching { source.close() }
                runCatching { owner?.close() }
                throw e
            }
        }
    }
}

/** 앱 캐시의 파일. 압축 속 권을 꺼내 둔 사본을 읽는다. */
private class FileSource(file: File) : SeekableSource {
    private val raf = java.io.RandomAccessFile(file, "r")
    override val size: Long = raf.length()

    override fun readAt(offset: Long, dest: ByteArray, destOffset: Int, length: Int): Int {
        if (offset >= size) return -1
        if (length == 0) return 0
        return raf.channel.read(java.nio.ByteBuffer.wrap(dest, destOffset, length), offset)
    }

    override fun close() = raf.close()
}
