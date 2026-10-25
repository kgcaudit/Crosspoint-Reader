@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.document.comic.ArchiveExtensions
import io.github.kgcaudit.reader.document.comic.ComicContents
import io.github.kgcaudit.reader.document.comic.ComicInfo
import io.github.kgcaudit.reader.document.comic.ComicOverrides
import io.github.kgcaudit.reader.document.comic.ComicShelf
import io.github.kgcaudit.reader.document.comic.ComicSpreads
import io.github.kgcaudit.reader.document.comic.ComicUnit
import io.github.kgcaudit.reader.document.comic.ComicUnitKind
import io.github.kgcaudit.reader.document.comic.Work
import io.github.kgcaudit.reader.document.image.ImageHeader
import io.github.kgcaudit.reader.document.image.ImageSize
import io.github.kgcaudit.reader.document.io.InputStream
import io.github.kgcaudit.reader.document.io.inputStream
import io.github.kgcaudit.reader.document.io.NamedCharsets
import io.github.kgcaudit.reader.document.tar.TarReader
import io.github.kgcaudit.reader.document.zip.ZipReader
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create

/**
 * 만화(구상안 35A · 36, 안드로이드 `ComicArchive` · `ReaderData.probeComics` 의 iOS 쪽). 무엇을 만화로 치는지 · 작품 묶기 · 쪽
 * 순서 · 두 쪽 짝짓기는 공통 코드(`ComicContents` · `ComicShelf` · `ComicSpreads`)가 정하고, 여기는 파일을 열어 그 코드에 넘긴다.
 *
 * 1단계는 zip(cbz · 그림만 든 zip) · tar(cbt) · 그림 폴더. CBR · CB7 은 iOS 에 풀 도구가 아직 없어 서재에 올리지 않는다(35A —
 * 보이는데 열리지 않는 책이 가장 나쁘다).
 */
object IosComics {
    /** iOS 가 열 수 있는 만화 압축 확장자. cbr · cb7 · rar · 7z 는 아직 아니다. */
    fun opensArchive(name: String): Boolean = ext(name) in setOf("cbz", "cbt", "zip", "tar")

    /** 이름만으로 만화인 것(cbz · cbt). zip · tar 는 열어 그림만 들었을 때만. */
    private fun trusted(name: String): Boolean = ext(name) in ArchiveExtensions.COMIC

    private fun ext(name: String) = name.substringAfterLast('.', "").lowercase()

    /**
     * 압축 하나를 살핀다. 만화가 아니면(그림이 없음 · 다른 파일이 섞인 zip · 열 수 없음) null. 이름이 만화라고 말하는 cbz 는 열지
     * 못해도 서재에 둔다 — 그건 열 때 까닭을 알린다(안드로이드와 같다).
     */
    fun probe(path: String, id: String, name: String, folders: List<String>, sizeBytes: Long, addedAt: Long): ComicUnit? {
        if (!opensArchive(name)) return null
        val opened = runCatching { open(path) }.getOrNull()
        val unit = ComicUnit(id, name, folders, ComicUnitKind.ARCHIVE, sizeBytes, addedAtEpochMs = addedAt)
        if (opened == null) return if (trusted(name)) unit else null
        return opened.use { a ->
            val contents = ComicContents.ofArchive(a.names, trustExtension = trusted(name)) ?: return@use null
            val info = contents.comicInfo?.let { n -> runCatching { a.bytes(n)?.let(::comicInfo) }.getOrNull() }
            unit.copy(info = info, contents = contents, pageCount = contents.pages.size)
        }
    }

    /** 그림 폴더(바로 아래 그림 3장 이상 · 책 없음). 아니면 null. */
    fun folder(id: String, name: String, folders: List<String>, fileNames: List<String>, addedAt: Long): ComicUnit? {
        val contents = ComicContents.ofFolder(fileNames) ?: return null
        return ComicUnit(id, name, folders, ComicUnitKind.IMAGE_FOLDER, contents = contents, pageCount = contents.pages.size, addedAtEpochMs = addedAt)
    }

    /** 단위들 → 작품들(공통 `ComicShelf.group`). */
    fun group(units: List<ComicUnit>, overrides: ComicOverrides): List<Work> = ComicShelf.group(units, overrides)

    private fun comicInfo(bytes: ByteArray): ComicInfo? =
        NamedCharsets.reader(bytes.inputStream(), "UTF-8")?.let { ComicInfo.parse(it) }

    /** 파일 머리로 종류를 가린다(이름이 zip 이어도 tar 일 수 있다). */
    internal fun open(path: String): Archive {
        val source = FileSeekableSource(path)
        try {
            val head = ByteArray(512)
            val read = source.readAt(0, head, 0, head.size)
            return if (read >= 512 && TarReader.looksLikeTar(head)) TarArchive(TarReader.open(source)) else ZipArchive(ZipReader.open(source))
        } catch (e: Throwable) {
            // 깨진 tar 는 여기서 던진다 — 닫지 않으면 서재를 훑을 때마다 깨진 파일 하나에 파일 핸들 하나가 샜다.
            source.close()
            throw e
        }
    }

    internal interface Archive : AutoCloseable {
        val names: List<String>
        fun bytes(name: String): ByteArray?

        /** 항목을 앞에서부터 읽는 흐름(그림 머리만 볼 때). */
        fun stream(name: String): InputStream?

