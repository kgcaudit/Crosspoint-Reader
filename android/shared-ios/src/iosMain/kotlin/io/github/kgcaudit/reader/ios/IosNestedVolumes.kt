@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.document.comic.ComicContents
import io.github.kgcaudit.reader.document.comic.ComicUnit
import io.github.kgcaudit.reader.document.comic.ComicUnitKind
import io.github.kgcaudit.reader.document.comic.NestedArchives
import io.github.kgcaudit.reader.document.io.IOException
import io.github.kgcaudit.reader.document.io.InputStream
import io.github.kgcaudit.reader.document.zip.ZipReader
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite

/**
 * 압축 속 권(안드로이드 0.48.0 `ReaderData.openArchive` · `Library.saveVolumes` 의 iOS 쪽, 사용자 결정: 권으로 펼침 · 한 겹만).
 * 완결 만화 · 웹툰은 흔히 권별 zip 을 다시 zip 하나로 묶어 둔다("[작가] 작품 (1-3, 완결).zip" → "작품 1권.zip" …). 바깥에는 그림이
 * 바로 없어 "만화 아님" 으로 서재에서 통째로 빠졌다(실기기에서 찾음).
 *
 * 무엇을 권으로 치는지 · id · 모은 곳은 공통 `NestedArchives` 가 정하고, 여기는 안쪽 압축을 꺼내 연다. 압축 없이 담긴 권은 바깥
 * 파일의 그 구간을 그대로 읽고(복사 없음), 압축해 담긴 권은 [cacheDir] 에 한 번 꺼내 둔다.
 */
object IosNestedVolumes {
    /** 꺼내 둔 권을 이만큼만 남긴다(안드로이드 `KEEP_UNPACKED`). 다 두면 몇십 권에 캐시가 기가바이트로 쌓인다. */
    const val KEEP_UNPACKED: Int = 4

    /**
     * 살핀 결과 파일의 첫 줄. 형식이나 읽을 수 있는 것이 바뀌면 올린다 — 옛 결과를 그대로 믿지 않게. 2 = rar · 7z · tar 권
     * (2026-10-25): 1 은 그런 권을 열지 못해 "빈 권" 으로 적었고, 그대로 두면 해제기를 넣은 뒤에도 그 권이 서재에 나오지 않는다.
     */
    private const val PROBE_HEADER = "olo-nested-2"

    /**
     * [path](바깥 압축) 안의 권들을 만화 단위로. 권이 없거나 열 수 없으면 빈 목록. [stamp] 는 바깥 파일의 크기 · 수정 시각 — 바뀌면
     * 다시 살핀다(같은 이름으로 바꿔 넣은 묶음이 옛 쪽 목록을 보이지 않게).
     *
     * 안쪽 목록은 [cacheDir] 에 적어 둔다: 압축해 담긴 권은 목록을 보려면 통째로 꺼내야 해서(권 하나 50~90MB), 서재를 훑을 때마다
     * 하면 앱을 열 때마다 몇 초씩 걸린다. 표지 그림도 그때 함께 적는다 — 표지 하나 보려고 권을 다시 꺼내지 않게.
     */
    fun probe(path: String, outerId: String, outerName: String, folders: List<String>, stamp: String, addedAt: Long, cacheDir: String): List<ComicUnit> {
        if (!IosComics.opensArchive(outerName)) return emptyList()
        val outer = runCatching { IosComics.open(path) }.getOrNull() ?: return emptyList()
        return outer.use { archive ->
            NestedArchives.volumes(archive.names).mapNotNull { inner ->
                val key = key(outerId, stamp, inner)
                val names = cachedNames(cacheDir, key) ?: listNames(archive, inner, cacheDir, key) ?: return@mapNotNull null
                val contents = ComicContents.ofArchive(names, trustExtension = true) ?: return@mapNotNull null
                ComicUnit(
                    id = NestedArchives.id(outerId, inner),
                    name = inner.substringAfterLast('/'),
                    folders = NestedArchives.folders(folders, outerName, inner),
                    kind = ComicUnitKind.NESTED,
                    sizeBytes = archive.sizeOf(inner),
                    contents = contents,
                    pageCount = contents.pages.size,
                    addedAtEpochMs = addedAt,
                )
            }
        }
    }

