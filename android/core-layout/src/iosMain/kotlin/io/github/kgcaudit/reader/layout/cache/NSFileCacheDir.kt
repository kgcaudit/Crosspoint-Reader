@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package io.github.kgcaudit.reader.layout.cache

import io.github.kgcaudit.reader.document.io.IOException
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeRegular
import platform.Foundation.NSNumber
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy

/**
 * NSFileManager 로 된 [CacheDir](iOS). [root] 는 폴더의 절대 경로(보통 Caches 아래).
 *
 * 바꿔 넣기는 `NSData.writeToFile(atomically = true)` 에 맡긴다 — 임시 파일에 다 쓴 뒤 이름을 바꾸는 것을 시스템이 해 준다.
 * 손으로 임시 파일을 만들면 앱이 쓰는 도중 죽었을 때 `.tmp` 가 남고, 같은 이름이 있을 때 rename 이 실패하는 경우를 따로
 * 다뤄야 한다(안드로이드 [FileCacheDir] 가 그렇다).
 */
class NSFileCacheDir(private val root: String) : CacheDir {
    private val files get() = NSFileManager.defaultManager

    private fun path(name: String) = "$root/$name"

    override fun dir(name: String): CacheDir = NSFileCacheDir(path(name))

    override fun subdirNames(): List<String> {
        val names = files.contentsOfDirectoryAtPath(root, null) ?: return emptyList()
        return names.filterIsInstance<String>().filter { isDirectory(path(it)) }
    }

    private fun isDirectory(path: String): Boolean = memScoped {
        val directory = alloc<BooleanVar>()
        files.fileExistsAtPath(path, directory.ptr) && directory.value
    }

    override fun deleteDir(name: String) {
        files.removeItemAtPath(path(name), null)
    }

    override fun mkdirs() {
        files.createDirectoryAtPath(root, withIntermediateDirectories = true, attributes = null, error = null)
    }

    override fun read(file: String): ByteArray? {
        val data = NSData.dataWithContentsOfFile(path(file)) ?: return null
        val size = data.length.toInt()
        val bytes = ByteArray(size)
        if (size > 0) bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
        return bytes
    }

    override fun size(file: String): Long? {
        val attributes = files.attributesOfItemAtPath(path(file), null) ?: return null
        if (attributes[NSFileType] != NSFileTypeRegular) return null
        return (attributes[NSFileSize] as? NSNumber)?.longLongValue
    }

    override fun write(file: String, bytes: ByteArray) {
        val data = if (bytes.isEmpty()) {
            NSData()
        } else {
            bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        }
        // 실패를 삼키면 색인만 바뀌고 쪽 파일은 옛것인 캐시가 남는다. 안드로이드(writeBytes)처럼 던진다.
        if (!data.writeToFile(path(file), atomically = true)) throw IOException("cannot write ${path(file)}")
    }

    override fun delete(file: String) {
        files.removeItemAtPath(path(file), null)
    }
}

/** 안드로이드의 `PageStore(File)` 과 같은 모양. */
fun PageStore(rootPath: String): PageStore = PageStore(NSFileCacheDir(rootPath))
