@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.document.comic.NestedArchives
import io.github.kgcaudit.reader.document.extensionOf
import io.github.kgcaudit.reader.document.io.IOException
import platform.Foundation.NSFileManager

/**
 * 압축 속 책(안드로이드 0.61.0 `UriSources` 의 압축 속 책 · `Library.saveNestedBooks` 의 iOS 쪽, 사용자 결정: 권처럼 펼침 · 열 때 그
 * 권만 · 만화와 섞이면 둘 다). 소설을 권별 TXT · EPUB 으로 zip 하나에 묶어 두면("[윤현승] 하얀늑대들.zip") 그림이 없어 서재에서
 * 통째로 빠졌다(실기기에서 찾음).
 *
 * 무엇을 책으로 치는지 · id(`바깥 id#!/안쪽 경로`)는 공통 `NestedArchives` 가 정한다. iOS 의 책 열기 · 이름표 · PDF 는 모두 파일
 * 경로를 받으므로, 열 때 그 권만 [cacheDir] 에 꺼내 둔다(안드로이드는 압축 없이 담은 EPUB 을 그 자리에서 읽지만, 여기서는 경로
 * 하나로 모든 길이 같아지는 편이 빠뜨릴 곳이 없다 — 소설 한 권은 몇 MB 라 꺼내는 데 1초가 안 걸린다).
 */
object IosNestedBooks {
    /** 꺼내 둔 책을 이만큼만 남긴다(안드로이드 `KEEP_NESTED`). 읽던 책 · 그 사이를 오가는 몇 권. */
    const val KEEP_UNPACKED: Int = 4

    /** 압축 속 책 하나. [id] 는 서재 · 기록의 책 id(안드로이드와 같은 꼴), [size] 는 풀린 크기(모르면 null). */
    class Entry(val id: String, val name: String, val size: Long?)

    /** [path](바깥 압축) 안의 책들, 자연 순. 압축이 아니거나 열 수 없으면 빈 목록 — 깨진 압축 하나가 서재 훑기를 멈추지 않게. */
    fun list(path: String, outerId: String, outerName: String): List<Entry> {
        if (!IosComics.opensArchive(outerName)) return emptyList()
        val archive = runCatching { IosComics.open(path) }.getOrNull() ?: return emptyList()
        return archive.use { a ->
            runCatching {
                NestedArchives.books(a.names.map { it to a.sizeOf(it) }).map { (inner, size) -> Entry(NestedArchives.id(outerId, inner), inner.substringAfterLast('/'), size) }
            }.getOrDefault(emptyList())
        }
    }

    /**
     * 책 [id] 를 꺼내 둔 파일의 경로. [stamp] 는 바깥 압축의 크기 · 고친 때 — 바뀌면 다른 사본이 된다(같은 이름으로 바꿔 넣은 묶음이
     * 옛 글을 보이지 않게). 다 꺼냈을 때만 표시를 남긴다 — 반쯤 꺼낸 사본(앱이 닫힘)을 다음에 그대로 열면 책 끝이 잘린다.
     */
    @Throws(Exception::class)
    fun extract(path: String, id: String, stamp: String, cacheDir: String): String {
        val (outerId, inner) = NestedArchives.split(id) ?: throw IOException("not a nested book: $id")
        val root = "$cacheDir/nested/books"
        val dir = "$root/${IosNestedVolumes.key(outerId, stamp, inner)}"
        val file = "$dir/book." + (extensionOf(inner) ?: "bin")
        val done = "$dir/done"
        val fm = NSFileManager.defaultManager
        if (!fm.fileExistsAtPath(done) || !fm.fileExistsAtPath(file)) {
            IosComics.open(path).use { archive ->
                if (inner !in archive.names) throw IOException("$inner is no longer in the archive")
                fm.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
                fm.removeItemAtPath(done, null)
                fm.removeItemAtPath(file, null)
                if (!IosNestedVolumes.copyEntry(archive.stream(inner), "$file.part") || !fm.moveItemAtPath("$file.part", file, null)) {
                    fm.removeItemAtPath("$file.part", null)
                    throw IOException("cannot copy out $inner")
                }
            }
            fm.createFileAtPath(done, null, null)
        }
        IosNestedVolumes.keepRecent(root, dir, KEEP_UNPACKED)
        return file
    }
}
