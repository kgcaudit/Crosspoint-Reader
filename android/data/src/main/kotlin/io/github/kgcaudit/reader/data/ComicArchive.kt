package io.github.kgcaudit.reader.data

import io.github.kgcaudit.reader.archive.NativeArchives
import io.github.kgcaudit.reader.archive.NativeKind
import io.github.kgcaudit.reader.data.saf.UriSources
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

    class Zip(private val zip: ZipReader) : ComicArchive() {
        override val names get() = zip.entries.keys.toList()
        override fun entry(name: String) = zip.openStream(name)
        override fun close() = zip.close()
    }

    class Tar(private val tar: TarReader) : ComicArchive() {
        override val names get() = tar.entries.keys.toList()
        override fun entry(name: String) = tar.openStream(name)
        override fun close() = tar.close()
    }

    class Native(
        private val kind: NativeKind,
        private val local: UriSources.LocalPath,
        private val scratch: File,
    ) : ComicArchive() {
        override val names: List<String> = NativeArchives.list(kind, local.path)
            .filter { !it.isDirectory && !it.encrypted && safeEntryName(it.path) }.map { it.path }

        override fun entry(name: String): InputStream? {
            val dir = File(scratch, "one-" + System.nanoTime())
            try {
                if (!NativeArchives.extract(kind, local.path, dir, setOf(name))) return null
                val file = File(dir, name).takeIf { it.isFile } ?: return null
                return file.readBytes().inputStream()
            } finally {
                dir.deleteRecursively()
            }
        }

        /** 전부 [into] 아래에 푼다. 다 풀지 못했으면 false — 풀린 쪽은 그대로 보이고 나머지는 못 그린 쪽이 된다. */
        fun unpackAll(into: File): Boolean = NativeArchives.extract(kind, local.path, into, null)

        override fun close() = local.close()
    }

    companion object {
        /**
         * 풀어도 되는 항목 이름인가: 절대 경로나 ".." 마디가 있으면 풀 곳 밖에 쓰인다 — 일부러 만든 압축이 앱의 DB · 설정을
         * 덮어쓸 수 있다("zip slip"). 7z 풀기(C++)도 같은 이름을 거르고, 여기서 한 번 더 걸러 쪽 목록에도 넣지 않는다.
         */
        fun safeEntryName(name: String): Boolean =
            name.isNotEmpty() && !name.startsWith("/") && !name.startsWith("\\") && name.replace('\\', '/').split('/').none { it == ".." }

        /** [uri] 의 압축을 연다. 아는 형식이 아니면 [IOException]. */
        fun open(sources: UriSources, uri: android.net.Uri, scratch: File): ComicArchive {
            val source = sources.seekableSource(uri)
            val head = ByteArray(512)
            val n = runCatching { source.readAt(0, head, 0, head.size) }.getOrDefault(-1)
            val kind = if (n >= 6) NativeArchives.kindOf(head) else null
            return when {
                kind != null -> {
                    source.close()
                    val local = sources.localPath(uri)
                    try {
                        Native(kind, local, scratch)
                    } catch (e: Throwable) {
                        local.close()
                        throw e
                    }
                }
                n >= 512 && head[0] != 'P'.code.toByte() && TarReader.looksLikeTar(head) -> try {
                    Tar(TarReader.open(source))
                } catch (e: Throwable) {
                    // 깨진 cbt: 닫지 않으면 파일 손잡이와 받아 둔 임시 사본(spool)이 남는다.
                    source.close()
                    throw e
                }
                else -> try {
                    Zip(ZipReader.open(source))
                } catch (e: Throwable) {
                    source.close()
                    throw e
                }
            }
        }

    }
}
