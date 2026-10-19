@file:OptIn(ExperimentalForeignApi::class)

package io.github.kgcaudit.reader.layout.cache

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** iOS 쪽 쪽 캐시 폴더가 안드로이드 [FileCacheDir] 와 같은 약속을 지키는지. 어기면 설정을 바꿔도 옛 쪽이 보이거나 캐시가 쌓인다. */
class NSFileCacheDirTest {
    private val root = NSTemporaryDirectory() + "cache-test-" + NSUUID().UUIDString
    private val cache = NSFileCacheDir(root)

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(root, null)
    }

    @Test
    fun `written bytes read back and a rewrite replaces them whole`() {
        cache.mkdirs()
        cache.write("index", byteArrayOf(1, 2, 3, 4, 5))
        cache.write("index", byteArrayOf(9))
        // 긴 옛 내용 위에 짧은 새 내용을 쓰면 꼬리가 남아선 안 된다.
        assertContentEquals(byteArrayOf(9), cache.read("index"))
        assertEquals(1L, cache.size("index"))
        cache.write("empty", ByteArray(0))
        assertContentEquals(ByteArray(0), cache.read("empty"))
    }

    @Test
    fun `missing files and folders read as nothing instead of failing`() {
        assertNull(cache.read("none"))
        assertNull(cache.size("none"))
        assertEquals(emptyList(), cache.subdirNames())
        cache.delete("none")
        cache.deleteDir("none")
    }

    @Test
    fun `sub folders are listed and deleted with their contents`() {
        val book = cache.dir("book-a")
        book.mkdirs()
        book.write("p0", byteArrayOf(1))
        cache.dir("book-b").mkdirs()
        cache.mkdirs()
        cache.write("loose-file", byteArrayOf(1))
        // 파일은 하위 폴더가 아니다.
        assertEquals(listOf("book-a", "book-b"), cache.subdirNames().sorted())
        // 폴더의 크기를 파일 크기로 알리면 캐시 크기 셈이 틀린다.
        assertNull(cache.size("book-a"))
        cache.deleteDir("book-a")
        assertEquals(listOf("book-b"), cache.subdirNames())
        assertNull(book.read("p0"))
    }
}
