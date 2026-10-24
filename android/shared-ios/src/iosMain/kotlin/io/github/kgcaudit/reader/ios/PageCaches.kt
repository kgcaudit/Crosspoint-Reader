@file:OptIn(ExperimentalForeignApi::class)

package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.document.io.currentTimeMillis
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.timeIntervalSince1970

/**
 * 쪽 캐시 폴더 정리(2026-10-24 사용자 결정 2A). 쪽 캐시는 책마다 `<fnv(책 id)>/` 에, 책 글꼴은 `bookfonts/` 에 쌓이는데 서재에서
 * 사라진 책(지운 책 · 옮기기 전 자리 · 뺀 폴더)의 것을 지우는 곳이 없어 계속 늘었다. 실기기에서 같은 글꼴 여섯 벌로 136MB 였다.
 */
object PageCaches {
    /** 막 꺼낸 글꼴을 지우지 않게 이만큼 새 파일은 남긴다 — 열리는 책이 목록(idx)을 적기 전에 정리가 돌 수 있다. */
    const val FRESH_MS: Long = 10 * 60 * 1000L

    /**
     * [cacheDir] 에서 [keep](서재에 있는 책 · 만화 id) 밖의 것을 지운다: 그 책들의 쪽 캐시 · 글꼴 목록, 옛 방식의 책마다 글꼴 폴더,
     * 어느 목록도 쓰지 않는 글꼴 파일. 지운 항목 수. [keep] 이 비면 아무것도 지우지 않는다 — 폴더 접근이 끊겨 서재가 잠깐 비어 보일
     * 때 모든 캐시를 날리면, 다시 열 때 큰 책마다 처음부터 쪽을 나눈다.
     */
    fun prune(cacheDir: String, keep: List<String>, now: Long = currentTimeMillis()): Int {
        if (keep.isEmpty()) return 0
        val fm = NSFileManager.defaultManager
        val kept = keep.mapTo(HashSet()) { pageDirName(it) }
        // 글꼴 목록은 iOS 의 셈(UTF-8 바이트)으로 이름을 짓는다(IosBookReader).
        val keptFonts = keep.mapTo(HashSet()) { fnv1aHex(it) }
        var removed = 0
        fun remove(path: String) {
            if (fm.removeItemAtPath(path, null)) removed++
        }
        for (name in names(cacheDir)) {
            if (name == "bookfonts") continue
            if (isDir("$cacheDir/$name") && name !in kept) remove("$cacheDir/$name")
        }
        val fonts = "$cacheDir/bookfonts"
        // 옛 방식(책마다 폴더)은 통째로 — 새 방식은 books · files 두 칸뿐이다.
        for (name in names(fonts)) if (name != "books" && name != "files") remove("$fonts/$name")
        val referenced = HashSet<String>()
        for (name in names("$fonts/books")) {
            val path = "$fonts/books/$name"
            if (name.removeSuffix(".idx") !in keptFonts) {
                remove(path)
                continue
            }
            TextFiles.read(path)?.lineSequence()?.drop(1)?.forEach { line ->
                line.substringAfter('\t', "").takeIf { it.isNotEmpty() }?.let(referenced::add)
            }
        }
        for (name in names("$fonts/files")) {
            val path = "$fonts/files/$name"
            if (name.removeSuffix(".font") in referenced) continue
            if (now - modifiedMs(path) < FRESH_MS) continue
            remove(path)
        }
        return removed
    }

    /**
     * 공통 코어 `PageStore` 가 짓는 책 폴더 이름 — 같은 FNV-1a 지만 **UTF-16 글자** 단위다(코어의 `fnv1aHex`, 공개되지 않아 여기 옮겨
     * 둔다). iOS 의 [fnv1aHex](UTF-8 바이트)로 견주면 한글이 든 책 이름마다 값이 달라, 서재에 있는 책의 쪽 캐시까지 지웠다(실기기에서
     * 찾음 — 시험은 영문 이름만 써서 놓쳤다).
     */
    internal fun pageDirName(bookId: String): String {
        var hash = -0x340d631b7bdddcdbL
        for (ch in bookId) {
            hash = hash xor ch.code.toLong()
            hash *= 0x100000001b3L
        }
        return hash.toULong().toString(16).padStart(16, '0')
    }

    private fun names(dir: String): List<String> =
        NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, null)?.map { it.toString() }.orEmpty()

    private fun isDir(path: String): Boolean =
        NSFileManager.defaultManager.contentsOfDirectoryAtPath(path, null) != null

    private fun modifiedMs(path: String): Long {
        val date = NSFileManager.defaultManager.attributesOfItemAtPath(path, null)?.get(NSFileModificationDate) as? NSDate ?: return 0
        return (date.timeIntervalSince1970 * 1000).toLong()
    }
}