    /** 살필 때 적어 둔 표지 그림. 없으면 null — 부르는 쪽이 권을 열어 첫 쪽을 쓴다. */
    fun cover(id: String, stamp: String, cacheDir: String): NSData? {
        val (outerId, inner) = NestedArchives.split(id) ?: return null
        return NSData.dataWithContentsOfFile(probeDir(cacheDir) + "/" + key(outerId, stamp, inner) + ".cover")
    }

    /**
     * 권 하나를 연다. 압축 없이 담겼으면 바깥 파일의 구간을 그대로, 아니면 꺼내 둔 사본에서. 꺼내는 도중 끊기면(앱이 닫힘) 다음에
     * 처음부터 다시 꺼낸다 — 반쯤 꺼낸 사본을 그대로 열면 뒤쪽 쪽들이 사라진다.
     */
    internal fun open(path: String, id: String, stamp: String, cacheDir: String): IosComics.Archive {
        val (outerId, inner) = NestedArchives.split(id) ?: throw IOException("not a volume id: $id")
        val outer = IosComics.open(path)
        try {
            if (inner !in outer.names) throw IOException("$inner is no longer in the archive")
            (outer as? IosComics.ZipArchive)?.zip?.storedSource(inner)?.let { slice ->
                // 압축 없이 담긴 zip 권: 바깥 파일의 구간을 그대로(복사 없음). 안쪽이 rar · 7z 면 해제기가 파일을 원해 아래로 간다.
                val head = ByteArray(8)
                val n = slice.readAt(0, head, 0, head.size)
                if (IosNativeArchives.kindOf(head, n) == null) {
                    return runCatching { IosComics.ZipArchive(ZipReader.open(slice), holds = outer) }
                        .getOrElse { slice.close(); throw it }
                }
                slice.close()
            }
            val dir = "${cacheDir}/nested/open/${key(outerId, stamp, inner)}"
            val file = "$dir/volume"
            val done = "$dir/done"
            val fm = NSFileManager.defaultManager
            if (!fm.fileExistsAtPath(done) || !fm.fileExistsAtPath(file)) {
                fm.removeItemAtPath(dir, null)
                fm.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
                if (!copyOut(outer, inner, "$file.part") || !fm.moveItemAtPath("$file.part", file, null)) {
                    fm.removeItemAtPath("$file.part", null)
                    throw IOException("cannot copy out $inner")
                }
                fm.createFileAtPath(done, null, null)
            }
            outer.close()
            keepRecent("${cacheDir}/nested/open", dir)
            val volume = IosComics.open(file)
            if (volume !is NativeArchive) return volume
            // RAR · 7z 권: 꺼낸 사본 곁의 pages 에 한 번에 다 푼다(통짜 RAR 을 쪽마다 풀면 n²). 다 풀었을 때만 표시.
            val pages = "$dir/pages"
            if (!fm.fileExistsAtPath("$pages/done")) {
                fm.removeItemAtPath(pages, null)
                fm.createDirectoryAtPath(pages, withIntermediateDirectories = true, attributes = null, error = null)
                if (volume.unpackAll(pages)) fm.createFileAtPath("$pages/done", null, null)
            }
            return UnpackedArchive(pages, volume.names)
        } catch (e: Throwable) {
            outer.close()
            throw e
        }
    }

    /** 바깥 압축의 항목 하나를 [target] 파일로. RAR · 7z 바깥은 해제기가 풀어 옮긴다(흐름으로 읽으면 권이 메모리에 통째로 오른다). */
    private fun copyOut(outer: IosComics.Archive, inner: String, target: String): Boolean =
        if (outer is NativeArchive) outer.copyEntry(inner, target) else copyEntry(outer.stream(inner), target)

    internal fun key(outerId: String, stamp: String, inner: String) = fnv1aHex("$outerId|$stamp|$inner")

    private fun probeDir(cacheDir: String) = "$cacheDir/nested/probe"

