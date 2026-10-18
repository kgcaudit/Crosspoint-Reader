package io.github.kgcaudit.reader.layout.cache

import java.io.File

/** `java.io.File` 로 된 [CacheDir](안드로이드). */
class FileCacheDir(private val root: File) : CacheDir {
    override fun dir(name: String): CacheDir = FileCacheDir(File(root, name))

    override fun subdirNames(): List<String> = root.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()

    override fun deleteDir(name: String) {
        File(root, name).deleteRecursively()
    }

    override fun mkdirs() {
        root.mkdirs()
    }

    override fun read(file: String): ByteArray? = runCatching { File(root, file).readBytes() }.getOrNull()

    override fun size(file: String): Long? = File(root, file).takeIf { it.isFile }?.length()

    override fun write(file: String, bytes: ByteArray) {
        val target = File(root, file)
        val temp = File(root, "$file.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            // 같은 이름이 이미 있으면 rename 이 실패하는 파일 시스템이 있다.
            target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        }
    }

    override fun delete(file: String) {
        File(root, file).delete()
    }
}

/** 안드로이드에서 쓰던 모양 그대로(`PageStore(File(filesDir, "pages"))`). */
fun PageStore(root: File): PageStore = PageStore(FileCacheDir(root))