        /** 풀린 크기. 모르면 null(압축 속 책이 작은 TXT 쪽지를 가를 때). */
        fun sizeOf(name: String): Long?
    }

    /** [holds] 는 함께 닫을 바깥 압축 — 압축 없이 담긴 권(`IosNestedVolumes`)은 바깥 파일의 구간을 읽어, 바깥을 먼저 닫으면 못 읽는다. */
    internal class ZipArchive(val zip: ZipReader, private val holds: AutoCloseable? = null) : Archive {
        override val names: List<String> get() = zip.entries.keys.toList()
        override fun bytes(name: String): ByteArray? = zip.readBytes(name)
        override fun stream(name: String): InputStream? = zip.openStream(name)
        override fun sizeOf(name: String): Long? = zip.entries[name]?.size
        override fun close() {
            zip.close()
            holds?.close()
        }
    }

    private class TarArchive(val tar: TarReader) : Archive {
        override val names: List<String> get() = tar.entries.keys.toList()
        override fun bytes(name: String): ByteArray? = tar.openStream(name)?.use { it.readAllBytes() }
        override fun stream(name: String): InputStream? = tar.openStream(name)
        override fun sizeOf(name: String): Long? = tar.entries[name]?.second
        override fun close() = tar.close()
    }
}

/**
 * 만화 한 권의 쪽(뷰어). 압축은 목록만 읽고 쪽은 볼 때 항목 하나씩 꺼낸다. 폴더는 파일을 읽는다. 쪽 그림을 푸는 것(화면 크기에
 * 맞춰 솎기)은 화면(ImageIO)이 한다.
 */
class IosComicBook private constructor(
    private val archive: IosComics.Archive?,
    private val folder: String?,
    val pages: List<String>,
) : AutoCloseable {
    val pageCount: Int get() = pages.size

    /** [i] 쪽의 바이트. 읽지 못하면 null — 그 쪽만 "그리지 못했습니다" 로 두고 넘기기는 막지 않는다. */
    fun data(i: Int): NSData? {
        val name = pages.getOrNull(i) ?: return null
        val bytes = runCatching { if (archive != null) archive.bytes(name) else FileBytes.file("$folder/$name").openStream().use { it.readAllBytes() } }.getOrNull()
            ?: return null
        if (bytes.isEmpty()) return null
        return bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
    }

    /** 쪽마다 그림 크기(머리만 읽는다). 모르면 null — 두 쪽 짝짓기에서 세로 쪽으로 친다. */
    fun sizes(): List<ImageSize?> = pages.map { name ->
        // 머리만 읽는다(흐름으로 열어 앞 몇 KB). 쪽 전체를 읽으면 수백 MB 짜리 만화를 열 때마다 통째로 풀어 몇 초씩 걸렸다.
        runCatching {
            val stream = if (archive != null) archive.stream(name) else FileBytes.file("$folder/$name").openStream()
            stream?.use { ImageHeader.read(it) }
        }.getOrNull()
    }

    /** 두 쪽 판들(공통 `ComicSpreads`): 표지 혼자 · 펼침면 혼자 · 끝에 남은 쪽 혼자. */
    fun spreads(sizes: List<ImageSize?>, coverAlone: Boolean): List<List<Int>> = ComicSpreads.of(sizes, coverAlone)

    override fun close() {
        archive?.close()
    }

    companion object {
        /** 압축(cbz · cbt · zip). 그림이 없거나 열 수 없으면 null. */
        fun openArchive(path: String): IosComicBook? = runCatching {
            val a = IosComics.open(path)
            val contents = ComicContents.ofArchive(a.names, trustExtension = true)
            if (contents == null || contents.pages.isEmpty()) {
                a.close()
                null
            } else {
                IosComicBook(a, null, contents.pages)
            }
        }.getOrNull()

        /** 압축 속 권(`IosNestedVolumes`). [stamp] 는 바깥 파일의 크기 · 수정 시각. 그림이 없거나 열 수 없으면 null. */
        fun openNested(path: String, id: String, stamp: String, cacheDir: String): IosComicBook? = runCatching {
            val a = IosNestedVolumes.open(path, id, stamp, cacheDir)
            val contents = ComicContents.ofArchive(a.names, trustExtension = true)
            if (contents == null || contents.pages.isEmpty()) {
                a.close()
                null
            } else {
                IosComicBook(a, null, contents.pages)
            }
        }.getOrNull()

        /**
         * 그림책 EPUB 의 그림들(구상안 46): EPUB 도 zip 이라 만화 압축처럼 항목을 하나씩 꺼낸다. [paths] 는 공통 `PictureBook` 의
         * 쪽 차례 그대로 — 압축 안의 그림 차례(이름순)로 다시 늘어놓으면 장 차례와 어긋난다.
         */
        fun openPictures(path: String, paths: List<String>): IosComicBook? = runCatching {
            if (paths.isEmpty()) return null
            IosComicBook(IosComics.open(path), null, paths)
        }.getOrNull()

        /** 그림 폴더. [fileNames] 는 폴더 바로 아래 파일 이름들. */
        fun openFolder(path: String, fileNames: List<String>): IosComicBook? {
            val pages = ComicContents.folderPages(fileNames)
            return if (pages.isEmpty()) null else IosComicBook(null, path, pages)
        }
    }
}
