package io.github.kgcaudit.reader.layout.cache

/**
 * 캐시 폴더 하나. [PageStore] 가 파일 시스템을 직접 모르게 하는 경계다 — 안드로이드는 `java.io.File`(jvmMain 의
 * [FileCacheDir]), iOS 는 NSFileManager 로 채운다. 이름(파일 · 하위 폴더)은 이 폴더 안의 한 단계 이름만 받는다.
 */
interface CacheDir {
    /** 하위 폴더. 아직 없어도 된다 — 쓸 때 만든다. */
    fun dir(name: String): CacheDir

    /** 하위 폴더 이름들. 폴더가 없으면 빈 목록. */
    fun subdirNames(): List<String>

    /** 하위 폴더를 통째로 지운다. */
    fun deleteDir(name: String)

    fun mkdirs()

    /** 파일 내용. 없거나 읽을 수 없으면 null. */
    fun read(file: String): ByteArray?

    /** 파일 크기. 없으면 null. */
    fun size(file: String): Long?

    /**
     * 파일을 **통째로 바꿔 넣는다**(임시 파일에 쓴 뒤 이름 바꾸기). 쓰다 만 파일이 멀쩡한 캐시처럼 읽히면 안 된다 —
     * [ChapterCache] 는 색인을 마지막에 바꿔 넣는 차례에 기댄다.
     */
    fun write(file: String, bytes: ByteArray)

    fun delete(file: String)
}
