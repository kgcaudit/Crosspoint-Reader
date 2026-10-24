@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.document.io.currentTimeMillis
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSLog
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * 짝 잃은 서로게이트(이모지를 반으로 자른 조각)를 대체 문자(U+FFFD)로. 그런 글이 하나라도 섞이면 파일 전체를 UTF-8 로 쓸 수 없어
 * (NSCocoaErrorDomain 517) 그 실행 동안의 기록이 모두 저장되지 않았다 — 쪽 첫머리 80자를 자른 책갈피 발췌 하나 때문에.
 */
fun String.wellFormed(): String {
    var bad = false
    for (i in indices) {
        val c = this[i]
        if (c.isHighSurrogate() && (i + 1 >= length || !this[i + 1].isLowSurrogate())) bad = true
        if (c.isLowSurrogate() && (i == 0 || !this[i - 1].isHighSurrogate())) bad = true
        if (bad) break
    }
    if (!bad) return this
    return buildString(length) {
        var i = 0
        while (i < this@wellFormed.length) {
            val c = this@wellFormed[i]
            if (c.isHighSurrogate() && i + 1 < this@wellFormed.length && this@wellFormed[i + 1].isLowSurrogate()) {
                append(c).append(this@wellFormed[i + 1])
                i += 2
                continue
            }
            append(if (c.isSurrogate()) '�' else c)
            i++
        }
    }
}

/** 앞에서 [n] 글자(UTF-16 단위)까지 자르되 이모지(서로게이트 쌍)를 반으로 가르지 않는다. */
fun String.takeWhole(n: Int): String {
    if (length <= n) return this
    val end = if (n > 0 && this[n - 1].isHighSurrogate()) n - 1 else n
    return substring(0, end.coerceAtLeast(0))
}

/**
 * 앱이 적는 작은 글 파일(읽은 기록 · 기다리는 백업 기록 · 이어 읽기 상태). 읽기 · 쓰기 실패를 조용히 삼키지 않는다:
 * - 읽기: 파일이 있는데 UTF-8 로 풀 수 없으면 깨진 바이트만 대체 문자로 바꿔 읽고, 원본은 옆에 남긴다. 예전에는 "없음" 으로 보고 빈
 *   상태로 시작해, 첫 저장이 기록 파일 전체를 빈 것으로 덮었다.
 * - 쓰기: 통째로 바꾼다(atomic — 반쯤 쓴 파일이 남지 않는다). 실패하면 false 와 기록(NSLog) — 부르는 쪽은 다음 저장에서 다시 쓴다.
 */
object TextFiles {
    /** 없으면 null. 있는데 읽을 수 없으면 원본을 "<이름>.unreadable-<때>" 로 옮겨 두고 null. */
    fun read(path: String): String? {
        val fm = NSFileManager.defaultManager
        if (!fm.fileExistsAtPath(path)) return null
        val data: NSData? = NSData.dataWithContentsOfFile(path)
        if (data == null) {
            setAside(path)
            return null
        }
        val bytes = data.toByteArray()
        // 엄격하게 풀어 보고, 안 되면 깨진 곳만 대체 문자로 — 나머지 기록은 살린다. 원본은 남겨 둔다(손으로 되살릴 수 있게).
        return runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrElse {
            copyAside(path)
            bytes.decodeToString()
        }
    }

    /** 통째로 쓴다. 폴더가 없으면 만든다. 실패하면 false. */
    fun write(path: String, text: String): Boolean {
        val dir = path.substringBeforeLast('/', "")
        if (dir.isNotEmpty()) NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        @Suppress("CAST_NEVER_SUCCEEDS")
        val ok = (text.wellFormed() as NSString).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
        if (!ok) log("could not write $path")
        return ok
    }

    /** 기기 기록(Console)에 한 줄. 가변 인자로 Kotlin 글을 넘기면 앱이 죽어, 미리 만든 글에서 % 만 풀어 둔다. */
    private fun log(message: String) = NSLog("OLO: " + message.replace("%", "%%"))

    private fun setAside(path: String) {
        val to = "$path.unreadable-${currentTimeMillis()}"
        NSFileManager.defaultManager.moveItemAtPath(path, to, null)
        log("unreadable file moved to $to")
    }

    private fun copyAside(path: String) {
        val to = "$path.unreadable-${currentTimeMillis()}"
        NSFileManager.defaultManager.copyItemAtPath(path, to, null)
        log("damaged text in $path, original kept at $to")
    }
}