    private fun cachedNames(cacheDir: String, key: String): List<String>? {
        val text = TextFiles.read(probeDir(cacheDir) + "/" + key + ".txt") ?: return null
        val lines = text.split('\n')
        if (lines.firstOrNull() != PROBE_HEADER) return null
        return lines.drop(1).filter { it.isNotEmpty() }
    }

    /**
     * 권의 항목 이름들. 못 연 권(깨짐 · 암호)도 빈 목록으로 적어 둔다 — 안 적으면 깨진 90MB 권을 서재를 훑을 때마다 다시 꺼낸다.
     * 적는 것 자체가 실패하면(공간 없음) null 이 아니라 읽은 목록을 돌려준다 — 이번 훑기에는 보인다.
     */
    private fun listNames(outer: IosComics.Archive, inner: String, cacheDir: String, key: String): List<String>? {
        val dir = probeDir(cacheDir)
        val tmp = "$dir/$key.tmp"
        NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        val names: List<String> = try {
            val stored = (outer as? IosComics.ZipArchive)?.zip?.storedSource(inner)
            val volume = if (stored != null && isZip(stored)) {
                IosComics.ZipArchive(ZipReader.open(stored))
            } else {
                stored?.close()
                if (!copyOut(outer, inner, tmp)) throw IOException("cannot copy out $inner")
                IosComics.open(tmp)
            }
            volume.use { v ->
                val list = v.names
                ComicContents.ofArchive(list, trustExtension = true)?.cover?.let { cover ->
                    v.bytes(cover)?.takeIf { it.isNotEmpty() }?.let { bytes ->
                        bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
                            .writeToFile("$dir/$key.cover", atomically = true)
                    }
                }
                list
            }
        } catch (e: Throwable) {
            emptyList()
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(tmp, null)
        }
        // 줄바꿈이 든 이름은 목록 파일에서 둘로 갈라진다 — 그런 권은 적지 않고 이번에만 쓴다.
        if (names.none { '\n' in it }) TextFiles.write("$dir/$key.txt", (listOf(PROBE_HEADER) + names).joinToString("\n"))
        return names
    }

    /** 압축 없이 담긴 항목이 zip 인가(rar · 7z · tar 면 그 자리에서 읽지 못한다). */
    private fun isZip(source: io.github.kgcaudit.reader.document.SeekableSource): Boolean {
        val head = ByteArray(4)
        return source.readAt(0, head, 0, 4) == 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()
    }

    /** 항목 하나를 파일로 꺼낸다(풀면서 쓴다 — 90MB 권을 메모리에 통째로 올리지 않게). [input] 은 여기서 닫는다. */
    internal fun copyEntry(input: InputStream?, target: String): Boolean {
        input ?: return false
        val out = fopen(target, "wb") ?: run { input.close(); return false }
        var ok = true
        try {
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buffer, 0, buffer.size)
                if (n <= 0) break
                val written = buffer.usePinned { fwrite(it.addressOf(0), 1u, n.toULong(), out) }
                if (written.toInt() != n) {
                    ok = false
                    break
                }
            }
        } catch (e: Throwable) {
            ok = false
        } finally {
            input.close()
            if (fclose(out) != 0) ok = false
        }
        return ok
    }

    /** 방금 연 [current] 를 새것으로 표시하고, 오래된 순으로 [keep] 개 밖을 지운다. */
    internal fun keepRecent(root: String, current: String, keep: Int = KEEP_UNPACKED) {
        val fm = NSFileManager.defaultManager
        fm.setAttributes(mapOf<Any?, Any?>(NSFileModificationDate to NSDate()), ofItemAtPath = current, error = null)
        val dirs = fm.contentsOfDirectoryAtPath(root, null)?.map { "$root/$it" }.orEmpty().filter { it != current }
        fun modified(p: String) = (fm.attributesOfItemAtPath(p, null)?.get(NSFileModificationDate) as? NSDate)?.timeIntervalSince1970 ?: 0.0
        dirs.sortedByDescending(::modified).drop(keep - 1).forEach { fm.removeItemAtPath(it, null) }
    }
}
